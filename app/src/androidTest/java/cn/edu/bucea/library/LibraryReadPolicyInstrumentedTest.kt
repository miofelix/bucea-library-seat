package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.data.LibraryReadPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryReadPolicyInstrumentedTest {
    @Test
    fun staleConnectionsCanRecoverForSeatAndReservationReads() {
        assertTrue(LibraryReadPolicy.permitsRetry("GET", "/freeBook/ajaxGetRooms?id=1"))
        assertTrue(LibraryReadPolicy.permitsRetry("GET", "/freeBook/ajaxGetTime?id=5773&date=2026-10-14"))
        assertTrue(LibraryReadPolicy.permitsRetry("GET", "/mapBook/getSeatsByRoom"))
        assertTrue(LibraryReadPolicy.permitsRetry("GET", "/history"))
        assertTrue(LibraryReadPolicy.permitsRetry("GET", "/user/getTimeExtend"))
    }

    @Test
    fun redirectsToMutationsAndUnknownPathsNeverInheritReadRetry() {
        // Reevaluate each redirect target, even when the original request is a whitelisted GET.
        assertTrue(LibraryReadPolicy.permitsRetry("GET", "/self"))
        for (path in listOf("/selfRes", "/user/checkIn", "/user/leave", "/user/resume",
            "/user/stopUsing", "/user/extend?endTime=600", "/logout", "/freeBook/newOperation")) {
            assertFalse("Mutation/unknown target must not retry: $path", LibraryReadPolicy.permitsRetry("GET", path))
        }
        assertFalse(LibraryReadPolicy.permitsRetry("POST", "/self"))
        assertFalse(LibraryReadPolicy.permitsRetry("POST", "/auth/signIn"))
    }
}
