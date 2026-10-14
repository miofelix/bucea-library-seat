package cn.edu.bucea.library.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal data class ReservationTimelineGroup(val title: String, val records: List<HubRecord>)

/** The loaded server records retain their identities and actions when their display order changes. */
internal fun reservationTimeline(
    records: List<HubRecord>,
    now: LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
): List<ReservationTimelineGroup> {
    val upcoming = mutableListOf<HubRecord>()
    val history = mutableListOf<HubRecord>()
    val undated = mutableListOf<HubRecord>()
    records.forEach { record ->
        val date = record.schedule.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (date == null) {
            undated += record
        } else {
            val end = record.schedule.end?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val inactive = listOf("取消", "违约", "完成", "结束", "CANCELED", "CANCELLED", "FINISHED", "STOPPED")
                .any { record.status.contains(it, ignoreCase = true) }
            val ended = date < now.toLocalDate() || (date == now.toLocalDate() && end != null && !end.isAfter(now.toLocalTime()))
            if (inactive || ended) history += record else upcoming += record
        }
    }
    val chronological = compareBy<HubRecord> { it.schedule.date }.thenBy { it.schedule.begin ?: "24:00" }
    return buildList {
        if (upcoming.isNotEmpty()) add(ReservationTimelineGroup("接下来的预约", upcoming.sortedWith(chronological)))
        if (undated.isNotEmpty()) add(ReservationTimelineGroup("时间待确认", undated))
        if (history.isNotEmpty()) add(ReservationTimelineGroup("历史预约", history.sortedWith(chronological.reversed())))
    }
}
