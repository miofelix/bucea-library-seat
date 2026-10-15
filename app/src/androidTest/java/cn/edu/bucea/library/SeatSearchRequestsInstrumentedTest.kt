package cn.edu.bucea.library

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.models.LibraryOption
import cn.edu.bucea.library.data.models.LibrarySeat
import cn.edu.bucea.library.data.models.SeatPage
import cn.edu.bucea.library.data.models.SeatQuery
import cn.edu.bucea.library.data.models.SeatSearchOptions
import cn.edu.bucea.library.ui.SeatSearchState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/** Controlled reads exercise races and recovery without network requests or account writes. */
@RunWith(AndroidJUnit4::class)
class SeatSearchRequestsInstrumentedTest {
    private fun fixtureOptions() = SeatSearchOptions(
        dates = listOf(LibraryOption(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "今天")),
        buildings = listOf(LibraryOption("null", "不限场馆"), LibraryOption("A", "场馆 A"), LibraryOption("B", "场馆 B")),
        rooms = listOf(LibraryOption("null", "不限区域"), LibraryOption("old", "初始区域")),
    )

    private fun requests(
        options: suspend () -> SeatSearchOptions = { fixtureOptions() },
        rooms: suspend (String) -> List<LibraryOption> = { listOf(LibraryOption("$it-room", "$it 区域")) },
        search: suspend (SeatQuery, Int) -> SeatPage = { _, _ -> error("Unexpected seat search") },
    ) = SeatSearchRequests(options, rooms, search) { it.message ?: "读取失败" }

    private suspend fun withSearch(source: SeatSearchRequests, block: suspend (LibraryFeatureViewModel) -> Unit) =
        withContext(Dispatchers.Main) {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val store = ViewModelStore()
            val viewModel = LibraryFeatureViewModel(application, source)
            store.put("synthetic-search", viewModel)
            try { block(viewModel) } finally { store.clear() }
        }

    private suspend fun LibraryFeatureViewModel.awaitState(predicate: (SeatSearchState) -> Boolean): SeatSearchState =
        withTimeout(5_000) { search.first(predicate) }

