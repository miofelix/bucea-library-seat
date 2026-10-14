package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/** Reads layout data and produces native coordinates; no HTML, script or CSS is rendered. */
class NativeMapRepository(private val request: suspend (SavedAccount, String) -> String) {
    constructor(library: LibraryRepository) : this(library::request)
    class VerificationRequired(val userId: String) : IOException("学校要求额外验证，请先完成验证")

    suspend fun options(account: SavedAccount): NativeMapOptions {
        val document = authenticatedPage(account, "/map")
        fun options(name: String) = LibraryFunctionsRepository.parseOptionsFragment(document.selectFirst("#options_$name")?.html().orEmpty())
        return NativeMapOptions(options("onDate"), options("building"), options("floor"))
    }

    suspend fun floors(account: SavedAccount, building: String): List<LibraryOption> {
        val response = request(account, path("/mapBook/ajaxGetFloor", "id" to building))
        requireData(response)
        verificationUserId(response)?.let { throw VerificationRequired(it) }
        return LibraryFunctionsRepository.parseOptionsFragment(response)
    }

    suspend fun rooms(account: SavedAccount, onDate: String, building: String, floor: String): NativeMapRooms {
        requireDate(onDate)
        return parseRooms(request(account, path("/mapBook/ajaxGetRooms", "building" to building, "floor" to floor, "onDate" to onDate)))
    }

    suspend fun layout(account: SavedAccount, room: NativeMapRoom, onDate: String): NativeRoomLayout {
        requireDate(onDate)
        val response = request(account, path("/mapBook/getSeatsByRoom", "room" to room.id, "date" to onDate))
        return parseLayout(response, room.id, room.name)
    }

    private suspend fun authenticatedPage(account: SavedAccount, endpoint: String): Document {
        val response = request(account, endpoint)
        requireData(response)
        verificationUserId(response, fullPage = true)?.let { throw VerificationRequired(it) }
        val document = LibraryHtml.parse(response)
        document.selectFirst("#sysUsername")?.attr("value")?.let {
            if (it != account.username) throw LoginFailure(FailureKind.CREDENTIALS, "登录状态已变化，请重新登录")
        }
        return document
    }

