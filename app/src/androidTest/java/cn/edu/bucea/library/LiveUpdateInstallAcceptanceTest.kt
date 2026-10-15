package cn.edu.bucea.library

import android.app.DownloadManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.AppearanceStore
import cn.edu.bucea.library.data.SeatFavoritesStore
import cn.edu.bucea.library.update.AndroidUpdatePlatform
import cn.edu.bucea.library.update.AndroidUpdateStorage
import cn.edu.bucea.library.update.ConfirmedUpdate
import cn.edu.bucea.library.update.DownloadStatus
import cn.edu.bucea.library.update.UpdateManifest
import cn.edu.bucea.library.update.updateApkUrl
import cn.edu.bucea.library.update.UpdatePhase
import kotlinx.coroutines.runBlocking
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

/**
 * Explicit, separately invoked steps for a real APK overwrite/install acceptance run.
 * Pass -e live_update_acceptance true and select one method with -e class Class#method.
 * No account/session plaintext is written to preferences, logs or test results.
 */
@RunWith(AndroidJUnit4::class)
class LiveUpdateInstallAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val acceptance get() = context.getSharedPreferences("update_install_acceptance", Context.MODE_PRIVATE)

    @Before fun requireExplicitAcceptanceRun() {
        assumeTrue("Real update installation acceptance is opt-in",
            InstrumentationRegistry.getArguments().getString("live_update_acceptance") == "true")
    }

    @Test fun snapshotUpgradeData() {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val snapshots = dataDigests(salt)
        assertTrue("The private upgrade snapshot must persist", acceptance.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("data_digest", snapshots.first)
            .putString("cookies_digest", snapshots.second)
            .commit())
    }

    @Test fun assertUpgradeDataPreserved() {
        val encodedSalt = acceptance.getString("salt", null)
        assertNotNull("Take an upgrade snapshot before overwriting the app", encodedSalt)
        val salt = Base64.decode(requireNotNull(encodedSalt), Base64.NO_WRAP)
        assertEquals("The snapshot must contain a full random salt", 32, salt.size)
        val snapshots = dataDigests(salt)
        assertTrue("Accounts, credentials, choices, preferences, sign-out, favorites and appearance must survive overwrite installation",
            acceptance.getString("data_digest", null) == snapshots.first)
        if (InstrumentationRegistry.getArguments().getString("compare_upgrade_cookies") == "true") {
            assertTrue("Saved login sessions must survive overwrite installation",
                acceptance.getString("cookies_digest", null) == snapshots.second)
        }
    }

    /** Seeds only a root-supplied final, full release APK; it never opens an installer. */
    @Suppress("DEPRECATION")
    @Test fun prepareVerifiedInstallerFixture() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val expectedCode = arguments.getString("expected_version_code", "9").toLong()
        val expectedName = arguments.getString("expected_version_name", "0.3.4")
        require(expectedCode in 9L..10L)
        val record = File(context.filesDir, "updates/task.json")
        assertFalse("Do not replace an existing update record", record.exists())
        assertFalse("Do not replace a recoverable update record", File(record.path + ".bak").exists())
        assertFalse("Do not replace an in-progress update record", File(record.path + ".new").exists())
        val storage = AndroidUpdateStorage(context)
        assertNull("A preexisting update task must be handled by the user", storage.load())
        val external = requireNotNull(context.getExternalFilesDir(null))
        val candidate = File(external, "update-acceptance/candidate.apk")
        assertTrue("Push the final full release APK to the acceptance candidate path first", candidate.isFile && candidate.length() > 0)
        val archive = archive(candidate)
        assertEquals("Only this application's real release may be used", context.packageName, archive.packageName)
        assertEquals("Synthetic high-version fixtures must never be offered for installation", expectedCode, versionCode(archive))
        assertEquals("Use the final release's expected display version", expectedName, archive.versionName)
        assertEquals("The acceptance APK must be a non-debuggable release", 0,
            requireNotNull(archive.applicationInfo).flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val platform = AndroidUpdatePlatform(context)
        assertTrue("The real release candidate must be newer than the installed app", platform.installedVersion() < expectedCode)
        val manifest = UpdateManifest(
            packageName = context.packageName,
            versionCode = versionCode(archive),
            versionName = requireNotNull(archive.versionName),
            minSdk = requireNotNull(archive.applicationInfo).minSdkVersion,
            notes = "本地发布验收：最终 release APK。点击安装后由系统确认。",
            assetId = 2_147_483_646L,
            apkName = "BUCEA-Library-Seat-v$expectedName.apk",
            size = candidate.length(),
            sha256 = fileDigest(candidate),
            url = updateApkUrl("v$expectedName", "BUCEA-Library-Seat-v$expectedName.apk"),
            prerelease = false,
            releaseTag = "v$expectedName",
        )
        val task = ConfirmedUpdate(manifest, UUID.randomUUID().toString(), phase = UpdatePhase.DOWNLOADING)
        val directory = File(external, "updates")
        assertTrue("The managed update destination must be available", directory.mkdirs() || directory.isDirectory)
        val download = File(directory, "download-${task.token}.apk")
        assertFalse("The acceptance run must not overwrite any update file", download.exists())
        assertFalse("The acceptance run must not overwrite any verified APK", platform.readyFile(task).exists())
        val manager = context.getSystemService(DownloadManager::class.java)
        var id: Long? = null
        var saved = false
        try {
            candidate.copyTo(download, overwrite = false)
            val created = manager.addCompletedDownload("北建大图书馆选座 $expectedName", "最终 release 安装验收", false,
                "application/vnd.android.package-archive", download.absolutePath, download.length(), true)
            id = created
            assertEquals("Use a real completed Android system download", DownloadStatus.COMPLETE, platform.status(created).status)
            val confirmed = task.copy(downloadId = created)
            platform.verify(confirmed)
            assertEquals("The internal install file must retain the final APK bytes", manifest.sha256, fileDigest(platform.readyFile(confirmed)))
            assertNull("No concurrent update task may be replaced", storage.load())
            assertTrue("Record only the unique acceptance token for later cleanup verification", acceptance.edit()
                .putString("candidate_token", task.token).putString("candidate_digest", manifest.sha256).commit())
            assertTrue("Persist framework-only release readback paths", context.getSharedPreferences(
                "update_release_acceptance", Context.MODE_PRIVATE).edit()
                .putString("candidate_token", task.token)
                .putString("candidate_internal_path", "updates/install/${task.token}.apk")
                .putString("candidate_external_path", "updates/download-${task.token}.apk")
                .putString("candidate_digest", manifest.sha256).commit())
            storage.save(confirmed.copy(phase = UpdatePhase.READY))
            saved = true
            platform.removeDownload(created)
            assertEquals("The unverified system-download entry must be removed", DownloadStatus.MISSING, platform.status(created).status)
            assertEquals("Only the verified internal APK is offered by the application", UpdatePhase.READY, storage.load()?.phase)
        } finally {
            if (!saved) {
                id?.let { manager.remove(it) }
                // Only this method's freshly generated token/files may be removed on failure.
                platform.cleanup(task.token)
            }
        }
    }

    private fun dataDigests(salt: ByteArray): Pair<String, String> {
        val store = AccountStore(context)
        val favorites = SeatFavoritesStore(context)
        val accounts = store.accounts()
        val data = SaltedDigest(salt, "upgrade-data-v1")
        val cookies = SaltedDigest(salt, "upgrade-cookies-v1")
        data.add(accounts.size.toString())
        data.add(store.activeAccountId)
        data.add(store.autoLogin.toString())
        data.add(store.autoSwitch.toString())
        data.add(AppearanceStore(context).themeColor.preferenceValue)
        accounts.forEach { account ->
            data.add(account.id)
            data.add(account.username)
            data.add(account.password)
            data.add(account.label)
            data.add(store.isSignedOut(account.id).toString())
            val seats = favorites.seats(account.id)
            data.add(seats.size.toString())
            seats.forEach { seat -> data.add(seat.id); data.add(seat.label); data.add(seat.room) }
            cookies.add(account.id)
            cookies.add(store.cookies(account.id))
        }
        return data.finish() to cookies.finish()
    }

    private class SaltedDigest(salt: ByteArray, namespace: String) {
        private val digest = MessageDigest.getInstance("SHA-256").apply { update(salt) }
        init { add(namespace) }
        fun add(value: String?) {
            if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return }
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        fun finish(): String = hex(digest.digest())
    }

    @Suppress("DEPRECATION")
    private fun archive(file: File): PackageInfo = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)) {
        "Android must parse the final release APK"
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

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
        return hex(digest.digest())
    }

    private companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
