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
import cn.edu.bucea.library.ui.HubFavorite
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

    @Test fun allServerOffsetsAggregateAutomaticallyAndDuplicateIdsKeepTheirLatestStatus() = runBlocking {
        val laterResults = CompletableDeferred<SeatPage>()
        val reads = mutableListOf<Int>()
        withSearch(requests(search = { _, offset ->
            reads += offset
            when (offset) {
                0 -> SeatPage(listOf(
                    LibrarySeat("a", "A101", "区域", "空闲", true),
                    LibrarySeat("b", "B202", "区域", "空闲", true),
                ), 99, 17)
                17 -> laterResults.await()
                53 -> SeatPage(listOf(LibrarySeat("d", "D404", "区域", "不可用", false)), 99, null)
                else -> error("Unexpected offset $offset")
            }
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.searchSeats()
            val partial = viewModel.awaitState { it.loading && it.seats.size == 2 }
            assertEquals(listOf(0, 17), reads)
            assertFalse(partial.resultsComplete)
            assertNull("A server page count must not be shown as a complete total", partial.total)
            laterResults.complete(SeatPage(listOf(
                LibrarySeat("b", "B202", "区域", "正在使用", false),
                LibrarySeat("c", "C303", "区域", "空闲", true),
            ), 99, 53))
            val complete = viewModel.awaitState { !it.loading && it.resultsComplete }
            assertEquals(listOf(0, 17, 53), reads)
            assertEquals(listOf("a", "b", "c", "d"), complete.seats.map { it.id })
            assertFalse(complete.seats.single { it.id == "b" }.available)
            assertEquals("正在使用", complete.seats.single { it.id == "b" }.status)
            assertEquals(4, complete.total)
            assertFalse(complete.hasNextPage)
            assertNull(complete.error)
        }
    }

    @Test fun laterReadFailureRetainsResultsAndRetriesTheExactQueryAndOffset() = runBlocking {
        val reads = mutableListOf<Pair<SeatQuery, Int>>()
        var failed = false
        withSearch(requests(search = { query, offset ->
            reads += query to offset
            if (offset == 17 && !failed) { failed = true; throw IOException("合成后续读取失败") }
            SeatPage(listOf(LibrarySeat("seat-$offset", "座位 $offset", "区域", "空闲", true)), 1,
                when (offset) { 0 -> 17; 17 -> 36; else -> null })
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.setFilter("name", "001D")
            viewModel.searchSeats()
            val partial = viewModel.awaitState { !it.loading && it.failedPage == 1 }
            val query = reads.first().first
            assertEquals(0, partial.page)
            assertEquals("seat-0", partial.seats.single().id)
            assertFalse(partial.resultsComplete)
            assertNull(partial.total)
            viewModel.retrySearch()
            val complete = viewModel.awaitState { !it.loading && it.resultsComplete }
            assertEquals(listOf(query to 0, query to 17, query to 17, query to 36), reads)
            assertEquals(listOf("seat-0", "seat-17", "seat-36"), complete.seats.map { it.id })
            assertNull(complete.error)
            assertNull(complete.failedPage)
        }
    }

    @Test fun verificationRetainsPartialResultsAndResumesTheBlockedOffset() = runBlocking {
        val reads = mutableListOf<Int>()
        var verified = false
        withSearch(requests(search = { _, offset ->
            reads += offset
            when {
                offset == 0 -> SeatPage(listOf(LibrarySeat("first", "A101", "区域", "空闲", true)), 1, 17)
                !verified -> SeatPage(emptyList(), 0, null, verificationRequired = true,
                    message = "请验证后继续", verificationUserId = "synthetic-user")
                else -> SeatPage(listOf(LibrarySeat("last", "B202", "区域", "空闲", true)), 1, null)
            }
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.searchSeats()
            val blocked = viewModel.awaitState { !it.loading && it.verificationRequired }
            assertEquals(listOf(0, 17), reads)
            assertEquals(listOf("first"), blocked.seats.map { it.id })
            assertFalse(blocked.resultsComplete)
            assertNull(blocked.total)
            assertTrue(blocked.hasNextPage)
            assertEquals("synthetic-user", blocked.verificationUserId)
            verified = true
            viewModel.retrySearch()
            val complete = viewModel.awaitState { !it.loading && it.resultsComplete }
            assertEquals(listOf(0, 17, 17), reads)
            assertEquals(listOf("first", "last"), complete.seats.map { it.id })
            assertFalse(complete.verificationRequired)
            assertNull(complete.failedPage)
        }
    }

    @Test fun repeatedServerOffsetStopsWithoutClaimingAllResultsHaveLoaded() = runBlocking {
        val reads = mutableListOf<Int>()
        withSearch(requests(search = { _, offset ->
            reads += offset
            SeatPage(listOf(LibrarySeat("seat-$offset", "座位 $offset", "区域", "空闲", true)), 1,
                if (offset == 0) 17 else 0)
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.searchSeats()
            val partial = viewModel.awaitState { !it.loading && it.error != null }
            assertEquals(listOf(0, 17), reads)
            assertEquals(listOf("seat-0", "seat-17"), partial.seats.map { it.id })
            assertFalse(partial.resultsComplete)
            assertNull(partial.total)
            assertTrue(partial.error.orEmpty().contains("重复"))
            assertNull("An invalid cursor must require a fresh search", partial.failedPage)
        }
    }

    @Test fun filterChangeDiscardsALateContinuationWithoutReplacingTheNewQuery() = runBlocking {
        val response = CompletableDeferred<SeatPage>()
        val returned = CompletableDeferred<Unit>()
        val reads = mutableListOf<Pair<String, Int>>()
        withSearch(requests(search = { query, offset ->
            reads += query.name to offset
            when {
                query.name == "new" -> SeatPage(listOf(LibrarySeat("new", "N001", "新区", "空闲", true)), 1, null)
                offset == 0 -> SeatPage(listOf(LibrarySeat("old-first", "O001", "旧区", "空闲", true)), 1, 17)
                else -> withContext(NonCancellable) { response.await() }.also { returned.complete(Unit) }
            }
        })) { viewModel ->
            viewModel.loadSearch()
            viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.searchSeats()
            viewModel.awaitState { it.loading && it.seats.singleOrNull()?.id == "old-first" }
            viewModel.setFilter("name", "new")
            viewModel.searchSeats()
            viewModel.awaitState { !it.loading && it.resultsComplete && it.seats.singleOrNull()?.id == "new" }
            response.complete(SeatPage(listOf(LibrarySeat("old-last", "O002", "旧区", "空闲", true)), 1, 36))
            returned.await()
            yield()
            assertEquals(listOf("" to 0, "" to 17, "new" to 0), reads)
            assertEquals(listOf("new"), viewModel.search.value.seats.map { it.id })
            assertTrue(viewModel.search.value.resultsComplete)
            assertFalse(viewModel.search.value.loading)
            assertNull(viewModel.search.value.error)
        }
    }

    @Test fun openingFavoriteBookingCancelsALateSearchContinuationAndKeepsCachedDates() = runBlocking {
        val response = CompletableDeferred<SeatPage>()
        val returned = CompletableDeferred<Unit>()
        val reads = mutableListOf<Int>()
        var optionsReads = 0
        withSearch(requests(options = { optionsReads++; fixtureOptions() }, search = { _, offset ->
            reads += offset
            if (offset == 0) SeatPage(listOf(LibrarySeat("first", "A101", "区域", "空闲", true)), 1, 17)
            else withContext(NonCancellable) { response.await() }.also { returned.complete(Unit) }
        })) { viewModel ->
            viewModel.loadSearch()
            val ready = viewModel.awaitState { !it.loading && it.options.isNotEmpty() }
            viewModel.searchSeats()
            viewModel.awaitState { it.loading && it.seats.singleOrNull()?.id == "first" }
            viewModel.prepareFavoriteBooking(HubFavorite("favorite", "F606", "收藏区域", local = true))
            assertFalse("A cached favorite date picker must not wait for an unrelated search", viewModel.search.value.loading)
            assertEquals(ready.options["onDate"], viewModel.search.value.options["onDate"])
            assertNull(viewModel.search.value.selectedSeat)
            response.complete(SeatPage(listOf(LibrarySeat("old-last", "O002", "旧区", "空闲", true)), 1, null,
                verificationRequired = true, message = "旧查询要求验证", verificationUserId = "old-user"))
            returned.await()
            yield()
            val booking = viewModel.search.value
            assertEquals(listOf(0, 17), reads)
            assertEquals("Fresh cached dates must be reused without another options read", 1, optionsReads)
            assertEquals(listOf("first"), booking.seats.map { it.id })
            assertEquals(ready.options["onDate"], booking.options["onDate"])
            assertFalse(booking.loading)
            assertFalse(booking.verificationRequired)
            assertTrue(booking.verificationUserId.isEmpty())
            assertNull(booking.failedPage)
            assertNull(booking.error)
            assertNull(booking.status)
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