    @Test fun buildingChangeClearsOldRoomsAndRejectsPrematureOrInvalidQueries() = runBlocking {
        val roomResponse = CompletableDeferred<List<LibraryOption>>()
        val queries = mutableListOf<SeatQuery>()
        withSearch(requests(rooms = { roomResponse.await() }, search = { query, _ ->
            queries += query
            SeatPage(emptyList(), 0, null)
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.setFilter("room", "old")
            viewModel.setFilter("building", "B")
            assertTrue(viewModel.search.value.roomsLoading)
            assertTrue(viewModel.search.value.options["room"].orEmpty().isEmpty())
            assertEquals("null", viewModel.search.value.filters["room"])
            viewModel.setFilter("room", "old")
            viewModel.searchSeats()
            assertTrue("A query must wait for the new building's regions", queries.isEmpty())
            roomResponse.complete(listOf(LibraryOption("B-room", "B 区域")))
            viewModel.awaitState { !it.roomsLoading }
            viewModel.setFilter("room", "old")
            assertEquals("null", viewModel.search.value.filters["room"])
            viewModel.setFilter("room", "B-room")
            viewModel.searchSeats()
            viewModel.awaitState { it.hasSearched && !it.loading }
            assertEquals("B", queries.single().building)
            assertEquals("B-room", queries.single().room)
        }
    }

    @Test fun cancelledRoomReadCannotOverwriteTheNextBuildingEvenIfItReturnsLate() = runBlocking {
        val oldResponse = CompletableDeferred<List<LibraryOption>>()
        val oldReturned = CompletableDeferred<Unit>()
        withSearch(requests(rooms = { building ->
            if (building == "A") {
                withContext(NonCancellable) { oldResponse.await() }.also { oldReturned.complete(Unit) }
            } else listOf(LibraryOption("B-room", "B 区域"))
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.setFilter("building", "A")
            viewModel.setFilter("building", "B")
            viewModel.awaitState { !it.roomsLoading && it.options["room"].orEmpty().any { room -> room.value == "B-room" } }
            oldResponse.complete(listOf(LibraryOption("A-room", "A 区域")))
            oldReturned.await()
            yield()
            assertEquals("B", viewModel.search.value.filters["building"])
            assertEquals(listOf("null", "B-room"), viewModel.search.value.options["room"].orEmpty().map { it.value })
            assertFalse(viewModel.search.value.roomsLoading)
            assertNull(viewModel.search.value.roomsError)
        }
    }

    @Test fun roomFailureRetriesOnlyThatBuildingAndRefreshReconcilesTheRetainedSelection() = runBlocking {
        var optionsReads = 0
        val roomReads = mutableListOf<String>()
        var returnedRoom = "B-room"
        withSearch(requests(options = { optionsReads++; fixtureOptions() }, rooms = { building ->
            roomReads += building
            if (roomReads.size == 1) throw IOException("合成区域读取失败")
            listOf(LibraryOption(returnedRoom, "B 区域"))
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.setFilter("building", "B")
            viewModel.awaitState { !it.roomsLoading && it.roomsError != null }
            assertNull(viewModel.search.value.error)
            viewModel.setFilter("room", "old")
            viewModel.searchSeats()
            viewModel.retryRooms()
            viewModel.awaitState { !it.roomsLoading && it.roomsError == null }
            assertEquals(1, optionsReads)
            assertEquals(listOf("B", "B"), roomReads)
            viewModel.setFilter("room", "B-room")
            viewModel.refreshSearch()
            viewModel.awaitState { !it.loading && !it.roomsLoading }
            assertEquals("B", viewModel.search.value.filters["building"])
            assertEquals("B-room", viewModel.search.value.filters["room"])
            assertEquals(listOf("null", "B-room"), viewModel.search.value.options["room"].orEmpty().map { it.value })
            returnedRoom = "B-new"
            viewModel.refreshSearch()
            viewModel.awaitState { !it.loading && !it.roomsLoading }
            assertEquals("null", viewModel.search.value.filters["room"])
            viewModel.setFilter("room", "B-room")
            assertEquals("null", viewModel.search.value.filters["room"])
            assertEquals(listOf("B", "B", "B", "B"), roomReads)
        }
    }

    @Test fun nextPageFailureRetainsResultsAndRetriesTheExactQueryAndOffset() = runBlocking {
        val reads = mutableListOf<Pair<SeatQuery, Int>>()
        var failed = false
        withSearch(requests(search = { query, offset ->
            reads += query to offset
            if (offset == 17 && !failed) { failed = true; throw IOException("合成分页读取失败") }
            SeatPage(listOf(LibrarySeat("seat-$offset", "座位 $offset", "区域", "空闲", true)), 1, if (offset == 0) 17 else null)
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.setFilter("name", "001D")
            viewModel.searchSeats()
            viewModel.awaitState { it.hasSearched && !it.loading }
            val query = reads.single().first
            viewModel.nextPage()
            viewModel.awaitState { !it.loading && it.failedPage == 1 }
            assertEquals(0, viewModel.search.value.page)
            assertEquals("seat-0", viewModel.search.value.seats.single().id)
            viewModel.retrySearch()
            viewModel.awaitState { !it.loading && it.page == 1 }
            assertEquals(listOf(query to 0, query to 17, query to 17), reads)
            assertNull(viewModel.search.value.error)
            assertNull(viewModel.search.value.failedPage)
            viewModel.previousPage()
            assertEquals("seat-0", viewModel.search.value.seats.single().id)
            assertEquals(3, reads.size)
        }
    }

    @Test fun accountRebindDiscardsLateSearchResultsAndFailedRetryContext() = runBlocking {
        val response = CompletableDeferred<SeatPage>()
        val returned = CompletableDeferred<Unit>()
        var reads = 0
        withSearch(requests(search = { _, _ ->
            reads++
            if (reads == 1) throw IOException("合成查询读取失败")
            withContext(NonCancellable) { response.await() }.also { returned.complete(Unit) }
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.searchSeats()
            viewModel.awaitState { !it.loading && it.failedPage == 0 }
            viewModel.retrySearch()
            viewModel.bindAccount("synthetic-new-account", false)
            response.complete(SeatPage(listOf(LibrarySeat("old", "旧座位", "旧区域", "空闲", true)), 1, 20))
            returned.await()
            yield()
            assertTrue(viewModel.search.value.seats.isEmpty())
            assertFalse(viewModel.search.value.loading)
            assertFalse(viewModel.search.value.hasSearched)
            assertNull(viewModel.search.value.failedPage)
            assertEquals(2, reads)
        }
    }
}
