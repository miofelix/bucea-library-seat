package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.RegionOverviewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Synthetic school responses prove the read sequence and prevent missing data becoming zero occupancy. */
class RegionOverviewRepositoryTest {
    private val account = SavedAccount("fixture", "209999990001", "synthetic", "测试")
    // It is already the 15th in Shanghai while the clock's own zone still reports the 14th.
    private val clock = Clock.fixed(Instant.parse("2030-01-14T17:00:00Z"), ZoneOffset.UTC)
    private val today = "2030-01-15"

    @Test fun discoversAllBuildingsAndFloorsDeduplicatesRoomsAndUsesOnlyShanghaiToday() = runTest {
        val calls = mutableListOf<HttpUrl>()
        val progress = mutableListOf<RegionOverviewState>()
        val repository = repository(calls) { url ->
            when (url.encodedPath) {
                "/map" -> options("""
                    <a value='null'>全部</a><a value='1'>大兴馆</a><a value='1'>重复馆舍</a><a value='2'>西城馆</a>
                """)
                "/mapBook/ajaxGetFloor" -> if (url.queryParameter("id") == "1")
                    "<a value='null'>不限</a><a value='11'>一层</a><a value='12'>二层</a><a value='12'>重复楼层</a>"
                    else "<a value='21'>三层</a>"
                "/mapBook/ajaxGetRooms" -> when (url.queryParameter("floor")) {
                    "11" -> """{"rooms":[{"id":"r1","name":"东区"},{"id":"r1","name":"重复东区"}]}"""
                    "12" -> """{"rooms":[{"id":"r1","name":"重复东区"},{"id":"r2","name":"西区"}]}"""
                    else -> """{"rooms":[{"id":"r1","name":"西城东区"}]}"""
                }
                "/mapBook/getSeatsByRoom" -> seats("idle")
                else -> error("Unexpected endpoint $url")
            }
        }
        val state = repository.load(account) { progress += it }
        assertEquals(today, state.date)
        assertTrue(state.discoveryComplete)
        assertEquals(3, state.totalRegions)
        assertEquals(3, state.completedRegions)
        assertEquals(listOf("东区", "西区", "西城东区"), state.regions.map { it.name })
        assertEquals(listOf("11", "12", "21"), state.regions.map { it.floorId })
        assertEquals(listOf("1", "1", "2"), state.regions.map { it.buildingId })
        assertTrue(state.regions.all { it.date == today && it.counts?.free == 1 })
        assertEquals(clock.millis(), state.updatedAt)
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(2, calls.count { it.encodedPath == "/mapBook/ajaxGetFloor" })
        assertEquals(3, calls.count { it.encodedPath == "/mapBook/ajaxGetRooms" })
        val firstLayout = calls.indexOfFirst { it.encodedPath == "/mapBook/getSeatsByRoom" }
        assertTrue(calls.take(firstLayout).none { it.encodedPath == "/mapBook/getSeatsByRoom" })
        assertTrue(calls.drop(firstLayout).all { it.encodedPath == "/mapBook/getSeatsByRoom" })
        calls.filter { it.encodedPath == "/mapBook/ajaxGetRooms" }.forEach {
            assertEquals(today, it.queryParameter("onDate"))
            assertNull(it.queryParameter("date"))
        }
        calls.filter { it.encodedPath == "/mapBook/getSeatsByRoom" }.forEach {
            assertEquals(today, it.queryParameter("date"))
            assertNull(it.queryParameter("onDate"))
        }
        assertTrue(progress.any { it.loading && it.totalRegions == 3 && it.completedRegions == 0 })
        assertTrue(progress.any { it.loading && it.completedRegions == 1 })
    }

    @Test fun countsCurrentStatusesIndependentlyOfBookingAndKeepsLocalAndUnknownDistinct() {
        val layout = NativeMapRepository.parseLayout("""
            <ul>
              <li id='seat_free'><a class='idle_both'>001</a></li>
              <li id='seat_using'><a class='usre_power'>002</a></li>
              <li id='seat_reserved'><a class='agreement'>003</a></li>
              <li id='seat_away'><a class='leave'>004</a></li>
              <li id='seat_off'><a class='noUsre'>005</a></li>
              <li id='seat_unknown'><a class='new-school-status'>006</a></li>
              <li id='seat_local'><a class='idle' local='true'>007</a></li>
              <li id='seat_current-free'><a class='usre' title='座位空闲'>008</a></li>
              <li id='seat_current-use'><a class='idle' title='正在使用'>009</a></li>
              <li id='seat_free'><a class='idle_both'>重复001</a></li>
              <li class='table'>桌</li><li>&nbsp;</li>
            </ul>
        """.trimIndent(), "room", "区域")
        val counts = RegionOverviewRepository.counts(layout)!!
        assertEquals(9, counts.total)
        assertEquals(2, counts.free)
        assertEquals(2, counts.inUse)
        assertEquals(1, counts.reserved)
        assertEquals(1, counts.away)
        assertEquals(1, counts.unavailable)
        assertEquals(1, counts.unknown)
        assertEquals(1, counts.localOnly)
        assertEquals(4, counts.occupied)
        assertEquals("未知状态", layout.cells.single { it.seat?.id == "unknown" }.seat!!.status)
        assertFalse(layout.cells.single { it.seat?.id == "current-free" }.seat!!.available)
        assertTrue(layout.cells.single { it.seat?.id == "current-use" }.seat!!.available)
    }

