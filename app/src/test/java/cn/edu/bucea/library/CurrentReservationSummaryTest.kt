package cn.edu.bucea.library

import cn.edu.bucea.library.ui.currentReservationSummary
import cn.edu.bucea.library.ui.reservationMapTarget
import cn.edu.bucea.library.data.models.ReservationSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CurrentReservationSummaryTest {
    private val today = LocalDate.of(2026, 10, 20)

    @Test fun currentReceiptShowsOneSeatLocationAndNormalizedTime() {
        val summary = currentReservationSummary(listOf("凭证号：fixture-123", "座位：007D",
            "位置：图书馆2层二层内环区，座位号007D", "日期：2026-10-20", "时间：17 : 58 - 19 : 00", "状态：使用中"), today)
        assertEquals("007D", summary.seat)
        assertEquals("图书馆2层内环区", summary.location)
        assertEquals("今天", summary.date)
        assertEquals("17:58 — 19:00", summary.time)
    }

    @Test fun historyLocationAndFutureDatesRemainExplicit() {
        val summary = currentReservationSummary(listOf("图书馆2层二层内环区056B号",
            "日期：2026-10-21", "时间：08:00 -- 10:00"), today)
        assertEquals("056B", summary.seat)
        assertEquals("图书馆2层内环区", summary.location)
        assertEquals("明天", summary.date)
        assertEquals("08:00 — 10:00", summary.time)
        assertEquals("2027年1月1日", currentReservationSummary(listOf("日期：2027-01-01"), today).date)
    }

    @Test fun missingFieldsDoNotInventSeatDateOrTime() {
        val empty = currentReservationSummary(emptyList(), today)
        assertFalse(empty.hasInformation)
        assertNull(empty.seat)
        assertNull(empty.date)
        assertNull(empty.time)
        val partial = currentReservationSummary(listOf("位置：图书馆二层", "日期：10月21日"), today)
        assertEquals("图书馆2层", partial.location)
        assertEquals("10月21日", partial.date)
        assertNull(partial.seat)
        assertNull(partial.time)
        assertEquals("2层外环区", currentReservationSummary(listOf("区域：二层外环区"), today).location)
    }

    @Test fun mapTargetPreservesFutureBookingDateAndOriginalLocation() {
        val location = "图书馆2层二层内环区，座位号007D"
        val target = requireNotNull(reservationMapTarget(listOf("位置：$location"),
            ReservationSchedule("2026-10-21", "08:00", "10:00")))
        assertEquals("2026-10-21", target.date)
        assertEquals("007D", target.seatLabel)
        assertEquals(location, target.location)
        assertNull(reservationMapTarget(listOf("座位：007D")))
        assertNull(reservationMapTarget(listOf("位置：$location")))
        assertNull(reservationMapTarget(listOf("位置：$location"), ReservationSchedule("2026-02-31")))
    }
}
