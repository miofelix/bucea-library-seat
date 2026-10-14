package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.LibraryHistoryItem
import cn.edu.bucea.library.data.models.ReservationReceipt
import cn.edu.bucea.library.data.models.ReservationSchedule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Synthetic schedules prevent unknown timestamps or guessed years entering the chronology. */
class ReservationSchedulesTest {
    private val today = LocalDate.of(2026, 10, 14)

    @Test fun actualRelativeHistoryTitleUsesFetchDateAndPreservesSource() {
        val title = "明天 07:30 -- 08:00"
        val details = listOf("图书馆2层二层内环区001D号", "已预约")
        val item = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse("""
            <div class='myReserveList'><dl><dt>$title</dt>
            <dd>${details[0]}</dd><dd>${details[1]}</dd></dl></div>
        """), today).items.single()
        assertEquals(ReservationSchedule("2026-10-15", "07:30", "08:00", "明天"), item.schedule)
        assertEquals(title, item.title)
        assertEquals(details, item.details)
    }

    @Test fun tonightAndTomorrowStayOnSeparateDatesAcrossYearBoundary() {
        val reference = LocalDate.of(2026, 12, 31)
        val tonight = ReservationSchedules.parse("今晚 20:00 -- 22:30", emptyList(), reference)
        val tomorrow = ReservationSchedules.parse("明天 7:30—8:00", emptyList(), reference)
        assertEquals(ReservationSchedule("2026-12-31", "20:00", "22:30", "今晚"), tonight)
        assertEquals(ReservationSchedule("2027-01-01", "07:30", "08:00", "明天"), tomorrow)
        assertEquals("2027-01-02", ReservationSchedules.parse("后天", emptyList(), reference).date)
        assertEquals("2026-12-30", ReservationSchedules.parse("昨天", emptyList(), reference).date)
    }

    @Test fun absoluteAndChineseDatesAreValidatedAndNormalized() {
        assertEquals(ReservationSchedule("2026-10-14", "08:00", "09:30", "2026-10-14"),
            ReservationSchedules.parse("2026-10-14 08:00 - 09:30", emptyList(), today))
        assertEquals(ReservationSchedule("2028-02-29", "07:30", "08:00", "2028 年 2 月 29 日"),
            ReservationSchedules.parse("预约凭证", listOf("日期：2028 年 2 月 29 日", "时间：07 : 30 ～ 08 : 00"), today))
        assertEquals("2026-10-14", ReservationSchedules.parse("2026/10/14", emptyList(), today).date)
    }

    @Test fun missingScheduleRemainsUnknownAndMonthDayDoesNotGuessYear() {
        assertEquals(ReservationSchedule(), ReservationSchedules.parse("预约凭证", listOf("座位：001D", "状态：待签到"), today))
        assertEquals(ReservationSchedule(null, "08:00", "09:00", "10月14日"),
            ReservationSchedules.parse("10月14日 08:00 - 09:00", emptyList(), today))
        assertEquals(ReservationSchedule(null, null, null, "10-14"), ReservationSchedules.parse("10-14", emptyList(), today))
        assertEquals("2026-10-14", ReservationSchedules.parse("2026年预约", listOf("日期：10月14日"), today).date)
    }

    @Test fun invalidExplicitDatesNeverFallBackToRelativeLabelsOrRollIntoNextMonth() {
        listOf("2026-02-29", "2026-02-30", "2026-13-01", "2026-00-14", "0000-10-14").forEach { label ->
            val schedule = ReservationSchedules.parse("$label 今天 08:00 - 09:00", emptyList(), today)
            assertNull(label, schedule.date)
            assertEquals(label, schedule.dateLabel)
            assertEquals("08:00", schedule.begin)
        }
        val ambiguous = ReservationSchedules.parse("日期：2026-10-14 / 2026-10-15", emptyList(), today)
        assertNull(ambiguous.date)
        assertEquals("2026-10-14 / 2026-10-15", ambiguous.dateLabel)
    }

    @Test fun explicitReservationBeginAndEndIgnoreUnrelatedEventTimestamps() {
        val details = listOf(
            "提交时间：2026-10-13 21:50", "创建时间：2026-10-13 21:50 - 21:51",
            "签到时间：2026-10-14 07:40", "取消时间：2026-10-14 07:50",
            "预约日期：2026-10-14", "开始时间：08:00", "结束时间：09:30",
        )
        assertEquals(ReservationSchedule("2026-10-14", "08:00", "09:30", "2026-10-14"),
            ReservationSchedules.parse("预约凭证", details, today))
        assertEquals(ReservationSchedule(), ReservationSchedules.parse("预约凭证", details.take(4), today))
        assertEquals(ReservationSchedule("2026-10-14", null, null, "2026-10-14"),
            ReservationSchedules.parse("今天", listOf("预约日期：2026-10-14", "提交时间：08:00"), today))
    }

    @Test fun invalidTimesRemainUnknownAndEndOfDayIsOnlyValidAsAnEnd() {
        assertEquals(ReservationSchedule("2026-10-14", null, null, "今天"),
            ReservationSchedules.parse("今天 25:00 - 08:60", emptyList(), today))
        assertEquals(ReservationSchedule("2026-10-14", "22:00", "24:00", "今天"),
            ReservationSchedules.parse("今天 22:00 - 24:00", emptyList(), today))
        assertNull(ReservationSchedules.fromReceipt(receipt("2026-10-14", "24:00", "24:00"), today).begin)
        assertNull(ReservationSchedules.fromReceipt(receipt("2026-10-14", "07:30:bad", "08:00"), today).begin)
    }

    @Test fun receiptProvidesAbsoluteScheduleIndependentOfOriginalRelativeHistory() = runTest {
        val calls = mutableListOf<String>()
        val repository = LibraryFunctionsRepository(
            request = { _, path ->
                calls += path
                """<div class='layoutSeat'><dl><dd>凭证号：fixture</dd><dd>日期：2026年10月15日</dd>
                <dd>时间：07:30 - 08:00</dd><dd>位置：图书馆2层二层内环区001D号</dd><dd>状态：预约</dd></dl></div>"""
            },
            post = { _, _, _ -> error("Reading receipt must not mutate reservations") },
        )
        val record = LibraryHistoryItem("reservation_9", "明天 07:30 -- 08:00", emptyList(), "已预约",
            reservationId = "9", detailPath = "/view?id=9&type=SEAT",
            schedule = ReservationSchedule("2026-10-15", "07:30", "08:00", "明天"))
        val updated = repository.reservationDetails(SavedAccount("fixture", "209999990001", "synthetic", "测试"), record)
        assertEquals(listOf("/view?id=9&type=SEAT"), calls)
        assertEquals(ReservationSchedule("2026-10-15", "07:30", "08:00", "2026-10-15"), updated.schedule)
        assertEquals(record.title, updated.title)
        assertTrue(updated.details.contains("日期：2026-10-15"))
    }

    @Test fun detailWithoutSchedulePreservesTheOriginallyAnchoredDate() = runTest {
        val repository = LibraryFunctionsRepository(
            request = { _, _ -> "<div class='layoutSeat'><dl><dd>位置：图书馆二层</dd><dd>状态：待签到</dd></dl></div>" },
            post = { _, _, _ -> error("Unexpected mutation") },
        )
        val original = ReservationSchedule("2026-10-15", "07:30", "08:00", "明天")
        val record = LibraryHistoryItem("reservation_9", "明天 07:30 -- 08:00", emptyList(), "已预约",
            reservationId = "9", detailPath = "/view?id=9&type=SEAT", schedule = original)
        val updated = repository.reservationDetails(SavedAccount("fixture", "209999990001", "synthetic", "测试"), record)
        assertEquals(original, updated.schedule)
    }

    private fun receipt(date: String, begin: String, end: String) = ReservationReceipt("fixture", date, begin, end, "位置", "预约")
}
