package cn.edu.bucea.library

import cn.edu.bucea.library.data.models.ReservationSchedule
import cn.edu.bucea.library.ui.HubRecord
import cn.edu.bucea.library.ui.reservationTimeline
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ReservationTimelineTest {
    private val now = LocalDateTime.parse("2030-01-15T12:00:00")

    @Test fun tonightAndTomorrowAppearChronologicallyEvenWhenServerPagesArriveInReverseOrder() {
        val evening = record("evening", "2030-01-15", "18:00", "20:00")
        val lateEvening = record("late", "2030-01-15", "20:30", "22:00")
        val tomorrow = record("tomorrow", "2030-01-16", "08:00", "10:00")
        val group = reservationTimeline(listOf(tomorrow, lateEvening, evening), now).single()
        assertEquals("接下来的预约", group.title)
        assertEquals(listOf("evening", "late", "tomorrow"), group.records.map { it.id })
        assertSame("Sorting must preserve the record and its server-authorized cancellation action", evening, group.records.first())
        assertTrue(group.records.first().canCancel)
    }

    @Test fun finishedRecordsAndUnresolvedTimesStaySeparateFromUpcomingReservations() {
        val old = record("old", "2030-01-14", "18:00", "20:00", "已完成", canCancel = false)
        val endedToday = record("ended", "2030-01-15", "08:00", "10:00", "已结束", canCancel = false)
        val cancelledTomorrow = record("cancelled", "2030-01-16", "08:00", "10:00", "已取消", canCancel = false)
        val upcoming = record("upcoming", "2030-01-16", "10:00", "12:00")
        val unresolved = HubRecord("unknown", "服务器未提供日期", "待签到", listOf("座位：A101"))
        val groups = reservationTimeline(listOf(old, upcoming, cancelledTomorrow, unresolved, endedToday), now)
        assertEquals(listOf("接下来的预约", "时间待确认", "历史预约"), groups.map { it.title })
        assertEquals(listOf("upcoming"), groups[0].records.map { it.id })
        assertSame(unresolved, groups[1].records.single())
        assertEquals(listOf("cancelled", "ended", "old"), groups[2].records.map { it.id })
        assertTrue(groups[2].records.none { it.canCancel })
    }

    @Test fun missingAndInvalidDatesDoNotAcquireAnInventedTimelinePosition() {
        val ambiguous = record("ambiguous", null, "18:00", "20:00")
        val invalid = record("invalid", "2030-02-31", "18:00", "20:00")
        val group = reservationTimeline(listOf(ambiguous, invalid), now).single()
        assertEquals("时间待确认", group.title)
        assertEquals(listOf(ambiguous, invalid), group.records)
    }

    @Test fun fulfilledAndMissedReservationsUseHistoryEvenBeforeTheirScheduledEnd() {
        val fulfilled = record("fulfilled", "2030-01-15", "08:00", "14:00", "已履约", canCancel = false)
        val missed = record("missed", "2030-01-15", "10:00", "16:00", "失约", canCancel = false)
        val stopped = record("stopped", "2030-01-15", "11:00", "18:00", "结束使用", canCancel = false)
        val upcoming = record("upcoming", "2030-01-16", "08:00", "10:00")

        val groups = reservationTimeline(listOf(fulfilled, upcoming, missed, stopped), now)

        assertEquals(listOf("接下来的预约", "历史预约"), groups.map { it.title })
        assertEquals(listOf(upcoming), groups[0].records)
        assertEquals(listOf(stopped, missed, fulfilled), groups[1].records)
    }

    private fun record(id: String, date: String?, begin: String, end: String, status: String = "已预约", canCancel: Boolean = true) =
        HubRecord(id, "$id 预约", status, listOf("座位：A101", "位置：图书馆二层"), canCancel,
            ReservationSchedule(date, begin, end))
}
