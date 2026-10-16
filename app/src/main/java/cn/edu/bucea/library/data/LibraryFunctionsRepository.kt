package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Native library functions using the same per-account authenticated campus session. */
class LibraryFunctionsRepository(
    private val request: suspend (SavedAccount, String) -> String,
    private val post: suspend (SavedAccount, String, Map<String, String>) -> String,
) {
    constructor(library: LibraryRepository) : this(library::request, library::post)
    private val submittedTokens = mutableSetOf<String>()
    private val cancellationsInFlight = mutableSetOf<String>()
    private data class Catalog(val loadedAt: Long, val seats: List<LibrarySeat>)
    private val catalogs = mutableMapOf<String, Catalog>()
    suspend fun options(account: SavedAccount): SeatSearchOptions =
        parseOptions(authenticatedPage(account, "/self"))

    suspend fun rooms(account: SavedAccount, building: String): List<LibraryOption> =
        parseOptionsFragment(request(account, path("/freeBook/ajaxGetRooms", "id" to building)))

    suspend fun search(account: SavedAccount, query: SeatQuery, offset: Int = 0): SeatPage {
        require(query.onDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "请选择预约日期" }
        require(offset >= 0)
        val name = query.name.trim()
        require(name.isEmpty() || name.matches(Regex("[0-9]{1,12}[A-Za-z]?"))) { "请输入座位数字编号，可带一位字母，例如001或001D" }
        if (name.isNotBlank()) return searchCatalog(account, query.copy(name = name))
        val fields = listOf("onDate" to query.onDate, "building" to query.building, "room" to query.room,
            "hour" to query.hour, "power" to query.power, "window" to query.window,
            "startMin" to query.startMin, "endMin" to query.endMin, "offset" to offset.toString())
        val request = path("/freeBook/ajaxSearch", *fields.toTypedArray())
        return parseSeatPage(this.request(account, request))
    }

    private suspend fun searchCatalog(account: SavedAccount, query: SeatQuery): SeatPage {
        // The school's numeric name endpoint misses lettered labels. Use its documented
        // room maps, whose complete catalog exposes label, date availability and local-only flag.
        val choices = if (query.building == "null") options(account).rooms else rooms(account, query.building)
        val selectedRooms = choices.filter { it.value != "null" && (query.room == "null" || it.value == query.room) }
        require(selectedRooms.size <= 20) { "请先选择房间，再按座位号查询" }
        val matches = mutableListOf<LibrarySeat>()
        for (room in selectedRooms) {
            val key = "${account.id}|${query.onDate}|${room.value}"
            val cached = synchronized(catalogs) { catalogs[key]?.takeIf { System.currentTimeMillis() - it.loadedAt < 30_000 } }
            val seats = cached?.seats ?: run {
                val response = request(account, path("/mapBook/getSeatsByRoom", "room" to room.value, "date" to query.onDate))
                if (response.trimStart().startsWith("{")) {
                    val data = json(response)
                    if (data.optBoolean("limit")) return SeatPage(emptyList(), 0, null, true,
                        "查询频率过快，请完成图片验证", data.optString("userId"))
                }
                val document = LibraryHtml.parse(response)
                if (document.selectFirst("form[action='/auth/signIn']") != null)
                    throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录")
                if (response.contains("clickVerifyCode(") || response.contains("您操作的频率太快"))
                    return SeatPage(emptyList(), 0, null, true, "查询频率过快，请完成图片验证")
                parseMapSeats(document, room.label).also { parsed ->
                    synchronized(catalogs) {
                        catalogs.entries.removeAll { System.currentTimeMillis() - it.value.loadedAt >= 30_000 }
                        catalogs[key] = Catalog(System.currentTimeMillis(), parsed)
                    }
                }
            }
            matches += seats.filter { seat -> seat.available && matchesName(seat.label, query.name) }
        }
        val seats = matches.distinctBy { it.id }
        return SeatPage(seats, seats.size, null, message = if (seats.isEmpty())
            "没有找到匹配的空闲座位，可换一个编号或房间" else "按编号查询展示所选日期和区域的空闲座位；可用时段请点选确认")
    }

    /** Frequent seats are selected by the school; local bookmarks are stored separately. */
    suspend fun frequentSeats(account: SavedAccount): SeatPage {
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

    /** A cancellation is offered only when the account's fresh history exposes that exact action. */
    suspend fun cancelHistory(account: SavedAccount, record: LibraryHistoryItem): ReservationResult {
        val operation = "${account.id}|${record.id}"
        synchronized(cancellationsInFlight) {
            require(cancellationsInFlight.add(operation)) { "正在处理这条预约，请等待结果后刷新记录" }
        }
        return try { cancelHistoryOnce(account, record) }
        finally { synchronized(cancellationsInFlight) { cancellationsInFlight.remove(operation) } }
    }

    private suspend fun cancelHistoryOnce(account: SavedAccount, record: LibraryHistoryItem): ReservationResult {
        val action = record.cancelPath ?: throw IllegalArgumentException("这条记录当前不能取消")
        val (freshDocument, matched) = findHistoryRecord(account, record)
            ?: throw IllegalArgumentException("预约状态已改变，请同步记录后重试")
        require(matched.cancelPath == action && matched.cancelMethod == record.cancelMethod) {
            "预约状态已改变，请同步记录后重试"
        }
        val url = (LibraryRepository.BASE_URL + action).toHttpUrl()
        require(action.startsWith("/") && !action.startsWith("//") && url.host == "10.1.20.7" &&
            url.encodedPath.contains("cancel", ignoreCase = true)) { "取消操作参数异常，请更新记录" }
        val response = if (matched.cancelMethod == "POST") {
            require(url.encodedPath == "/reservation/cancel" && !matched.reservationId.isNullOrBlank()) { "取消操作参数异常，请更新记录" }
            val form = freshDocument.select("form[action][method]").firstOrNull {
                schoolPath(it.attr("action")) == action && it.attr("method").equals("post", true) &&
                    it.selectFirst("input[name=id]")?.attr("value") == matched.reservationId
            } ?: throw IllegalArgumentException("预约状态已改变，请同步记录后重试")
            val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
            require(!fields["SYNCHRONIZER_TOKEN"].isNullOrBlank() && fields["SYNCHRONIZER_URI"] == "/history" &&
                fields["id"] == matched.reservationId) { "取消预约校验参数已失效，请刷新记录" }
            markSubmitted(account.id, "cancel", fields.getValue("SYNCHRONIZER_TOKEN"))
            post(account, action, fields)
        } else request(account, action)
        if (response.trimStart().startsWith("{")) {
            val data = json(response)
            return ReservationResult(data.optString("status") == "success", data.optString("message").ifBlank { "取消请求已提交，请同步记录核实" })
        }
        val document = LibraryHtml.parse(response)
        if (document.selectFirst("form[action='/auth/signIn']") != null) throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录")
        val canceled = parseHistory(document).items.firstOrNull { it.id == record.id }
        if (canceled?.status?.contains("取消") == true)
            return ReservationResult(true, "取消预约成功")
        val scripts = document.select("script").joinToString("\n") { it.data() }
        val message = document.selectFirst("#msgBoxDIV .msg, .errors, .error")?.text()?.takeIf { it.isNotBlank() }
            ?: Regex("""flashMsg\s*=\s*['"]([^'"]+)['"]""").find(scripts)?.groupValues?.get(1)
            ?: "取消请求已提交，请同步记录核实结果"
        if (message.trimEnd('！', '!', '。') in setOf("取消成功", "预约已取消", "取消预约成功"))
            return ReservationResult(true, message)
        // The school's POST can redirect to a generic page. Confirm state via a fresh GET,
        // rather than treating an HTTP 200 or a successful submission as cancellation evidence.
        try {
            val confirmed = findHistoryRecord(account, matched)?.second
            if (confirmed?.status?.contains("取消") == true) return ReservationResult(true, "取消预约成功")
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Preserve the unknown result; never send the mutation again. */ }
        return ReservationResult(false, message)
    }

    /** Reads only, including older pages when the reservation is not in the first batch. */
    private suspend fun findHistoryRecord(account: SavedAccount, record: LibraryHistoryItem): Pair<Document, LibraryHistoryItem>? {
        var document = authenticatedPage(account, "/history?type=SEAT")
        var parsed = parseHistory(document)
        val visitedOffsets = mutableSetOf<Int>()
        while (true) {
            val match = parsed.items.firstOrNull { it.id == record.id ||
                (record.reservationId != null && it.reservationId == record.reservationId) }
            if (match != null) return document to match
            val offset = parsed.nextOffset ?: return null
            if (!visitedOffsets.add(offset) || visitedOffsets.size > 10) return null
            val data = json(request(account, path("/user/viewMoreHistory", "type" to "SEAT", "offset" to offset.toString())))
            document = LibraryHtml.parse(data.optString("resStr"))
            parsed = parseHistory(document).copy(nextOffset =
                data.optInt("offset", -1).takeIf { it >= 0 && data.optInt("resNum") > 0 })
        }
    }

    suspend fun reservationDetails(account: SavedAccount, record: LibraryHistoryItem): LibraryHistoryItem {
        val target = record.detailPath ?: throw IllegalArgumentException("这条记录没有可读取的详情")
        val url = (LibraryRepository.BASE_URL + target).toHttpUrl()
        require(target.startsWith("/") && !target.startsWith("//") && url.encodedPath == "/view" &&
            url.queryParameter("id") == record.reservationId && url.queryParameter("type") == "SEAT") { "预约详情参数无效" }
        val document = authenticatedPage(account, target)
        val receipt = parseReservationResult(document).receipt
        val details = receipt?.let { listOf("凭证号：${it.number}", "日期：${it.onDate}", "时间：${it.begin} - ${it.end}",
            "位置：${it.location}", "状态：${it.status}") }
            ?: document.select(".receipt dd, #receipt dd, .reserveResult dd, .layoutSeat dd, .myReserveList dd, .reserveTime dd")
                .map { it.text() }.filter { it.isNotBlank() }
        val updatedSchedule = receipt?.let { ReservationSchedules.fromReceipt(it) }
            ?: ReservationSchedules.parse("", details)
        val schedule = updatedSchedule.copy(
            date = if (updatedSchedule.dateLabel != null) updatedSchedule.date else record.schedule.date,
            begin = updatedSchedule.begin ?: record.schedule.begin,
            end = updatedSchedule.end ?: record.schedule.end,
            dateLabel = updatedSchedule.dateLabel ?: record.schedule.dateLabel,
        )
        return record.copy(details = details.ifEmpty { record.details },
            status = receipt?.status?.ifBlank { record.status } ?: record.status, schedule = schedule)
    }

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
        require(action in state.actions) {
            when {
                state.status == "LOC_RESTRICT" || !state.softMode || state.status.isBlank() -> state.actionMessage
                action == "checkIn" && state.status == "NO_RESERVATION" -> "当前没有可签到的预约，请同步预约记录"
                else -> "当前预约没有此操作，请同步预约状态后重试"
            }
        }
        val endpoint = if (action == "extend") {
            require(!endTime.isNullOrBlank() && endTime.toIntOrNull() != null) { "请选择续约结束时间" }
            require(extensionTimes(account).any { it.value == endTime }) { "该续约时段已失效，请重新选择" }
            path("/user/extend", "endTime" to endTime)
        } else "/user/$action"
        val data = json(request(account, endpoint))
        val success = data.optString("status") == "success"
        val receipt = (data.optJSONObject("data")?.optJSONObject("reservation") ?: data.optJSONObject("data"))
            ?.takeIf { it.has("receipt") }?.let(::parseReceipt)
        val message = data.optString("message").ifBlank {
            if (success) "操作成功" else "操作未成功，请刷新当前预约核实结果"
        }
        val locationRestricted = data.optString("status") == "LOC_RESTRICT" ||
            data.optJSONObject("data")?.optString("status") == "LOC_RESTRICT" ||
            message.contains("仅在场馆内") || message.contains("馆内网络")
        val guidance = if (!success && (action == "checkIn" || locationRestricted)) {
            "$message；签到等座位操作需连接图书馆 Wi-Fi，连接后请同步预约状态"
        } else message
        return ReservationResult(success, guidance, receipt)
    }

    suspend fun prepareReservation(account: SavedAccount, selection: ReservationSelection): PreparedReservation {
        val document = authenticatedPage(account, "/self")
        val form = document.selectFirst("form#reserveForm[action='/selfRes']")
            ?: throw LoginFailure(FailureKind.PROTOCOL, "预约接口已变更，请更新应用后重试")
        val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
        if (fields["SYNCHRONIZER_TOKEN"].isNullOrBlank())
            throw LoginFailure(FailureKind.PROTOCOL, "预约页面缺少校验令牌，请重试")
        val scripts = document.select("script").joinToString("\n") { it.data() }
        // Verification-enabled installations require a server-validated native challenge.
        val verification = scripts.contains(Regex("""reservationCaptcha[^\n]*(?:\.html|\.append|captcha|Captcha)""")) ||
            form.selectFirst("input[name=authid]")?.attr("value")?.let { it.isNotBlank() && it != "-1" } == true
        return PreparedReservation(selection, fields, verification, account.id)
    }

    /** Call only after the user confirms the displayed seat, date and times. */
    suspend fun reserve(account: SavedAccount, reservation: PreparedReservation): ReservationResult {
        require(!reservation.verificationRequired || !reservation.verificationAuthId.isNullOrBlank()) { "请先完成预约图片验证" }
        require(reservation.accountId == account.id) { "账号已切换，请重新选择座位" }
        val selection = reservation.selection
        require(runCatching { LocalDate.parse(selection.onDate) }.isSuccess && selection.seat.id.matches(Regex("[0-9]+"))) {
            "预约日期或座位参数无效，请重新选择"
        }
        val startMinutes = selection.start.value.toIntOrNull()
        val endMinutes = selection.end.value.toIntOrNull()
        val startsNow = selection.start.value == "now"
        val validStart = if (startsNow) selection.onDate == LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
            else startMinutes != null && startMinutes in 0..1439 && endMinutes != null && startMinutes < endMinutes
        require(validStart && endMinutes != null && endMinutes in 1..1440) { "请选择有效的开始和结束时间" }
        require(selection.seat.available) { "该座位当前不可预约" }
        val fields = reservation.fields + mapOf("date" to selection.onDate, "seat" to selection.seat.id,
            "start" to selection.start.value, "end" to selection.end.value) +
            reservation.verificationAuthId?.let { mapOf("authid" to it) }.orEmpty()
        val token = requireNotNull(fields["SYNCHRONIZER_TOKEN"])
        markSubmitted(account.id, "reserve", token)
        val response = post(account, "/selfRes", fields)
        val document = LibraryHtml.parse(response)
        if (document.selectFirst("form[action='/auth/signIn']") != null)
            throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录后预约")
        return parseReservationResult(document)
    }

    private fun markSubmitted(accountId: String, action: String, token: String) {
        require(token.isNotBlank()) { "操作校验令牌无效，请刷新后重试" }
        synchronized(submittedTokens) {
            require(submittedTokens.add("$accountId|$action|$token")) { "此操作已提交，请先查看预约状态" }
        }
    }

    private suspend fun authenticatedPage(account: SavedAccount, path: String): Document {
        val document = LibraryHtml.parse(request(account, path))
        val identity = document.selectFirst("#sysUsername")?.attr("value")
        if (document.selectFirst("form[action='/auth/signIn']") != null || (identity != null && identity != account.username))
            throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录")
        return document
    }

    companion object {
        /** Normalize only URLs on the school origin, never an external form/link target. */
        private fun schoolPath(target: String): String? {
            val base = LibraryRepository.BASE_URL.toHttpUrl()
            val url = base.resolve(target.trim()) ?: return null
            if (target.isBlank() || url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
                url.username.isNotBlank() || url.password.isNotBlank()) return null
            return url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        }

        private fun matchesName(label: String, name: String): Boolean {
            fun number(value: String) = value.takeWhile { it.isDigit() }.trimStart('0').ifEmpty { "0" }
            return number(label) == number(name) &&
                (!name.last().isLetter() || label.last().equals(name.last(), ignoreCase = true))
        }

        fun parseMapSeats(document: Document, room: String): List<LibrarySeat> = document.select("li[id^=seat_]").mapNotNull {
            val link = it.selectFirst("a") ?: return@mapNotNull null
            LibrarySeat(it.id().removePrefix("seat_"), link.text(), room, link.attr("title").ifBlank { link.className() },
                link.hasClass("idle") && link.attr("local") != "true")
        }.filter { it.id.isNotBlank() && it.label.isNotBlank() }

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
                    if (verification) "查询频率过快，请完成图片验证" else if (seats.isEmpty()) "没有符合条件的座位" else ""
                }, data.optString("userId"))
        }

        fun parseTimes(response: String): SeatTimes {
            if (response.trimStart().startsWith("{")) {
                val data = json(response)
                val verification = data.optBoolean("limit")
                return SeatTimes(emptyList(), message = data.optString("msg").ifBlank {
                    if (verification) "查询频率过快，请完成图片验证" else "该座位暂无可用时段"
                }, verificationRequired = verification, verificationUserId = data.optString("userId"))
            }
            val document = LibraryHtml.parse(response)
            if (document.selectFirst("form[action='/auth/signIn']") != null)
                throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录")
            val options = document.select("a[time]").filterNot { it.hasClass("disable") || it.hasClass("disabled") ||
                it.parent()?.hasClass("disabled") == true || it.parent()?.hasClass("disable") == true }
                .map { LibraryOption(it.attr("time"), it.text()) }.filter { option ->
                    (option.value == "now" || option.value.toIntOrNull()?.let { it in 0..1440 } == true) && option.label.isNotBlank()
                }
                .distinctBy { it.value }
            return SeatTimes(options, document.selectFirst("#building")?.attr("value").orEmpty(),
                document.selectFirst("#room")?.attr("value").orEmpty(),
                if (options.isEmpty()) document.body().text().ifBlank { "该座位暂无可用时段" } else "",
                response.contains("clickVerifyCode(") || response.contains("您操作的频率太快"))
        }

        fun parseHistory(document: Document, referenceDate: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))): ReservationHistory {
            val container = document.selectFirst(".myReserveList, #myReserveList") ?: document.body()
            val items = container.select("dl").filterNot { it.id() == "moreBlock" || it.select("dd").isEmpty() }.map { item ->
                val details = item.select("dd").map { it.text() }.filter { it.isNotBlank() }
                val detail = item.select("a[href]").mapNotNull { schoolPath(it.attr("href")) }.firstOrNull {
                    val url = (LibraryRepository.BASE_URL + it).toHttpUrl()
                    url.encodedPath == "/view" && url.queryParameter("type") == "SEAT" &&
                        url.queryParameter("id")?.matches(Regex("[0-9]+")) == true
                }
                val detailId = detail?.let { (LibraryRepository.BASE_URL + it).toHttpUrl().queryParameter("id") }
                // The school normally renders each record's status as a .normal anchor,
                // but historical fragments may turn that label into a link (or another
                // element with the same class). Keep the displayed status independent of
                // the link/action shape so terminal labels such as “结束使用” survive.
                val normalLabels = item.select(".normal").filter { it.closest("form") == null && it.text().isNotBlank() }
                val status = normalLabels.lastOrNull { it.text().trim() in ReservationStatuses.knownLabels }?.text()
                    ?: normalLabels.lastOrNull { it.tagName() != "a" || it.attr("href").isBlank() }?.text()
                    ?: details.firstOrNull { it.contains("状态") }
                    ?: item.select("a, span, em, strong, b").lastOrNull {
                        it.closest("form") == null && it.text().trim() in ReservationStatuses.knownLabels
                    }?.text().orEmpty()
                val inactive = ReservationStatuses.isInactive(status)
                val reservationForm = item.select("form[action][method]").firstOrNull {
                    val id = it.selectFirst("input[name=id]")?.attr("value")
                    schoolPath(it.attr("action")) == "/reservation/cancel" &&
                        it.attr("method").equals("post", true) && id?.matches(Regex("[0-9]+")) == true &&
                        (detailId == null || id == detailId)
                }
                // A cancellation form also identifies the server record when its detail link is absent.
                val serverId = detailId ?: reservationForm?.selectFirst("input[name=id]")?.attr("value")
                val cancelForm = reservationForm?.takeUnless { inactive }
                val cancel = cancelForm?.let { schoolPath(it.attr("action")) }
                    ?: if (inactive) null else item.select("a[href]").mapNotNull { schoolPath(it.attr("href")) }
                        .firstOrNull {
                            val url = (LibraryRepository.BASE_URL + it).toHttpUrl()
                            url.encodedPath.contains("cancel", true) &&
                                (serverId == null || url.queryParameter("id") == null || url.queryParameter("id") == serverId)
                        }
                // Older pages omit DOM IDs. Content-based IDs remain stable across pagination.
                val contentId = MessageDigest.getInstance("SHA-256").digest(item.text().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val title = item.selectFirst("dt")?.text().orEmpty()
                LibraryHistoryItem(serverId?.let { "reservation_$it" } ?: item.id().ifBlank { "history_$contentId" },
                    title, details, status, cancel,
                    cancel?.let { if (cancelForm != null) "POST" else "GET" }, serverId, detail,
                    ReservationSchedules.parse(title, details, referenceDate))
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
                    "CHECK_IN", "IN_USE", "USING", "CHECKED_IN" -> {
                        actions += "extend"
                        if (user?.optBoolean("wifiAwayStopDisabled", user.optBoolean("wifiAwayDisabled")) != true) actions += "leave"
                        if (user?.optBoolean("wifiStopDisabled") != true) actions += "stopUsing"
                    }
                    "AWAY" -> actions += listOf("resume", "stopUsing")
                    "COMPLETE" -> actions += "stopUsing"
                }
                if (user?.optBoolean("hasPrevUnStoppedReservation") == true && "stopUsing" !in actions) actions += "stopUsing"
            }
            val details = document.select(".receipt dd, #receipt dd, .reserveResult dd, .layoutSeat dd").map { it.text() }.filter { it.isNotBlank() }
            val actionMessage = when {
                status == "LOC_RESTRICT" -> "签到等座位操作需连接图书馆 Wi-Fi，连接后请同步预约状态"
                status == "NO_RESERVATION" -> "当前没有可签到的预约，已预约座位请查看预约记录"
                status.isBlank() -> "暂未读取到当前预约状态，请同步后重试"
                !soft -> "图书馆系统暂未开放在线座位操作，请连接图书馆 Wi-Fi 后同步，或按馆内指引签到"
                status == "RESERVE" -> "签到前请连接图书馆 Wi-Fi，并在预约允许的时间内操作"
                actions.isEmpty() -> "当前预约没有可执行的座位操作，请同步预约状态"
                else -> "座位操作以图书馆系统当前提供的选项为准"
            }
            return LibraryUserState(status, soft, details, actions, actionMessage,
                status in setOf("RESERVE", "CHECK_IN", "AWAY", "COMPLETE", "LOC_RESTRICT"))
        }

        private fun parseReceipt(data: JSONObject): ReservationReceipt = ReservationReceipt(
            data.optString("receipt"), data.optString("onDate"), data.optString("begin"), data.optString("end"),
            data.optString("location"), data.optString("statusDisplay").ifBlank { data.optString("status") })

        fun parseReservationResult(document: Document): ReservationResult {
            val receiptContainer = document.selectFirst(".receipt, #receipt, .reserveResult, .reserveSuccess, .layoutSeat")
            val text = document.body().text()
            var message = document.selectFirst("#msgBoxDIV .msg, .errors, .error, .alert-danger")?.text()?.takeIf { it.isNotBlank() }
                ?: Regex("(?:flashMsg\\s*=\\s*|showmsg\\()\\s*['\"]([^'\"]+)")
                    .find(document.select("script").joinToString("\n") { it.data() })?.groupValues?.get(1)
                ?: if (document.select("p, h1, h2, h3, .resultMessage").any { it.text() in setOf("预约成功", "座位预约成功", "预约成功！", "预约成功!", "预约成功。") })
                    "预约成功" else "已提交预约，请在我的预约中确认结果"
            val successMessages = setOf("预约成功", "座位预约成功", "预约成功！", "预约成功!", "预约成功。")
            val receiptFields = receiptContainer?.select("dd")?.map { it.text() }.orEmpty()
            fun receiptField(label: String) = receiptFields.firstOrNull { it.replace(Regex("[\\s\\u00a0]"), "").startsWith(label) }
                ?.substringAfter('：', "")?.trim()?.ifBlank { null }
            val number = receiptField("凭证号")
            val date = receiptField("日期")?.let { value ->
                Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日""").find(value)?.let { match ->
                    "%s-%02d-%02d".format(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt())
                } ?: value.trim()
            }
            val time = receiptField("时间")?.replace(Regex("[\\s\\u00a0]"), "")?.split('-')
            val receipt = if (!number.isNullOrBlank() && !date.isNullOrBlank() && time?.size == 2)
                ReservationReceipt(number, date, time[0], time[1], receiptField("位置").orEmpty(), receiptField("状态").orEmpty())
                else null
            val inactiveReceipt = receipt?.status?.let(ReservationStatuses::isInactive) == true
            val activeReceipt = receipt != null && receipt.status in setOf("预约", "已预约", "待签到")
            val explicitFailure = document.select(".errors, .error, .alert-danger").any { it.text().isNotBlank() }
            val confirmed = !explicitFailure && !inactiveReceipt && ((activeReceipt && !message.contains("失败")) || message in successMessages)
            if (receipt != null && confirmed && message == "已提交预约，请在我的预约中确认结果") message = "预约成功"
            return ReservationResult(confirmed, message, receipt)
        }
    }
}
