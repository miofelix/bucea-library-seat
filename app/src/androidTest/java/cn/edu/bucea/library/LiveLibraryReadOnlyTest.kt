package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryFunctionsRepository
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.NativeMapRepository
import cn.edu.bucea.library.data.models.ReservationSelection
import cn.edu.bucea.library.data.models.SeatQuery
import cn.edu.bucea.library.ocr.DdddOcr
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in campus integration check. Uses the stored account and preserves explicit sign-out.
 * Reads seat availability and prepares a CSRF-bound form; never submits a reservation/action.
 *
 * adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveLibraryReadOnlyTest \
 *   -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
 * Optional: -e live_account_id <local UUID>. No passwords, cookies or tokens are printed.
 */
@RunWith(AndroidJUnit4::class)
class LiveLibraryReadOnlyTest {
    @Test
    fun readsNativeBusinessFlowAndPreparesButNeverSubmitsReservation() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live campus checks are opt-in: pass -e live_library true", arguments.getString("live_library") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = AccountStore(instrumentation.targetContext)
        val accountId = arguments.getString("live_account_id") ?: store.activeAccountId
        val account = store.accounts().find { it.id == accountId }
        assumeTrue("Add a library account in the app before running live checks", account != null)
        val current = requireNotNull(account)
        assumeTrue("The selected account is explicitly signed out; live checks preserve that choice", !store.isSignedOut(current.id))

