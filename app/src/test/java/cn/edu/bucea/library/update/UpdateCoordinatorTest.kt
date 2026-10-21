package cn.edu.bucea.library.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private fun manifest(code: Long = 9, asset: Long = 90, sdk: Int = 26) = UpdateManifest(
    "cn.edu.bucea.library", code, "0.$code", sdk, "说明", asset, "app-$asset.apk", 1000,
    "a".repeat(64), updateApkUrl("v0.$code-beta.1", "app-$asset.apk"), true, "v0.$code-beta.1")

private class MemoryStorage : UpdateStorage {
    override var lastAttempt = 0L
    override var lastSuccess = 0L
    override var deferredVersion = 0L
    override var deferredUntil = 0L
    var record: ConfirmedUpdate? = null
    override fun load() = record
    override fun save(task: ConfirmedUpdate?) { record = task }
}

private class FakePlatform : UpdatePlatform {
    override var sdkInt = 36
    var installed = 8L
    var enqueues = mutableListOf<UpdateManifest>()
    var removed = mutableListOf<Long>()
    var cleaned = mutableListOf<String>()
    var download = DownloadSnapshot(DownloadStatus.RUNNING)
    var existingId: Long? = null
    var verifyError: Exception? = null
    var enqueueError: Exception? = null
    var verified = 0
    var statusQueries = 0
    var verifyAction: suspend () -> Unit = {}
    override fun installedVersion() = installed
    override fun enqueue(manifest: UpdateManifest, token: String): Long {
        enqueueError?.let { throw it }
        enqueues += manifest
        return enqueues.size.toLong()
    }
    override fun findDownload(token: String) = existingId
    override fun status(id: Long): DownloadSnapshot { statusQueries++; return download }
    override fun removeDownload(id: Long) { removed += id }
    override fun cleanup(token: String) { cleaned += token }
    override suspend fun verify(task: ConfirmedUpdate) {
        verified++
        verifyAction()
        verifyError?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {
    @Test fun checkDoesNotDownloadAndConsentEnqueuesExactlyOneSnapshot() = runTest {
        val storage = MemoryStorage()
        val platform = FakePlatform()
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.check(true); runCurrent()
        assertEquals(emptyList<UpdateManifest>(), platform.enqueues)
        assertTrue(updates.state.value.promptVisible)
        updates.confirmDownload(manifest()); updates.confirmDownload(manifest()); runCurrent()
        assertEquals(listOf(manifest()), platform.enqueues)
        assertEquals(manifest(), storage.record?.manifest)
        assertEquals(UpdatePhase.DOWNLOADING, storage.record?.phase)
    }

    @Test fun automaticFailureIsSilentAndAttemptsAreThrottledSeparatelyFromSuccess() = runTest {
        val storage = MemoryStorage()
        var calls = 0
        var now = 10000L
        val updates = UpdateCoordinator(backgroundScope, storage, FakePlatform(), { calls++; throw IOException() }, { now }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        assertEquals(1, calls)
        assertNull(updates.state.value.message)
        assertEquals(10000L, storage.lastAttempt)
        assertEquals(0, storage.lastSuccess)
        updates.onForeground(); runCurrent()
        assertEquals(1, calls)
        updates.check(true); runCurrent()
        assertEquals(2, calls)
        assertNotNull(updates.state.value.message)
        now += UpdateIntervalMillis
        updates.onForeground(); runCurrent()
        assertEquals(3, calls)
    }

    @Test fun deferredVersionWaits24HoursAndManualCheckBypassesDeferral() = runTest {
        val storage = MemoryStorage()
        var now = 10000L
        val updates = UpdateCoordinator(backgroundScope, storage, FakePlatform(), { manifest() }, { now }, StandardTestDispatcher(testScheduler))
        updates.check(true); runCurrent()
        updates.defer(manifest()); runCurrent()
        assertEquals(now + UpdateIntervalMillis, storage.deferredUntil)
        storage.lastAttempt = 0
        updates.check(false); runCurrent()
        assertFalse(updates.state.value.promptVisible)
        updates.check(true); runCurrent()
        assertTrue(updates.state.value.promptVisible)
        updates.defer(manifest()); runCurrent()
        now += UpdateIntervalMillis
        updates.check(false); runCurrent()
        assertTrue(updates.state.value.promptVisible)
    }

    @Test fun newVersionBypassesOldDeferralAndComparisonUsesInstalledVersionCode() = runTest {
        val storage = MemoryStorage().apply { deferredVersion = 9; deferredUntil = Long.MAX_VALUE }
        val platform = FakePlatform()
        var selected = manifest(10)
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { selected }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.check(false); runCurrent()
        assertTrue(updates.state.value.promptVisible)
        platform.installed = 10
        updates.check(true); runCurrent()
        assertNull(updates.state.value.available)
        assertEquals("已是最新版本", updates.state.value.message)
        selected = manifest(11, sdk = 37)
        updates.check(true); runCurrent()
        assertNull(updates.state.value.available)
        assertTrue(updates.state.value.message.orEmpty().contains("37"))
    }

    @Test fun newerManifestCannotRetargetConfirmedDownloadOrRetry() = runTest {
        val storage = MemoryStorage()
        val platform = FakePlatform()
        var selected = manifest()
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { selected }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.check(true); runCurrent()
        updates.confirmDownload(selected); runCurrent()
        selected = manifest(10, asset = 100)
        updates.check(true); runCurrent()
        platform.download = DownloadSnapshot(DownloadStatus.FAILED)
        updates.refresh(); runCurrent()
        assertEquals(UpdatePhase.FAILED, storage.record?.phase)
        updates.retry(); updates.retry(); runCurrent()
        assertEquals(listOf(manifest(), manifest()), platform.enqueues)
    }

    @Test fun restartResumesDownloadAndNeverEnqueuesOrInstalls() = runTest {
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token", 42, UpdatePhase.DOWNLOADING) }
        val platform = FakePlatform().apply { download = DownloadSnapshot(DownloadStatus.COMPLETE, 1000, 1000) }
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        assertEquals(UpdatePhase.READY, storage.record?.phase)
        assertEquals(emptyList<UpdateManifest>(), platform.enqueues)
        assertEquals(1, platform.verified)
        assertFalse(updates.state.value.taskVisible)
        assertTrue(platform.removed.contains(42))
    }

    @Test fun enqueueCrashWindowAdoptsExistingTaskWithoutDownloadingAgain() = runTest {
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token") }
        val platform = FakePlatform().apply { existingId = 42 }
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        assertEquals(42L, storage.record?.downloadId)
        assertEquals(UpdatePhase.DOWNLOADING, storage.record?.phase)
        assertTrue(platform.enqueues.isEmpty())
    }

    @Test fun missingTaskAfterCrashRequiresExplicitRetry() = runTest {
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token") }
        val platform = FakePlatform()
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        assertEquals(UpdatePhase.FAILED, storage.record?.phase)
        assertTrue(platform.enqueues.isEmpty())
        updates.retry(); runCurrent()
        assertEquals(1, platform.enqueues.size)
    }

    @Test fun interruptedVerificationIsResumedAndBadFilesAreCleaned() = runTest {
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token", 42, UpdatePhase.VERIFYING) }
        val platform = FakePlatform().apply { verifyError = IllegalArgumentException("摘要失败") }
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        assertEquals(UpdatePhase.FAILED, storage.record?.phase)
        assertEquals(listOf("token"), platform.cleaned)
        assertTrue(platform.removed.contains(42))
        assertNull(storage.record?.downloadId)
    }

    @Test fun readyFileIsReverifiedAfterRestartAndBeforeEveryInstallClick() = runTest {
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token", 42, UpdatePhase.READY) }
        val platform = FakePlatform()
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        assertEquals(1, platform.verified)
        assertNotNull(updates.validateForInstall())
        assertNotNull(updates.validateForInstall())
        assertEquals(3, platform.verified)
        platform.verifyError = IllegalArgumentException("文件被修改")
        assertNull(updates.validateForInstall())
        assertEquals(UpdatePhase.FAILED, storage.record?.phase)
    }

    @Test fun cancelRemovesDownloadAndTemporaryFilesWithoutEnqueuing() = runTest {
        val storage = MemoryStorage()
        val platform = FakePlatform()
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.confirmDownload(manifest()); runCurrent()
        val token = requireNotNull(storage.record).token
        updates.cancel(); runCurrent()
        assertNull(storage.record)
        assertNull(updates.state.value.task)
        assertEquals(listOf(token), platform.cleaned)
        assertEquals(listOf(1L), platform.removed)
        assertEquals(1, platform.enqueues.size)
    }

    @Test fun onlyActualInstalledVersionClearsReadyTaskAndCancelledInstallerCanRetry() = runTest {
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token", 42, UpdatePhase.READY) }
        val platform = FakePlatform()
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        updates.refresh(); runCurrent()
        assertEquals(UpdatePhase.READY, storage.record?.phase)
        platform.installed = 9
        updates.refresh(); runCurrent()
        assertNull(storage.record)
        assertEquals("更新已安装", updates.state.value.message)
        assertEquals(listOf("token"), platform.cleaned)
    }

    @Test fun concurrentChecksAreCoalescedWhileNetworkRequestIsPending() = runTest {
        var calls = 0
        val response = CompletableDeferred<UpdateManifest>()
        val updates = UpdateCoordinator(backgroundScope, MemoryStorage(), FakePlatform(), { calls++; response.await() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.check(true); updates.check(true); runCurrent()
        assertEquals(1, calls)
        response.complete(manifest()); runCurrent()
        assertFalse(updates.state.value.checking)
    }

    @Test fun insufficientSpaceEnqueueFailureKeepsConfirmedVersionForRetry() = runTest {
        val storage = MemoryStorage()
        val platform = FakePlatform().apply { enqueueError = IllegalArgumentException("存储空间不足") }
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.confirmDownload(manifest()); runCurrent()
        assertEquals(UpdatePhase.FAILED, storage.record?.phase)
        assertEquals(manifest(), storage.record?.manifest)
        assertTrue(platform.enqueues.isEmpty())
        platform.enqueueError = null
        updates.retry(); runCurrent()
        assertEquals(1, platform.enqueues.size)
    }

    @Test fun missingPersistedDownloadIdFailsInsteadOfStalling() = runTest {
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token", phase = UpdatePhase.DOWNLOADING) }
        val updates = UpdateCoordinator(backgroundScope, storage, FakePlatform(), { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        assertEquals(UpdatePhase.FAILED, storage.record?.phase)
        assertNull(storage.record?.downloadId)
    }

    @Test fun attemptPolicyHandlesClockRollbackAndExactDailyBoundary() {
        assertTrue(updateAttemptDue(100, 0))
        assertTrue(updateAttemptDue(100, 101))
        assertFalse(updateAttemptDue(100 + UpdateIntervalMillis - 1, 100))
        assertTrue(updateAttemptDue(100 + UpdateIntervalMillis, 100))
    }

    @Test fun idleForegroundDoesNotPollAndDownloadPollingStopsAfterReady() = runTest {
        val platform = FakePlatform()
        val updates = UpdateCoordinator(backgroundScope, MemoryStorage(), platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        advanceTimeBy(10000); runCurrent()
        assertEquals(0, platform.statusQueries)
        updates.confirmDownload(manifest()); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, platform.statusQueries)
        platform.download = DownloadSnapshot(DownloadStatus.COMPLETE, 1000, 1000)
        advanceTimeBy(1000); runCurrent()
        assertEquals(UpdatePhase.READY, updates.state.value.task?.phase)
        val queries = platform.statusQueries
        advanceTimeBy(10000); runCurrent()
        assertEquals(queries, platform.statusQueries)
    }

    @Test fun cancellationInterruptsRestoredVerificationAndCannotStartAnotherRefresh() = runTest {
        val verification = CompletableDeferred<Unit>()
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token", 42, UpdatePhase.VERIFYING) }
        val platform = FakePlatform().apply { verifyAction = { verification.await() } }
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.onForeground(); runCurrent()
        assertEquals(1, platform.verified)
        updates.refresh(); updates.refresh(); runCurrent()
        assertEquals(1, platform.verified)
        val cancellation = updates.cancel()
        assertTrue(updates.state.value.cancelling)
        updates.refresh(); runCurrent()
        assertTrue(cancellation.isCompleted)
        assertNull(storage.record)
        assertNull(updates.state.value.task)
        assertFalse(updates.state.value.cancelling)
        assertFalse(verification.isCompleted)
        assertEquals(1, platform.verified)
    }

    @Test fun installationVerificationShowsBusyAndRejectsRepeatedClicks() = runTest {
        val storage = MemoryStorage().apply { record = ConfirmedUpdate(manifest(), "token", 42, UpdatePhase.READY) }
        val platform = FakePlatform()
        val updates = UpdateCoordinator(backgroundScope, storage, platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.refresh(); runCurrent()
        val verification = CompletableDeferred<Unit>()
        platform.verifyAction = { verification.await() }
        val install = launch { assertNotNull(updates.validateForInstall()) }
        runCurrent()
        assertTrue(updates.state.value.preparingInstall)
        assertNull(updates.validateForInstall())
        updates.cancel(); runCurrent()
        assertNotNull(updates.state.value.task)
        verification.complete(Unit)
        runCurrent()
        assertTrue(install.isCompleted)
        assertFalse(updates.state.value.preparingInstall)
        assertEquals(2, platform.verified)
    }

    @Test fun pausedNetworkMessageAndBoundedProgressAreExposedWithoutFailingTask() = runTest {
        val platform = FakePlatform().apply {
            download = DownloadSnapshot(DownloadStatus.RUNNING, 1200, 1000, "正在等待网络连接，恢复后自动继续")
        }
        val updates = UpdateCoordinator(backgroundScope, MemoryStorage(), platform, { manifest() }, { 10000 }, StandardTestDispatcher(testScheduler))
        updates.confirmDownload(manifest()); runCurrent()
        updates.refresh(); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, updates.state.value.task?.phase)
        assertEquals(1000L, updates.state.value.downloadedBytes)
        assertEquals("正在等待网络连接，恢复后自动继续", updates.state.value.downloadMessage)
    }
}
