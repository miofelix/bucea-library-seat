package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.LibraryHistoryItem
import cn.edu.bucea.library.data.models.LibraryUserState
import cn.edu.bucea.library.data.models.ReservationSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class CurrentReservationDetailsTest {
    private val now = LocalDateTime.of(2026, 10, 20, 17, 30)

    @Test fun currentUsageMatchesTodayAndServerStateInsteadOfHistoryOrder() {
        val using = record("current", "使用中")
        val items = listOf(
            record("tomorrow", "已预约", date = "2026-10-21"),
            record("canceled", "已取消"),
            record("ended", "结束使用"),
            record("later", "待签到", begin = "20:00", end = "22:00"),
            using,
        )
        for (state in listOf("CHECK_IN", "IN_USE", "USING", "CHECKED_IN")) {
            assertEquals(using, CurrentReservationDetails.select(state, items, now))
        }
        assertNull(CurrentReservationDetails.select("AWAY", items, now))
    }

    @Test fun occupiedSeatRemainsCurrentUntilExplicitlyEndedEvenAfterScheduledEnd() {
        val using = record("current", "使用中", end = "17:00")
        val away = using.copy(status = "暂离")
        assertEquals(using, CurrentReservationDetails.select("CHECK_IN", listOf(using), now))
        assertEquals(away, CurrentReservationDetails.select("AWAY", listOf(away), now))
        assertEquals(using, CurrentReservationDetails.select("COMPLETE", listOf(using), now))
        assertEquals(away, CurrentReservationDetails.select("COMPLETE", listOf(away), now))
        for (terminal in listOf("结束使用", "已完成", "已取消", "失约", "COMPLETE")) {
            assertNull(CurrentReservationDetails.select("COMPLETE", listOf(using.copy(status = terminal)), now))
        }
        assertNull(CurrentReservationDetails.select("CHECK_IN", listOf(using.copy(schedule = using.schedule.copy(date = "2026-10-19"))), now))
        assertNull(CurrentReservationDetails.select("CHECK_IN", listOf(using.copy(schedule = using.schedule.copy(date = "2026-10-21"))), now))
    }

    @Test fun reservedStateRejectsExpiredFutureAndUndatedRecords() {
        for (item in listOf(
            record("ended", "待签到", end = "17:30"),
            record("tomorrow", "待签到", date = "2026-10-21"),
            record("undated", "待签到", date = null),
            record("unknown", ""),
        )) assertNull(CurrentReservationDetails.select("RESERVE", listOf(item), now))
        val active = record("active", "状态：待签到")
        assertEquals(active, CurrentReservationDetails.select("RESERVE", listOf(active), now))
        val midnight = record("midnight", "预约", begin = "23:00", end = "24:00")
        assertEquals(midnight, CurrentReservationDetails.select("RESERVE", listOf(midnight), now.withHour(23)))
    }

    @Test fun locationRestrictionAllowsOnlyAnUnambiguousDatedReservation() {
        val future = record("future", "已预约", date = "2026-10-21")
        assertEquals(future, CurrentReservationDetails.select("LOC_RESTRICT", listOf(future), now))
        assertNull(CurrentReservationDetails.select("LOC_RESTRICT", listOf(future, record("today", "待签到")), now))
        assertNull(CurrentReservationDetails.select("LOC_RESTRICT", listOf(future.copy(schedule = future.schedule.copy(date = null))), now))
        assertNull(CurrentReservationDetails.select("CHECK_IN", listOf(future), now))
        assertNull(CurrentReservationDetails.select("RESERVE", listOf(record("one", "预约"), record("two", "预约")), now))
    }

    @Test fun loadedHistoryProvidesCanonicalSeatAndTimeWithoutDuplicateReads() = runTest {
        val details = CurrentReservationDetails.read(
            user("CHECK_IN"), listOf(record("current", "使用中")),
            readHistory = { error("Already loaded history must be reused") },
            readDetails = { error("Complete summary must not fetch a receipt") }, now,
        )
        assertTrue(details.contains("位置：图书馆2层二层内环区056B号"))
        assertTrue(details.contains("日期：2026-10-20"))
        assertTrue(details.contains("时间：17:00 — 18:00"))
        assertEquals(ReservationSchedule("2026-10-20", "17:00", "18:00", "2026-10-20"),
            ReservationSchedules.parse("", details, now.toLocalDate()))
    }

    @Test fun refreshReadsNewTimesAndNeverCarriesSummaryAcrossAccounts() = runTest {
        var reads = 0
        var current = record("current", "使用中")
        suspend fun refresh() = CurrentReservationDetails.read(user("CHECK_IN"), null,
            readHistory = { reads++; listOf(current) },
            readDetails = { error("Complete summary") }, now)

        assertTrue(refresh().contains("时间：17:00 — 18:00"))
        current = current.copy(schedule = current.schedule.copy(end = "18:30"))
        assertTrue(refresh().contains("时间：17:00 — 18:30"))
        assertEquals(2, reads)

        val otherAccount = CurrentReservationDetails.read(user("CHECK_IN"), null,
            readHistory = { listOf(record("other", "使用中", location = "图书馆3层三层外环区013B号")) },
            readDetails = { error("Complete summary") }, now)
        assertTrue(otherAccount.contains("位置：图书馆3层三层外环区013B号"))
        assertFalse(otherAccount.any { it.contains("056B") || it.contains("18:30") })
    }

    @Test fun noReservationClearsSummaryWithoutReadingOldHistory() = runTest {
        val none = CurrentReservationDetails.read(user("NO_RESERVATION", listOf("座位：旧座位")), null,
            readHistory = { error("No active reservation must not read history") },
            readDetails = { error("No active reservation") }, now)
        assertTrue(none.isEmpty())
        val emptyHistory = CurrentReservationDetails.read(user("CHECK_IN"), emptyList(),
            readHistory = { error("An already loaded empty page must not be read twice") },
            readDetails = { error("No selected record") }, now)
        assertTrue(emptyHistory.isEmpty())
    }

    @Test fun incompleteCurrentRecordResolvesReceiptAndRevalidatesTerminalStatus() = runTest {
        var reads = 0
        val sparse = record("current", "使用中", date = null).copy(details = listOf("座位：013B"))
        val resolved = CurrentReservationDetails.read(user("CHECK_IN"), listOf(sparse),
            readHistory = { error("Loaded history") },
            readDetails = { reads++; it.copy(details = listOf("位置：图书馆三层，座位号999X"),
                schedule = it.schedule.copy(date = "2026-10-20")) }, now)
        assertEquals(1, reads)
        assertTrue(resolved.contains("座位：013B"))
        assertFalse(resolved.any { it.contains("999X") || it.contains("图书馆三层") })
        assertTrue(resolved.contains("时间：17:00 — 18:00"))

        val ended = CurrentReservationDetails.read(user("CHECK_IN"), listOf(sparse),
            readHistory = { error("Loaded history") },
            readDetails = { it.copy(status = "结束使用", schedule = it.schedule.copy(date = "2026-10-20")) }, now)
        assertTrue(ended.isEmpty())
    }

    @Test fun historyReadFailureNeverFallsBackToSelfSeatButCancellationPropagates() = runTest {
        val direct = listOf("座位：A101", "位置：图书馆二层")
        assertTrue(CurrentReservationDetails.read(user("CHECK_IN", direct), null,
            readHistory = { throw IllegalStateException("offline") },
            readDetails = { error("No history") }, now).isEmpty())
        try {
            CurrentReservationDetails.read(user("CHECK_IN"), null,
                readHistory = { throw CancellationException("Account switched") },
                readDetails = { error("Canceled") }, now)
            fail("Account switch cancellation must propagate")
        } catch (_: CancellationException) {
            // The old account cannot publish a summary after the bind job was canceled.
        }
    }

    @Test fun uniqueUndatedStatusMatchedRecordReadsReceiptBeforePublishingItsDate() = runTest {
        val undated = record("current", "使用中", date = null)
        val ignored = listOf(record("canceled", "已取消", date = null),
            record("ended", "结束使用", date = null), record("reserved", "待签到", date = null))
        var reads = 0
        val details = CurrentReservationDetails.read(user("CHECK_IN"), listOf(undated) + ignored,
            readHistory = { error("Loaded history must be reused") },
            readDetails = { item ->
                reads++
                assertEquals(undated.id, item.id)
                item.copy(schedule = item.schedule.copy(date = "2026-10-20"))
            }, now)
        assertEquals(1, reads)
        assertTrue(details.contains("日期：2026-10-20"))
        assertTrue(details.contains("位置：图书馆2层二层内环区056B号"))
        assertTrue(details.contains("时间：17:00 — 18:00"))
    }

    @Test fun undatedReceiptCannotPublishAnotherDaysOrTerminalReservation() = runTest {
        val undated = record("current", "使用中", date = null)
        for (receipt in listOf(
            undated.copy(schedule = undated.schedule.copy(date = "2026-10-19")),
            undated.copy(schedule = undated.schedule.copy(date = "2026-10-21")),
            undated.copy(status = "结束使用", schedule = undated.schedule.copy(date = "2026-10-20")),
            undated.copy(status = "已取消", schedule = undated.schedule.copy(date = "2026-10-20")),
            undated.copy(status = "暂离", schedule = undated.schedule.copy(date = "2026-10-20")),
            undated,
        )) {
            var reads = 0
            val details = CurrentReservationDetails.read(user("CHECK_IN"), listOf(undated),
                readHistory = { error("Loaded history") },
                readDetails = { reads++; receipt }, now)
            assertEquals(1, reads)
            assertTrue("Receipt $receipt must remain unexposed", details.isEmpty())
        }
    }

    @Test fun locationWithoutSeatNumberStaysMissingInsteadOfReadingReceiptSeat() = runTest {
        val sparse = record("current", "使用中", location = "位置：图书馆2层二层内环区")
        val details = CurrentReservationDetails.read(user("CHECK_IN", listOf("座位：SELF999")), listOf(sparse),
            readHistory = { error("Loaded history") },
            readDetails = { error("Receipt must not fill history seat information") }, now)
        assertTrue(details.contains("位置：图书馆2层二层内环区"))
        assertFalse(details.any { it.contains("SELF999") || it.contains("003D") })
        assertTrue(details.contains("日期：2026-10-20"))
        assertTrue(details.contains("时间：17:00 — 18:00"))
    }

    @Test fun ambiguousUndatedOrMixedDatedRecordsNeverGuessOrReadAnArbitraryReceipt() = runTest {
        val direct = listOf("来自当前状态页的信息")
        val undated = record("undated", "使用中", date = null)
        for (records in listOf(
            listOf(undated, undated.copy(id = "second")),
            listOf(undated, record("dated", "使用中")),
            listOf(undated.copy(detailPath = null), record("dated", "使用中")),
        )) {
            assertTrue(CurrentReservationDetails.read(user("CHECK_IN", direct), records,
                readHistory = { error("Loaded history") },
                readDetails = { error("Ambiguous records cannot choose a receipt") }, now).isEmpty())
        }
        for (records in listOf(
            listOf(undated.copy(detailPath = null)),
            listOf(undated.copy(status = "待签到")),
            listOf(undated.copy(status = "已取消")),
            listOf(undated.copy(status = "结束使用")),
        )) assertTrue(CurrentReservationDetails.read(user("CHECK_IN", direct), records,
            readHistory = { error("Loaded history") },
            readDetails = { error("No eligible receipt to read") }, now).isEmpty())
    }

    @Test fun undatedReceiptFailureDoesNotGuessDateAndReceiptCancellationPropagates() = runTest {
        val undated = record("current", "使用中", date = null)
        val direct = listOf("来自当前状态页的信息")
        assertTrue(CurrentReservationDetails.read(user("CHECK_IN", direct), listOf(undated),
            readHistory = { error("Loaded history") },
            readDetails = { throw IllegalStateException("offline") }, now).isEmpty())
        try {
            CurrentReservationDetails.read(user("CHECK_IN"), listOf(undated),
                readHistory = { error("Loaded history") },
                readDetails = { throw CancellationException("Account switched while resolving receipt") }, now)
            fail("Account cancellation must propagate out of the receipt read")
        } catch (_: CancellationException) {
        }
    }

    @Test fun historyLocationRemainsAuthoritativeWhenSelfAndReceiptDisagree() = runTest {
        val history = record("current", "使用中", date = null)
        val details = CurrentReservationDetails.read(user("CHECK_IN", listOf("位置：SELF馆，座位号999X")), listOf(history),
            readHistory = { error("Loaded history") },
            readDetails = { it.copy(details = listOf("凭证号：fixture", "位置：VIEW馆，座位号888X"),
                schedule = it.schedule.copy(date = "2026-10-20")) }, now)
        assertTrue(details.contains("位置：图书馆2层二层内环区056B号"))
        assertTrue(details.contains("日期：2026-10-20"))
        assertFalse(details.any { it.contains("SELF馆") || it.contains("999X") || it.contains("VIEW馆") || it.contains("888X") })
    }

    @Test fun missingHistoryLocationCannotBeInventedFromReceiptOrSelf() = runTest {
        val history = record("current", "使用中", date = null).copy(details = emptyList())
        val details = CurrentReservationDetails.read(user("CHECK_IN", listOf("座位：SELF999", "位置：SELF馆")), listOf(history),
            readHistory = { error("Loaded history") },
            readDetails = { it.copy(details = listOf("位置：VIEW馆，座位号888X", "座位：888X", "状态：使用中"),
                schedule = it.schedule.copy(date = "2026-10-20")) }, now)
        assertTrue(details.contains("日期：2026-10-20"))
        assertTrue(details.contains("时间：17:00 — 18:00"))
        assertFalse(details.any { it.contains("座位") || it.contains("位置") || it.contains("SELF") || it.contains("VIEW") })
    }

    private fun user(status: String, details: List<String> = emptyList()) =
        LibraryUserState(status, false, details, emptyList())

    private fun record(
        id: String,
        status: String,
        date: String? = "2026-10-20",
        begin: String = "17:00",
        end: String = "18:00",
        location: String = "图书馆2层二层内环区056B号",
    ) = LibraryHistoryItem(id, "今天 $begin -- $end", listOf(location), status,
        reservationId = id, detailPath = "/view?id=$id&type=SEAT",
        schedule = ReservationSchedule(date, begin, end))
}
