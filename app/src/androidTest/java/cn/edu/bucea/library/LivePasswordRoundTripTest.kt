package cn.edu.bucea.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.FailureKind
import cn.edu.bucea.library.data.LibraryHtml
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.LoginFailure
import cn.edu.bucea.library.data.NativeAccountRepository
import cn.edu.bucea.library.data.PasswordChangeUncertain
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.data.models.PasswordChangeOutcome
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * Explicitly opted-in real password mutation and restoration. Never run beside another live test.
 * -e live_password_round_trip true changes to a random temporary password and restores the original.
 * -e live_restore_password true restores an existing encrypted recovery record without a new change.
 * Optional -e live_password_profile six_digits tests the school's six-digit credential format.
 * No credentials are accepted as runner arguments, printed, or embedded in assertion output.
 */
@RunWith(AndroidJUnit4::class)
class LivePasswordRoundTripTest {
    @Test
    fun changesPasswordAndRestoresOriginalWithDurableRecovery() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val roundTrip = arguments.getString("live_password_round_trip") == "true"
        val restoreOnly = arguments.getString("live_restore_password") == "true"
        assumeTrue("Real password tests require an explicit live password flag", roundTrip || restoreOnly)
        val profile = arguments.getString("live_password_profile") ?: "alphanumeric_12"
        require(profile in setOf("alphanumeric_12", "six_digits")) { "Unknown live password profile" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context)
        val recovery = PasswordRecovery(context)
        val existing = recovery.read()
        if (restoreOnly) assertTrue("No encrypted password recovery record is pending", existing != null)
        val source = if (existing != null) existing.original else {
            val account = store.accounts().find { it.id == store.activeAccountId }
            assumeTrue("Save and select the intended account before the live password test", account != null)
            requireNotNull(account)
        }
        assertTrue("The recovery account must still exist in the production vault",
            store.accounts().any { it.id == source.id && it.username == source.username })
        if (existing == null) assumeTrue("Live password tests preserve explicit sign-out", !store.isSignedOut(source.id))
        val record = existing ?: recovery.create(source, store, profile)
        val temporary = source.copy(password = record.temporaryPassword)
        val captures = File(context.filesDir, "actual-password").apply { mkdirs() }
        var remote = if (existing == null) RemoteCredential.ORIGINAL else RemoteCredential.UNKNOWN
        var mutationMayHaveApplied = existing != null
        var originalAuthenticated = false
        var firstFailure: String? = null
        var restored = false
        var receiptConfirmed = true
        var captureName = "change.html"
        var temporaryAttempted = false
        var temporaryAuthenticated: Boolean? = null
        var originalCandidateAttempted = false
        var originalCandidateAuthenticated: Boolean? = null

