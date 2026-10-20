package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.LibraryHistoryItem
import cn.edu.bucea.library.data.models.ReservationSchedule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReservationHistoryDetailsTest {
    @Test fun linkedReceiptKeepsHistoryLocationAndSeatWhenOpeningDetailsOrLocating() = runTest {
        val repository = repository()
        val history = record(listOf("图书馆2层二层内环区003D号", "座位：003D"))
        val resolved = repository.reservationDetails(account, history)
        assertEquals(listOf("位置：图书馆2层二层内环区003D号", "座位：003D"),
            resolved.details.filter { it.startsWith("位置：") || it.startsWith("座位：") })
        assertFalse(resolved.details.any { it.contains("999X") || it.contains("VIEW馆") })
        assertTrue(resolved.details.contains("凭证号：fixture"))
        assertEquals(ReservationSchedule("2026-10-20", "17:00", "18:30", "2026-10-20"), resolved.schedule)
        assertEquals("使用中", resolved.status)
    }

    @Test fun receiptCannotFillMissingHistorySeatOrLocation() = runTest {
        val resolved = repository().reservationDetails(account, record(emptyList()))
        assertFalse(resolved.details.any { it.startsWith("位置：") || it.startsWith("座位：") || it.contains("999X") })
        assertTrue(resolved.details.contains("日期：2026-10-20"))
        assertTrue(resolved.details.any { it.startsWith("时间：") })
        assertEquals("2026-10-20", resolved.schedule.date)
    }

    @Test fun receiptMergeIsIdempotentAndPreservesHistoryLocationWithMissingSeat() {
        val history = listOf("位置：图书馆二层内环区")
        val first = ReservationHistoryDetails.merge(history, listOf("位置：VIEW馆，座位号999X", "状态：使用中"))
        assertEquals(listOf("位置：图书馆二层内环区", "状态：使用中"), first)
        assertEquals(first, ReservationHistoryDetails.merge(history, first))
    }

    private fun repository() = LibraryFunctionsRepository(
        request = { _, _ -> """<div class='layoutSeat'><dl><dd>凭证号：fixture</dd><dd>日期：2026年10月20日</dd>
            <dd>时间：17:00 - 18:30</dd><dd>位置：VIEW馆，座位号999X</dd><dd>状态：使用中</dd></dl></div>""" },
        post = { _, _, _ -> error("Reservation detail reads must not mutate") },
    )

    private val account = SavedAccount("fixture-history", "209999990001", "synthetic", "测试")
    private fun record(details: List<String>) = LibraryHistoryItem("reservation_9", "今天 17:00 -- 18:00", details, "使用中",
        reservationId = "9", detailPath = "/view?id=9&type=SEAT", schedule = ReservationSchedule())
}
