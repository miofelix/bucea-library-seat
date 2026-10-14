package cn.edu.bucea.library

import cn.edu.bucea.library.data.models.RegionOverviewState
import cn.edu.bucea.library.data.models.RegionSeatCounts
import cn.edu.bucea.library.data.models.RegionSeatOverview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private class OverviewClock(var now: Instant = Instant.parse("2026-10-14T03:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    fun today(): String = LocalDate.now(withZone(ZoneId.of("Asia/Shanghai"))).toString()
}

private fun region(clock: OverviewClock, id: String = "north", free: Int = 7, buildingId: String = "building") = RegionSeatOverview(
    id, "区域 $id", buildingId, "图书馆 $buildingId", "floor", "二层", clock.today(),
    counts = RegionSeatCounts(total = 10, free = free, inUse = 10 - free),
    loading = false, updatedAt = clock.millis(),
)

private fun snapshot(clock: OverviewClock, vararg regions: RegionSeatOverview) = RegionOverviewState(
    date = clock.today(), regions = regions.toList(), completedRegions = regions.size,
    totalRegions = regions.size, discoveryComplete = true, updatedAt = clock.millis(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class RegionOverviewCoordinatorTest {
    @Test fun initialEntryCoalescesAndSuccessUsesTwoMinuteTtlWhileManualRefreshBypassesIt() = runTest {
        val clock = OverviewClock()
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val overview = RegionOverviewCoordinator(backgroundScope, { _, progress ->
            calls++
            progress(RegionOverviewState(date = clock.today(), loading = true))
            pending.await()
            snapshot(clock, region(clock))
        }, clock)
        overview.bindAccount("account", true)
        overview.ensureFresh()
        overview.ensureFresh()
        overview.refresh()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(overview.state.value.loading)
        pending.complete(Unit)
        runCurrent()
        assertFalse(overview.state.value.loading)
        assertFalse(overview.state.value.stale)
        assertEquals(clock.millis(), overview.state.value.updatedAt)
        overview.ensureFresh()
        clock.advance(119)
        overview.ensureFresh()
        runCurrent()
        assertEquals(1, calls)
        clock.advance(1)
        overview.ensureFresh()
        runCurrent()
        assertEquals(2, calls)
        overview.refresh()
        runCurrent()
        assertEquals(3, calls)
    }

    @Test fun shanghaiMidnightRefreshesEvenWithinTtlAndWithUtcDeviceClock() = runTest {
        val clock = OverviewClock(Instant.parse("2026-10-14T15:59:30Z"))
        var calls = 0
        val overview = RegionOverviewCoordinator(backgroundScope, { _, _ ->
            calls++
            snapshot(clock, region(clock))
        }, clock)
        overview.bindAccount("account", true)
        overview.ensureFresh()
        runCurrent()
        assertEquals("2026-10-14", overview.state.value.date)
        clock.advance(45)
        overview.ensureFresh()
        runCurrent()
        assertEquals(2, calls)
        assertEquals("2026-10-15", overview.state.value.date)
    }

    @Test fun refreshPreservesCachedRowsThenReplacesFreshRowsWhileKeepingFailedRowsExplicitlyStale() = runTest {
        val clock = OverviewClock()
        val finishRefresh = CompletableDeferred<Unit>()
        var calls = 0
        val overview = RegionOverviewCoordinator(backgroundScope, { _, progress ->
            calls++
            if (calls == 1) snapshot(clock, region(clock), region(clock, "south", 2)) else {
                val pending = listOf(region(clock).copy(counts = null, loading = true, updatedAt = null),
                    region(clock, "south").copy(counts = null, loading = true, updatedAt = null))
                progress(RegionOverviewState(date = clock.today(), regions = pending, loading = true,
                    totalRegions = 2, discoveryComplete = true))
                val freshNorth = region(clock, free = 4)
                progress(RegionOverviewState(date = clock.today(), regions = listOf(freshNorth, pending[1]),
                    loading = true, totalRegions = 2, completedRegions = 1, discoveryComplete = true))
                finishRefresh.await()
                snapshot(clock, freshNorth, pending[1].copy(loading = false, error = "该区域布局未能读取"))
            }
        }, clock)
        overview.bindAccount("account", true)
        overview.ensureFresh()
        runCurrent()
        val lastFullSuccess = overview.state.value.updatedAt
        clock.advance(10)
        overview.refresh()
        assertEquals(7, overview.state.value.regions[0].counts?.free)
        assertTrue(overview.state.value.regions.all { it.stale && it.loading })
        runCurrent()
        assertTrue(overview.state.value.loading)
        assertTrue(overview.state.value.stale)
        assertEquals(1, overview.state.value.completedRegions)
        assertEquals(4, overview.state.value.regions[0].counts?.free)
        assertFalse(overview.state.value.regions[0].stale)
        assertEquals(2, overview.state.value.regions[1].counts?.free)
        assertTrue(overview.state.value.regions[1].stale)
        finishRefresh.complete(Unit)
        runCurrent()
        assertFalse(overview.state.value.loading)
        assertTrue(overview.state.value.stale)
        assertEquals(lastFullSuccess, overview.state.value.updatedAt)
        assertEquals("该区域布局未能读取", overview.state.value.regions[1].error)
        assertNotNull(overview.state.value.error)
        overview.ensureFresh()
        runCurrent()
        assertEquals("Automatic partial retry waits until the two-minute attempt interval", 2, calls)
    }

    @Test fun failedReadsRetainDataAndRetrySpacingButManualAndDayChangesBypassTheSpacing() = runTest {
        val clock = OverviewClock()
        var calls = 0
        var fail = false
        val overview = RegionOverviewCoordinator(backgroundScope, { _, _ ->
            calls++
            if (fail) throw IOException("校园连接中断")
            snapshot(clock, region(clock))
        }, clock)
        overview.bindAccount("account", true)
        overview.ensureFresh()
        runCurrent()
        val lastSuccess = overview.state.value.updatedAt
        fail = true
        clock.advance(120)
        overview.ensureFresh()
        runCurrent()
        assertEquals(2, calls)
        assertEquals("校园连接中断", overview.state.value.error)
        assertTrue(overview.state.value.stale)
        assertEquals(7, overview.state.value.regions.single().counts?.free)
        assertEquals(lastSuccess, overview.state.value.updatedAt)
        repeat(3) { overview.ensureFresh(); runCurrent() }
        assertEquals(2, calls)
        clock.advance(119)
        overview.ensureFresh()
        runCurrent()
        assertEquals(2, calls)
        clock.advance(1)
        overview.ensureFresh()
        runCurrent()
        assertEquals(3, calls)
        overview.refresh()
        runCurrent()
        assertEquals(4, calls)
        clock.now = Instant.parse("2026-10-14T16:00:01Z")
        overview.ensureFresh()
        runCurrent()
        assertEquals(5, calls)
    }

    @Test fun duplicateRoomIdsAcrossBuildingsKeepIndependentCachedCountsAndCards() = runTest {
        val clock = OverviewClock()
        val finishRefresh = CompletableDeferred<Unit>()
        var calls = 0
        val firstBuilding = region(clock, free = 7, buildingId = "first-building")
        val secondBuilding = region(clock, free = 2, buildingId = "second-building")
        val overview = RegionOverviewCoordinator(backgroundScope, { _, progress ->
            calls++
            if (calls == 1) snapshot(clock, firstBuilding, secondBuilding) else {
                progress(RegionOverviewState(date = clock.today(), loading = true, totalRegions = 1,
                    regions = listOf(firstBuilding.copy(counts = null, loading = true, updatedAt = null))))
                finishRefresh.await()
                snapshot(clock, region(clock, free = 5, buildingId = "first-building"),
                    secondBuilding.copy(counts = null, error = "第二馆区域布局未能读取", updatedAt = null))
            }
        }, clock)
        overview.bindAccount("account", true)
        overview.ensureFresh()
        runCurrent()
        clock.advance(10)
        overview.refresh()
        runCurrent()
        assertEquals("The incomplete first-building discovery must retain the second card", 2,
            overview.state.value.regions.size)
        val during = overview.state.value.regions.associateBy { it.buildingId }
        assertEquals(7, during.getValue("first-building").counts?.free)
        assertEquals(2, during.getValue("second-building").counts?.free)
        finishRefresh.complete(Unit)
        runCurrent()
        val completed = overview.state.value.regions.associateBy { it.buildingId }
        assertEquals(5, completed.getValue("first-building").counts?.free)
        assertFalse(completed.getValue("first-building").stale)
        assertEquals(2, completed.getValue("second-building").counts?.free)
        assertTrue(completed.getValue("second-building").stale)
        assertEquals("第二馆区域布局未能读取", completed.getValue("second-building").error)
    }

    @Test fun noncooperativeOldAccountCallbacksCannotRepopulateAndRequestsNeverOverlap() = runTest {
        val clock = OverviewClock()
        val releaseOld = CompletableDeferred<Unit>()
        var oldProgress: ((RegionOverviewState) -> Unit)? = null
        val calledAccounts = mutableListOf<String>()
        var active = 0
        var maximumActive = 0
        val overview = RegionOverviewCoordinator(backgroundScope, { id, progress ->
            calledAccounts += id
            active++
            maximumActive = maxOf(active, maximumActive)
            try {
                if (id == "old") withContext(NonCancellable) {
                    oldProgress = progress
                    releaseOld.await()
                    progress(snapshot(clock, region(clock, "old-region")))
                    snapshot(clock, region(clock, "old-region"))
                } else snapshot(clock, region(clock, "new-region"))
            } finally { active-- }
        }, clock)
        overview.bindAccount("old", true)
        overview.ensureFresh()
        runCurrent()
        overview.bindAccount("new", true)
        assertTrue(overview.state.value.regions.isEmpty())
        overview.ensureFresh()
        runCurrent()
        assertEquals(listOf("old"), calledAccounts)
        oldProgress!!(snapshot(clock, region(clock, "late-old-region")))
        assertTrue(overview.state.value.regions.isEmpty())
        releaseOld.complete(Unit)
        runCurrent()
        assertEquals(listOf("old", "new"), calledAccounts)
        assertEquals(1, maximumActive)
        assertEquals(listOf("new-region"), overview.state.value.regions.map { it.id })
        oldProgress!!(snapshot(clock, region(clock, "even-later-old-region")))
        assertEquals(listOf("new-region"), overview.state.value.regions.map { it.id })
    }

    @Test fun logoutClearsRowsAndRejectsLateProgressWhileReauthenticationFetchesAgain() = runTest {
        val clock = OverviewClock()
        var lateProgress: ((RegionOverviewState) -> Unit)? = null
        var calls = 0
        val overview = RegionOverviewCoordinator(backgroundScope, { _, progress ->
            calls++
            lateProgress = progress
            snapshot(clock, region(clock))
        }, clock)
        overview.bindAccount("account", true)
        overview.ensureFresh()
        runCurrent()
        overview.bindAccount("account", false)
        lateProgress!!(snapshot(clock, region(clock, "old-result")))
        overview.ensureFresh()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(overview.state.value.regions.isEmpty())
        assertNull(overview.state.value.updatedAt)
        overview.bindAccount("account", true)
        overview.ensureFresh()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("north"), overview.state.value.regions.map { it.id })
    }

    @Test fun verificationPreservesUserIdStopsAutomaticReadsAndProofForcesANewPass() = runTest {
        val clock = OverviewClock()
        var calls = 0
        val overview = RegionOverviewCoordinator(backgroundScope, { _, _ ->
            calls++
            if (calls == 1) snapshot(clock, region(clock)).copy(verificationRequired = true,
                verificationUserId = "school-user", error = "学校要求额外验证")
            else snapshot(clock, region(clock, free = 4))
        }, clock)
        overview.bindAccount("account", true)
        overview.ensureFresh()
        runCurrent()
        assertTrue(overview.state.value.verificationRequired)
        assertEquals("school-user", overview.state.value.verificationUserId)
        assertNull(overview.state.value.updatedAt)
        assertEquals(7, overview.state.value.regions.single().counts?.free)
        clock.advance(121)
        overview.ensureFresh()
        runCurrent()
        assertEquals(1, calls)
        overview.onVerified()
        runCurrent()
        assertEquals(2, calls)
        assertFalse(overview.state.value.verificationRequired)
        assertEquals("", overview.state.value.verificationUserId)
        assertFalse(overview.state.value.stale)
        assertEquals(4, overview.state.value.regions.single().counts?.free)
        assertEquals(clock.millis(), overview.state.value.updatedAt)
    }
}