    @Test fun unreadableAndEmptyLayoutsStayErrorsAndSuccessfulRegionsRemainVisible() = runTest {
        val calls = mutableListOf<HttpUrl>()
        val state = repository(calls) { url ->
            if (url.encodedPath == "/mapBook/getSeatsByRoom") when (url.queryParameter("room")) {
                "r1" -> seats("usre")
                "r2" -> "<div>该区域暂未提供座位布局</div>"
                else -> """{"status":"fail","msg":"服务暂不可用"}"""
            } else commonDiscovery(url)
        }.load(account)
        assertEquals(3, state.regions.size)
        assertEquals(1, state.regions.first().counts!!.inUse)
        state.regions.drop(1).forEach {
            assertNull(it.counts)
            assertFalse(it.loading)
            assertNotNull(it.error)
            assertNull(it.updatedAt)
        }
        assertEquals(3, state.completedRegions)
        assertNotNull(state.error)
        assertTrue(state.discoveryComplete)
    }

    @Test fun missingTodayNeverFallsBackToFutureDateOrContinuesDiscovery() = runTest {
        val calls = mutableListOf<HttpUrl>()
        val state = repository(calls) { options(dates = "2030-01-16") }.load(account)
        assertEquals(1, calls.size)
        assertEquals(today, state.date)
        assertTrue(state.regions.isEmpty())
        assertFalse(state.discoveryComplete)
        assertTrue(state.error!!.contains(today))
    }

    @Test fun malformedRoomDiscoveryLeavesKnownRegionsButMarksListIncomplete() = runTest {
        val calls = mutableListOf<HttpUrl>()
        val state = repository(calls) { url -> when (url.encodedPath) {
            "/map" -> options()
            "/mapBook/ajaxGetFloor" -> "<a value='11'>一层</a><a value='12'>二层</a>"
            "/mapBook/ajaxGetRooms" -> if (url.queryParameter("floor") == "11")
                """{"rooms":[{"id":"r1","name":"东区"}]}""" else """{"status":"fail"}"""
            else -> seats("idle")
        } }.load(account)
        assertEquals(1, state.regions.size)
        assertNotNull(state.regions.single().counts)
        assertFalse(state.discoveryComplete)
        assertEquals(1, state.discoveryErrors.size)
        assertTrue(state.discoveryErrors.single().contains("二层"))
        assertNotNull(state.error)
    }

    @Test fun partiallyMalformedRoomsPreserveReadableAreaAndCannotClaimCompleteDiscovery() = runTest {
        val calls = mutableListOf<HttpUrl>()
        val state = repository(calls) { url -> when (url.encodedPath) {
            "/mapBook/ajaxGetRooms" -> """{"rooms":[{"id":"r1","name":"东区"},{"id":"r2"},null]}"""
            "/mapBook/getSeatsByRoom" -> seats("idle")
            else -> commonDiscovery(url)
        } }.load(account)
        assertEquals("东区", state.regions.single().name)
        assertEquals(1, state.regions.single().counts!!.free)
        assertFalse(state.discoveryComplete)
        assertTrue(state.discoveryErrors.single().contains("部分阅览区域"))
        assertNotNull(state.error)
    }

    @Test fun verificationStopsAtEveryEndpointAndPreservesExplicitChallengeIdentity() = runTest {
        listOf("/map", "/mapBook/ajaxGetFloor", "/mapBook/ajaxGetRooms", "/mapBook/getSeatsByRoom").forEach { endpoint ->
            val calls = mutableListOf<HttpUrl>()
            val state = repository(calls) { url ->
                if (url.encodedPath == endpoint) """{"limit":true,"userId":"fixture-verification"}"""
                else commonDiscovery(url)
            }.load(account)
            assertTrue("$endpoint must stop for verification", state.verificationRequired)
            assertEquals("fixture-verification", state.verificationUserId)
            assertFalse(state.loading)
            assertEquals(endpoint, calls.last().encodedPath)
            assertTrue(state.regions.all { !it.loading && it.counts == null && it.error != null })
            assertEquals(1, calls.count { it.encodedPath == endpoint })
        }
    }

