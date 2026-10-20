package cn.edu.bucea.library

import cn.edu.bucea.library.data.NativeMapRepository
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.data.models.RegionSeatOverview
import cn.edu.bucea.library.data.models.ReservationMapTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class NativeMapCoordinatorTest {
    @Test fun retryLoadsTheFailedRoomInsteadOfThePreviouslyRenderedRoom() = runTest {
        val fixture = Fixture(backgroundScope)
        showRoomAAndFailB(fixture)

        val finishRetry = CompletableDeferred<Unit>()
        fixture.layoutResponse = { _, _ -> finishRetry.await(); null }
        fixture.map.refresh()
        runCurrent()
        assertTrue(fixture.map.mapState.value.loading)
        assertNull(fixture.map.mapState.value.error)
        assertEquals("room-a", fixture.map.mapState.value.layout?.roomId)
        assertEquals(listOf("room-a", "room-b", "room-b"), fixture.requestedRooms())

        finishRetry.complete(Unit)
        runCurrent()
        assertFalse(fixture.map.mapState.value.loading)
        assertEquals("room-b", fixture.map.mapState.value.layout?.roomId)
        assertEquals("001A", fixture.map.mapState.value.layout?.cells?.single()?.seat?.label)
    }

    @Test fun verificationRetryRetainsTheSelectedRoom() = runTest {
        val fixture = Fixture(backgroundScope)
        openRoomA(fixture)
        fixture.layoutResponse = { _, _ -> """{"limit":true,"userId":"synthetic-user"}""" }
        fixture.map.loadRoom("room-b")
        runCurrent()
        assertTrue(fixture.map.mapState.value.verificationRequired)
        assertEquals("synthetic-user", fixture.map.mapState.value.verificationUserId)

        fixture.layoutResponse = { _, _ -> null }
        fixture.map.refresh()
        runCurrent()
        assertEquals(listOf("room-a", "room-b", "room-b"), fixture.requestedRooms())
        assertEquals("room-b", fixture.map.mapState.value.layout?.roomId)
        assertFalse(fixture.map.mapState.value.verificationRequired)
    }

    @Test fun changingAnyMapFilterDiscardsTheFailedRoomEvenWhenItsIdExistsInTheNewRooms() = runTest {
        listOf("onDate" to "2030-01-16", "building" to "building-b", "floor" to "floor-2").forEach { (key, value) ->
            val fixture = Fixture(backgroundScope)
            showRoomAAndFailB(fixture)
            fixture.layoutResponse = { _, _ -> null }
            fixture.requests.clear()

            fixture.map.onFilter(key, value)
            runCurrent()
            fixture.map.refresh()
            runCurrent()

            assertEquals(value, fixture.map.mapState.value.filters[key])
            assertEquals(listOf("room-a", "room-b"), fixture.map.mapState.value.rooms.map { it.id })
            assertNull("Changing $key returns to the region picker", fixture.map.mapState.value.layout)
            assertTrue("Changing $key must not retry the old room", fixture.requestedRooms().isEmpty())
            fixture.map.close()
        }
    }

    @Test fun aNewServerDayDiscardsTheFailedRoomAndReloadsTheChoices() = runTest {
        val fixture = Fixture(backgroundScope)
        showRoomAAndFailB(fixture)
        fixture.layoutResponse = { _, _ -> null }
        fixture.requests.clear()
        fixture.day = fixture.day.plusDays(1)

        fixture.map.refresh()
        runCurrent()

        assertEquals(listOf("/map", "/mapBook/ajaxGetFloor", "/mapBook/ajaxGetRooms"), fixture.requests.map { it.url.encodedPath })
        assertNull(fixture.map.mapState.value.layout)
        assertTrue(fixture.requestedRooms().isEmpty())
    }

    @Test fun accountSwitchCancelsTheOldRoomAndStartsWithNewAccountChoices() = runTest {
        val fixture = Fixture(backgroundScope)
        openRoomA(fixture)
        val oldRead = CompletableDeferred<Unit>()
        var cancelled = false
        fixture.layoutResponse = { account, _ ->
            if (account.id == "old") {
                try { oldRead.await() } finally { cancelled = true }
            }
            null
        }
        fixture.map.loadRoom("room-b")
        runCurrent()
        assertTrue(fixture.map.mapState.value.loading)

        fixture.map.bindAccount("new", true)
        fixture.map.refresh()
        runCurrent()

        assertTrue(cancelled)
        assertNull(fixture.map.mapState.value.layout)
        assertNull(fixture.map.mapState.value.error)
        assertFalse(fixture.map.mapState.value.loading)
        assertEquals(listOf("/map", "/mapBook/ajaxGetFloor", "/mapBook/ajaxGetRooms"),
            fixture.requests.filter { it.accountId == "new" }.map { it.url.encodedPath })
        assertEquals(listOf("room-a", "room-b"), fixture.map.mapState.value.rooms.map { it.id })
    }

    @Test fun supersedingRoomSelectionCancelsTheOldReadAndRefreshUsesTheLatestRoom() = runTest {
        val fixture = Fixture(backgroundScope)
        openRoomA(fixture)
        val oldRead = CompletableDeferred<Unit>()
        var cancelled = false
        fixture.layoutResponse = { _, url ->
            if (url.queryParameter("room") == "room-b") {
                try { oldRead.await() } finally { cancelled = true }
            }
            null
        }
        fixture.map.loadRoom("room-b")
        runCurrent()
        fixture.map.loadRoom("room-a")
        runCurrent()
        fixture.map.refresh()
        runCurrent()

        assertTrue(cancelled)
        assertEquals(listOf("room-a", "room-b", "room-a", "room-a"), fixture.requestedRooms())
        assertEquals("room-a", fixture.map.mapState.value.layout?.roomId)
        assertFalse(fixture.map.mapState.value.loading)
        assertNull(fixture.map.mapState.value.error)
    }

    @Test fun directRegionFailureRetriesTheExactRegionThenRefreshesItsLayout() = runTest {
        val fixture = Fixture(backgroundScope)
        val target = RegionSeatOverview("room-b", "区域 B", "building-b", "目标馆", "floor-2", "二层", "2030-01-16")
        fixture.layoutResponse = { _, _ -> throw IOException("synthetic failure") }
        fixture.map.selectRegion(target)
        fixture.map.setListMode(true)
        fixture.map.refresh()
        runCurrent()
        assertNotNull(fixture.map.mapState.value.error)
        assertTrue(fixture.map.mapState.value.directRegionView)

        fixture.layoutResponse = { _, _ -> null }
        fixture.map.refresh()
        runCurrent()
        fixture.map.refresh()
        runCurrent()

        assertEquals(listOf("room-b", "room-b", "room-b"), fixture.requestedRooms())
        val roomReads = fixture.requests.filter { it.url.encodedPath == "/mapBook/ajaxGetRooms" }
        assertEquals(2, roomReads.size)
        roomReads.forEach {
            assertEquals(target.buildingId, it.url.queryParameter("building"))
            assertEquals(target.floorId, it.url.queryParameter("floor"))
            assertEquals(target.date, it.url.queryParameter("onDate"))
        }
        assertEquals(2, fixture.requests.count { it.url.encodedPath == "/map" })
        assertEquals(target.id, fixture.map.mapState.value.layout?.roomId)
        assertTrue(fixture.map.mapState.value.directRegionView)
        assertTrue(fixture.map.mapState.value.listMode)
        assertNull(fixture.map.mapState.value.error)
    }

    @Test fun reservationFailureAndRefreshRetainItsExactDateSeatAndRoomTarget() = runTest {
        val fixture = Fixture(backgroundScope)
        val target = ReservationMapTarget("2030-01-16", "001A", "目标馆二层区域 B001A号")
        fixture.reservationRooms = true
        fixture.layoutResponse = { _, _ -> throw IOException("synthetic failure") }
        fixture.map.openReservation(target)
        assertEquals(target.date, fixture.map.mapState.value.filters["onDate"])
        assertEquals(target.seatLabel, fixture.map.mapState.value.focusSeatLabel)
        assertNull(fixture.map.mapState.value.focusSeatId)
        fixture.map.refresh()
        runCurrent()
        assertEquals("synthetic failure", fixture.map.mapState.value.error)
        assertEquals(target.date, fixture.map.mapState.value.filters["onDate"])
        assertEquals(target.seatLabel, fixture.map.mapState.value.focusSeatLabel)
        assertTrue(fixture.map.mapState.value.directRegionView)

        fixture.layoutResponse = { _, _ -> null }
        fixture.map.setListMode(true)
        fixture.map.refresh()
        runCurrent()
        assertEquals("room-b", fixture.map.mapState.value.layout?.roomId)
        assertEquals("1", fixture.map.mapState.value.focusSeatId)
        assertEquals("001A", fixture.map.mapState.value.focusSeatLabel)
        assertTrue(fixture.map.mapState.value.listMode)
        assertNull(fixture.map.mapState.value.error)
        fixture.day = fixture.day.plusDays(1)
        fixture.map.refresh()
        runCurrent()
        assertEquals(listOf("room-b", "room-b", "room-b"), fixture.requestedRooms())
        fixture.requests.filter { it.url.encodedPath == "/mapBook/getSeatsByRoom" }.forEach {
            assertEquals(target.date, it.url.queryParameter("date"))
        }
        assertEquals(3, fixture.requests.count { it.url.encodedPath == "/map" })
    }

    @Test fun reservationAccountSwitchCancelsAndClearsStagedFocusBeforeNewAccountReads() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.reservationRooms = true
        val oldRead = CompletableDeferred<Unit>()
        var canceled = false
        fixture.layoutResponse = { account, _ ->
            if (account.id == "old") {
                try { oldRead.await() } finally { canceled = true }
            }
            null
        }
        fixture.map.openReservation(ReservationMapTarget("2030-01-16", "001A", "目标馆二层区域 B001A号"))
        fixture.map.refresh()
        runCurrent()
        assertTrue(fixture.map.mapState.value.loading)
        fixture.map.bindAccount("new", true)
        fixture.map.refresh()
        runCurrent()
        assertTrue(canceled)
        assertNull(fixture.map.mapState.value.focusSeatId)
        assertNull(fixture.map.mapState.value.focusSeatLabel)
        assertNull(fixture.map.mapState.value.layout)
        assertFalse(fixture.map.mapState.value.directRegionView)
        assertEquals("2030-01-15", fixture.map.mapState.value.filters["onDate"])
        assertTrue(fixture.requests.filter { it.accountId == "new" }.none { it.url.encodedPath == "/mapBook/getSeatsByRoom" })
    }

    @Test fun historicalReservationKeepsBookingDateWhileRefreshingTodaysPhysicalLayout() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.reservationRooms = true
        val target = ReservationMapTarget("2030-01-10", "001A", "目标馆二层区域 B001A号")
        fixture.map.openReservation(target)
        assertEquals(target.date, fixture.map.mapState.value.reservationDate)
        fixture.map.refresh()
        runCurrent()
        assertEquals("2030-01-15", fixture.map.mapState.value.filters["onDate"])
        assertEquals(target.date, fixture.map.mapState.value.reservationDate)
        assertEquals("1", fixture.map.mapState.value.focusSeatId)
        assertTrue(fixture.map.mapState.value.notice.orEmpty().contains("当前布局日期 2030-01-15"))
        fixture.day = fixture.day.plusDays(1)
        fixture.map.refresh()
        runCurrent()
        assertEquals("2030-01-16", fixture.map.mapState.value.filters["onDate"])
        assertEquals(target.date, fixture.map.mapState.value.reservationDate)
        assertTrue(fixture.map.mapState.value.notice.orEmpty().contains("预约日期 2030-01-10；当前布局日期 2030-01-16"))
        fixture.map.showRegionSelector()
        assertNull(fixture.map.mapState.value.reservationDate)
        assertNull(fixture.map.mapState.value.notice)
    }

    @Test fun leavingReservationByRegionFilterOrRoomClearsFocusAndThePendingTarget() = runTest {
        for (exit in listOf("selector", "region", "filter", "room")) {
            val fixture = Fixture(backgroundScope)
            fixture.reservationRooms = true
            fixture.map.openReservation(ReservationMapTarget("2030-01-16", "001A", "目标馆二层区域 B001A号"))
            fixture.map.refresh()
            runCurrent()
            assertEquals("1", fixture.map.mapState.value.focusSeatId)
            fixture.requests.clear()
            when (exit) {
                "selector" -> fixture.map.showRegionSelector()
                "region" -> fixture.map.selectRegion(RegionSeatOverview("room-a", "区域 A", "building-a", "默认馆", "floor-1", "一层", "2030-01-15"))
                "filter" -> fixture.map.onFilter("onDate", "2030-01-15")
                else -> fixture.map.loadRoom("room-a")
            }
            runCurrent()
            assertNull(exit, fixture.map.mapState.value.focusSeatId)
            assertNull(exit, fixture.map.mapState.value.focusSeatLabel)
            assertNull(exit, fixture.map.mapState.value.reservationDate)
            fixture.map.refresh()
            runCurrent()
            assertNull(exit, fixture.map.mapState.value.focusSeatId)
            assertNull(exit, fixture.map.mapState.value.focusSeatLabel)
            fixture.map.close()
        }
    }

    private fun TestScope.openRoomA(fixture: Fixture) {
        fixture.map.refresh()
        runCurrent()
        fixture.map.loadRoom("room-a")
        runCurrent()
        assertEquals("room-a", fixture.map.mapState.value.layout?.roomId)
    }

    private fun TestScope.showRoomAAndFailB(fixture: Fixture) {
        openRoomA(fixture)
        fixture.layoutResponse = { _, _ -> throw IOException("synthetic failure") }
        fixture.map.loadRoom("room-b")
        runCurrent()
        assertEquals("synthetic failure", fixture.map.mapState.value.error)
        assertEquals("room-a", fixture.map.mapState.value.layout?.roomId)
        assertFalse(fixture.map.mapState.value.loading)
    }

    private data class Request(val accountId: String, val url: HttpUrl)

    private class Fixture(scope: CoroutineScope) {
        var day: LocalDate = LocalDate.parse("2030-01-15")
        val requests = mutableListOf<Request>()
        var reservationRooms: Boolean = false
        var layoutResponse: suspend (SavedAccount, HttpUrl) -> String? = { _, _ -> null }
        private val repository = NativeMapRepository { account, endpoint ->
            val url = ("http://fixture.invalid" + endpoint).toHttpUrl()
            requests += Request(account.id, url)
            when (url.encodedPath) {
                "/map" -> """
                    <p id='options_onDate'><a value='2030-01-15'>今天</a><a value='2030-01-16'>明天</a></p>
                    <p id='options_building'><a value='building-a'>默认馆</a><a value='building-b'>目标馆</a></p>
                """.trimIndent()
                "/mapBook/ajaxGetFloor" -> "<a value='floor-1'>一层</a><a value='floor-2'>二层</a>"
                "/mapBook/ajaxGetRooms" -> if (reservationRooms &&
                    !(url.queryParameter("building") == "building-b" && url.queryParameter("floor") == "floor-2"))
                    """{"rooms":[{"id":"room-a","name":"区域 A"}]}"""
                else """{"rooms":[{"id":"room-a","name":"区域 A"},{"id":"room-b","name":"区域 B"}]}"""
                "/mapBook/getSeatsByRoom" -> layoutResponse(account, url)
                    ?: "<ul><li id='seat_1'><a class='idle'>001A</a></li></ul>"
                else -> error("Unexpected request $url")
            }
        }
        val map = NativeMapCoordinator(scope, { repository },
            account = { SavedAccount(requireNotNull(it), "209999990001", "synthetic", "测试") },
            releaseSession = {}, userMessage = { it.message.orEmpty() }, today = { day },
        ).apply { bindAccount("old", true) }

        fun requestedRooms() = requests.filter { it.url.encodedPath == "/mapBook/getSeatsByRoom" }
            .map { it.url.queryParameter("room") }
    }
}
