package cn.edu.bucea.library.ui

private val reservationLocationLabels = setOf("位置", "地点", "房间", "区域")
private val reservationFloor = Regex("第?([零〇一二三四五六七八九十百0-9]+)(?:层|楼)")
private val repeatedReservationFloor = Regex("(?<![0-9])([0-9]+层)(?:[，,、\\s]*\\1)+")

/** Presentation only: the original receipt fields remain the source for seat location requests. */
internal fun reservationLocationText(details: List<String>, includeSeat: Boolean = true): String? {
    val location = details.firstOrNull { hubDetailText(it).first in reservationLocationLabels }
        ?: details.firstOrNull { isUnlabeledReservationLocation(it) } ?: return null
    return reservationLocationDetailText(location, details, includeSeat)
}

/** A null result leaves unrelated detail rows, including their labels and numbers, untouched. */
internal fun reservationLocationDetailText(detail: String, details: List<String>, includeSeat: Boolean = true): String? {
    val (label, value) = hubDetailText(detail)
    if (label !in reservationLocationLabels && !isUnlabeledReservationLocation(detail)) return null
    if (value.isBlank()) return null
    val seat = hubSeatNumber(listOf(value)) ?: hubSeatNumber(details)
    var location = value.trim()
    if (seat != null) {
        val number = Regex.escape(seat)
        val seatPart = Regex("[，,、\\s]*(?:座位(?:号)?\\s*[：:]?\\s*$number(?:\\s*号(?:座位)?)?|$number\\s*号(?:座位)?)(?![0-9A-Za-z-])")
        val hasSeat = seatPart.containsMatchIn(location)
        location = seatPart.replace(location, if (includeSeat) "${seat}号" else "")
        if (includeSeat && !hasSeat) location = location.trimEnd(' ', '，', ',', '、') + "${seat}号"
    }
    location = reservationFloor.replace(location) { match ->
        reservationFloorNumber(match.groupValues[1])?.let { "${it}层" } ?: match.value
    }
    location = repeatedReservationFloor.replace(location) { it.groupValues[1] }
    return location.trim(' ', '，', ',', '、').takeIf(String::isNotBlank)
}

private fun isUnlabeledReservationLocation(detail: String): Boolean =
    hubDetailText(detail).first == null && detail.contains("图书馆") && hubSeatNumber(listOf(detail)) != null

private fun reservationFloorNumber(value: String): Int? {
    value.toIntOrNull()?.let { return it }
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    if (value.all { it in digits }) return value.map { digits.getValue(it) }.joinToString("").toIntOrNull()
    var total = 0
    var number = 0
    for (character in value) {
        when (character) {
            '十', '百' -> { total += number.coerceAtLeast(1) * if (character == '十') 10 else 100; number = 0 }
            else -> number = digits[character] ?: return null
        }
    }
    return total + number
}