        DdddOcr(context).use { ocr ->
            val library = LibraryRepository(store, ocr)
            val accounts = NativeAccountRepository(
                request = library::request,
                post = { account, path, fields ->
                    val response = library.post(account, path, fields)
                    // Receipt pages may echo request values after a server error. Captures must
                    // never persist a plaintext password or a usable form token.
                    val document = LibraryHtml.parse(response)
                    document.select("input[type=password], input[type=hidden]").forEach { it.attr("value", "[REDACTED]") }
                    document.select("script").forEach { script ->
                        // Keep a password-result flash message for diagnosis, but no executable
                        // website code or inline session/verification data from the response.
                        val flash = Regex("""flashMsg\s*=\s*['"]([^'"]+)['"]""").find(script.data())?.groupValues?.get(1)
                        if (flash == null) script.remove()
                        else script.replaceWith(org.jsoup.nodes.Element("p").attr("class", "captured-flash-message").text(flash))
                    }
                    var capture = document.outerHtml()
                    listOf(source.password, temporary.password, source.username,
                        fields["SYNCHRONIZER_TOKEN"].orEmpty()).filter { it.isNotEmpty() }
                        .distinct().sortedByDescending { it.length }.forEach { secret ->
                            capture = capture.replace(secret, "[REDACTED]")
                        }
                    File(captures, captureName).writeText(capture)
                    response
                },
                verifySession = { library.restore(it) != null },
                saveCredentials = { account, password ->
                    val active = store.activeAccountId
                    try {
                        val saved = store.save(account.username, password, account.label)
                        check(saved.id == source.id) { "The saved account identity changed" }
                        library.forget(account.id)
                        true
                    } finally { store.activeAccountId = active }
                },
                clearSession = { account -> library.forget(account.id); store.setSignedOut(account.id, true) },
            )

            suspend fun authenticate(candidate: SavedAccount): Boolean {
                // A restored old cookie cannot establish that a candidate password is valid.
                library.forget(candidate.id)
                store.setSignedOut(candidate.id, false)
                return try {
                    library.login(candidate) { /* Never print progress, credentials or server values. */ }
                    library.restore(candidate) != null
                } catch (error: LoginFailure) {
                    if (error.kind == FailureKind.CREDENTIALS) false else throw SafePasswordTestFailure()
                } catch (_: Exception) { throw SafePasswordTestFailure() }
            }

            suspend fun authenticateTemporaryOnce(): Boolean {
                if (temporaryAttempted) return temporaryAuthenticated ?: throw SafePasswordTestFailure()
                temporaryAttempted = true
                return authenticate(temporary).also { temporaryAuthenticated = it }
            }

            suspend fun authenticateOriginalOnce(): Boolean {
                if (originalCandidateAttempted) return originalCandidateAuthenticated ?: throw SafePasswordTestFailure()
                originalCandidateAttempted = true
                return authenticate(source).also { originalCandidateAuthenticated = it }
            }

            suspend fun restoreOriginal() {
                if (remote == RemoteCredential.UNKNOWN) {
                    if (authenticateTemporaryOnce()) remote = RemoteCredential.TEMPORARY
                    else if (authenticateOriginalOnce()) {
                        remote = RemoteCredential.ORIGINAL
                        originalAuthenticated = true
                    } else throw SafePasswordTestFailure()
                }
                if (remote == RemoteCredential.TEMPORARY) {
                    // A fresh form is obtained inside changePassword. This mutation is never retried.
                    recovery.stage("RESTORE_STARTED")
                    captureName = "restore.html"
                    originalCandidateAttempted = false
                    originalCandidateAuthenticated = null
                    originalAuthenticated = false
                    val result = try {
                        accounts.changePassword(temporary, temporary.password, source.password, source.password)
                    } catch (_: PasswordChangeUncertain) { null }
                    if (result?.outcome != PasswordChangeOutcome.SUCCESS) receiptConfirmed = false
                    println("PASSWORD_ROUND_TRIP restore_receipt=${result?.outcome?.name ?: "UNKNOWN"}")
                    // Check the expected post-write credential once, even for an unknown receipt.
                    if (!authenticateOriginalOnce()) throw SafePasswordTestFailure()
                    originalAuthenticated = true
                    remote = RemoteCredential.ORIGINAL
                }
                if (!originalAuthenticated) {
                    if (!authenticateOriginalOnce()) throw SafePasswordTestFailure()
                    originalAuthenticated = true
                }
                // Unknown receipts may have left the temporary password in the production vault.
                // Preserve the newly verified original session while updating the encrypted value.
                val verifiedCookies = store.cookies(source.id)
                val saved = store.save(source.username, source.password, source.label)
                if (saved.id != source.id) throw SafePasswordTestFailure()
                store.saveCookies(source.id, verifiedCookies)
                library.releaseSession(source.id)
                if (library.restore(source) == null || store.accounts().none {
                        it.id == source.id && it.password == source.password
                    }) throw SafePasswordTestFailure()
                recovery.stage("ORIGINAL_VERIFIED")
                restored = true
                println("PASSWORD_ROUND_TRIP original_authenticated=true vault_restored=true")
            }

            try {
                if (existing == null) {
                    if (!authenticate(source)) throw SafePasswordTestFailure()
                    originalAuthenticated = true
                    // This encrypted stage is committed before entering any mutation code.
                    recovery.stage("CHANGE_STARTED")
                    mutationMayHaveApplied = true
                    originalAuthenticated = false
                    remote = RemoteCredential.UNKNOWN
                    val result = try {
                        accounts.changePassword(source, source.password, temporary.password, temporary.password)
                    } catch (_: PasswordChangeUncertain) { null }
                    println("PASSWORD_ROUND_TRIP change_receipt=${result?.outcome?.name ?: "UNKNOWN"}")
                    if (result?.outcome == PasswordChangeOutcome.REJECTED) {
                        remote = RemoteCredential.ORIGINAL
                        throw SafePasswordTestFailure()
                    }
                    if (result?.outcome != PasswordChangeOutcome.SUCCESS) receiptConfirmed = false
                    if (authenticateTemporaryOnce()) {
                        remote = RemoteCredential.TEMPORARY
                        recovery.stage("TEMPORARY_VERIFIED")
                        println("PASSWORD_ROUND_TRIP temporary_authenticated=true")
                    } else if (authenticateOriginalOnce()) {
                        remote = RemoteCredential.ORIGINAL
                        originalAuthenticated = true
                        throw SafePasswordTestFailure()
                    } else throw SafePasswordTestFailure()
                } else println("PASSWORD_ROUND_TRIP encrypted_recovery_found=true restore_only=true")
            } catch (_: Throwable) {
                firstFailure = "The live password change did not complete normally"
            } finally {
                // Once a password write may have reached the server, restoration must finish even
                // if the initial test assertion or coroutine was interrupted.
                withContext(NonCancellable) {
                    try {
                        if (mutationMayHaveApplied) restoreOriginal()
                        else restored = true // No server password mutation was entered.
                        store.activeAccountId = record.activeAccountId
                        store.autoLogin = record.autoLogin
                        store.autoSwitch = record.autoSwitch
                        store.setSignedOut(source.id, record.signedOut)
                        recovery.clear()
                    } catch (_: Throwable) {
                        restored = false
                        // Prevent automatic attempts with an uncertain saved password. Keep both
                        // encrypted candidates available to a later restore-only run.
                        runCatching { recovery.stage("RECOVERY_REQUIRED") }
                        runCatching { store.setSignedOut(source.id, true) }
                        runCatching { library.releaseSession(source.id) }
                        runCatching { store.activeAccountId = record.activeAccountId }
                        firstFailure = "Original password restoration is not confirmed; encrypted recovery retained. Run this class with -e live_restore_password true"
                    }
                }
            }
        }
        assertTrue(firstFailure ?: "Original password restoration must be confirmed", restored)
        assertTrue("Original password was restored, but a server password receipt was unknown; inspect private actual-password receipts before updating the parser", receiptConfirmed)
        assertTrue("The original password was restored, but the initial live change did not complete; inspect private receipts", firstFailure == null)
    }
}

