package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.data.FailureKind
import cn.edu.bucea.library.data.LoginFailure
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusAccessPolicyInstrumentedTest {
    @Test
    fun schoolHttpAndLoginErrorsProveReachabilityWhileTransportFailureDoesNot() {
        for (kind in FailureKind.entries) {
            assertEquals("A server reply cannot be labeled as an inaccessible campus network", CampusAccess.REACHABLE,
                CampusAccessPolicy.afterFailure(CampusAccess.UNKNOWN, LoginFailure(kind, "synthetic server reply")))
        }
        assertEquals(CampusAccess.UNREACHABLE, CampusAccessPolicy.afterFailure(CampusAccess.REACHABLE, IOException("synthetic connection failure")))
        assertEquals(CampusAccess.REACHABLE, CampusAccessPolicy.afterFailure(CampusAccess.REACHABLE, IllegalArgumentException("invalid local input")))
    }

    @Test
    fun failedAccessCheckBlocksLoginSideEffectsAndRetryCanRecover() = runBlocking {
        val states = mutableListOf<CampusAccess>()
        var clearedSignOut = false
        var beganOcr = false
        var switchedAccount = false
        try {
            CampusAccessPolicy.check(probe = { throw IOException("synthetic unreachable host") }, update = states::add)
            clearedSignOut = true; beganOcr = true; switchedAccount = true
            fail("Access failure must stop the dependent login flow")
        } catch (_: IOException) { }
        assertFalse(clearedSignOut)
        assertFalse(beganOcr)
        assertFalse(switchedAccount)
        assertEquals(listOf(CampusAccess.CHECKING, CampusAccess.UNREACHABLE), states)
        val overview = CampusAccessPolicy.check(probe = { LibraryOverview(available = 12) }, update = states::add)
        assertEquals(12, overview.available)
        assertEquals(CampusAccess.REACHABLE, states.last())
    }
}
