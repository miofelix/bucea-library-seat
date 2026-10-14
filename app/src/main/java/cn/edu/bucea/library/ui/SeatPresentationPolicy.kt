package cn.edu.bucea.library.ui

/** Presentation describes current status; only the server's available flag permits selection. */
enum class SeatVisualState { FREE, OCCUPIED, RESERVED, AWAY, UNAVAILABLE, UNKNOWN }

internal object SeatPresentationPolicy {
    fun visualState(status: String, available: Boolean): SeatVisualState {
        val text = status.trim().lowercase()
        val tokens = text.split(Regex("[\\s,;/]+"))
        fun matches(vararg names: String) = tokens.any { token -> names.any { token == it || token.startsWith("${it}_") } }
        val current = when {
            listOf("不可用", "不可使用", "不可预约", "不能预约", "暂停使用", "停用", "维护", "仅限现场").any(text::contains) ||
                matches("unavailable", "disabled", "nousre", "unfree", "not_available") -> SeatVisualState.UNAVAILABLE
            text.contains("暂离") || matches("leave", "away") -> SeatVisualState.AWAY
            listOf("使用", "占用", "占座").any(text::contains) ||
                matches("usre", "inuse", "in_use", "using", "occupied", "checked_in") -> SeatVisualState.OCCUPIED
            text.contains("待签到") || text.contains("未签到") || text.contains("等待签到") ||
                text.contains("已预约") || text.contains("已有预约") || text.contains("被预约") || text.contains("已预订") ||
                text == "预约" || matches("agreement", "order", "reserved", "reserve", "booked", "awaiting_check_in") -> SeatVisualState.RESERVED
            listOf("空闲", "可用", "可预约", "可预订").any(text::contains) ||
                matches("idle", "free", "available") -> SeatVisualState.FREE
            else -> SeatVisualState.UNKNOWN
        }
        return if (current == SeatVisualState.FREE && !available) SeatVisualState.UNAVAILABLE else current
    }

    fun visibleSeats(loadedPage: List<NativeSeat>, onlyAvailable: Boolean): List<NativeSeat> =
        if (onlyAvailable) loadedPage.filter { it.available } else loadedPage

    fun availabilityLabel(available: Boolean): String = if (available) "可预约" else "不可预约"

    fun statusLabel(state: SeatVisualState): String = when (state) {
        SeatVisualState.FREE -> "空闲"
        SeatVisualState.OCCUPIED -> "使用中"
        SeatVisualState.RESERVED -> "已预约"
        SeatVisualState.AWAY -> "暂离"
        SeatVisualState.UNAVAILABLE -> "不可用"
        SeatVisualState.UNKNOWN -> "未知状态"
    }

    /** Short labels remain readable inside a seat when the room is zoomed in. */
    fun statusMarker(state: SeatVisualState): String = when (state) {
        SeatVisualState.FREE -> "空"
        SeatVisualState.OCCUPIED -> "用"
        SeatVisualState.RESERVED -> "约"
        SeatVisualState.AWAY -> "离"
        SeatVisualState.UNAVAILABLE -> "停"
        SeatVisualState.UNKNOWN -> "?"
    }

    fun availabilityDetail(state: SeatVisualState, available: Boolean): String? = when {
        available && state in setOf(SeatVisualState.OCCUPIED, SeatVisualState.RESERVED, SeatVisualState.AWAY) ->
            "当前状态如上；可预约时段以点选后的查询为准。"
        !available && state == SeatVisualState.UNAVAILABLE -> "服务器当前不允许预约。"
        available && state == SeatVisualState.UNKNOWN -> "服务器允许预约；请点选确认可用时段。"
        else -> null
    }
}
