package cn.edu.bucea.library

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.*
import cn.edu.bucea.library.ocr.DdddOcr
import cn.edu.bucea.library.ui.reservationMapTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: reads an existing reservation and its map; no seat mutations. */
@RunWith(AndroidJUnit4::class)
class LiveReservationMapLocationTest {
    @Test fun locatesExistingReservationUsingFreshServerRegionAndSeatIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_library") == "true")
        val store = AccountStore(instrumentation.targetContext)
        val current = store.accounts().firstOrNull { it.id == store.activeAccountId }
        assumeTrue(current != null && !store.isSignedOut(current.id))
        val account = requireNotNull(current)
        DdddOcr(instrumentation.targetContext).use { ocr ->
            val library = LibraryRepository(store, ocr)
            try {
                assertNotNull(library.restore(account))
                val functions = LibraryFunctionsRepository(library)
                val target = functions.history(account).items.firstNotNullOfOrNull { record ->
                    reservationMapTarget(record.details, record.schedule)
                }
                assumeTrue("An existing record with a definite location is required", target != null)
                val maps = NativeMapRepository { saved, endpoint ->
                    library.request(saved, endpoint).also { response ->
                        val description = when {
                            endpoint.startsWith("/mapBook/ajaxGetFloor") -> "floors=" + LibraryFunctionsRepository.parseOptionsFragment(response).map { it.label }
                            endpoint.startsWith("/mapBook/ajaxGetRooms") -> "rooms=" + NativeMapRepository.parseRooms(response).rooms.map { it.name }
                            else -> null
                        }
                        description?.let { instrumentation.sendStatus(0, Bundle().apply { putString("map_metadata", it) }) }
                    }
                }
                val state = maps.loadReservation(account, requireNotNull(target))
                assertFalse(state.verificationRequired)
                assertEquals(target.date, state.reservationDate)
                val focused = state.layout?.cells?.singleOrNull { it.seat?.id == state.focusSeatId }?.seat
                assertNotNull("The real layout must identify exactly one reservation seat", focused)
                assertEquals(target.seatLabel, focused?.label)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("located_seat", focused?.label)
                    putString("located_region", state.layout?.roomName)
                    putString("reservation_date", state.reservationDate)
                    putString("map_date", state.filters["onDate"])
                })
            } finally { library.releaseSession(account.id) }
        }
    }
}
