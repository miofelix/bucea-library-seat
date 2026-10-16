package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.data.LibraryFunctionsRepository
import cn.edu.bucea.library.data.LibraryHtml
import cn.edu.bucea.library.data.LoginFailure
import cn.edu.bucea.library.data.FailureKind
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.data.models.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Synthetic responses exercise library contracts without booking or modifying an account. */
@RunWith(AndroidJUnit4::class)
class LibraryFunctionsInstrumentedTest {
    @Test
    fun seatNumberLookupUsesRoomCatalogAndRejectsUnrelatedOrLocalOnlySeats() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = LibraryFunctionsRepository(
            request = { _, endpoint ->
                calls += endpoint
                if (endpoint == "/self") "<p id='options_room'><a value='1'>二层内环区</a></p>"
                else """<li id='seat_1'><a class='idle' local='false'>001D</a></li>
                    <li id='seat_2'><a class='idle' local='false'>001A</a></li>
                    <li id='seat_3'><a class='idle' local='false'>002D</a></li>
                    <li id='seat_4'><a class='idle' local='true'>001B</a></li>"""
            },
            post = { _, _, _ -> error("A lookup must never post") },
        )
        val account = SavedAccount("fake", "209999990001", "fake", "测试")
        val exact = repository.search(account, SeatQuery("2026-10-14", name = "001D"))
        val url = ("http://10.1.20.7" + calls.last()).toHttpUrl()
        assertEquals("/mapBook/getSeatsByRoom", url.encodedPath)
        assertEquals("1", url.queryParameter("room"))
        assertEquals("2026-10-14", url.queryParameter("date"))
        assertNull(url.queryParameter("onDate"))
        assertNull(url.queryParameter("offset"))
        assertEquals(listOf("001D"), exact.seats.map { it.label })
        assertNull(exact.nextOffset)
        val numbered = repository.search(account, SeatQuery("2026-10-14", name = "001"))
        assertEquals(listOf("001D", "001A"), numbered.seats.map { it.label })
        assertFalse(numbered.seats.any { it.label.startsWith("002") })
        assertFalse(numbered.seats.any { it.label == "001B" })
        assertEquals(1, calls.count { it.startsWith("/mapBook/getSeatsByRoom") })
        for (challenge in listOf("""{"limit":true}""", "<script>clickVerifyCode('')</script>")) {
            var mapsRequested = 0
            val limited = LibraryFunctionsRepository(
                request = { _, endpoint ->
                    if (endpoint == "/self") "<p id='options_room'><a value='1'>一室</a><a value='2'>二室</a></p>"
                    else { mapsRequested++; challenge }
                },
                post = { _, _, _ -> error("Verification must never post") },
            ).search(account, SeatQuery("2026-10-14", name = "001"))
            assertTrue(limited.verificationRequired)
            assertTrue(limited.seats.isEmpty())
            assertEquals(1, mapsRequested)
        }
    }

    @Test
    fun confirmedReservationUsesFreshTokenAndCannotSubmitTwiceOrUnderAnotherAccount() = runBlocking {
        val account = SavedAccount("fake-A", "209999990001", "fake", "测试")
        var posted: Map<String, String>? = null
        var postCount = 0
        val repository = LibraryFunctionsRepository(
            request = { _, endpoint ->
                assertEquals("/self", endpoint)
                """<input id="sysUsername" value="209999990001"><form id="reserveForm" action="/selfRes">
                <input type="hidden" name="SYNCHRONIZER_TOKEN" value="synthetic-fresh-token">
                <input type="hidden" name="SYNCHRONIZER_URI" value="/self"></form>"""
            },
            post = { _, endpoint, fields ->
                assertEquals("/selfRes", endpoint)
                posted = fields
                postCount++
                "<p>预约成功</p>"
            },
        )
        val selection = ReservationSelection("2026-10-14", LibrarySeat("5773", "001D", "二层内环区", "空闲", true),
            LibraryOption("450", "07:30"), LibraryOption("480", "08:00"))
        val prepared = repository.prepareReservation(account, selection)
        try { repository.reserve(account.copy(id = "fake-B"), prepared); fail("Switched account must not submit") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, postCount)
        assertTrue(repository.reserve(account, prepared).success)
        assertEquals("synthetic-fresh-token", posted?.get("SYNCHRONIZER_TOKEN"))
        assertEquals("5773", posted?.get("seat"))
        assertEquals("450", posted?.get("start"))
        assertEquals("480", posted?.get("end"))
        try { repository.reserve(account, prepared); fail("Duplicate confirmation must not submit twice") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, postCount)
    }

    @Test
    fun verificationEnabledBookingDoesNotPostOrBypassOfficialChallenge() = runBlocking {
        val account = SavedAccount("fake", "209999990001", "fake", "测试")
        var posts = 0
        val repository = LibraryFunctionsRepository(
            request = { _, _ -> """<form id="reserveForm" action="/selfRes"><input type="hidden" name="SYNCHRONIZER_TOKEN" value="test">
            <input name="authid" value="official-challenge-required"></form>""" },
            post = { _, _, _ -> posts++; "" },
        )
        val selection = ReservationSelection("2026-10-14", LibrarySeat("1", "001", "阅览室", "空闲", true),
            LibraryOption("450", "07:30"), LibraryOption("480", "08:00"))
        val prepared = repository.prepareReservation(account, selection)
        assertTrue(prepared.verificationRequired)
        try { repository.reserve(account, prepared); fail("Verification must require original flow") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, posts)
    }

    @Test
    fun verifiedReservationReplacesPlaceholderWithValidatedProof() = runBlocking {
        val account = SavedAccount("fake", "209999990001", "fake", "测试")
        var submitted: Map<String, String>? = null
        val repository = LibraryFunctionsRepository(
            request = { _, _ -> """<form id="reserveForm" action="/selfRes">
                <input type="hidden" name="SYNCHRONIZER_TOKEN" value="fresh-csrf-token">
                <input type="hidden" name="SYNCHRONIZER_URI" value="/self">
                <input type="hidden" name="authid" value="placeholder-needs-verification"></form>""" },
            post = { _, _, fields -> submitted = fields; "<p>预约成功</p>" },
        )
        val selection = ReservationSelection("2026-10-14", LibrarySeat("1", "001", "阅览室", "空闲", true),
            LibraryOption("450", "07:30"), LibraryOption("480", "08:00"))
        val prepared = repository.prepareReservation(account, selection)
        assertTrue(prepared.verificationRequired)
        val verified = prepared.copy(verificationAuthId = "server-validated-synthetic-proof")
        assertTrue(repository.reserve(account, verified).success)
        assertEquals("server-validated-synthetic-proof", submitted?.get("authid"))
        assertEquals("fresh-csrf-token", submitted?.get("SYNCHRONIZER_TOKEN"))
    }

    @Test
    fun reservationActionsRespectServerModeAndLocationRestrictions() {
        fun state(json: String) = LibraryFunctionsRepository.parseUserState(LibraryHtml.parse("<script>var userInfo='$json';</script>"))
        assertTrue(state("""{"currentReservationStatus":"CHECK_IN","softMode":false}""").actions.isEmpty())
        assertTrue(state("""{"currentReservationStatus":"LOC_RESTRICT","softMode":true,"hasPrevUnStoppedReservation":true}""").actions.isEmpty())
        assertEquals(listOf("extend"), state("""{"currentReservationStatus":"CHECK_IN","softMode":true,"wifiAwayDisabled":true,"wifiStopDisabled":true}""").actions)
        assertEquals(listOf("resume", "stopUsing"), state("""{"currentReservationStatus":"AWAY","softMode":true}""").actions)
        for (status in listOf("IN_USE", "USING", "CHECKED_IN")) {
            assertEquals("$status must expose the server's end-use action", listOf("extend", "leave", "stopUsing"),
                state("""{"currentReservationStatus":"$status","softMode":true}""").actions)
        }
    }

    @Test
    fun reservationGuidanceDistinguishesNoReservationFromLibraryWifiRestriction() {
        fun state(json: String) = LibraryFunctionsRepository.parseUserState(LibraryHtml.parse("<script>var userInfo='$json';</script>"))
        val none = state("""{"currentReservationStatus":"NO_RESERVATION","softMode":false}""")
        assertTrue(none.actions.isEmpty())
        assertTrue(none.actionMessage.contains("当前没有可签到的预约"))
        assertFalse(none.requiresLibraryWifi)
        assertFalse(none.actionMessage.contains("当前网络"))
        val restricted = state("""{"currentReservationStatus":"LOC_RESTRICT","softMode":true}""")
        assertTrue(restricted.actions.isEmpty())
        assertTrue(restricted.requiresLibraryWifi)
        assertTrue(restricted.actionMessage.contains("图书馆 Wi-Fi"))
        assertFalse(restricted.actionMessage.contains("没有"))
        val reserved = state("""{"currentReservationStatus":"RESERVE","softMode":true}""")
        assertEquals(listOf("checkIn"), reserved.actions)
        assertTrue(reserved.requiresLibraryWifi)
        val manualMode = state("""{"currentReservationStatus":"RESERVE","softMode":false}""")
        assertTrue(manualMode.actions.isEmpty())
        assertTrue(manualMode.actionMessage.contains("按馆内指引签到"))
        assertFalse(manualMode.actionMessage.contains("当前网络"))
    }

    @Test
    fun checkInRequiresFreshServerActionAndPreservesSchoolFailureWithWifiGuidance() = runBlocking {
        val account = SavedAccount("fake", "209999990001", "fake", "测试")
        var actions = 0
        var restricted = true
        val repository = LibraryFunctionsRepository(request = { _, endpoint ->
            if (endpoint == "/self") {
                val status = if (restricted) "LOC_RESTRICT" else "RESERVE"
                """<script>var userInfo='{"currentReservationStatus":"$status","softMode":true}';</script>"""
            } else {
                assertEquals("/user/checkIn", endpoint)
                actions++
                """{"status":"fail","message":"该功能仅在场馆内有效","data":{"status":"LOC_RESTRICT"}}"""
            }
        }, post = { _, _, _ -> error("Seat actions must not invent a POST contract") })
        try { repository.performAction(account, "checkIn"); fail("Restricted state must not send an action") }
        catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("图书馆 Wi-Fi")) }
        assertEquals(0, actions)
        restricted = false
        val result = repository.performAction(account, "checkIn")
        assertFalse(result.success)
        assertTrue(result.message.contains("该功能仅在场馆内有效"))
        assertTrue(result.message.contains("图书馆 Wi-Fi"))
        assertEquals(1, actions)
    }

    @Test
    fun seatSearchPreservesIdentityAvailabilityAndPagination() {
        val response = """{"seatNum":2,"offset":3,"seatStr":"<ul><li id='seat_5773' class='free' title='座位空闲'><dl><dt>001D</dt><dd>二层内环区</dd></dl></li><li id='seat_8' class='noUsre' title='不可预约'><dl><dt>002A</dt><dd>三层外环区</dd></dl></li></ul>"}"""
        val page = LibraryFunctionsRepository.parseSeatPage(response)
        assertEquals(2, page.seatNum)
        assertEquals(3, page.nextOffset)
        assertEquals("5773", page.seats.first().id)
        assertEquals("001D", page.seats.first().label)
        assertEquals("二层内环区", page.seats.first().room)
        assertTrue(page.seats.first().available)
        assertFalse(page.seats.last().available)
    }

    @Test
    fun rateLimitRequiresOfficialVerificationAndNeverReturnsBookableSeats() {
        val page = LibraryFunctionsRepository.parseSeatPage("""{"limit":true,"seatNum":32,"offset":1,"seatStr":"<li id='seat_1' class='free'><dt>001</dt></li>"}""")
        assertTrue(page.verificationRequired)
        assertTrue(page.seats.isEmpty())
        assertNull(page.nextOffset)
        assertTrue(page.message.contains("验证"))
    }

    @Test
    fun invalidDateAndOutOfDayTimesNeverSubmitReservation() = runBlocking {
        var posts = 0
        val account = SavedAccount("fake", "209999990001", "fake", "测试")
        val repository = LibraryFunctionsRepository(request = { _, _ -> error("Prepared input needs no GET") },
            post = { _, _, _ -> posts++; "<p>预约成功</p>" })
        val seat = LibrarySeat("1", "001", "阅览室", "空闲", true)
        for ((date, start, end) in listOf(Triple("2026-02-30", "450", "480"), Triple("2026-10-14", "-30", "30"),
            Triple("2026-10-14", "450", "1500"))) {
            val prepared = PreparedReservation(ReservationSelection(date, seat, LibraryOption(start, "开始"), LibraryOption(end, "结束")),
                mapOf("SYNCHRONIZER_TOKEN" to "synthetic-token"), false, account.id)
            try { repository.reserve(account, prepared); fail("Invalid reservation must be blocked before POST") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, posts)
    }

    @Test
    fun actualStartNowSentinelRemainsLiteralAndCanOnlyBookSchoolToday() = runBlocking {
        val times = LibraryFunctionsRepository.parseTimes("""<li><a time="now">现在</a></li><li><a time="1140">19:00</a></li>
            <a time="-1">非法哨兵</a><a time="immediate">非法哨兵</a><li class="disabled"><a time="now">禁用现在</a></li>""")
        assertEquals(listOf("now", "1140"), times.options.map { it.value })
        val account = SavedAccount("fake", "209999990001", "fake", "测试")
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val seat = LibrarySeat("1", "001D", "二层内环区", "空闲", true)
        var posts = 0
        var postedStart: String? = null
        val repository = LibraryFunctionsRepository(request = { _, _ -> error("Prepared request needs no GET") },
            post = { _, _, fields -> posts++; postedStart = fields["start"]; "<p>预约成功</p>" })
        fun prepared(date: LocalDate, start: String = "now", end: String = "1440") = PreparedReservation(
            ReservationSelection(date.toString(), seat, LibraryOption(start, "现在"), LibraryOption(end, "24:00")),
            mapOf("SYNCHRONIZER_TOKEN" to "synthetic-now-token"), false, account.id)
        for (invalid in listOf(prepared(today.plusDays(1)), prepared(today.minusDays(1)), prepared(today, "immediate"),
            prepared(today, end = "now"), prepared(today, end = "-1"))) {
            try { repository.reserve(account, invalid); fail("Only exact now on school today is allowed") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, posts)
        assertTrue(repository.reserve(account, prepared(today)).success)
        assertEquals(1, posts)
        assertEquals("now", postedStart)
    }

    @Test
    fun timeOptionsExcludeDisabledSlotsAndPreserveServerValues() {
        val times = LibraryFunctionsRepository.parseTimes("""
            <input id="building" value="图书馆"><input id="room" value="二层内环区">
            <a time="480">08:00</a><a time="495" class="disabled">08:15</a><a time="510">08:30</a>
            <li class="disabled"><a time="540">09:00</a></li><a time="-10">非法时间</a><a time="5000">非法时间</a>
            <a time="510">08:30重复项</a>
        """.trimIndent())
        assertEquals(listOf("480", "510"), times.options.map { it.value })
        assertEquals(listOf("08:00", "08:30"), times.options.map { it.label })
        assertEquals("二层内环区", times.room)
        assertFalse(times.verificationRequired)
    }

    @Test
    fun historyParsesRecordsAndDoesNotExposeHiddenLoadMoreAsReservation() {
        val document = LibraryHtml.parse("""
            <div class="myReserveList"><dl id="reservation_9"><dt>预约凭证</dt>
            <dd>日期：2026-10-14</dd><dd>时间：08:00 - 09:00</dd><dd>状态：待签到</dd></dl>
            <dl id="moreBlock"><dd class="more" style="display: none"><input id="offset" value="1"></dd></dl></div>
            <script>var userInfo='{"currentReservationStatus":"RESERVE"}';</script>
        """.trimIndent())
        val history = LibraryFunctionsRepository.parseHistory(document)
        assertEquals(1, history.items.size)
        assertEquals("reservation_9", history.items.single().id)
        assertEquals("状态：待签到", history.items.single().status)
        assertEquals("RESERVE", history.userStatus)
        assertNull(history.nextOffset)
    }

    @Test
    fun expiredLoginResponseCannotBeMisreportedAsEmptySeatList() {
        try {
            LibraryFunctionsRepository.parseSeatPage("<form action='/auth/signIn'></form>")
            fail("An expired session must require login")
        } catch (failure: LoginFailure) {
            assertEquals(FailureKind.CREDENTIALS, failure.kind)
        }
    }

    @Test
    fun historyWithoutDomIdsRemainsUniqueAndStableAcrossPages() {
        fun page(date: String) = LibraryFunctionsRepository.parseHistory(LibraryHtml.parse(
            "<div class='myReserveList'><dl><dt>座位预约</dt><dd>日期：$date</dd><dd>座位：001D</dd></dl></div>"))
        val first = page("2026-10-14").items.single()
        val second = page("2026-10-15").items.single()
        assertNotEquals(first.id, second.id)
        assertEquals(first.id, page("2026-10-14").items.single().id)
    }

    @Test
    fun jsonTimeRateLimitRequiresOfficialVerification() {
        val limited = LibraryFunctionsRepository.parseTimes("""{"limit":true,"msg":"请完成验证码验证"}""")
        assertTrue(limited.verificationRequired)
        assertTrue(limited.options.isEmpty())
        assertEquals("请完成验证码验证", limited.message)
    }

    @Test
    fun actualSchoolReceiptConfirmsBookingAndNormalizesChineseDateAndSpacedTime() {
        val document = LibraryHtml.parse("""<div class="content"><div class="layoutSeat"><dl>
            <dt>系统已经为您预定好了<span>座位</span></dt>
            <dd><em>凭证号 ： </em>test-receipt</dd><dd><em>日&nbsp;&nbsp;&nbsp;&nbsp;期 ： </em>2026 年 10 月 14 日</dd>
            <dd><em>时&nbsp;&nbsp;&nbsp;&nbsp;间 ： </em>07 : 30 - 08 : 00</dd>
            <dd><em>位&nbsp;&nbsp;&nbsp;&nbsp;置 ： </em>图书馆二层，座位号001D</dd><dd><em>状&nbsp;&nbsp;&nbsp;&nbsp;态 ： </em>预约</dd>
            </dl></div></div>""")
        val result = LibraryFunctionsRepository.parseReservationResult(document)
        assertTrue(result.success)
        assertEquals("预约成功", result.message)
        assertEquals("test-receipt", result.receipt?.number)
        assertEquals("2026-10-14", result.receipt?.onDate)
        assertEquals("07:30", result.receipt?.begin)
        assertEquals("08:00", result.receipt?.end)
        assertEquals("预约", result.receipt?.status)
        val canceledDocument = LibraryHtml.parse(document.outerHtml())
        canceledDocument.select(".layoutSeat dd").last()!!.html("<em>状态 ： </em>取消")
        val canceled = LibraryFunctionsRepository.parseReservationResult(canceledDocument)
        assertFalse("A canceled receipt must not be reported as a newly successful booking", canceled.success)
        assertEquals("取消", canceled.receipt?.status)
        val rejectedDocument = LibraryHtml.parse(document.outerHtml())
        rejectedDocument.body().append("<div class='errors'>座位已被占用，请重新查询</div>")
        assertFalse("An old receipt beside an explicit error cannot confirm a new booking",
            LibraryFunctionsRepository.parseReservationResult(rejectedDocument).success)
    }

    @Test
    fun unknownReservationResponseIsNeverReportedAsSuccess() {
        val unknown = LibraryFunctionsRepository.parseReservationResult(LibraryHtml.parse("<html><body>预约页面</body></html>"))
        assertFalse(unknown.success)
        val failure = LibraryFunctionsRepository.parseReservationResult(LibraryHtml.parse("<div class='errors'>座位已被预约</div>"))
        assertFalse(failure.success)
        assertEquals("座位已被预约", failure.message)
        val success = LibraryFunctionsRepository.parseReservationResult(LibraryHtml.parse("<p>预约成功</p>"))
        assertTrue(success.success)
        assertFalse(LibraryFunctionsRepository.parseReservationResult(LibraryHtml.parse("<p>未预约成功</p>")).success)
        assertFalse(LibraryFunctionsRepository.parseReservationResult(LibraryHtml.parse("<p>预约成功前请确认日期</p>")).success)
    }
}
