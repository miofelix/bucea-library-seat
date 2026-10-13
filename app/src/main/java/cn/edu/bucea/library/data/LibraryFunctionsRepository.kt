package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.security.MessageDigest

/** Native library functions using the same per-account authenticated campus session. */
class LibraryFunctionsRepository(
    private val request: suspend (SavedAccount, String) -> String,
    private val post: suspend (SavedAccount, String, Map<String, String>) -> String,
) {
    constructor(library: LibraryRepository) : this(library::request, library::post)
    private val submittedTokens = mutableSetOf<String>()
    suspend fun options(account: SavedAccount): SeatSearchOptions =
        parseOptions(authenticatedPage(account, "/self"))

    suspend fun rooms(account: SavedAccount, building: String): List<LibraryOption> =
        parseOptionsFragment(request(account, path("/freeBook/ajaxGetRooms", "id" to building)))

    suspend fun search(account: SavedAccount, query: SeatQuery, offset: Int = 0): SeatPage {
        require(query.onDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "请选择预约日期" }
        require(offset >= 0)
        val fields = if (query.name.isNotBlank()) listOf("onDate" to query.onDate, "name" to query.name)
        else listOf("onDate" to query.onDate, "building" to query.building, "room" to query.room,
            "hour" to query.hour, "power" to query.power, "window" to query.window,
            "startMin" to query.startMin, "endMin" to query.endMin)
        val request = path("/freeBook/ajaxSearch", *(fields + ("offset" to offset.toString())).toTypedArray())
        return parseSeatPage(this.request(account, request))
    }

    suspend fun favorites(account: SavedAccount): SeatPage {
        val document = authenticatedPage(account, "/freeBook/fav")
        val seats = parseSeats(document.selectFirst("#favSeatList")?.html().orEmpty())
        return SeatPage(seats, seats.size, null, message = if (seats.isEmpty()) "暂无常用座位" else "")
    }

    suspend fun startTimes(account: SavedAccount, seatId: String, onDate: String): SeatTimes =
        parseTimes(request(account, path("/freeBook/ajaxGetTime", "id" to seatId, "date" to onDate)))

    suspend fun endTimes(account: SavedAccount, seatId: String, onDate: String, start: String): SeatTimes =
        parseTimes(request(account, path("/freeBook/ajaxGetEndTime", "seat" to seatId, "date" to onDate, "start" to start)))

    suspend fun history(account: SavedAccount, offset: Int? = null): ReservationHistory {
        if (offset == null) return parseHistory(authenticatedPage(account, "/history?type=SEAT"))
        val json = json(request(account, path("/user/viewMoreHistory", "type" to "SEAT", "offset" to offset.toString())))
        return parseHistory(LibraryHtml.parse(json.optString("resStr"))).copy(
            nextOffset = json.optInt("offset", -1).takeIf { it >= 0 && json.optInt("resNum") > 0 },
            message = if (json.optInt("resNum") == 0) "已显示过去一个月的所有记录" else "",
        )
    }

    suspend fun violations(account: SavedAccount, offset: Int? = null): ReservationHistory {
        if (offset != null) {
            val data = json(request(account, path("/user/viewMoreViolations", "offset" to offset.toString())))
            return parseHistory(LibraryHtml.parse(data.optString("violationStr"))).copy(
                nextOffset = data.optInt("offset", -1).takeIf { it >= 0 && data.optInt("total") > 0 },
                message = if (data.optInt("total") == 0) "已显示过去60天所有违约记录" else "",
            )
        }
        val parsed = parseHistory(authenticatedPage(account, "/user/violations"))
        return parsed.copy(message = if (parsed.items.isEmpty()) "暂无违约记录" else parsed.message)
    }

    suspend fun userState(account: SavedAccount): LibraryUserState = parseUserState(authenticatedPage(account, "/self"))

    suspend fun extensionTimes(account: SavedAccount): List<LibraryOption> {
        val response = json(request(account, "/user/getTimeExtend"))
        if (response.optString("status") == "fail") throw LoginFailure(FailureKind.PROTOCOL,
            response.optString("message").ifBlank { "当前预约无法续约" })
        val times = response.optJSONObject("data")?.optJSONArray("endTimes") ?: return emptyList()
        return (0 until times.length()).map { index -> times.getJSONObject(index).let {
            LibraryOption(it.getString("id"), it.getString("value"))
        } }
    }

    /** Mutating actions are sent once, after explicit UI confirmation; no automatic retry. */
    suspend fun performAction(account: SavedAccount, action: String, endTime: String? = null): ReservationResult {
        require(action in setOf("checkIn", "leave", "resume", "stopUsing", "extend")) { "预约操作无效" }
        val state = userState(account)
        require(action in state.actions) { "当前状态或所在网络不支持此操作" }
        val endpoint = if (action == "extend") {
            require(!endTime.isNullOrBlank() && endTime.toIntOrNull() != null) { "请选择续约结束时间" }
            require(extensionTimes(account).any { it.value == endTime }) { "该续约时段已失效，请重新选择" }
            path("/user/extend", "endTime" to endTime)
        } else "/user/$action"
        val data = json(request(account, endpoint))
        val success = data.optString("status") == "success"
        val receipt = (data.optJSONObject("data")?.optJSONObject("reservation") ?: data.optJSONObject("data"))
            ?.takeIf { it.has("receipt") }?.let(::parseReceipt)
        return ReservationResult(success, data.optString("message").ifBlank { if (success) "操作成功" else "操作失败，请在原页面查看详情" }, receipt)
    }

    suspend fun prepareReservation(account: SavedAccount, selection: ReservationSelection): PreparedReservation {
        val document = authenticatedPage(account, "/self")
        val form = document.selectFirst("form#reserveForm[action='/selfRes']")
            ?: throw LoginFailure(FailureKind.PROTOCOL, "预约页面已变更，请打开原页面预约")
        val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
        if (fields["SYNCHRONIZER_TOKEN"].isNullOrBlank())
            throw LoginFailure(FailureKind.PROTOCOL, "预约页面缺少校验令牌，请重试")
        val scripts = document.select("script").joinToString("\n") { it.data() }
        // Verification-enabled installations must finish the official challenge in the browser.
        val verification = scripts.contains(Regex("""reservationCaptcha[^\n]*(?:\.html|\.append|captcha|Captcha)""")) ||
            form.selectFirst("input[name=authid]")?.attr("value")?.let { it.isNotBlank() && it != "-1" } == true
        return PreparedReservation(selection, fields, verification, account.id)
    }

    /** Call only after the user confirms the displayed seat, date and times. */
    suspend fun reserve(account: SavedAccount, reservation: PreparedReservation): ReservationResult {
        require(!reservation.verificationRequired) { "请在原页面完成预约验证" }
        require(reservation.accountId == account.id) { "账号已切换，请重新选择座位" }
        val selection = reservation.selection
        val startMinutes = selection.start.value.toIntOrNull()
        val endMinutes = selection.end.value.toIntOrNull()
        require(startMinutes != null && endMinutes != null && startMinutes < endMinutes) { "请选择有效的开始和结束时间" }
        require(selection.seat.available) { "该座位当前不可预约" }
        val fields = reservation.fields + mapOf("date" to selection.onDate, "seat" to selection.seat.id,
            "start" to selection.start.value, "end" to selection.end.value)
        val token = requireNotNull(fields["SYNCHRONIZER_TOKEN"])
        synchronized(submittedTokens) {
            require(submittedTokens.add(token)) { "此预约已提交，请先查看我的预约" }
        }
        val response = post(account, "/selfRes", fields)
        val document = LibraryHtml.parse(response)
        if (document.selectFirst("form[action='/auth/signIn']") != null)
            throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录后预约")
        return parseReservationResult(document)
    }

    private suspend fun authenticatedPage(account: SavedAccount, path: String): Document {
        val document = LibraryHtml.parse(request(account, path))
        val identity = document.selectFirst("#sysUsername")?.attr("value")
        if (document.selectFirst("form[action='/auth/signIn']") != null || (identity != null && identity != account.username))
            throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录")
        return document
    }

    companion object {
        private fun path(endpoint: String, vararg fields: Pair<String, String>): String {
            val url = (LibraryRepository.BASE_URL + endpoint).toHttpUrl().newBuilder().apply {
                fields.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
            return url.encodedPath + "?" + url.encodedQuery
        }

        private fun json(response: String): JSONObject {
            if (response.trimStart().startsWith("<"))
                throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录")
            return try { JSONObject(response) } catch (_: Exception) {
                throw LoginFailure(FailureKind.PROTOCOL, "图书馆返回的内容无法读取，请重试")
            }
        }

        fun parseOptions(document: Document): SeatSearchOptions {
            fun options(id: String) = parseOptionsFragment(document.selectFirst("#options_$id")?.html().orEmpty())
            return SeatSearchOptions(options("onDate"), options("building"), options("room"), options("hour"),
                options("startMin"), options("endMin"), options("power"), options("window"))
        }

        fun parseOptionsFragment(html: String): List<LibraryOption> = LibraryHtml.parse(html)
            .select("a[value]").map { LibraryOption(it.attr("value"), it.text()) }
            .filter { it.value.isNotBlank() && it.label.isNotBlank() }

        fun parseSeats(html: String): List<LibrarySeat> = LibraryHtml.parse(html).select("li[id^=seat_]").map {
            LibrarySeat(it.id().removePrefix("seat_"), it.selectFirst("dt")?.text().orEmpty(),
                it.selectFirst("dd")?.text().orEmpty(), it.attr("title").ifBlank { it.className() },
                it.hasClass("free"))
        }.filter { it.id.isNotBlank() && it.label.isNotBlank() }

        fun parseSeatPage(response: String): SeatPage {
            val data = json(response)
            val verification = data.optBoolean("limit")
            val seats = if (verification) emptyList() else parseSeats(data.optString("seatStr"))
            return SeatPage(seats, data.optInt("seatNum", seats.size),
                data.optInt("offset", -1).takeIf { it > 0 && seats.isNotEmpty() }, verification,
                data.optString("msg").ifBlank {
                    if (verification) "查询频率过快，请在原页面完成验证" else if (seats.isEmpty()) "没有符合条件的座位" else ""
                })
        }

        fun parseTimes(response: String): SeatTimes {
            if (response.trimStart().startsWith("{")) {
                val data = json(response)
                val verification = data.optBoolean("limit")
                return SeatTimes(emptyList(), message = data.optString("msg").ifBlank {
                    if (verification) "查询频率过快，请在原页面完成验证" else "该座位暂无可用时段"
                }, verificationRequired = verification)
            }
            val document = LibraryHtml.parse(response)
            if (document.selectFirst("form[action='/auth/signIn']") != null)
                throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录")
            val options = document.select("a[time]").filterNot { it.hasClass("disable") || it.hasClass("disabled") }
                .map { LibraryOption(it.attr("time"), it.text()) }.filter { it.value.isNotBlank() }
            return SeatTimes(options, document.selectFirst("#building")?.attr("value").orEmpty(),
                document.selectFirst("#room")?.attr("value").orEmpty(),
                if (options.isEmpty()) document.body().text().ifBlank { "该座位暂无可用时段" } else "",
                response.contains("clickVerifyCode(") || response.contains("您操作的频率太快"))
        }

        fun parseHistory(document: Document): ReservationHistory {
            val container = document.selectFirst(".myReserveList, #myReserveList") ?: document.body()
            val items = container.select("dl").filterNot { it.id() == "moreBlock" || it.select("dd").isEmpty() }.map { item ->
                val details = item.select("dd").map { it.text() }.filter { it.isNotBlank() }
                val cancel = item.selectFirst("a[href*=cancel]")?.attr("href")?.takeIf { it.startsWith("/") }
                // Older pages omit DOM IDs. Content-based IDs remain stable across pagination.
                val contentId = MessageDigest.getInstance("SHA-256").digest(item.text().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                LibraryHistoryItem(item.id().ifBlank { "history_$contentId" }, item.selectFirst("dt")?.text().orEmpty(),
                    details, details.firstOrNull { it.contains("状态") }.orEmpty(), cancel)
            }
            val scripts = document.select("script").joinToString("\n") { it.data() }
            val status = Regex("currentReservationStatus\"\\s*:\\s*\"([^\"]+)\"").find(scripts)?.groupValues?.get(1).orEmpty()
            val message = Regex("showmsg\\(['\"]([^'\"]+)").find(scripts)?.groupValues?.get(1)
                ?: if (items.isEmpty()) "暂无预约记录" else ""
            val moreVisible = document.selectFirst("#moreBlock .more")?.attr("style")?.contains("display: none") != true
            val offset = if (moreVisible) document.selectFirst("#offset")?.attr("value")?.toIntOrNull()?.takeIf { it > 0 } else null
            return ReservationHistory(items, offset, status, message)
        }

        fun parseUserState(document: Document): LibraryUserState {
            val scripts = document.select("script").joinToString("\n") { it.data() }
            val encoded = Regex("""var\s+userInfo\s*=\s*'([^']+)'""").find(scripts)?.groupValues?.get(1)
            val user = try { encoded?.let(::JSONObject) } catch (_: Exception) { null }
            val status = user?.optString("currentReservationStatus").orEmpty()
            val soft = user?.optBoolean("softMode") == true
            val actions = mutableListOf<String>()
            if (soft && status != "LOC_RESTRICT") {
                when (status) {
                    "RESERVE" -> actions += "checkIn"
                    "CHECK_IN" -> {
                        actions += "extend"
                        if (user?.optBoolean("wifiAwayStopDisabled", user.optBoolean("wifiAwayDisabled")) != true) actions += "leave"
                        if (user?.optBoolean("wifiStopDisabled") != true) actions += "stopUsing"
                    }
                    "AWAY" -> actions += listOf("resume", "stopUsing")
                    "COMPLETE" -> actions += "stopUsing"
                }
                if (user?.optBoolean("hasPrevUnStoppedReservation") == true && "stopUsing" !in actions) actions += "stopUsing"
            }
            val details = document.select(".receipt dd, #receipt dd, .reserveResult dd").map { it.text() }.filter { it.isNotBlank() }
            return LibraryUserState(status, soft, details, actions)
        }

        private fun parseReceipt(data: JSONObject): ReservationReceipt = ReservationReceipt(
            data.optString("receipt"), data.optString("onDate"), data.optString("begin"), data.optString("end"),
            data.optString("location"), data.optString("statusDisplay").ifBlank { data.optString("status") })

        fun parseReservationResult(document: Document): ReservationResult {
            val receiptContainer = document.selectFirst(".receipt, #receipt, .reserveResult, .reserveSuccess")
            val text = document.body().text()
            val message = document.selectFirst("#msgBoxDIV .msg, .errors, .error, .alert-danger")?.text()?.takeIf { it.isNotBlank() }
                ?: Regex("(?:flashMsg\\s*=\\s*|showmsg\\()\\s*['\"]([^'\"]+)")
                    .find(document.select("script").joinToString("\n") { it.data() })?.groupValues?.get(1)
                ?: if (document.select("p, h1, h2, h3, .resultMessage").any { it.text() in setOf("预约成功", "座位预约成功", "预约成功！", "预约成功!", "预约成功。") })
                    "预约成功" else "已提交预约，请在我的预约中确认结果"
            val successMessages = setOf("预约成功", "座位预约成功", "预约成功！", "预约成功!", "预约成功。")
            val receiptFields = receiptContainer?.select("dd")?.map { it.text() }.orEmpty()
            fun receiptField(label: String) = receiptFields.firstOrNull { it.replace(" ", "").startsWith(label) }
                ?.substringAfter('：', "")?.ifBlank { null }
            val number = receiptField("凭证号")
            val date = receiptField("日期")
            val time = receiptField("时间")?.split(Regex("\\s*-\\s*"))
            val receipt = if (!number.isNullOrBlank() && !date.isNullOrBlank() && time?.size == 2)
                ReservationReceipt(number, date, time[0], time[1], receiptField("位置").orEmpty(), receiptField("状态").orEmpty())
                else null
            val confirmed = (receipt != null && !message.contains("失败")) || message in successMessages
            return ReservationResult(confirmed, message, receipt)
        }
    }
}
