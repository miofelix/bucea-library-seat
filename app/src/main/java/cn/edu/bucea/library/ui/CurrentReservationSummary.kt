package cn.edu.bucea.library.ui

import cn.edu.bucea.library.data.ReservationSchedules
import cn.edu.bucea.library.data.models.ReservationMapTarget
import cn.edu.bucea.library.data.models.ReservationSchedule
import java.time.LocalDate
import java.time.ZoneId

internal data class CurrentReservationSummary(
    val seat: String?,
    val location: String?,
    val date: String?,
    val time: String?,
) {
    val hasInformation: Boolean get() = seat != null || location != null || date != null || time != null
}

/** One copy of each useful field; receipts and status explanations remain in the detail view. */
internal fun currentReservationSummary(
    details: List<String>,
    today: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai")),
): CurrentReservationSummary {
    val seat = hubSeatNumber(details)
    val fields = details.map(::hubDetailText)
    val compactLocation = reservationLocationText(details, includeSeat = false)
    val schedule = ReservationSchedules.parse("", details, today)
    val date = schedule.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let {
        when (it) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> if (it.year == today.year) "${it.monthValue}月${it.dayOfMonth}日"
                else "${it.year}年${it.monthValue}月${it.dayOfMonth}日"
        }
    } ?: schedule.dateLabel
    val time = when {
        schedule.begin != null && schedule.end != null -> "${schedule.begin} — ${schedule.end}"
        schedule.begin != null -> "开始 ${schedule.begin}"
        schedule.end != null -> "结束 ${schedule.end}"
        else -> fields.firstOrNull { it.first in setOf("时间", "时段") }?.second?.takeIf { it.isNotBlank() }
    }
    return CurrentReservationSummary(seat, compactLocation, date, time)
}

/** Map navigation keeps the original location and absolute booking date. */
internal fun reservationMapTarget(details: List<String>, schedule: ReservationSchedule = ReservationSchedule()): ReservationMapTarget? {
    val seat = hubSeatNumber(details) ?: return null
    val location = details.map(::hubDetailText).firstOrNull { it.first in setOf("位置", "地点", "房间", "区域") }?.second
        ?: details.firstOrNull { it.contains("图书馆") && hubSeatNumber(listOf(it)) != null } ?: return null
    val date = schedule.date ?: ReservationSchedules.parse("", details).date ?: return null
    if (runCatching { LocalDate.parse(date) }.isFailure) return null
    return ReservationMapTarget(date, seat, location)
}
