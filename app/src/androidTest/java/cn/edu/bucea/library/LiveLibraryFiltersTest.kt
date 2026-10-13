package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryFunctionsRepository
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.NativeVerificationRepository
import cn.edu.bucea.library.data.models.SeatPage
import cn.edu.bucea.library.data.models.SeatQuery
import cn.edu.bucea.library.ocr.DdddClickSolver
import cn.edu.bucea.library.ocr.DdddOcr
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real filter transport/response checks, not proof of unreported power/window attributes.
 * Unsupported filter options and unavailable pagination are reported explicitly.
 * Never books, cancels, signs in to a seat or edits an account.
 */
@RunWith(AndroidJUnit4::class)
class LiveLibraryFiltersTest {
    @Test
    fun nativeFiltersPaginationAndMissingNumberFollowActualServerOptions() = runBlocking {
        assumeTrue("Live filter checks are opt-in", InstrumentationRegistry.getArguments().getString("live_library") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context)
        val account = store.accounts().firstOrNull { it.id == store.activeAccountId }
        assumeTrue("A stored active account is required", account != null)
        val current = requireNotNull(account)
        assumeTrue("Preserve explicit sign-out", !store.isSignedOut(current.id))
        DdddOcr(context).use { ocr ->
            val library = LibraryRepository(store, ocr)
            try {
                assertTrue("Run filter checks after logging in through the app", library.restore(current) != null)
                val functions = LibraryFunctionsRepository(library)
                val options = functions.options(current)
                val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                val date = options.dates.firstOrNull { runCatching { LocalDate.parse(it.value).isAfter(today) }.getOrDefault(false) }
                assumeTrue("School has not opened a future date", date != null)
                val building = options.buildings.firstOrNull { it.value != "null" }
                assumeTrue("No building currently available", building != null)
                val rooms = functions.rooms(current, requireNotNull(building).value)
                val room = rooms.firstOrNull { it.value != "null" }
                assumeTrue("No reading area currently available", room != null)
                val selectedRoom = requireNotNull(room)
                val baselineQuery = SeatQuery(requireNotNull(date).value, building = building.value, room = selectedRoom.value)
                val verification = NativeVerificationRepository(library)
                DdddClickSolver(context).use { solver ->
                    var challengeRecoveryUsed = false
                    suspend fun query(phase: String, selection: SeatQuery, offset: Int = 0): SeatPage {
                        delay(750)
                        var page = functions.search(current, selection, offset)
                        if (page.verificationRequired) {
                            assertFalse("Repeated rate limit stops the bounded filter run", challengeRecoveryUsed)
                            challengeRecoveryUsed = true
                            val verificationContext = verification.context(current, page.verificationUserId)
                            verification.automaticallyVerify(current, verificationContext, solver::solve)
                            delay(750)
                            page = functions.search(current, selection, offset)
                        }
                        assertFalse("Server must accept the verified filter query", page.verificationRequired)
                        assertEquals("One page must contain unique seats", page.seats.size, page.seats.map { it.id }.distinct().size)
                        assertTrue("Search results must report server availability", page.seats.all { it.available })
                        assertTrue("A room-specific query must return only that reading area", page.seats.all { it.room == selectedRoom.label })
                        if (page.seats.isEmpty()) assertTrue("Empty queries must explain their result", page.message.isNotBlank())
                        println("LIVE_FILTER phase=$phase results=${page.seats.size} hasNext=${page.nextOffset != null}")
                        return page
                    }

                    val baseline = query("baseline-room", baselineQuery)
                    var paginationCovered = false
                    baseline.nextOffset?.let { offset ->
                        val next = query("next-page", baselineQuery, offset)
                        assertTrue("Consecutive server pages must not repeat seat IDs", baseline.seats.map { it.id }.toSet()
                            .intersect(next.seats.map { it.id }.toSet()).isEmpty())
                        paginationCovered = true
                    }
                    if (!paginationCovered) println("LIVE_FILTER phase=next-page unavailable-no-next-offset")
                    val matrix = mutableListOf<Pair<String, SeatQuery>>()
                    options.durations.firstOrNull { it.value != "null" }?.let { matrix += "duration" to baselineQuery.copy(hour = it.value) }
                    options.powers.firstOrNull { it.value == "1" }?.let { matrix += "power" to baselineQuery.copy(power = it.value) }
                    options.windows.firstOrNull { it.value == "1" }?.let { matrix += "window" to baselineQuery.copy(window = it.value) }
                    val start = options.startTimes.firstOrNull { it.value == "450" }
                        ?: options.startTimes.firstOrNull { it.value.toIntOrNull() != null }
                    val end = start?.value?.toIntOrNull()?.let { minimum -> options.endTimes.firstOrNull {
                        it.value.toIntOrNull()?.let { minutes -> minutes > minimum } == true
                    } }
                    if (start != null) matrix += "start-time" to baselineQuery.copy(startMin = start.value)
                    if (end != null) matrix += "end-time" to baselineQuery.copy(endMin = end.value)
                    if (start != null && end != null) matrix += "time-window" to baselineQuery.copy(startMin = start.value, endMin = end.value)
                    if (options.powers.any { it.value == "1" } && options.windows.any { it.value == "1" })
                        matrix += "power-and-window" to baselineQuery.copy(power = "1", window = "1")
                    for ((phase, selection) in matrix.take(7)) query(phase, selection)
                    for (missing in listOf("duration", "power", "window", "start-time", "end-time", "time-window", "power-and-window")
                        .filterNot { expected -> matrix.any { it.first == expected } }) {
                        println("LIVE_FILTER phase=$missing unsupported-server-option")
                    }
                    val absent = query("missing-seat-number", baselineQuery.copy(name = "999999999999Z"))
                    assertTrue("An impossible number must not leak unrelated seats", absent.seats.isEmpty())
                    assertNull("An empty number lookup must have no next page", absent.nextOffset)
                    println("LIVE_FILTER completed supportedFilterCases=${matrix.take(7).size} paginationCovered=$paginationCovered missingNumberChecked=true")
                }
            } finally { library.releaseSession(current.id) }
        }
    }
}
