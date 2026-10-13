package cn.edu.bucea.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.bucea.library.update.UpdateCoordinator
import cn.edu.bucea.library.update.UpdatePhase
import cn.edu.bucea.library.update.UpdateUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateSettings(state: UpdateUiState, installedVersion: String, updates: UpdateCoordinator) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("当前版本 $installedVersion", style = MaterialTheme.typography.bodyMedium)
        Text("进入前台后每天检查一次，测试版和正式版使用同一更新入口。确认后才下载，支持 Wi-Fi 和移动数据。",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { updates.check(manual = true) }, enabled = !state.checking) {
            Text(if (state.checking) "正在检查…" else "检查更新")
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (state.task != null) {
            Text("${state.task.manifest.displayVersion} · ${phaseLabel(state.task.phase)}", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = updates::showTask) { Text("查看更新任务") }
        } else state.available?.let { manifest ->
            Text("${manifest.displayVersion} · ${formatUpdateSize(manifest.size)}")
            Button(onClick = updates::showAvailable) { Text("查看更新") }
        }
        if (state.lastAttempt > 0) Text("最近尝试：${formatTime(state.lastAttempt)}", style = MaterialTheme.typography.bodySmall)
        if (state.lastSuccess > 0) Text("最近成功：${formatTime(state.lastSuccess)}", style = MaterialTheme.typography.bodySmall)
    }
}

/** Only business-idle, resumed UI may show a queued update prompt. */
@Composable
fun UpdateDialogs(state: UpdateUiState, allowed: Boolean, updates: UpdateCoordinator, onInstall: () -> Unit) {
    if (!allowed) return
    val task = state.task
    if (task != null && state.taskVisible) {
        AlertDialog(onDismissRequest = updates::hideTask,
            title = { Text("更新 ${task.manifest.displayVersion}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(phaseLabel(task.phase))
                    Text(formatUpdateSize(task.manifest.size))
                    when (task.phase) {
                        UpdatePhase.ENQUEUING, UpdatePhase.VERIFYING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        UpdatePhase.DOWNLOADING -> {
                            LinearProgressIndicator(progress = {
                                (state.downloadedBytes.toFloat() / task.manifest.size).coerceIn(0f, 1f)
                            }, modifier = Modifier.fillMaxWidth())
                            Text("${formatUpdateSize(state.downloadedBytes)} / ${formatUpdateSize(task.manifest.size)}")
                        }
                        UpdatePhase.READY -> Text("文件已校验。点击安装后由系统确认；取消安装后可再次尝试。")
                        UpdatePhase.FAILED -> Text(task.error ?: "更新失败，请重试")
                    }
                    state.message?.let { Text(it) }
                    Text(task.manifest.notes, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { updates.cancel() }) { Text(if (task.phase == UpdatePhase.READY) "删除安装文件" else "取消下载") }
                }
            },
            confirmButton = {
                when (task.phase) {
                    UpdatePhase.READY -> TextButton(onClick = onInstall) { Text("安装更新") }
                    UpdatePhase.FAILED -> TextButton(onClick = { updates.retry() }) { Text("重试下载") }
                    else -> TextButton(onClick = updates::hideTask) { Text("放到后台") }
                }
            }, dismissButton = { TextButton(onClick = updates::hideTask) { Text("关闭") } })
    } else if (task == null && state.promptVisible) state.available?.let { manifest ->
        AlertDialog(onDismissRequest = { updates.defer(manifest) },
            title = { Text("发现新版本 ${manifest.displayVersion}") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("下载大小：${formatUpdateSize(manifest.size)}")
                if (manifest.prerelease) Text("这是测试版，可能包含尚在验证的功能。")
                Text(manifest.notes)
                Text("下载可使用 Wi-Fi 和移动数据。稍后将暂停此版本的自动提示 24 小时。", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { updates.confirmDownload(manifest) }) { Text("下载更新") } },
            dismissButton = { TextButton(onClick = { updates.defer(manifest) }) { Text("稍后") } })
    }
}

private fun phaseLabel(phase: UpdatePhase) = when (phase) {
    UpdatePhase.ENQUEUING -> "正在创建下载"
    UpdatePhase.DOWNLOADING -> "正在下载（网络暂停后会自动继续）"
    UpdatePhase.VERIFYING -> "正在校验安装文件"
    UpdatePhase.READY -> "等待安装"
    UpdatePhase.FAILED -> "需要重试"
}

private fun formatUpdateSize(bytes: Long) = String.format(Locale.CHINA, "%.1f MB", bytes.coerceAtLeast(0) / (1024.0 * 1024))
private fun formatTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA).format(Date(time))
