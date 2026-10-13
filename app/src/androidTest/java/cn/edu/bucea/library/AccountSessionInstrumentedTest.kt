package cn.edu.bucea.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.SessionCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountSessionInstrumentedTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var preferenceFile: String
    private val address = "https://library.example.test/seat".toHttpUrl()

    @Before
    fun createIsolatedVault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Instrumentation runs as the target UID, which cannot write the test APK's data
        // directory. Keep the test context, but route preferences to one unique test file.
        // The production library_vault file is never opened, modified or cleared here.
        preferenceFile = "instrumentation_fake_${UUID.randomUUID()}_library_vault"
        context = object : ContextWrapper(instrumentation.context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                require(name == "library_vault")
                return instrumentation.targetContext.getSharedPreferences(preferenceFile, mode)
            }
        }
        assertNotEquals(instrumentation.targetContext.packageName, context.packageName)
        preferences = context.getSharedPreferences("library_vault", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
    }

    @After
    fun removeOnlyFakeTestAccounts() {
        if (::preferences.isInitialized) {
            assertTrue(preferences.edit().clear().commit())
            InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences(preferenceFile)
        }
    }

    @Test
    fun encryptedAccountsAndSessionsSurviveReloadAndRemainSeparate() {
        val store = AccountStore(context)
        val a = store.save("209999990001", "test-A-password-only!", "测试甲")
        val b = store.save("209999990002", "test-B-password-only!", "测试乙")
        val aToken = "fake-session-for-account-A"
        val bToken = "fake-session-for-account-B"
        val aJar = SessionCookieJar(store, a.id)
        val bJar = SessionCookieJar(store, b.id)
        aJar.saveFromResponse(address, listOf(sessionCookie(aToken)))
        bJar.saveFromResponse(address, listOf(sessionCookie(bToken)))

        store.activeAccountId = a.id
        assertEquals(listOf(aToken), aJar.loadForRequest(address).map { it.value })
        store.activeAccountId = b.id
        assertEquals(listOf(bToken), bJar.loadForRequest(address).map { it.value })
        assertEquals(listOf(aToken), aJar.loadForRequest(address).map { it.value })
        store.autoLogin = false
        store.autoSwitch = true

        assertCiphertext("accounts", listOf(a.username, a.password, a.label, b.username, b.password, b.label))
        assertCiphertext("cookies_${a.id}", listOf(aToken))
        assertCiphertext("cookies_${b.id}", listOf(bToken))

        val reloaded = AccountStore(context)
        assertEquals(listOf(a, b), reloaded.accounts())
        assertEquals(b.id, reloaded.activeAccountId)
        assertFalse(reloaded.autoLogin)
        assertTrue(reloaded.autoSwitch)
        assertEquals(listOf(aToken), SessionCookieJar(reloaded, a.id).loadForRequest(address).map { it.value })
        assertEquals(listOf(bToken), SessionCookieJar(reloaded, b.id).loadForRequest(address).map { it.value })

        reloaded.delete(a.id)
        val afterDeletion = AccountStore(context)
        assertEquals(listOf(b), afterDeletion.accounts())
        assertEquals(b.id, afterDeletion.activeAccountId)
        assertTrue(SessionCookieJar(afterDeletion, a.id).loadForRequest(address).isEmpty())
        assertEquals(listOf(bToken), SessionCookieJar(afterDeletion, b.id).loadForRequest(address).map { it.value })
    }

    @Test
    fun updatedCredentialsClearOnlyTheirOwnSavedSession() {
        val store = AccountStore(context)
        val a = store.save("209999990001", "fake-original-A", "甲")
        val b = store.save("209999990002", "fake-original-B", "乙")
        SessionCookieJar(store, a.id).saveFromResponse(address, listOf(sessionCookie("fake-A-session")))
        SessionCookieJar(store, b.id).saveFromResponse(address, listOf(sessionCookie("fake-B-session")))

        val edited = store.save(a.username, "fake-replaced-A", a.label)
        val reloaded = AccountStore(context)
        assertEquals(a.id, edited.id)
        assertEquals(2, reloaded.accounts().size)
        assertEquals("fake-replaced-A", reloaded.accounts().first { it.id == a.id }.password)
        assertTrue(SessionCookieJar(reloaded, a.id).loadForRequest(address).isEmpty())
        assertEquals(listOf("fake-B-session"), SessionCookieJar(reloaded, b.id).loadForRequest(address).map { it.value })
    }

    @Test
    fun explicitSignOutPersistsPerAccountUntilUserLogsInAgain() {
        val store = AccountStore(context)
        val a = store.save("209999990005", "fake-signout-A", "甲")
        val b = store.save("209999990006", "fake-signout-B", "乙")
        store.setSignedOut(a.id, true)

        assertTrue(AccountStore(context).isSignedOut(a.id))
        assertFalse(AccountStore(context).isSignedOut(b.id))
        store.setSignedOut(a.id, false)
        assertFalse(AccountStore(context).isSignedOut(a.id))
        store.setSignedOut(a.id, true)
        store.delete(a.id)
        assertFalse(AccountStore(context).isSignedOut(a.id))
    }

    @Test
    fun browserSnapshotRemovesDeletedCookiesAndEmptySnapshotClearsSession() {
        val store = AccountStore(context)
        val account = store.save("209999990007", "fake-browser-snapshot", "测试")
        val root = "http://10.1.20.7/".toHttpUrl()
        val jar = SessionCookieJar(store, account.id)
        val original = Cookie.Builder().name("session").value("old").hostOnlyDomain(root.host)
            .path("/").httpOnly().expiresAt(System.currentTimeMillis() + 60_000).build()
        val deleted = Cookie.Builder().name("server_deleted").value("gone").hostOnlyDomain(root.host).path("/").build()
        jar.saveFromResponse(root, listOf(original, deleted))

        jar.replaceBrowserCookies("session=new; preference=compact")
        val restored = SessionCookieJar(AccountStore(context), account.id).loadForRequest(root)
        assertEquals(setOf("session", "preference"), restored.map { it.name }.toSet())
        assertEquals("new", restored.first { it.name == "session" }.value)
        assertTrue(restored.first { it.name == "session" }.httpOnly)
        assertEquals(original.expiresAt, restored.first { it.name == "session" }.expiresAt)

        jar.replaceBrowserCookies(null)
        assertTrue(SessionCookieJar(AccountStore(context), account.id).all().isEmpty())
    }

    @Test
    fun invalidatedJarCannotRestoreSessionFromLateResponse() {
        val store = AccountStore(context)
        val account = store.save("209999990004", "fake-late-response-password", "测试")
        val oldJar = SessionCookieJar(store, account.id)
        oldJar.saveFromResponse(address, listOf(sessionCookie("fake-original-session")))

        oldJar.invalidate()
        oldJar.saveFromResponse(address, listOf(sessionCookie("fake-late-session")))

        assertTrue(oldJar.loadForRequest(address).isEmpty())
        assertTrue(SessionCookieJar(AccountStore(context), account.id).loadForRequest(address).isEmpty())
    }

    @Test
    fun releasingCachedJarPreservesLatestWebSession() {
        val store = AccountStore(context)
        val account = store.save("209999990005", "fake-web-return-password", "测试")
        val cached = SessionCookieJar(store, account.id)
        cached.saveFromResponse(address, listOf(sessionCookie("fake-native-session")))
        SessionCookieJar(store, account.id).saveFromResponse(address, listOf(sessionCookie("fake-web-session")))
        cached.invalidate(clearSaved = false)
        cached.saveFromResponse(address, listOf(sessionCookie("fake-late-native-session")))
        assertTrue(cached.loadForRequest(address).isEmpty())
        assertEquals(listOf("fake-web-session"), SessionCookieJar(AccountStore(context), account.id).loadForRequest(address).map { it.value })
    }

    @Test
    fun restoredCookiesRespectHostPathSecurityAndServerExpiry() {
        val store = AccountStore(context)
        val account = store.save("209999990003", "fake-scope-password", "测试")
        val jar = SessionCookieJar(store, account.id)
        jar.saveFromResponse(address, listOf(sessionCookie("fake-scoped-session")))
        val restored = SessionCookieJar(AccountStore(context), account.id)

        assertEquals(1, restored.loadForRequest(address).size)
        assertTrue(restored.loadForRequest("http://library.example.test/seat".toHttpUrl()).isEmpty())
        assertTrue(restored.loadForRequest("https://other.example.test/seat".toHttpUrl()).isEmpty())
        assertTrue(restored.loadForRequest("https://library.example.test/account".toHttpUrl()).isEmpty())

        restored.saveFromResponse(address, listOf(sessionCookie("deleted", 1)))
        assertTrue(SessionCookieJar(AccountStore(context), account.id).loadForRequest(address).isEmpty())
    }

    private fun sessionCookie(value: String, expires: Long = System.currentTimeMillis() + 60_000): Cookie =
        Cookie.Builder().name("library_session").value(value)
            .hostOnlyDomain(address.host).path("/seat").secure().httpOnly().expiresAt(expires).build()

    private fun assertCiphertext(key: String, secrets: List<String>) {
        val encoded = requireNotNull(preferences.getString(key, null))
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        // Check the saved representation, rather than relying on the store's decryption path.
        assertTrue(bytes.size > 28)
        val binaryText = String(bytes, Charsets.UTF_8)
        for (secret in secrets) {
            assertFalse("Stored $key exposes plaintext", encoded.contains(secret))
            assertFalse("Decoded $key exposes plaintext", binaryText.contains(secret))
        }
    }
}