private enum class RemoteCredential { ORIGINAL, TEMPORARY, UNKNOWN }
private class SafePasswordTestFailure : Exception("Live password verification did not complete")

/** Existing AccountStore encryption and synchronous commits, isolated from the production vault. */
private class PasswordRecovery(private val target: Context) {
    private val preferenceName = "library_password_test_recovery"
    private val preferences: SharedPreferences = target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            require(name == "library_vault")
            return preferences
        }
    }
    private val vault = AccountStore(context)
    private val temporaryUsername = "99990001"

    data class Record(val original: SavedAccount, val temporaryPassword: String,
        val activeAccountId: String?, val autoLogin: Boolean, val autoSwitch: Boolean, val signedOut: Boolean)

    fun read(): Record? {
        val values = vault.accounts()
        if (values.isEmpty()) return null
        val original = values.firstOrNull { it.label.startsWith("{") }
            ?: throw AssertionError("Encrypted password recovery is incomplete; do not start another mutation")
        val metadata = JSONObject(original.label)
        val temporary = values.firstOrNull { it.label == "temporary" }
        if (temporary == null && metadata.optString("stage") == "PREPARED") {
            // A process may stop between the two initial encrypted writes. No mutation can
            // begin before CHANGE_STARTED, so this incomplete preparation is safe to discard.
            clear()
            return null
        }
        if (temporary == null) throw AssertionError("Encrypted password recovery is incomplete; do not start another mutation")
        return Record(SavedAccount(metadata.getString("sourceId"), original.username, original.password,
            metadata.getString("sourceLabel")), temporary.password, metadata.optString("activeId").ifBlank { null },
            metadata.getBoolean("autoLogin"), metadata.getBoolean("autoSwitch"), metadata.getBoolean("signedOut"))
    }

    fun create(source: SavedAccount, store: AccountStore, profile: String): Record {
        check(vault.accounts().isEmpty()) { "Existing encrypted recovery must be completed first" }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        var temporary: String
        do { temporary = if (profile == "six_digits") buildString { repeat(6) { append(random.nextInt(10)) } }
            else "Bu7" + buildString { repeat(9) { append(alphabet[random.nextInt(alphabet.length)]) } } }
        while (temporary == source.password)
        val metadata = JSONObject().put("sourceId", source.id).put("sourceLabel", source.label)
            .put("activeId", store.activeAccountId.orEmpty()).put("autoLogin", store.autoLogin)
            .put("autoSwitch", store.autoSwitch).put("signedOut", store.isSignedOut(source.id))
            .put("profile", profile).put("stage", "PREPARED")
        vault.save(source.username, source.password, metadata.toString())
        vault.save(if (source.username == temporaryUsername) "99990002" else temporaryUsername, temporary, "temporary")
        val reloaded = requireNotNull(read())
        check(reloaded.original.password == source.password && reloaded.temporaryPassword == temporary) {
            "Encrypted password recovery could not be reopened"
        }
        return reloaded
    }

    fun stage(value: String) {
        val original = vault.accounts().first { it.label.startsWith("{") }
        val metadata = JSONObject(original.label).put("stage", value)
        vault.save(original.username, original.password, metadata.toString())
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Encrypted recovery cleanup failed" }
        // The durable empty namespace proves cleanup. Some Android versions can report false
        // when the already-cleared preference file is absent; that is not a retained recovery.
        target.deleteSharedPreferences(preferenceName)
    }
}
