package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.RegionSeatCounts
import cn.edu.bucea.library.data.models.RegionSeatOverview
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NativeMapRegionNavigationTest {
    private val account = SavedAccount("fixture-region", "209999990001", "synthetic", "测试")
    private val region = RegionSeatOverview("target-room", "过期区域名称", "target-building", "目标馆", "target-floor", "目标楼层",
        "2030-01-15", counts = RegionSeatCounts(total = 88, free = 88), stale = true)

    @Test fun revalidatesTheExactRegionAndLoadsFreshGeometryInsteadOfTheFirstRoomOrCachedCounts() = runTest {
        val calls = mutableListOf<HttpUrl>()
        val state = repository(calls).loadRegion(account, region)
        assertTrue("A homepage target opens the layout directly", state.directRegionView)
        assertEquals(listOf("/map", "/mapBook/ajaxGetFloor", "/mapBook/ajaxGetRooms", "/mapBook/getSeatsByRoom"), calls.map { it.encodedPath })
        assertEquals("target-building", calls[1].queryParameter("id"))
        assertEquals("target-building", calls[2].queryParameter("building"))
        assertEquals("target-floor", calls[2].queryParameter("floor"))
        assertEquals(region.date, calls[2].queryParameter("onDate"))
        assertEquals(region.id, calls[3].queryParameter("room"))
        assertEquals(region.date, calls[3].queryParameter("date"))
        assertEquals(mapOf("onDate" to region.date, "building" to region.buildingId, "floor" to region.floorId), state.filters)
        assertEquals(region.id, state.layout!!.roomId)
        assertEquals("当前区域名称", state.layout.roomName)
        assertEquals("001A", state.layout.cells.single().seat!!.label)
        assertFalse(state.verificationRequired)
    }

    @Test fun absentDatesBuildingsFloorsAndRoomsStopAtTheirValidationStageWithoutFallingBack() = runTest {
        val invalidTargets = listOf(
            region.copy(date = "2030-01-14") to 1,
            region.copy(buildingId = "deleted-building") to 1,
            region.copy(floorId = "deleted-floor") to 2,
            region.copy(id = "deleted-room") to 3,
        )
        invalidTargets.forEach { (target, expectedReads) ->
            val calls = mutableListOf<HttpUrl>()
            try {
                repository(calls).loadRegion(account, target)
                fail("An absent target must not open a different region")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("刷新首页"))
            }
            assertEquals("Invalid target must stop before any fallback layout is loaded", expectedReads, calls.size)
            assertTrue(calls.none { it.encodedPath == "/mapBook/getSeatsByRoom" })
        }
    }

    @Test fun aRoomChallengeStopsBeforeLayoutAndAFreshRetryUsesTheSameTarget() = runTest {
        val calls = mutableListOf<HttpUrl>()
        var challenge = true
        val repository = repository(calls) { url ->
            if (url.encodedPath == "/mapBook/ajaxGetRooms" && challenge) """{"limit":true,"userId":"fixture-user"}""" else null
        }
        val blocked = repository.loadRegion(account, region)
        assertTrue(blocked.verificationRequired)
        assertEquals("fixture-user", blocked.verificationUserId)
        assertNull(blocked.layout)
        assertEquals(3, calls.size)
        challenge = false
        val restored = repository.loadRegion(account, region)
        assertEquals(region.id, restored.layout!!.roomId)
        assertEquals("/map", calls[3].encodedPath)
        assertEquals(region.id, calls.last().queryParameter("room"))
        assertFalse(restored.verificationRequired)
    }

    @Test fun aLayoutChallengeKeepsItsRealTargetAndNeverAppearsAsAnEmptyAvailableRoom() = runTest {
        val calls = mutableListOf<HttpUrl>()
        val blocked = repository(calls) { url ->
            if (url.encodedPath == "/mapBook/getSeatsByRoom") """{"limit":true,"userId":"fixture-user"}""" else null
        }.loadRegion(account, region)
        assertTrue(blocked.verificationRequired)
        assertTrue(blocked.layout!!.verificationRequired)
        assertEquals(region.id, blocked.layout.roomId)
        assertTrue(blocked.layout.cells.isEmpty())
        assertEquals("fixture-user", blocked.verificationUserId)
    }

    private fun repository(calls: MutableList<HttpUrl>, responseOverride: (HttpUrl) -> String? = { null }) = NativeMapRepository { _, endpoint ->
        val url = ("http://10.1.20.7" + endpoint).toHttpUrl()
        calls += url
        responseOverride(url) ?: when (url.encodedPath) {
            "/map" -> """
                <p id='options_onDate'><a value='2030-01-16'>明天</a><a value='${region.date}'>今天</a></p>
                <p id='options_building'><a value='first-building'>默认馆</a><a value='${region.buildingId}'>目标馆</a></p>
            """.trimIndent()
            "/mapBook/ajaxGetFloor" -> "<a value='first-floor'>默认楼层</a><a value='${region.floorId}'>目标楼层</a>"
            "/mapBook/ajaxGetRooms" -> """{"rooms":[{"id":"first-room","name":"默认区域"},{"id":"${region.id}","name":"当前区域名称"}]}"""
            "/mapBook/getSeatsByRoom" -> "<ul><li id='seat_1'><a class='idle'>001A</a></li></ul>"
            else -> error("Unexpected request $url")
        }
    }
}
