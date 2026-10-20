package cn.edu.bucea.library.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract observed on library Wi-Fi (2026-10-20): the school keeps softMode=false, which
 * only hides its kiosk button bar, and still accepts check-in and the following seat actions.
 */
class ReservationActionsTest {
    private fun userInfo(status: String) = """{"currentReservationStatus":"$status","hasPrevUnStoppedReservation":false,""" +
        """"userCheckedIn":false,"lastIn":null,"wifiAwayDisabled":false,"wifiStopDisabled":false,"softMode":false}"""

    private fun page(status: String) = "<script>var userInfo = '${userInfo(status)}';</script>"

    private fun state(status: String) = LibraryFunctionsRepository.parseUserState(LibraryHtml.parse(page(status)))

    @Test fun reservedStateOffersCheckInAlthoughKioskBarIsHidden() {
        val reserved = state("RESERVE")
        assertFalse(reserved.softMode)
        assertEquals(listOf("checkIn"), reserved.actions)
        assertTrue(reserved.requiresLibraryWifi)
        assertTrue(reserved.actionMessage.contains("图书馆 Wi-Fi"))
        assertFalse(reserved.actionMessage.contains("暂未开放"))
    }

    @Test fun seatUseStatesOfferTheSchoolsFollowOnActions() {
        assertEquals(listOf("extend", "leave", "stopUsing"), state("CHECK_IN").actions)
        assertEquals(listOf("resume", "stopUsing"), state("AWAY").actions)
        assertEquals(listOf("stopUsing"), state("COMPLETE").actions)
    }

    @Test fun locationRestrictionAndNoReservationOfferNoActions() {
        val restricted = state("LOC_RESTRICT")
        assertTrue(restricted.actions.isEmpty())
        assertTrue(restricted.requiresLibraryWifi)
        assertTrue(restricted.actionMessage.contains("图书馆 Wi-Fi"))
        val none = state("NO_RESERVATION")
        assertTrue(none.actions.isEmpty())
        assertFalse(none.requiresLibraryWifi)
    }

    @Test fun actualCheckInResponseIsConfirmedOnceWithItsReceipt() = runTest {
        val calls = mutableListOf<String>()
        val repository = LibraryFunctionsRepository(request = { _, endpoint ->
            calls += endpoint
            when (endpoint) {
                "/self" -> page("RESERVE")
                "/user/checkIn" -> """{"status":"success","data":{"id":1,"receipt":"0000-001-1","status":"CHECK_IN",""" +
                    """"statusDisplay":"使用中","onDate":"2026 年 10 月 20 日","begin":"16 : 39","end":"17 : 00",""" +
                    """"location":"图书馆3层三层外环区，座位号013B"},"message":"您已登记入场","code":"0"}"""
                else -> error("Unexpected request")
            }
        }, post = { _, _, _ -> error("Check-in is a single GET") })
        val result = repository.performAction(SavedAccount("fixture", "209999990001", "synthetic", "测试"), "checkIn")
        assertTrue(result.success)
        assertEquals("您已登记入场", result.message)
        assertEquals("使用中", result.receipt?.status)
        assertEquals("图书馆3层三层外环区，座位号013B", result.receipt?.location)
        assertEquals(listOf("/self", "/user/checkIn"), calls)
    }

    @Test fun reservationCheckedInBySchoolAfterEntryIsReportedWithoutResubmitting() = runTest {
        val calls = mutableListOf<String>()
        val repository = LibraryFunctionsRepository(request = { _, endpoint ->
            calls += endpoint
            if (endpoint == "/self") page("CHECK_IN").replace(""""userCheckedIn":false,"lastIn":null""",
                """"userCheckedIn":true,"lastIn":"16:46"""")
            else error("A reservation already in use must not be checked in again")
        }, post = { _, _, _ -> error("Unexpected mutation") })
        val result = repository.performAction(SavedAccount("fixture", "209999990001", "synthetic", "测试"), "checkIn")
        assertFalse(result.success)
        assertTrue(result.message.contains("已签到"))
        assertEquals(listOf("/self"), calls)
    }
}
