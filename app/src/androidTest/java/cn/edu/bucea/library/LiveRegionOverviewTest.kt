package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.RegionOverviewRepository
import cn.edu.bucea.library.ocr.DdddOcr
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real campus GETs only; never submits a booking or seat-use operation. */
@RunWith(AndroidJUnit4::class)
class LiveRegionOverviewTest {
    @Test fun readsEveryReturnedRegionForTodayAndKeepsStatusCountsConsistent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Live region reads require -e live_library true",
            InstrumentationRegistry.getArguments().getString("live_library") == "true")
        val accounts = AccountStore(instrumentation.targetContext)
        val account = accounts.accounts().firstOrNull { it.id == accounts.activeAccountId }
        assumeTrue("A saved active account is required", account != null)
        val selected = requireNotNull(account)
        assumeTrue("Explicit logout must be preserved", !accounts.isSignedOut(selected.id))
        DdddOcr(instrumentation.targetContext).use { ocr ->
            val library = LibraryRepository(accounts, ocr)
            try {
                assertNotNull("The saved account session must authenticate", library.restore(selected))
                val state = RegionOverviewRepository(library).load(selected)
                assertFalse("School verification must not be mistaken for successful data", state.verificationRequired)
                assertEquals("Every returned building/floor must be discovered", emptyList<String>(), state.discoveryErrors)
                assertTrue("Complete discovery is required", state.discoveryComplete)
                assertEquals("All region reads must complete without a hidden failure", null, state.error)
                assertTrue("The school must return at least one actual region", state.regions.isNotEmpty())
                assertEquals(state.regions.size, state.totalRegions)
                assertEquals(state.totalRegions, state.completedRegions)
                val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
                assertEquals(today, state.date)
                state.regions.forEach { region ->
                    assertEquals(today, region.date)
                    assertFalse(region.loading)
                    assertEquals(null, region.error)
                    val counts = requireNotNull(region.counts) { "Actual layout counts must exist" }
                    val categories = listOf(counts.free, counts.inUse, counts.reserved, counts.away,
                        counts.unavailable, counts.unknown, counts.localOnly)
                    assertTrue(categories.all { it >= 0 })
                    assertTrue(counts.total > 0)
                    assertEquals(counts.total, categories.sum())
                    assertNotNull(region.updatedAt)
                }
                println("REGION_OVERVIEW regions=${state.totalRegions} seats=${state.regions.sumOf { it.counts!!.total }} " +
                    "unknown=${state.regions.sumOf { it.counts!!.unknown }}")
            } finally { library.releaseSession(selected.id) }
        }
    }
}