        DdddOcr(instrumentation.targetContext).use { ocr ->
            val repository = LibraryRepository(store, ocr)
            try {
                val restored = repository.restore(current)
                if (restored == null) {
                    assumeTrue("No valid session and automatic login is disabled", store.autoLogin)
                    val result = repository.login(current) { /* Never log account/session data. */ }
                    assumeTrue("Automatic login verification did not complete", result.overview != null)
                }
                // restore() verifies the returned identity equals this account's username.
                assertTrue("The saved session must authenticate as the selected account", repository.restore(current) != null)
                val functions = LibraryFunctionsRepository(repository)
                val options = functions.options(current)
                assertTrue("The server must return bookable date options", options.dates.isNotEmpty())
                val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                val nextDate = options.dates.firstOrNull { option ->
                    runCatching { LocalDate.parse(option.value).isAfter(today) }.getOrDefault(false)
                }
                assumeTrue("The server has not opened a future booking date", nextDate != null)
                val date = requireNotNull(nextDate).value
                val building = options.buildings.firstOrNull { it.value != "null" }
                assumeTrue("No building is currently available", building != null)
                val rooms = functions.rooms(current, requireNotNull(building).value)
                assertTrue("The building must return room choices", rooms.isNotEmpty())

                val page = functions.search(current, SeatQuery(date, building = building.value))
                assumeTrue("The school requested official search verification; finish it in the app", !page.verificationRequired)
                val seat = page.seats.firstOrNull { it.available }
                assumeTrue("No free seat is available for the server's next booking date", seat != null)
                val selectedSeat = requireNotNull(seat)
                val seatNumber = selectedSeat.label.takeWhile { it.isDigit() }
                assumeTrue("The selected seat uses a non-numeric school label", seatNumber.isNotEmpty())
                val named = functions.search(current, SeatQuery(date, name = seatNumber))
                assumeTrue("The school requested official number-search verification", !named.verificationRequired)
                assertTrue("Number lookup must find the selected seat", named.seats.any { it.id == selectedSeat.id })
                assertTrue("Number lookup must not return unrelated seat numbers", named.seats.all {
                    it.label.takeWhile { character -> character.isDigit() }.trimStart('0') == seatNumber.trimStart('0')
                })
                val starts = functions.startTimes(current, selectedSeat.id, date)
                assumeTrue("The school requested official time verification", !starts.verificationRequired)
                assumeTrue("This seat has no available start times", starts.options.isNotEmpty())
                val start = starts.options.first()
                val ends = functions.endTimes(current, selectedSeat.id, date, start.value)
                assumeTrue("The school requested official end-time verification", !ends.verificationRequired)
                val end = ends.options.firstOrNull {
                    (it.value.toIntOrNull() ?: -1) > (start.value.toIntOrNull() ?: Int.MAX_VALUE)
                }
                assumeTrue("This seat has no valid end time", end != null)

                val selection = ReservationSelection(date, selectedSeat, start, requireNotNull(end))
                val prepared = functions.prepareReservation(current, selection)
                assertEquals(current.id, prepared.accountId)
                assertEquals(selection, prepared.selection)
                assertTrue("A fresh reservation token must be returned", prepared.fields["SYNCHRONIZER_TOKEN"].isNullOrBlank().not())
                assertEquals("/self", prepared.fields["SYNCHRONIZER_URI"])
                // Deliberately stop before reserve() or any mutating user action.
                val history = functions.history(current)
                assertEquals(history.items.size, history.items.map { it.id }.distinct().size)
                val violations = functions.violations(current)
                assertEquals(violations.items.size, violations.items.map { it.id }.distinct().size)
                val state = functions.userState(current)
                assertTrue("The server must expose reservation state", state.status.isNotBlank())
                if (!state.softMode || state.status == "LOC_RESTRICT") assertTrue(state.actions.isEmpty())

                val nativeMap = NativeMapRepository(repository)
                val mapOptions = nativeMap.options(current)
                assertTrue("Native map must return server date choices", mapOptions.dates.isNotEmpty())
                val mapDate = mapOptions.dates.firstOrNull { option ->
                    runCatching { LocalDate.parse(option.value).isAfter(today) }.getOrDefault(false)
                }
                assumeTrue("The map has not opened a future booking date", mapDate != null)
                val mapDateValue = requireNotNull(mapDate).value
                val mapBuilding = mapOptions.buildings.firstOrNull { it.value.isNotBlank() && it.value != "null" }
                assumeTrue("The map has no available building", mapBuilding != null)
                val mapBuildingValue = requireNotNull(mapBuilding).value
                val floors = try { nativeMap.floors(current, mapBuildingValue) }
                catch (_: NativeMapRepository.VerificationRequired) {
                    assumeTrue("The school requested native map floor verification; finish it in the app", false)
                    return@use
                }
                val floor = floors.firstOrNull { it.value.isNotBlank() && it.value != "null" }
                assumeTrue("The building has no map floor choices", floor != null)
                val mapRooms = nativeMap.rooms(current, mapDateValue, mapBuildingValue, requireNotNull(floor).value)
                assumeTrue("The school requested native map room verification; finish it in the app", !mapRooms.verificationRequired)
                assumeTrue("The selected floor has no available room layout", mapRooms.rooms.isNotEmpty())
                val room = mapRooms.rooms.first()
                val layout = nativeMap.layout(current, room, mapDateValue)
                assumeTrue("The school requested native map layout verification; finish it in the app", !layout.verificationRequired)
                assertEquals(room.id, layout.roomId)
                assertEquals(room.name, layout.roomName)
                assertTrue("Native room dimensions must retain the server grid", layout.rows > 0 && layout.columns > 0)
                assertTrue("Native room must contain parsed seat cells", layout.cells.any { it.seat != null })
                assertTrue("All native cell bounds must fit the reported grid", layout.cells.all {
                    it.row >= 0 && it.column >= 0 && it.row + it.rowSpan <= layout.rows && it.column + it.columnSpan <= layout.columns
                })
                assertEquals(layout.cells.count { it.seat != null }, layout.cells.mapNotNull { it.seat?.id }.distinct().size)
                assumeTrue("No remotely available seat is free in the selected room", layout.cells.any { it.seat?.available == true })
                assertTrue("A site-only seat must never become remotely bookable", layout.cells.filter { it.localOnly }.none { it.seat?.available == true })
                assertFalse("Read-only checks must preserve the sign-in choice", store.isSignedOut(current.id))
            } finally {
                // Dispose cached clients without clearing the user's persisted session.
                repository.releaseSession(current.id)
            }
        }
    }
}
