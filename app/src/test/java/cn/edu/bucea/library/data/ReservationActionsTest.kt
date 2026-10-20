package cn.edu.bucea.library.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract observed on library Wi-Fi (2026-10-20): the school keeps softMode=false, which
 * only hides its kiosk button bar, and still accepts check-in and the following seat actions.
 * /user/extend saves the new end time, then redirects to /error/serverError instead of JSON.
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

    private val account = SavedAccount("fixture", "209999990001", "synthetic", "测试")
    private val extensionOffer = """{"status":"success","data":{"endTimes":[{"id":"1110","value":"18:30"}]},"message":"","code":"0"}"""
    // Body of /error/serverError, where the school redirects /user/extend after saving the new end time.
    private val schoolErrorPage = """<!DOCTYPE html><html><head><title>出错了</title><meta name="layout" content="error"/></head>
        <body><div class="main"><h1>Oops...</h1><h3>对不起，我们暂时无法处理您的请求，请稍候再试。</h3></div></body></html>"""

    private fun history(end: String) = """<div class="myReserveList"><dl><dt style="font-weight: bold">今天 17:20 -- $end</dt>
        <dd><a href="/view?id=9&amp;type=SEAT" title="图书馆2层二层内环区056B号">图书馆2层二层内环区056B号</a></dd>
        <a class="dateUsed">使用中</a></dl></div>"""

    private fun extendRepository(calls: MutableList<String>, extendReply: String, historyEnd: String) =
        LibraryFunctionsRepository(request = { _, endpoint ->
            calls += endpoint
            when (endpoint) {
                "/self" -> page("CHECK_IN")
                "/user/getTimeExtend" -> extensionOffer
                "/user/extend?endTime=1110" -> extendReply
                "/history?type=SEAT" -> history(historyEnd)
                else -> error("Unexpected request")
            }
        }, post = { _, _, _ -> error("续约 is a single GET") })

    @Test fun extensionSavedBeforeSchoolErrorPageIsConfirmedFromHistory() = runTest {
        val calls = mutableListOf<String>()
        val result = extendRepository(calls, schoolErrorPage, historyEnd = "18:30").performAction(account, "extend", "1110")
        assertTrue(result.success)
        assertEquals("续约成功，可使用至18:30", result.message)
        assertFalse(result.message.contains("登录"))
        assertEquals(listOf("/self", "/user/getTimeExtend", "/user/extend?endTime=1110", "/history?type=SEAT"), calls)
    }

    @Test fun schoolErrorPageWithUnchangedEndTimeIsNotReportedAsExtended() = runTest {
        val calls = mutableListOf<String>()
        val result = extendRepository(calls, schoolErrorPage, historyEnd = "18:00").performAction(account, "extend", "1110")
        assertFalse(result.success)
        assertTrue(result.message.contains("续约未生效"))
        assertEquals("The extension must be sent once", 1, calls.count { it.startsWith("/user/extend") })
    }

    @Test fun loginPageAfterAnActionStillReportsAnExpiredSession() = runTest {
        val calls = mutableListOf<String>()
        val repository = extendRepository(calls, "<form action='/auth/signIn'></form>", historyEnd = "18:30")
        val failure = runCatching { repository.performAction(account, "extend", "1110") }.exceptionOrNull()
        assertEquals(FailureKind.CREDENTIALS, (failure as LoginFailure).kind)
        assertEquals(1, calls.count { it.startsWith("/user/extend") })
    }

    @Test fun schoolRefusalToExtendIsAnAnswerRatherThanAFailedRequest() = runTest {
        val repository = LibraryFunctionsRepository(request = { _, endpoint ->
            assertEquals("/user/getTimeExtend", endpoint)
            """{"status":"fail","data":{"endTimes":[]},"message":"该预约已无法续约","code":"0"}"""
        }, post = { _, _, _ -> error("Unexpected mutation") })
        val times = repository.extensionTimes(account)
        assertTrue(times.options.isEmpty())
        assertEquals("该预约已无法续约", times.message)
        val offered = LibraryFunctionsRepository(request = { _, _ -> extensionOffer },
            post = { _, _, _ -> error("Unexpected mutation") }).extensionTimes(account)
        assertEquals(listOf("1110" to "18:30"), offered.options.map { it.value to it.label })
        assertEquals("", offered.message)
    }
}
