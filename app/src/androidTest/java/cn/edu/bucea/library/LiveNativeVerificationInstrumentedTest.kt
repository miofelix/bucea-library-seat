package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.NativeVerificationRepository
import cn.edu.bucea.library.ocr.DdddClickSolver
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real server proof; creates no booking and performs no account/password action. */
@RunWith(AndroidJUnit4::class)
class LiveNativeVerificationInstrumentedTest {
    @Test
    fun localAndroidDdddocrCompletesSchoolVerificationWithoutHumanInput() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live verification is opt-in", arguments.getString("live_library") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context)
        val id = arguments.getString("live_account_id") ?: store.activeAccountId
        val account = store.accounts().firstOrNull { it.id == id }
        assumeTrue("Add a library account before live verification", account != null)
        val current = requireNotNull(account)
        assumeTrue("Explicit sign-out must be preserved", !store.isSignedOut(current.id))
        DdddOcr(context).use { ocr ->
            val library = LibraryRepository(store, ocr)
            try {
                if (library.restore(current) == null) {
                    assumeTrue("No valid session and automatic login disabled", store.autoLogin)
                    assertTrue("Automatic login must succeed before verification", library.login(current) {}.overview != null)
                }
                val verification = NativeVerificationRepository(library)
                val verificationContext = verification.context(current)
                DdddClickSolver(context).use { solver ->
                    val proof = verification.automaticallyVerify(current, verificationContext, solver::solve)
                    assertEquals(current.id, proof.accountId)
                    assertTrue("Server-validated proof must have an auth ID", proof.authId.isNotBlank())
                    assertTrue("Proof must be fresh", System.currentTimeMillis() - proof.validatedAt in 0..120_000)
                }
                assertFalse(store.isSignedOut(current.id))
            } finally { library.releaseSession(current.id) }
        }
    }
}
