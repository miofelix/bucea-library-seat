package cn.edu.bucea.library

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Framework-only readback can run against the actual R8-minified release APK. */
@RunWith(AndroidJUnit4::class)
class LiveReleaseAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val acceptance get() = context.getSharedPreferences("update_release_acceptance", Context.MODE_PRIVATE)

    @Before fun requireExplicitAcceptanceRun() {
        assumeTrue("Actual release acceptance is opt-in",
            InstrumentationRegistry.getArguments().getString("live_update_acceptance") == "true")
    }

    @Test fun snapshotRawUpgradeState() {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val digests = rawDigests(salt)
        assertTrue("Persist only salted digests for final release comparison", acceptance.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("data_digest", digests.first)
            .putString("cookies_digest", digests.second)
            .commit())
    }

    @Test fun assertRawUpgradeStatePreserved() {
        assertRawState()
    }

    @Suppress("DEPRECATION")
    @Test fun checkInstalledReleaseAndCleanup() {
        val installed = installed()
        assertEquals("The actual package must reach the final release code", 9L, versionCode(installed))
        assertEquals("The actual package must carry the final release version", "0.3.4", installed.versionName)
        assertEquals("The final installed APK must not be debuggable", 0,
            requireNotNull(installed.applicationInfo).flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val expectedDigest = acceptance.getString("candidate_digest", null)
        assertNotNull("Record the verified final candidate bytes before installation", expectedDigest)
        assertEquals("The installed release must retain the exact verified candidate APK bytes", expectedDigest,
            fileDigest(File(requireNotNull(installed.applicationInfo).sourceDir)))
        assertRawState()
        val token = acceptance.getString("candidate_token", null)
        assertNotNull("Prepare a verified final candidate before checking cleanup", token)
        assertEquals("Only a UUID created by the acceptance seed is tracked", token, UUID.fromString(requireNotNull(token)).toString())
        val internalPath = acceptance.getString("candidate_internal_path", null)
        val externalPath = acceptance.getString("candidate_external_path", null)
        assertEquals("Readback must remain inside the updater's internal install directory", "updates/install/$token.apk", internalPath)
        assertEquals("Readback must remain inside the updater's external download directory", "updates/download-$token.apk", externalPath)
        val record = File(context.filesDir, "updates/task.json")
        if (record.exists()) {
            val json = JSONObject(record.readText())
            assertEquals("Only the acceptance run's task may be cleaned by the installed app", token, json.getString("token"))
            assertEquals("The tracked task must target this final release", 9L, json.getJSONObject("manifest").getLong("versionCode"))
        }
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("The installed release must expose its real launcher", launcher)
        val activity = instrumentation.startActivitySync(requireNotNull(launcher).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val ready = File(context.filesDir, requireNotNull(internalPath))
            val partial = File(context.filesDir, "updates/install/$token.part")
            val external = context.getExternalFilesDir(null)?.let { File(it, requireNotNull(externalPath)) }
            val backup = File(record.path + ".bak")
            val writing = File(record.path + ".new")
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < deadline &&
                (record.exists() || backup.exists() || writing.exists() || ready.exists() || partial.exists() || external?.exists() == true)) {
                SystemClock.sleep(200)
            }
            assertFalse("The real release must naturally clear its installed update task", record.exists())
            assertFalse("The real release must clear any update record backup", backup.exists())
            assertFalse("The real release must clear any in-progress update record", writing.exists())
            assertFalse("The real release must remove its verified internal install APK", ready.exists())
            assertFalse("The real release must remove its partial install copy", partial.exists())
            assertFalse("The real release must remove its tracked download file", external?.exists() == true)
            // Starting the app may refresh a session; raw account/favorite/appearance data
            // must still be intact. Cookie comparison remains an explicit optional check.
            assertRawState()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun assertRawState() {
        val encodedSalt = acceptance.getString("salt", null)
        assertNotNull("Snapshot the existing private state before overwriting the app", encodedSalt)
        val salt = Base64.decode(requireNotNull(encodedSalt), Base64.NO_WRAP)
        assertEquals("The private snapshot must use a full random salt", 32, salt.size)
        val digests = rawDigests(salt)
        assertTrue("Encrypted accounts, selected account, preferences, sign-out, manual favorites and appearance must be preserved",
            acceptance.getString("data_digest", null) == digests.first)
        if (InstrumentationRegistry.getArguments().getString("compare_upgrade_cookies") == "true") {
            assertTrue("Saved encrypted session cookies must be preserved",
                acceptance.getString("cookies_digest", null) == digests.second)
        }
    }

    private fun rawDigests(salt: ByteArray): Pair<String, String> {
        val data = SaltedPreferenceDigest(salt, "release-data-v1")
        val cookies = SaltedPreferenceDigest(salt, "release-cookies-v1")
        val vault = context.getSharedPreferences("library_vault", Context.MODE_PRIVATE)
        data.addPreferences("library_vault", vault) { !it.startsWith("cookies_") }
        cookies.addPreferences("library_vault", vault) { it.startsWith("cookies_") }
        data.addPreferences("seat_favorites", context.getSharedPreferences("seat_favorites", Context.MODE_PRIVATE))
        data.addPreferences("library_appearance", context.getSharedPreferences("library_appearance", Context.MODE_PRIVATE))
        return data.finish() to cookies.finish()
    }

    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class SaltedPreferenceDigest(salt: ByteArray, namespace: String) {
        private val digest = MessageDigest.getInstance("SHA-256").apply { update(salt) }
        init { add(namespace) }

        fun addPreferences(name: String, preferences: SharedPreferences, include: (String) -> Boolean = { true }) {
            add(name)
            val entries = preferences.all.entries.filter { include(it.key) }.sortedBy { it.key }
            add(entries.size.toString())
            entries.forEach { (key, value) ->
                add(key)
                when (value) {
                    null -> add("null")
                    is String -> { add("string"); add(value) }
                    is Boolean -> { add("boolean"); add(value.toString()) }
                    is Int -> { add("int"); add(value.toString()) }
                    is Long -> { add("long"); add(value.toString()) }
                    is Float -> { add("float"); add(value.toRawBits().toString()) }
                    is Set<*> -> {
                        add("string-set")
                        val values = value.map { requireNotNull(it as? String) }.sorted()
                        add(values.size.toString())
                        values.forEach(::add)
                    }
                    else -> error("Unsupported private preference type")
                }
            }
        }

        private fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }

        fun finish(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
