package cn.edu.bucea.library.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** All actions are serialized; transport and verification are injected for offline tests. */
class UpdateCoordinator(
    private val scope: CoroutineScope,
    private val storage: UpdateStorage,
    private val platform: UpdatePlatform,
    private val fetch: suspend () -> UpdateManifest,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(UpdateUiState(lastAttempt = storage.lastAttempt, lastSuccess = storage.lastSuccess))
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var restored = false
    private var foreground = false
    private var polling: Job? = null

    private fun operation(action: suspend () -> Unit) = scope.launch {
        try { action() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { mutable.update { it.copy(message = "更新操作失败，请稍后重试") } }
    }

    fun onForeground() {
        foreground = true
        operation {
            mutex.withLock {
                restoreLocked()
                refreshLocked()
            }
            if (foreground) check(manual = false)
            if (polling?.isActive != true && foreground) polling = scope.launch {
                while (foreground) {
                    delay(1500)
                    refresh()
                }
            }
        }
    }

    fun onBackground() {
        foreground = false
        polling?.cancel()
        polling = null
    }

    fun check(manual: Boolean) {
        if (state.value.checking || (!manual && !updateAttemptDue(clock(), storage.lastAttempt))) return
        mutable.update { it.copy(checking = true, message = if (manual) "正在检查更新…" else it.message) }
        scope.launch {
            try {
                storage.lastAttempt = clock()
                mutable.update { it.copy(lastAttempt = storage.lastAttempt) }
                val manifest = fetch()
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
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (manual) mutable.update { it.copy(message = "检查更新失败，请稍后重试") }
            } finally {
                mutable.update { it.copy(checking = false) }
            }
        }
    }

    fun showAvailable() { mutable.update { it.copy(promptVisible = it.available != null) } }
    fun showTask() { mutable.update { it.copy(taskVisible = it.task != null) } }
    fun hideTask() { mutable.update { it.copy(taskVisible = false) } }

    fun defer(manifest: UpdateManifest) {
        storage.deferredVersion = manifest.versionCode
        storage.deferredUntil = clock() + UpdateIntervalMillis
        mutable.update { it.copy(promptVisible = false) }
    }

    /** Called only by the consent dialog's 下载更新 button, with its visible snapshot. */
    fun confirmDownload(manifest: UpdateManifest) = operation {
        mutex.withLock {
            restoreLocked()
            if (state.value.task != null || manifest.versionCode <= platform.installedVersion()) return@withLock
            enqueueLocked(manifest)
        }
    }

    fun retry() = operation {
        mutex.withLock {
            val task = state.value.task?.takeIf { it.phase == UpdatePhase.FAILED } ?: return@withLock
            clearLocked(task)
            if (task.manifest.versionCode > platform.installedVersion()) enqueueLocked(task.manifest)
        }
    }

    fun cancel() = operation {
        mutex.withLock {
            val task = state.value.task ?: return@withLock
            clearLocked(task)
            defer(task.manifest)
            mutable.update { it.copy(message = "已取消更新下载") }
        }
    }

    fun refresh() = operation { mutex.withLock { restoreLocked(); refreshLocked() } }

    /** Re-check on every user installation click; never called on download completion. */
    suspend fun validateForInstall(): ConfirmedUpdate? = mutex.withLock {
        val task = state.value.task?.takeIf { it.phase == UpdatePhase.READY } ?: return@withLock null
        if (task.manifest.versionCode <= platform.installedVersion()) {
            clearLocked(task)
            return@withLock null
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

    fun installationMessage(message: String) { mutable.update { it.copy(message = message) } }

    private suspend fun restoreLocked() {
        if (restored) return
        val task = storage.load()
        platform.cleanupOrphans(task?.token)
        mutable.update { it.copy(task = task, taskVisible = false) }
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
        mutable.update { it.copy(promptVisible = false, taskVisible = true, downloadedBytes = 0, totalBytes = manifest.size) }
        try {
            val id = platform.enqueue(manifest, task.token)
            saveLocked(task.copy(downloadId = id, phase = UpdatePhase.DOWNLOADING))
        } catch (error: Exception) {
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
            mutable.update { it.copy(downloadedBytes = download.downloaded, totalBytes = task.manifest.size) }
            when (download.status) {
                DownloadStatus.COMPLETE -> finishLocked(task)
                DownloadStatus.FAILED -> failLocked(task, "下载失败，请重试")
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
        mutable.update { it.copy(task = null, taskVisible = false, downloadedBytes = 0, totalBytes = 0) }
    }

    private fun cleanupLocked(task: ConfirmedUpdate) {
        task.downloadId?.let { runCatching { platform.removeDownload(it) } }
        runCatching { platform.findDownload(task.token)?.let(platform::removeDownload) }
        runCatching { platform.cleanup(task.token) }
    }
}
