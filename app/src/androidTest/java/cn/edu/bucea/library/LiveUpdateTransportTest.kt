package cn.edu.bucea.library

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicitly staged, opt-in real HTTPS DownloadManager acceptance. Root controls network
 * changes and force-stop between methods. This does not launch MainActivity or write an
 * updater consent record, account vault, session or school transaction.
 */
@RunWith(AndroidJUnit4::class)
class LiveUpdateTransportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val manager: DownloadManager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences(PreferenceName, Context.MODE_PRIVATE)
    private val directory: File get() = File(requireNotNull(context.getExternalFilesDir(null)), "update-transport")

    @Before fun requireExplicitOptIn() {
        assumeTrue("Real HTTPS transfer needs live_update_transport=true",
            InstrumentationRegistry.getArguments().getString("live_update_transport") == "true")
    }

    @Test fun enqueueTransfer() {
        removeTrackedFixture()
        check(directory.mkdirs() || directory.isDirectory)
        val token = UUID.randomUUID().toString()
        val name = "download-$token.apk"
        // Persist the unique fixture name first so cleanup remains scoped after a failure.
        check(preferences.edit().putString("token", token).putInt("enqueued_pid", Process.myPid()).commit())
        val id = manager.enqueue(DownloadManager.Request(Uri.parse(ReleaseUrl))
            .setTitle("Synthetic HTTPS update transfer")
            .setDescription("Instrumentation network and process restoration test")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "update-transport", name))
        check(preferences.edit().putLong("download_id", id).commit())
        assertTrue("DownloadManager must return a tracked download ID", id > 0)
        val snapshot = query(id)
        assertNotNull("Queued system download must exist", snapshot)
        assertTrue("The queued fixture must use its private destination", snapshot?.name == name)
        assertEquals("application/vnd.android.package-archive", snapshot?.mime)
        logSnapshot("enqueued", requireNotNull(snapshot))
    }

    @Test fun assertQueuedOrPaused() {
        val id = trackedId()
        val before = query(id)
        assertNotNull("Tracked row must survive the offline interval", before)
        val snapshot = requireNotNull(before)
        assertTrue("Expected pending, running or paused while network is disabled; status=${snapshot.status}",
            snapshot.status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED))
        assertEquals("Disconnect must not create another download ID", id, trackedId())
        assertEquals("download-${trackedToken()}.apk", snapshot.name)
        logSnapshot("offline", snapshot)
    }

    @Test fun assertTransferSurvivesProcessRestart() {
        val id = trackedId()
        assertNotEquals("Acceptance must run in a process different from enqueueTransfer",
            preferences.getInt("enqueued_pid", -1), Process.myPid())
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var snapshot: Snapshot
        while (true) {
            snapshot = requireNotNull(query(id)) { "Tracked system download disappeared after process restart" }
            assertNotEquals("Download failed after network reconnect; reason=${snapshot.reason}",
                DownloadManager.STATUS_FAILED, snapshot.status)
            if (snapshot.status == DownloadManager.STATUS_SUCCESSFUL) break
            if (SystemClock.elapsedRealtime() >= deadline) {
                logSnapshot("bounded-wait", snapshot)
                fail("Download has not completed within 60 seconds; status=${snapshot.status}, reason=${snapshot.reason}, bytes=${snapshot.bytes}/${snapshot.total}. Repeat this stage after the system retry.")
            }
            SystemClock.sleep(200)
        }
        assertEquals("The same system row must finish after process restart", id, trackedId())
        assertEquals("download-${trackedToken()}.apk", snapshot.name)
        assertTrue("Completed transfer must contain bytes", snapshot.bytes > 0)
        assertEquals(snapshot.total, snapshot.bytes)
        val sha256 = MessageDigest.getInstance("SHA-256")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha256.update(buffer, 0, count)
            }
        }
        val actual = sha256.digest().joinToString("") { "%02x".format(it) }
        assertEquals("Actual HTTPS APK bytes must match the published old-release digest", ReleaseDigest, actual)
        logSnapshot("verified-after-restart", snapshot)
        Log.i(LogTag, "Published APK SHA-256 matched")
    }

    @Test fun cleanup() {
        val id = preferences.getLong("download_id", -1)
        removeTrackedFixture()
        if (id > 0) assertNull("Cleanup must remove only the tracked transfer row", query(id))
        assertTrue(preferences.all.isEmpty())
        Log.i(LogTag, "Tracked transport fixture cleaned")
    }

    private fun trackedId(): Long = preferences.getLong("download_id", -1).also {
        require(it > 0) { "Run enqueueTransfer with explicit transport opt-in first" }
    }

    private fun trackedToken(): String = requireNotNull(preferences.getString("token", null)).also {
        require(UUID.fromString(it).toString() == it) { "Invalid transport fixture token" }
    }

    private fun removeTrackedFixture() {
        val token = preferences.getString("token", null)
        // Refuse unexpected state instead of constructing a path from arbitrary values.
        val validToken = token?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        val id = preferences.getLong("download_id", -1)
        if (id > 0 && validToken != null) {
            val snapshot = query(id)
            require(snapshot == null || snapshot.name == "download-$validToken.apk") {
                "Tracked ID no longer identifies the transport fixture; refusing cleanup"
            }
            manager.remove(id)
        }
        validToken?.let { File(directory, "download-$it.apk").delete() }
        check(preferences.edit().clear().commit())
    }

    private fun query(id: Long): Snapshot? = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty())
        val path = uri.path?.let(::File)
        require(uri.scheme == "file" && path?.canonicalFile?.parentFile == directory.canonicalFile) {
            "Transport download must remain in its isolated fixture directory"
        }
        Snapshot(
            status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            name = requireNotNull(path).name,
            mime = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)),
        )
    }

    private fun logSnapshot(stage: String, snapshot: Snapshot) {
        Log.i(LogTag, "$stage status=${snapshot.status} reason=${snapshot.reason} bytes=${snapshot.bytes}/${snapshot.total}")
    }

    private data class Snapshot(val status: Int, val reason: Int, val bytes: Long, val total: Long,
        val name: String, val mime: String?)

    companion object {
        private const val PreferenceName = "instrumentation_live_update_transport"
        private const val LogTag = "UpdateTransportTest"
        private const val ReleaseUrl = "https://github.com/miofelix/bucea-library-seat/releases/download/v0.3.3-beta.1/BUCEA-Library-Seat-v0.3.3-beta.1.apk"
        private const val ReleaseDigest = "67c90dc11effc60329cbbdf3e4941e21b3b1608c1961505bb90ddbd02c733aab"
    }
}
