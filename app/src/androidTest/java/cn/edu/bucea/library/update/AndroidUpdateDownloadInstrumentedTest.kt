package cn.edu.bucea.library.update

import android.app.DownloadManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidUpdateDownloadInstrumentedTest {
    private lateinit var environment: IsolatedUpdateEnvironment

    @Before fun createEnvironment() { environment = IsolatedUpdateEnvironment() }
    @After fun removeEnvironment() { environment.close() }

    @Test fun completedSystemDownloadIsCopiedVerifiedAndPublishedAtomically() = runBlocking {
        val task = environment.task(phase = UpdatePhase.DOWNLOADING)
        val id = environment.completedDownload(task)
        assertEquals(id, environment.platform.findDownload(task.token))
        val snapshot = environment.platform.status(id)
        assertEquals(DownloadStatus.COMPLETE, snapshot.status)
        // Completed-row registration does not represent a network transfer; providers may
        // leave current_bytes at zero. Actual HTTPS progress has its own opt-in test.
        assertTrue(snapshot.downloaded in 0..task.manifest.size)
        val confirmed = task.copy(downloadId = id)
        environment.platform.verify(confirmed)
        assertEquals(task.manifest.sha256, digest(environment.platform.readyFile(confirmed)))
        assertFalse(File(environment.files, "updates/install/${task.token}.part").exists())
        // Installation clicks must repeat verification without needing the external file.
        environment.platform.removeDownload(id)
        assertEquals(DownloadStatus.MISSING, environment.platform.status(id).status)
        environment.platform.verify(confirmed)
    }

    @Test fun interruptedInternalCopyIsReplacedWithVerifiedCompleteDownload() = runBlocking {
        val task = environment.task(phase = UpdatePhase.VERIFYING)
        val scratch = File(environment.files, "updates/install/${task.token}.part")
        scratch.parentFile?.mkdirs()
        scratch.writeText("Interrupted partial APK")
        val confirmed = task.copy(downloadId = environment.completedDownload(task))
        environment.platform.verify(confirmed)
        assertEquals(task.manifest.sha256, digest(environment.platform.readyFile(confirmed)))
        assertFalse(scratch.exists())
    }

    @Test fun oversizedSystemDownloadCannotLeaveInternalInstallableOrPartialFile() = runBlocking {
        val task = environment.task()
        val oversized = File(environment.root, "oversized.apk")
        environment.fixture().copyTo(oversized)
        oversized.appendBytes(byteArrayOf(1))
        val confirmed = task.copy(downloadId = environment.completedDownload(task, oversized))
        val failure = try { environment.platform.verify(confirmed); null } catch (error: IllegalArgumentException) { error }
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("更新文件大小不匹配"))
        assertFalse(environment.platform.readyFile(confirmed).exists())
        assertFalse(File(environment.files, "updates/install/${task.token}.part").exists())
    }

    @Test fun mismatchedDigestDuringSystemCopyCannotPublishAnInstallableFile() = runBlocking {
        val task = environment.task(environment.manifest().copy(sha256 = "0".repeat(64)))
        val confirmed = task.copy(downloadId = environment.completedDownload(task))
        val failure = try { environment.platform.verify(confirmed); null } catch (error: IllegalArgumentException) { error }
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("更新文件摘要校验失败"))
        assertFalse(environment.platform.readyFile(confirmed).exists())
        assertFalse(File(environment.files, "updates/install/${task.token}.part").exists())
    }

    @Test fun orphanCleanupPreservesActiveFilesAndUnrelatedSameBasenameSystemDownload() {
        val active = environment.task()
        val orphan = environment.task()
        val activeId = environment.completedDownload(active)
        val orphanId = environment.completedDownload(orphan)
        val unrelatedId = environment.completedDownload(orphan, directory = File(environment.external, "unrelated"))
        environment.ready(active)
        environment.ready(orphan)
        val activePart = File(environment.files, "updates/install/${active.token}.part").apply { writeText("active-copy") }
        val orphanPart = File(environment.files, "updates/install/${orphan.token}.part").apply { writeText("orphan-copy") }
        assertEquals(orphanId, environment.platform.findDownload(orphan.token))
        environment.platform.cleanupOrphans(active.token)
        assertEquals(DownloadStatus.COMPLETE, environment.platform.status(activeId).status)
        assertEquals(DownloadStatus.MISSING, environment.platform.status(orphanId).status)
        assertEquals("Unrelated matching basename must not be removed", DownloadStatus.COMPLETE, environment.platform.status(unrelatedId).status)
        assertNull("Unrelated matching basename must not be recovered as an update", environment.platform.findDownload(orphan.token))
        assertTrue(environment.platform.readyFile(active).exists())
        assertTrue(activePart.exists())
        assertFalse(environment.platform.readyFile(orphan).exists())
        assertFalse(orphanPart.exists())
    }

    @Test fun taskCleanupDeletesOnlyItsInternalAndExternalFiles() {
        val selected = environment.task()
        val other = environment.task()
        environment.completedDownload(selected)
        environment.completedDownload(other)
        environment.ready(selected)
        environment.ready(other)
        val selectedPart = File(environment.files, "updates/install/${selected.token}.part").apply { writeText("selected") }
        environment.platform.cleanup(selected.token)
        assertFalse(environment.platform.readyFile(selected).exists())
        assertFalse(selectedPart.exists())
        assertFalse(File(environment.external, "updates/download-${selected.token}.apk").exists())
        assertTrue(environment.platform.readyFile(other).exists())
        assertTrue(File(environment.external, "updates/download-${other.token}.apk").exists())
    }

    @Test fun realDownloadRequestIsRecoverableAndCancelableWithoutWaitingForNetwork() {
        val task = environment.task()
        val id = environment.trackDownload(environment.platform.enqueue(task.manifest, task.token))
        try {
            assertEquals(id, environment.platform.findDownload(task.token))
            environment.manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                assertNotNull(cursor)
                assertTrue(requireNotNull(cursor).moveToFirst())
                assertEquals(task.manifest.url, cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
                val destination = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
                assertEquals(File(environment.external, "updates/download-${task.token}.apk").canonicalPath,
                    requireNotNull(destination.path).let { File(it).canonicalPath })
                assertEquals("application/vnd.android.package-archive",
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)))
            }
        } finally {
            environment.platform.removeDownload(id)
            environment.platform.cleanup(task.token)
        }
        assertEquals(DownloadStatus.MISSING, environment.platform.status(id).status)
        assertNull(environment.platform.findDownload(task.token))
    }

    @Test fun repeatedConfirmedClicksCreateOneRealSystemDownloadAndCancelRemovesIt() = runBlocking {
        val manifest = environment.manifest()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = UpdateCoordinator(scope, environment.storage, environment.platform, fetch = { manifest })
            val first = coordinator.confirmDownload(manifest)
            val duplicate = coordinator.confirmDownload(manifest)
            first.join()
            duplicate.join()
            val task = requireNotNull(coordinator.state.value.task)
            val id = environment.trackDownload(requireNotNull(task.downloadId))
            assertEquals(UpdatePhase.DOWNLOADING, task.phase)
            assertEquals(manifest, task.manifest)
            assertEquals(id, environment.platform.findDownload(task.token))
            environment.manager.query(DownloadManager.Query()).use { cursor ->
                var count = 0
                while (requireNotNull(cursor).moveToNext()) {
                    val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    if (updateDownloadToken(uri, File(environment.external, "updates")) != null) count++
                }
                assertEquals("Repeated clicks must create one system download", 1, count)
            }
            coordinator.cancel().join()
            assertNull(coordinator.state.value.task)
            assertNull(AndroidUpdateStorage(environment.context).load())
            assertEquals(DownloadStatus.MISSING, environment.platform.status(id).status)
            assertFalse(environment.platform.readyFile(task).exists())
            assertEquals(manifest.versionCode, environment.storage.deferredVersion)
        } finally { scope.cancel() }
    }

    @Test fun failedRetryStillEnqueuesOriginalConfirmedVersionAfterNewManifestCheck() = runBlocking {
        val original = environment.manifest()
        val newer = original.copy(versionCode = original.versionCode + 1, versionName = "newer-unconfirmed",
            assetId = original.assetId + 1, releaseTag = original.releaseTag + "-newer",
            url = updateApkUrl(original.releaseTag + "-newer", original.apkName))
        val failed = environment.task(original, UpdatePhase.FAILED).copy(error = "Synthetic transport failure")
        environment.storage.save(failed)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = UpdateCoordinator(scope, environment.storage, environment.platform, fetch = { newer })
            coordinator.refresh().join()
            coordinator.check(manual = true)
            kotlinx.coroutines.withTimeout(5_000) {
                while (coordinator.state.value.checking) kotlinx.coroutines.delay(10)
            }
            assertEquals(newer, coordinator.state.value.available)
            coordinator.retry().join()
            val retried = requireNotNull(coordinator.state.value.task)
            val id = environment.trackDownload(requireNotNull(retried.downloadId))
            assertEquals(UpdatePhase.DOWNLOADING, retried.phase)
            assertEquals(original, retried.manifest)
            assertNotEquals(failed.token, retried.token)
            environment.manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                assertTrue(requireNotNull(cursor).moveToFirst())
                assertEquals(original.url, cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
            }
            coordinator.cancel().join()
            assertEquals(DownloadStatus.MISSING, environment.platform.status(id).status)
        } finally { scope.cancel() }
    }

    @Test fun restoredDownloadingAndVerifyingTasksFinishThroughRealAndroidServices() = runBlocking {
        for (phase in listOf(UpdatePhase.ENQUEUING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
            val task = environment.task(phase = phase)
            val id = environment.completedDownload(task)
            val confirmed = if (phase == UpdatePhase.ENQUEUING) task else task.copy(downloadId = id)
            environment.storage.save(confirmed)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val coordinator = UpdateCoordinator(scope, AndroidUpdateStorage(environment.context),
                    AndroidUpdatePlatform(environment.context), fetch = { error("Restoration must not perform a manifest request") })
                coordinator.refresh().join()
                val restored = requireNotNull(coordinator.state.value.task)
                assertEquals(phase.name, UpdatePhase.READY, restored.phase)
                assertEquals(task.manifest, restored.manifest)
                assertFalse("Restoration must not show an install prompt", coordinator.state.value.taskVisible)
                assertEquals(task.manifest.sha256, digest(environment.platform.readyFile(restored)))
                assertEquals(DownloadStatus.MISSING, environment.platform.status(id).status)
                assertEquals(restored, AndroidUpdateStorage(environment.context).load())
                coordinator.cancel().join()
                assertNull(environment.storage.load())
                assertFalse(environment.platform.readyFile(restored).exists())
            } finally { scope.cancel() }
        }
    }

    @Test fun readyTaskIsRevalidatedAfterNewCoordinatorAndTamperBecomesRetryableFailure() = runBlocking {
        val task = environment.task()
        val ready = environment.ready(task)
        environment.storage.save(task)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = UpdateCoordinator(scope, environment.storage, environment.platform,
                fetch = { error("Restoration must not fetch") })
            coordinator.refresh().join()
            assertEquals(UpdatePhase.READY, coordinator.state.value.task?.phase)
            ready.appendText("tampered-after-restoration")
            assertNull(coordinator.validateForInstall())
            assertEquals(UpdatePhase.FAILED, coordinator.state.value.task?.phase)
            assertTrue(coordinator.state.value.task?.error.orEmpty().contains("更新文件大小不匹配"))
            assertFalse(ready.exists())
            assertEquals(UpdatePhase.FAILED, AndroidUpdateStorage(environment.context).load()?.phase)
        } finally { scope.cancel() }
    }

    @Test fun alreadyInstalledTaskIsRemovedUsingActualPackageManagerVersion() = runBlocking {
        val task = environment.task(environment.manifest().copy(versionCode = environment.platform.installedVersion()))
        environment.ready(task)
        environment.storage.save(task)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = UpdateCoordinator(scope, environment.storage, environment.platform,
                fetch = { error("Restoration must not fetch") })
            coordinator.refresh().join()
            assertNull(coordinator.state.value.task)
            assertNull(environment.storage.load())
            assertFalse(environment.platform.readyFile(task).exists())
            assertEquals("更新已安装", coordinator.state.value.message)
        } finally { scope.cancel() }
    }
}
