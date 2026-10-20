package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.ReservationMapTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReservationMapTargetTest {
    private val account = SavedAccount("fixture-map", "209999990001", "synthetic", "测试")
    private val target = ReservationMapTarget("2030-01-16", "056B", "图书馆2层二层内环区056B号")

    @Test fun resolvesFreshDateSpecificRoomAndOccupiedSeatWithoutInferringAnyId() = runTest {
        val fixture = Fixture()
        val state = fixture.repository.loadReservation(account, target)
        assertEquals(target.date, state.filters["onDate"])
        assertEquals("building-main", state.filters["building"])
        assertEquals("floor-2", state.filters["floor"])
        assertEquals("room-inner", state.layout!!.roomId)
        assertEquals("actual-seat-901", state.focusSeatId)
        assertEquals("056B", state.focusSeatLabel)
        assertFalse("Occupied seats still have a real location", state.layout.cells.single().seat!!.available)
        assertTrue(state.directRegionView)
        assertFalse(state.verificationRequired)
        val layout = fixture.calls.single { it.encodedPath == "/mapBook/getSeatsByRoom" }
        assertEquals("room-inner", layout.queryParameter("room"))
        assertEquals(target.date, layout.queryParameter("date"))
        fixture.calls.filter { it.encodedPath == "/mapBook/ajaxGetRooms" }.forEach {
            assertEquals(target.date, it.queryParameter("onDate"))
        }
        assertEquals("/map", fixture.calls.first().encodedPath)
    }

    @Test fun matchesOnlyExplicitRoomNamesAfterKnownLocationPrefixesAndFormatting() = runTest {
        for (location in listOf(
            "图书馆二层二层内环区，座位号056B",
            "位置：图书馆 2 层，二层内环区，座位：056B",
            "二层内环区056B号",
            "图书馆二层内环区056B号座位",
        )) {
            val state = Fixture().repository.loadReservation(account, target.copy(location = location))
            assertEquals(location, "room-inner", state.layout?.roomId)
        }
        val fixture = Fixture()
        assertFailure("未找到预约区域") { fixture.repository.loadReservation(account, target.copy(location = "图书馆二层内环区东侧056B号")) }
        assertTrue("Substring matches must not load an arbitrary layout", fixture.calls.none { it.encodedPath == "/mapBook/getSeatsByRoom" })
    }

    @Test fun actualBuildingPrefixedFloorLabelsResolveBothSchoolLocationForms() = runTest {
        for (location in listOf(
            "图书馆2层二层内环区，座位号003D",
            "图书馆2层二层内环区003D号",
        )) {
            val fixture = Fixture().apply {
                responseOverride = { url -> when (url.encodedPath) {
                    "/mapBook/ajaxGetFloor" -> "<a value='floor-2'>图书馆2层</a><a value='floor-3'>图书馆3层</a>" +
                        "<a value='floor-4'>图书馆4层</a><a value='floor-5'>图书馆5层</a>"
                    "/mapBook/ajaxGetRooms" -> if (url.queryParameter("building") == "building-main") {
                        when (url.queryParameter("floor")) {
                            "floor-2" -> """{"rooms":[{"id":"room-inner","name":"二层内环区"},{"id":"room-west","name":"二层西区"}]}"""
                            "floor-3" -> """{"rooms":[{"id":"room-3-inner","name":"三层内环区"},{"id":"room-3-outer","name":"三层外环区"}]}"""
                            else -> """{"rooms":[]}"""
                        }
                    } else """{"rooms":[]}"""
                    "/mapBook/getSeatsByRoom" -> "<ul><li id='seat_actual-seat-003d'><a class='using'>003D</a></li></ul>"
                    else -> null
                } }
            }
            val state = fixture.repository.loadReservation(account, target.copy(seatLabel = "003D", location = location))
            assertEquals(location, "building-main", state.filters["building"])
            assertEquals(location, "floor-2", state.filters["floor"])
            assertEquals(location, "room-inner", state.layout?.roomId)
            assertEquals(location, "actual-seat-003d", state.focusSeatId)
            assertEquals(location, "003D", state.focusSeatLabel)
            assertEquals("room-inner", fixture.calls.single { it.encodedPath == "/mapBook/getSeatsByRoom" }.queryParameter("room"))
        }
    }

    @Test fun malformedAndUnavailableDatesNeverFallBackToTheFirstAvailableDate() = runTest {
        for (date in listOf("", "明天", "2030-02-30", "2030-1-16")) {
            val fixture = Fixture()
            assertFailure("预约日期不明确") { fixture.repository.loadReservation(account, target.copy(date = date)) }
            assertTrue(fixture.calls.isEmpty())
        }
        val fixture = Fixture()
        assertFailure("2030-01-17") { fixture.repository.loadReservation(account, target.copy(date = "2030-01-17")) }
        assertEquals(listOf("/map"), fixture.calls.map { it.encodedPath })
    }

    @Test fun unavailableHistoricalDateUsesTodaysPhysicalMapWithBothDatesExplicit() = runTest {
        val today = LocalDate.parse("2030-01-15")
        val historical = target.copy(date = "2030-01-10")
        val fixture = Fixture()
        val state = fixture.repository.loadReservation(account, historical, today)
        assertEquals("2030-01-10", state.reservationDate)
        assertEquals("2030-01-15", state.filters["onDate"])
        assertEquals("actual-seat-901", state.focusSeatId)
        assertEquals("056B", state.focusSeatLabel)
        assertEquals("预约日期 2030-01-10；当前布局日期 2030-01-15，座位状态以当前布局为准", state.notice)
        assertFalse(state.layout!!.cells.single().seat!!.available)
        fixture.calls.filter { it.encodedPath == "/mapBook/ajaxGetRooms" }.forEach {
            assertEquals(today.toString(), it.queryParameter("onDate"))
        }
        assertEquals(today.toString(), fixture.calls.last().queryParameter("date"))
    }

    @Test fun offeredHistoricalMapKeepsItsOriginalDateAndFutureDatesNeverUseToday() = runTest {
        val fixture = Fixture()
        val state = fixture.repository.loadReservation(account, target.copy(date = "2030-01-15"), LocalDate.parse("2030-01-16"))
        assertEquals("2030-01-15", state.filters["onDate"])
        assertEquals("2030-01-15", state.reservationDate)
        assertNull(state.notice)
        assertEquals("2030-01-15", fixture.calls.last().queryParameter("date"))

        val unavailableFuture = Fixture()
        assertFailure("2030-01-17") { unavailableFuture.repository.loadReservation(account, target.copy(date = "2030-01-17"), LocalDate.parse("2030-01-15")) }
        assertEquals(listOf("/map"), unavailableFuture.calls.map { it.encodedPath })
        val unavailableToday = Fixture()
        assertFailure("2030-01-17") { unavailableToday.repository.loadReservation(account, target.copy(date = "2030-01-17"), LocalDate.parse("2030-01-17")) }
        assertEquals(listOf("/map"), unavailableToday.calls.map { it.encodedPath })
    }

    @Test fun historicalFallbackRequiresTodayInsteadOfChoosingAnArbitraryAvailableDate() = runTest {
        val fixture = Fixture()
        assertFailure("暂无 2030-01-17 的当前地图") {
            fixture.repository.loadReservation(account, target.copy(date = "2030-01-10"), LocalDate.parse("2030-01-17"))
        }
        assertEquals(listOf("/map"), fixture.calls.map { it.encodedPath })
    }

    @Test fun repeatedRoomNamesAcrossBuildingsOrFloorsFailBeforeOpeningAnyLayout() = runTest {
        for (duplicateScope in listOf("building", "floor")) {
            val fixture = Fixture()
            fixture.responseOverride = { url ->
                if (url.encodedPath == "/mapBook/ajaxGetRooms" &&
                    (duplicateScope == "building" && url.queryParameter("building") == "building-other" ||
                        duplicateScope == "floor" && url.queryParameter("floor") == "floor-3" && url.queryParameter("building") == "building-main"))
                    """{"rooms":[{"id":"duplicate-room","name":"二层内环区"}]}""" else null
            }
            assertFailure("多个地图位置") { fixture.repository.loadReservation(account, target.copy(location = "二层内环区056B号")) }
            assertTrue(fixture.calls.none { it.encodedPath == "/mapBook/getSeatsByRoom" })
        }
    }

    @Test fun missingAndRepeatedSeatLabelsFailInsteadOfUsingAnotherSeat() = runTest {
        for (layout in listOf(
            "<ul><li id='seat_other'><a class='idle'>057B</a></li></ul>" to "未找到 056B 号座位",
            "<ul><li id='seat_one'><a class='using'>056B</a></li><li id='seat_two'><a class='idle'>056B</a></li></ul>" to "多个 056B 号座位",
        )) {
            val fixture = Fixture()
            fixture.responseOverride = { if (it.encodedPath == "/mapBook/getSeatsByRoom") layout.first else null }
            assertFailure(layout.second) { fixture.repository.loadReservation(account, target) }
            assertEquals(target.date, fixture.calls.last().queryParameter("date"))
        }
    }

    @Test fun roomAndLayoutChallengesStopBeforeFocusAndPreserveTargetDate() = runTest {
        val roomChallenge = Fixture().apply {
            responseOverride = { if (it.encodedPath == "/mapBook/ajaxGetRooms") """{"limit":true,"userId":"fixture-verify"}""" else null }
        }
        try {
            roomChallenge.repository.loadReservation(account, target)
            fail("Room challenges must propagate for verification")
        } catch (error: NativeMapRepository.VerificationRequired) {
            assertEquals("fixture-verify", error.userId)
        }
        assertTrue(roomChallenge.calls.none { it.encodedPath == "/mapBook/getSeatsByRoom" })
        val layoutChallenge = Fixture().apply {
            responseOverride = { if (it.encodedPath == "/mapBook/getSeatsByRoom") """{"limit":true,"userId":"fixture-verify"}""" else null }
        }
        val state = layoutChallenge.repository.loadReservation(account, target)
        assertTrue(state.verificationRequired)
        assertEquals(target.date, state.filters["onDate"])
        assertNull(state.focusSeatId)
        assertNull(state.focusSeatLabel)
    }

    @Test fun incompleteDiscoveryDoesNotGuessAUniqueRoomAndCancellationPropagates() = runTest {
        val fixture = Fixture()
        fixture.responseOverride = { if (it.encodedPath == "/mapBook/ajaxGetRooms") """{"rooms":[{"id":"room-inner","name":"二层内环区"},{}]}""" else null }
        assertFailure("部分区域信息") { fixture.repository.loadReservation(account, target) }
        assertTrue(fixture.calls.none { it.encodedPath == "/mapBook/getSeatsByRoom" })
        fixture.responseOverride = { throw CancellationException("account changed") }
        try {
            fixture.repository.loadReservation(account, target)
            fail("Cancellation must not publish the previous account's seat")
        } catch (_: CancellationException) {
        }
    }

    private suspend fun assertFailure(fragment: String, block: suspend () -> Unit) {
        try { block(); fail("Expected failure containing $fragment") }
        catch (error: IllegalArgumentException) { assertTrue(error.message, error.message.orEmpty().contains(fragment)) }
        catch (error: IllegalStateException) { assertTrue(error.message, error.message.orEmpty().contains(fragment)) }
    }

    private class Fixture {
        val calls = mutableListOf<HttpUrl>()
        var responseOverride: (HttpUrl) -> String? = { null }
        val repository = NativeMapRepository { _, endpoint ->
            val url = ("http://fixture.invalid" + endpoint).toHttpUrl()
            calls += url
            responseOverride(url) ?: when (url.encodedPath) {
                "/map" -> """
                    <p id='options_onDate'><a value='2030-01-15'>今天</a><a value='2030-01-16'>明天</a></p>
                    <p id='options_building'><a value='building-other'>另一馆</a><a value='building-main'>图书馆</a></p>
                """.trimIndent()
                "/mapBook/ajaxGetFloor" -> "<a value='floor-2'>二层</a><a value='floor-3'>3</a>"
                "/mapBook/ajaxGetRooms" -> when {
                    url.queryParameter("building") == "building-main" && url.queryParameter("floor") == "floor-2" ->
                        """{"rooms":[{"id":"room-outer","name":"二层外环区"},{"id":"room-inner","name":"二层内环区"}]}"""
                    else -> """{"rooms":[{"id":"other-room","name":"三层外环区"}]}"""
                }
                "/mapBook/getSeatsByRoom" -> "<ul><li id='seat_actual-seat-901'><a class='using'>056B</a></li></ul>"
                else -> error("Unexpected request $url")
            }
        }
    }
}
