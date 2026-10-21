package cn.edu.bucea.library.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Task mutations are serialized; all storage and platform calls run off the UI thread. */
class UpdateCoordinator(
    private val scope: CoroutineScope,
    private val storage: UpdateStorage,
    private val platform: UpdatePlatform,
    private val fetch: suspend () -> UpdateManifest,
    private val clock: () -> Long = System::currentTimeMillis,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(UpdateUiState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var restored = false
    @Volatile private var foreground = false
    private var polling: Job? = null
    private var refreshing: Job? = null
    private var cancellation: Job? = null

    private fun operation(action: suspend () -> Unit) = scope.launch {
        try { action() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { mutable.update { it.copy(message = "更新操作失败，请稍后重试") } }
    }

    private suspend fun <T> withTaskLock(action: suspend () -> T): T = withContext(workDispatcher) {
        mutex.withLock { action() }
    }

    fun onForeground() {
        foreground = true
        operation {
            refresh().join()
            if (foreground) check(manual = false)
            ensurePolling()
        }
    }

    @Synchronized fun onBackground() {
        foreground = false
        polling?.cancel()
        polling = null
    }

    @Synchronized private fun ensurePolling() {
        if (!foreground || polling?.isActive == true || !state.value.hasActiveDownload) return
        polling = scope.launch {
            while (foreground && state.value.hasActiveDownload) {
                delay(if (state.value.taskVisible) 1000 else 3000)
                refresh().join()
            }
        }
    }

    fun check(manual: Boolean) {
        while (true) {
            val current = mutable.value
            if (current.checking) return
            if (mutable.compareAndSet(current, current.copy(checking = true, message = if (manual) null else current.message))) break
        }
        scope.launch {
            try {
                withContext(workDispatcher) {
                    val shouldCheck = mutex.withLock {
                        if (!manual && !updateAttemptDue(clock(), storage.lastAttempt)) false
                        else {
                            storage.lastAttempt = clock()
                            mutable.update { it.copy(lastAttempt = storage.lastAttempt) }
                            true
                        }
                    }
                    if (!shouldCheck) return@withContext
                    val manifest = fetch()
                    mutex.withLock {
                        storage.lastSuccess = clock()
                        val newer = manifest.versionCode > platform.installedVersion()
                        val compatible = manifest.minSdk <= platform.sdkInt
                        val deferred = storage.deferredVersion == manifest.versionCode && clock() < storage.deferredUntil
                        mutable.update { it.copy(
                            available = manifest.takeIf { newer && compatible },
                            promptVisible = newer && compatible && (manual || !deferred),
                            lastSuccess = storage.lastSuccess,
                            message = if (!manual) it.message else when {
                                !newer -> "已是最新版本"
                                !compatible -> "新版本需要 Android API ${manifest.minSdk} 或更高版本"
                                else -> "发现新版本 ${manifest.displayVersion}"
                            },
                        ) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (manual) mutable.update { it.copy(message = "检查更新失败，请检查网络后重试") }
            } finally {
                mutable.update { it.copy(checking = false) }
            }
        }
    }

    fun showAvailable() { mutable.update { it.copy(promptVisible = it.available != null) } }
    fun showTask() { mutable.update { it.copy(taskVisible = it.task != null) } }
    fun hideTask() { mutable.update { it.copy(taskVisible = false) } }

    fun defer(manifest: UpdateManifest) {
        mutable.update { it.copy(promptVisible = false) }
        operation { withTaskLock { deferLocked(manifest) } }
    }

    private fun deferLocked(manifest: UpdateManifest) {
        storage.deferredVersion = manifest.versionCode
        storage.deferredUntil = clock() + UpdateIntervalMillis
        mutable.update { it.copy(promptVisible = false) }
    }

    /** Called only by the consent dialog's 下载更新 button, with its visible snapshot. */
    fun confirmDownload(manifest: UpdateManifest) = operation {
        withTaskLock {
            restoreLocked()
            if (state.value.task != null || manifest.versionCode <= platform.installedVersion()) return@withTaskLock
            enqueueLocked(manifest)
        }
        ensurePolling()
    }

    fun retry() = operation {
        withTaskLock {
            if (state.value.cancelling || state.value.preparingInstall) return@withTaskLock
            val task = state.value.task?.takeIf { it.phase == UpdatePhase.FAILED } ?: return@withTaskLock
            clearLocked(task)
            if (task.manifest.versionCode > platform.installedVersion()) enqueueLocked(task.manifest)
        }
        ensurePolling()
    }

    @Synchronized fun cancel(): Job {
        while (true) {
            val current = mutable.value
            if (current.cancelling || current.preparingInstall || current.task == null) {
                return cancellation?.takeIf { it.isActive } ?: Job().apply { complete() }
            }
            if (mutable.compareAndSet(current, current.copy(cancelling = true, message = null))) break
        }
        return operation {
            try {
                // Stop a progress refresh, including cancellable APK verification, before cleanup.
                refreshing?.cancelAndJoin()
                withTaskLock {
                    val task = state.value.task ?: return@withTaskLock
                    clearLocked(task)
                    deferLocked(task.manifest)
                    mutable.update { it.copy(message = if (task.phase == UpdatePhase.READY) "已删除安装文件" else "已取消更新下载") }
                }
            } finally {
                mutable.update { it.copy(cancelling = false) }
            }
        }.also { cancellation = it }
    }

    /** Reuse an in-flight refresh instead of accumulating jobs behind a long verification. */
    @Synchronized fun refresh(): Job {
        if (state.value.cancelling) return Job().apply { complete() }
        return refreshing?.takeIf { it.isActive } ?: operation {
            withTaskLock { restoreLocked(); refreshLocked() }
        }.also { refreshing = it }
    }

    /** Re-check on every user installation click; never called on download completion. */
    suspend fun validateForInstall(): ConfirmedUpdate? {
        while (true) {
            val current = mutable.value
            if (current.preparingInstall || current.cancelling || current.task?.phase != UpdatePhase.READY) return null
            if (mutable.compareAndSet(current, current.copy(preparingInstall = true, message = null))) break
        }
        return try {
            withTaskLock {
                val task = state.value.task?.takeIf { it.phase == UpdatePhase.READY } ?: return@withTaskLock null
                if (task.manifest.versionCode <= platform.installedVersion()) {
                    clearLocked(task)
                    return@withTaskLock null
                }
                try {
                    platform.verify(task)
                    task
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    failLocked(task, error.message ?: "安装文件校验失败，请重新下载")
                    null
                }
            }
        } finally {
            mutable.update { it.copy(preparingInstall = false) }
        }
    }

    fun installationMessage(message: String) { mutable.update { it.copy(message = message) } }

    private suspend fun restoreLocked() {
        if (restored) return
        val task = storage.load()
        platform.cleanupOrphans(task?.token)
        mutable.update { it.copy(task = task, taskVisible = false,
            lastAttempt = storage.lastAttempt, lastSuccess = storage.lastSuccess) }
        restored = true
        if (task?.phase == UpdatePhase.ENQUEUING) {
            // Recover the crash window between DownloadManager.enqueue and saving its ID.
            val id = platform.findDownload(task.token)
            if (id != null) saveLocked(task.copy(downloadId = id, phase = UpdatePhase.DOWNLOADING))
            else failLocked(task, "下载任务未创建，请点击重试")
        } else if (task?.phase == UpdatePhase.READY && task.manifest.versionCode > platform.installedVersion()) {
            try { platform.verify(task) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failLocked(task, error.message ?: "安装文件已丢失，请重新下载") }
        } else if (task?.phase == UpdatePhase.FAILED) {
            cleanupLocked(task)
        }
    }

    private suspend fun enqueueLocked(manifest: UpdateManifest) {
        val task = ConfirmedUpdate(manifest, UUID.randomUUID().toString())
        // Persist consent before contacting DownloadManager. Restart recovery never enqueues.
        saveLocked(task)
        mutable.update { it.copy(promptVisible = false, taskVisible = true, downloadedBytes = 0,
            totalBytes = manifest.size, downloadMessage = null, message = null) }
        try {
            val id = platform.enqueue(manifest, task.token)
            saveLocked(task.copy(downloadId = id, phase = UpdatePhase.DOWNLOADING))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            failLocked(task, error.message ?: "无法开始下载，请重试")
        }
    }

    private suspend fun refreshLocked() {
        val task = state.value.task ?: return
        if (platform.installedVersion() >= task.manifest.versionCode) {
            clearLocked(task)
            mutable.update { it.copy(available = null, promptVisible = false, message = "更新已安装") }
            return
        }
        if (task.phase == UpdatePhase.FAILED) return
        try {
            if (task.phase == UpdatePhase.READY || task.phase == UpdatePhase.VERIFYING) {
                // Restarts must validate internal files again, including a copy interrupted
                // before its atomic rename. This never launches an installer.
                if (task.phase == UpdatePhase.VERIFYING) finishLocked(task)
                return
            }
            val id = task.downloadId ?: run { failLocked(task, "下载任务已丢失，请重试"); return }
            val download = platform.status(id)
            mutable.update { it.copy(downloadedBytes = download.downloaded.coerceIn(0, task.manifest.size),
                totalBytes = task.manifest.size, downloadMessage = download.message) }
            when (download.status) {
                DownloadStatus.COMPLETE -> finishLocked(task)
                DownloadStatus.FAILED -> failLocked(task, download.message ?: "下载失败，请重试")
                DownloadStatus.MISSING -> failLocked(task, "下载任务已丢失，请重试")
                DownloadStatus.RUNNING -> Unit
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failLocked(task, error.message ?: "下载文件校验失败，请重新下载") }
    }

    private suspend fun finishLocked(task: ConfirmedUpdate) {
        saveLocked(task.copy(phase = UpdatePhase.VERIFYING))
        platform.verify(task)
        saveLocked(task.copy(phase = UpdatePhase.READY))
        task.downloadId?.let { runCatching { platform.removeDownload(it) } }
        // The verified internal APK is kept until cancellation or actual installation.
    }

    private fun saveLocked(task: ConfirmedUpdate) {
        storage.save(task)
        mutable.update { it.copy(task = task) }
    }

    private fun failLocked(task: ConfirmedUpdate, message: String) {
        cleanupLocked(task)
        saveLocked(task.copy(downloadId = null, phase = UpdatePhase.FAILED, error = message))
    }

    private fun clearLocked(task: ConfirmedUpdate) {
        cleanupLocked(task)
        storage.save(null)
        mutable.update { it.copy(task = null, taskVisible = false, downloadedBytes = 0, totalBytes = 0, downloadMessage = null) }
    }

    private fun cleanupLocked(task: ConfirmedUpdate) {
        task.downloadId?.let { runCatching { platform.removeDownload(it) } }
        runCatching { platform.findDownload(task.token)?.let(platform::removeDownload) }
        runCatching { platform.cleanup(task.token) }
    }
}

private val UpdateUiState.hasActiveDownload: Boolean
    get() = !cancelling && when (task?.phase) {
        UpdatePhase.ENQUEUING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> true
        else -> false
    }
