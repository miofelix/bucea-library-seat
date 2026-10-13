package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.ui.SeatOption
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchFilterPolicyInstrumentedTest {
    @Test
    fun rollingBookingDatesDropExpiredSelectionAndKeepValidFilters() {
        val options = mapOf(
            "onDate" to listOf(SeatOption("2026-10-14", "今天"), SeatOption("2026-10-15", "明天")),
            "room" to listOf(SeatOption("null", "不限区域"), SeatOption("2", "二层西区")),
            "power" to listOf(SeatOption("null", "不限电源"), SeatOption("1", "有电源")),
        )
        val filters = SearchFilterPolicy.reconcile(mapOf("onDate" to "2026-10-13", "room" to "2", "power" to "1", "name" to "001D"), options)
        assertEquals("2026-10-14", filters["onDate"])
        assertEquals("2", filters["room"])
        assertEquals("1", filters["power"])
        assertEquals("001D", filters["name"])
        assertEquals("2026-10-15", SearchFilterPolicy.reconcile(filters + ("onDate" to "2026-10-15"), options)["onDate"])
    }

    @Test
    fun removedRoomFallsBackToServerDefaultAndInitialDateComesFromServer() {
        val options = mapOf(
            "onDate" to listOf(SeatOption("2026-11-02", "可预约日期")),
            "room" to listOf(SeatOption("null", "不限区域"), SeatOption("7", "四层期刊阅")),
        )
        assertEquals("null", SearchFilterPolicy.reconcile(mapOf("room" to "removed-room"), options)["room"])
        assertEquals("2026-11-02", SearchFilterPolicy.reconcile(emptyMap(), options)["onDate"])
    }
}
