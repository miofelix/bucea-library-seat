package cn.edu.bucea.library.update

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private class UpdateRuntime(application: Application) {
    val platform = AndroidUpdatePlatform(application)
    private val client = UpdateHttpClient(application.packageName)
    val coordinator = UpdateCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        AndroidUpdateStorage(application), platform, client::fetch)

    init {
        ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE &&
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == coordinator.state.value.task?.downloadId) {
                    coordinator.refresh()
                }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    companion object {
        @Volatile private var instance: UpdateRuntime? = null
        fun get(application: Application): UpdateRuntime = instance ?: synchronized(this) {
            instance ?: UpdateRuntime(application).also { instance = it }
        }
    }
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = UpdateRuntime.get(application)
    val coordinator = runtime.coordinator
    val state = coordinator.state
    @Suppress("DEPRECATION")
    val installedVersionName: String = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
    private var installing = false

    /** Every permission/settings/installer launch begins with an explicit user click. */
    fun install(activity: ComponentActivity) {
        if (installing || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        installing = true
        viewModelScope.launch {
            try {
                val task = coordinator.validateForInstall() ?: return@launch
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    coordinator.installationMessage("请允许此应用安装更新，返回后再次点击“安装更新”")
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    return@launch
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", runtime.platform.readyFile(task))
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("应用更新", uri) })
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                coordinator.installationMessage("无法打开安装器，请检查安装授权后重试")
            } finally {
                installing = false
            }
        }
    }
}
