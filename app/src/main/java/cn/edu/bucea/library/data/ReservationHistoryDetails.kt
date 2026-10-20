package cn.edu.bucea.library.data

/** Seat identity and physical location always come from the school's /history list. */
internal object ReservationHistoryDetails {
    private val placeField = Regex("^\\s*(?:座位(?:号)?|位置|地点|区域|房间)\\s*[:：]")
    private val placeText = Regex("图书馆|座位|(?:[0-9]{2,}[A-Za-z][0-9A-Za-z-]*|[0-9]{2,})\\s*号")

    private fun isPlace(detail: String): Boolean = placeField.containsMatchIn(detail) ||
        (!detail.contains(Regex("[:：]")) && placeText.containsMatchIn(detail))

    fun canonical(details: List<String>): List<String> = details.filter(String::isNotBlank).map { detail ->
        if (!detail.contains(Regex("[:：]")) && isPlace(detail)) "位置：$detail" else detail
    }.distinct()

    /** Receipt status and schedule may add context; receipt locations cannot replace history. */
    fun merge(historyDetails: List<String>, detailDetails: List<String>): List<String> =
        (canonical(historyDetails).filter(::isPlace) + detailDetails.filterNot(::isPlace)).distinct()
}
