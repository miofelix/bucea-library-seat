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
        if (response.trimStart().startsWith("{")) {
            val data = runCatching { JSONObject(response) }.getOrNull()
            if (data?.optBoolean("limit") == true) throw VerificationRequired(data.optString("userId"))
        }
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
        val document = LibraryHtml.parse(request(account, endpoint))
        requireData(document.outerHtml())
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

        fun parseRooms(response: String): NativeMapRooms {
            requireData(response)
            val data = try { JSONObject(response) } catch (_: Exception) {
                throw LoginFailure(FailureKind.PROTOCOL, "无法读取阅览区域，请刷新重试")
            }
            val verification = data.optBoolean("limit")
            val array = data.optJSONArray("rooms")
            val rooms = if (verification || array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
                val room = array.optJSONObject(index) ?: return@mapNotNull null
                val id = room.optString("id")
                val name = room.optString("name")
                if (id.isBlank() || name.isBlank()) null else NativeMapRoom(id, name)
            }
            return NativeMapRooms(rooms, verification, data.optString("msg").ifBlank {
                if (verification) "学校要求额外验证，请稍后再试" else if (rooms.isEmpty()) "该楼层暂无可预约区域" else ""
            }, if (verification) data.optString("userId") else "")
        }

        fun parseLayout(response: String, roomId: String, roomName: String): NativeRoomLayout {
            requireData(response)
            val data = if (response.trimStart().startsWith("{")) runCatching { JSONObject(response) }.getOrNull() else null
            val verification = response.contains("clickVerifyCode(") || response.contains("您操作的频率太快") || data?.optBoolean("limit") == true
            if (verification) return NativeRoomLayout(roomId, roomName, 0, 0, emptyList(), true, "学校要求额外验证，请先完成验证",
                data?.optString("userId").orEmpty())
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
            val title = anchor?.attr("title").orEmpty()
            val local = anchor?.attr("local").equals("true", ignoreCase = true)
            val available = anchor?.classNames()?.any { it == "idle" || it.startsWith("idle_") } == true && !local
            val seat = if (seatElement != null && anchor != null && anchor.text().isNotBlank()) LibrarySeat(
                seatElement.id().removePrefix("seat_"), anchor.text().trim(), room,
                title.ifBlank { when {
                    local -> "仅限现场预约"
                    classes.contains("idle") -> "座位空闲"
                    classes.contains("agreement") -> "已被预约"
                    classes.contains("leave") -> "暂离中"
                    classes.contains("nousre") -> "不可用"
                    else -> "正在使用"
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