    companion object {
        private fun requireDate(value: String) { require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "请选择预约日期" } }
        private fun path(endpoint: String, vararg fields: Pair<String, String>): String {
            val url = (LibraryRepository.BASE_URL + endpoint).toHttpUrl().newBuilder().apply {
                fields.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
            return url.encodedPath + "?" + url.encodedQuery
        }

        private fun requireData(value: String) {
            if (LibraryHtml.parse(value).selectFirst("form[action='/auth/signIn']") != null)
                throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录")
        }

        /** An empty ID is still a challenge; null alone means no rate-limit/verification response. */
        private fun verificationUserId(response: String, fullPage: Boolean = false): String? {
            val data = if (response.trimStart().startsWith("{")) runCatching { JSONObject(response) }.getOrNull() else null
            val verification = if (fullPage) {
                val document = LibraryHtml.parse(response)
                // Authenticated /map pages wire clickVerifyCode into callbacks even without a challenge.
                // Only a direct standalone invocation or a visible rate-limit is evidence on a full page.
                val directCall = Regex("""^clickVerifyCode\s*\([\s\S]*?\)\s*;?\s*$""")
                document.body().text().contains("您操作的频率太快") ||
                    document.select("script").any { directCall.matches(it.data().trim()) }
            } else response.contains("clickVerifyCode(") || response.contains("您操作的频率太快")
            return if (verification || data?.optBoolean("limit") == true)
                data?.optString("userId").orEmpty() else null
        }

        fun parseRooms(response: String): NativeMapRooms {
            requireData(response)
            verificationUserId(response)?.let {
                return NativeMapRooms(emptyList(), true, "学校要求额外验证，请先完成验证", it)
            }
            val data = try { JSONObject(response) } catch (_: Exception) {
                throw LoginFailure(FailureKind.PROTOCOL, "无法读取阅览区域，请刷新重试")
            }
            val array = data.optJSONArray("rooms")
                ?: throw LoginFailure(FailureKind.PROTOCOL, "无法读取阅览区域，请刷新重试")
            val rooms = (0 until array.length()).mapNotNull { index ->
                val room = array.optJSONObject(index) ?: return@mapNotNull null
                val id = room.optString("id")
                val name = room.optString("name")
                if (id.isBlank() || name.isBlank()) null else NativeMapRoom(id, name)
            }
            if (array.length() > 0 && rooms.isEmpty())
                throw LoginFailure(FailureKind.PROTOCOL, "阅览区域数据无法读取，请刷新重试")
            val incomplete = rooms.size != array.length()
            return NativeMapRooms(rooms, message = if (incomplete) "部分阅览区域数据无法读取" else data.optString("msg").ifBlank {
                if (rooms.isEmpty()) "该楼层暂无可预约区域" else ""
            }, incomplete = incomplete)
        }

        fun parseLayout(response: String, roomId: String, roomName: String): NativeRoomLayout {
            requireData(response)
            verificationUserId(response)?.let {
                return NativeRoomLayout(roomId, roomName, 0, 0, emptyList(), true,
                    "学校要求额外验证，请先完成验证", it)
            }
            val document = LibraryHtml.parse(response)
            val container = document.selectFirst("#seatLayout") ?: document.body()
            val table = container.selectFirst("table")
            val rows = if (table != null) table.select("tr").filter { it.parents().firstOrNull { parent -> parent.tagName() == "table" } === table }
                else container.select("ul").filter { row -> row.children().any { it.tagName() == "li" } && row.parents().none { it.tagName() == "li" } }
            val cells = mutableListOf<NativeMapCell>()
            val occupied = mutableSetOf<Pair<Int, Int>>()
            var maximumColumns = 0
            var maximumRows = rows.size
            rows.forEachIndexed { rowIndex, row ->
                var column = 0
                val elements = row.children().filter { it.tagName() in setOf("li", "td", "th") }
                elements.forEach { element ->
                    while ((rowIndex to column) in occupied) column++
                    val rowSpan = element.attr("rowspan").toIntOrNull()?.coerceIn(1, 100) ?: 1
                    val columnSpan = element.attr("colspan").toIntOrNull()?.coerceIn(1, 100) ?: 1
                    cells += parseCell(element, rowIndex, column, rowSpan, columnSpan, roomName)
                    for (r in rowIndex until rowIndex + rowSpan) for (c in column until column + columnSpan) occupied += r to c
                    column += columnSpan
                    maximumRows = maxOf(maximumRows, rowIndex + rowSpan)
                }
                maximumColumns = maxOf(maximumColumns, column)
            }
            if (rows.isEmpty()) {
                // Some deployments return bare seat elements; retain their order as one row.
                container.select("li[id^=seat_]").forEachIndexed { index, element -> cells += parseCell(element, 0, index, 1, 1, roomName) }
                maximumRows = if (cells.isEmpty()) 0 else 1
                maximumColumns = cells.size
            }
            val message = if (cells.none { it.seat != null }) container.text().replace('\u00a0', ' ').trim().ifBlank { "该区域暂未提供座位布局" } else ""
            return NativeRoomLayout(roomId, roomName, maximumRows, maximumColumns, cells, message = message)
        }

        private fun parseCell(element: Element, row: Int, column: Int, rowSpan: Int, columnSpan: Int, room: String): NativeMapCell {
            val seatElement = if (element.id().startsWith("seat_")) element else element.selectFirst("[id^=seat_]")
            val anchor = seatElement?.selectFirst("a")
            val classes = (element.className() + " " + anchor?.className().orEmpty()).lowercase()
            val classTokens = classes.split(Regex("\\s+"))
            fun hasStatus(vararg names: String) = classTokens.any { token ->
                names.any { token == it || token.startsWith("${it}_") }
            }
            val title = anchor?.attr("title").orEmpty()
            val local = anchor?.attr("local").equals("true", ignoreCase = true)
            val available = anchor?.classNames()?.any { it == "idle" || it.startsWith("idle_") } == true && !local
            val seat = if (seatElement != null && anchor != null && anchor.text().isNotBlank()) LibrarySeat(
                seatElement.id().removePrefix("seat_"), anchor.text().trim(), room,
                title.ifBlank { when {
                    local -> "仅限现场预约"
                    hasStatus("nousre", "unavailable", "disabled", "unfree", "not_available") -> "不可用"
                    hasStatus("leave", "away") -> "暂离中"
                    hasStatus("usre", "inuse", "in_use", "using", "occupied", "checked_in") -> "正在使用"
                    hasStatus("agreement", "order", "reserved", "reserve", "booked", "awaiting_check_in") -> "已被预约"
                    hasStatus("idle", "free", "available") -> "座位空闲"
                    else -> "未知状态"
                } }, available,
            ) else null
            val label = element.clone().apply { select(".seatLayoutTip, script, input").remove() }.text().replace('\u00a0', ' ').trim()
            val kind = when {
                seat != null -> NativeMapCellKind.SEAT
                classes.contains("table") || classes.contains("desk") -> NativeMapCellKind.TABLE
                classes.contains("wall") || classes.contains("pillar") -> NativeMapCellKind.WALL
                classes.contains("door") || label in setOf("门", "入口", "出口") -> NativeMapCellKind.DOOR
                classes.contains("window") || label == "窗" -> NativeMapCellKind.WINDOW
                label.isNotBlank() -> NativeMapCellKind.LABEL
                else -> NativeMapCellKind.EMPTY
            }
            return NativeMapCell(row, column, rowSpan, columnSpan, seat, local, kind, label,
                power = classes.contains("power") || classes.contains("both"), window = classes.contains("window") || classes.contains("both"))
        }
    }
}