    @Test fun htmlRateLimitStopsFloorAndRoomDiscoveryAsWellAsLayouts() = runTest {
        listOf("/map", "/mapBook/ajaxGetFloor", "/mapBook/ajaxGetRooms", "/mapBook/getSeatsByRoom").forEach { endpoint ->
            val calls = mutableListOf<HttpUrl>()
            val state = repository(calls) { url ->
                if (url.encodedPath == endpoint) "<p>您操作的频率太快</p><script>clickVerifyCode('')</script>"
                else commonDiscovery(url)
            }.load(account)
            assertTrue(state.verificationRequired)
            assertEquals("", state.verificationUserId)
            assertEquals(endpoint, calls.last().encodedPath)
            assertEquals(1, calls.count { it.encodedPath == endpoint })
        }
    }

    @Test fun challengeDuringLayoutsKeepsSuccessfulCountsAndAllUnqueriedRegions() = runTest {
        val calls = mutableListOf<HttpUrl>()
        val state = repository(calls) { url ->
            if (url.encodedPath != "/mapBook/getSeatsByRoom") commonDiscovery(url)
            else if (url.queryParameter("room") == "r1") seats("idle")
            else """{"limit":true,"userId":"stop-here"}"""
        }.load(account)
        assertEquals(3, state.totalRegions)
        assertEquals(1, state.completedRegions)
        assertTrue(state.discoveryComplete)
        assertEquals(1, state.regions.first().counts!!.free)
        assertTrue(state.regions.drop(1).all { it.counts == null && it.error != null && !it.loading })
        assertTrue(state.verificationRequired)
        assertEquals("stop-here", state.verificationUserId)
        assertEquals(listOf("r1", "r2"), calls.filter { it.encodedPath == "/mapBook/getSeatsByRoom" }.map { it.queryParameter("room") })
    }

    @Test fun networkAndExpiredSessionStopWithoutRepeatingOrRequestingOtherRegions() = runTest {
        for (authFailure in listOf(false, true)) {
            val calls = mutableListOf<HttpUrl>()
            val state = repository(calls) { url ->
                if (url.encodedPath != "/mapBook/getSeatsByRoom") commonDiscovery(url)
                else if (url.queryParameter("room") == "r1") seats("idle")
                else if (authFailure) "<form action='/auth/signIn'></form>"
                else throw IOException("Synthetic timeout")
            }.load(account)
            assertEquals(3, state.regions.size)
            assertEquals(1, state.regions.first().counts!!.free)
            assertTrue(state.regions.drop(1).all { it.error != null && it.counts == null })
            assertEquals(2, calls.count { it.encodedPath == "/mapBook/getSeatsByRoom" })
            assertFalse(state.verificationRequired)
            assertNotNull(state.error)
            if (authFailure) assertTrue(state.error!!.contains("登录"))
        }
    }

    @Test fun cancellationIsPropagatedWithoutIssuingAdditionalRequests() = runTest {
        val calls = mutableListOf<HttpUrl>()
        try {
            repository(calls) { throw CancellationException("Cancelled account") }.load(account)
            fail("Cancellation must reach the account-scoped coordinator")
        } catch (_: CancellationException) {
            assertEquals(1, calls.size)
        }
    }

    private fun repository(calls: MutableList<HttpUrl>, response: suspend (HttpUrl) -> String) =
        RegionOverviewRepository(NativeMapRepository { receivedAccount, path ->
            assertEquals(account, receivedAccount)
            val url = ("http://10.1.20.7" + path).toHttpUrl()
            calls += url
            response(url)
        }, clock)

    private fun options(buildings: String = "<a value='1'>大兴馆</a>", dates: String = today) =
        "<p id='options_onDate'><a value='$dates'>$dates</a></p><p id='options_building'>$buildings</p>"

    private fun commonDiscovery(url: HttpUrl): String = when (url.encodedPath) {
        "/map" -> options()
        "/mapBook/ajaxGetFloor" -> "<a value='11'>一层</a>"
        "/mapBook/ajaxGetRooms" -> """{"rooms":[{"id":"r1","name":"东区"},{"id":"r2","name":"西区"},{"id":"r3","name":"南区"}]}"""
        else -> error("Unexpected endpoint $url")
    }

    private fun seats(status: String) = "<ul><li id='seat_1'><a class='$status'>001</a></li></ul>"
}
