package cn.edu.bucea.library.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.update.UpdateCoordinator
import cn.edu.bucea.library.update.UpdatePhase
import cn.edu.bucea.library.update.UpdateUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateSettings(state: UpdateUiState, installedVersion: String, updates: UpdateCoordinator) {
    Column(verticalArrangement = Arrangement.spacedBy(LibrarySpacing.itemGap)) {
        Text("当前版本 $installedVersion", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text("无需连接校园网，可随时手动检查新版本。发现更新后，确认才会开始下载。",
            color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        Button(onClick = { updates.check(manual = true) }, enabled = !state.checking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) {
            if (state.checking) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.checking) "正在检查更新…" else "手动检查更新", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        state.message?.takeUnless { state.checking }?.let { message ->
            Surface(color = Paper, shape = RoundedCornerShape(14.dp)) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp), color = Ink, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
        if (state.task != null) {
            Surface(color = Paper, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.task.manifest.displayVersion, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    UpdateTaskProgress(state)
                    UpdateSecondaryAction(when (state.task.phase) {
                        UpdatePhase.READY -> "继续安装更新"
                        UpdatePhase.FAILED -> "查看原因并重试"
                        else -> "查看下载进度"
                    }, updates::showTask, Modifier.fillMaxWidth())
                }
            }
        } else state.available?.let { manifest ->
            Text("${manifest.displayVersion} · ${formatUpdateSize(manifest.size)}", color = Ink, fontSize = 13.sp)
            UpdateSecondaryAction("查看更新", updates::showAvailable, Modifier.fillMaxWidth())
        }
        if (state.lastAttempt > 0) Text("最近尝试：${formatTime(state.lastAttempt)}", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
        if (state.lastSuccess > 0) Text("最近成功：${formatTime(state.lastSuccess)}", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
    }
}

/** Only business-idle, resumed UI may show a queued update prompt. */
@Composable
fun UpdateDialogs(state: UpdateUiState, allowed: Boolean, updates: UpdateCoordinator, onInstall: () -> Unit) {
    if (!allowed) return
    val task = state.task
    if (task != null && state.taskVisible) {
        val busy = state.preparingInstall || state.cancelling
        AlertDialog(onDismissRequest = updates::hideTask,
            shape = RoundedCornerShape(24.dp), containerColor = Color.White, tonalElevation = 0.dp,
            title = { Text("更新 ${task.manifest.displayVersion}", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LibrarySpacing.sectionGap)) {
                    Surface(color = SoftAccent, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("安装包 ${formatUpdateSize(task.manifest.size)}", color = Muted, fontSize = 12.sp)
                            UpdateTaskProgress(state)
                        }
                    }
                    when (task.phase) {
                        UpdatePhase.ENQUEUING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> Text("下载和校验可在后台继续，稍后可在“应用更新”中查看进度。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                        UpdatePhase.READY -> Text("点击安装后将先检查文件，再打开系统安装器。取消系统安装后可再次尝试。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                        UpdatePhase.FAILED -> {
                            Surface(color = MaterialTheme.colorScheme.error.copy(alpha = .07f), shape = RoundedCornerShape(14.dp)) {
                                Text(task.error ?: "更新失败，请重试", Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp)
                            }
                        }
                    }
                    state.message?.let { Text(it, color = Ink, fontSize = 13.sp, lineHeight = 21.sp) }
                    TextButton(onClick = { updates.cancel() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(when {
                            state.cancelling -> "正在取消…"
                            task.phase == UpdatePhase.READY -> "删除安装文件"
                            task.phase == UpdatePhase.FAILED -> "移除更新任务"
                            else -> "取消下载"
                        }, color = if (busy) Muted else MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    Text("更新内容", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(task.manifest.notes, color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                }
            },
            confirmButton = {
                when (task.phase) {
                    UpdatePhase.READY -> UpdatePrimaryAction(if (state.preparingInstall) "正在准备安装…" else "安装更新", onInstall,
                        enabled = !busy, loading = state.preparingInstall)
                    UpdatePhase.FAILED -> UpdatePrimaryAction("重试下载", { updates.retry() }, enabled = !busy)
                    else -> UpdatePrimaryAction("后台继续", updates::hideTask)
                }
            }, dismissButton = {
                if (task.phase == UpdatePhase.READY || task.phase == UpdatePhase.FAILED) {
                    TextButton(onClick = updates::hideTask, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (task.phase == UpdatePhase.READY) "稍后安装" else "关闭", color = Muted, fontSize = 14.sp)
                    }
                }
            })
    } else if (task == null && state.promptVisible) state.available?.let { manifest ->
        AlertDialog(onDismissRequest = { updates.defer(manifest) },
            shape = RoundedCornerShape(24.dp), containerColor = Color.White, tonalElevation = 0.dp,
            title = { Text("发现新版本 ${manifest.displayVersion}", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LibrarySpacing.sectionGap)) {
                Surface(color = Paper, shape = RoundedCornerShape(14.dp)) {
                    Text("下载大小：${formatUpdateSize(manifest.size)}", Modifier.fillMaxWidth().padding(12.dp), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                if (manifest.prerelease) Text("这是测试版，可能包含尚在验证的功能。", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                Text(manifest.notes, color = Ink, fontSize = 14.sp, lineHeight = 23.sp)
                Text("下载可使用 Wi-Fi 和移动数据。稍后将暂停此版本的自动提示 24 小时。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            } },
            confirmButton = { UpdatePrimaryAction("下载更新", { updates.confirmDownload(manifest) }) },
            dismissButton = { TextButton(onClick = { updates.defer(manifest) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("稍后", color = Muted, fontSize = 14.sp) } })
    }
}

@Composable
private fun UpdatePrimaryAction(label: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Muted, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UpdateTaskProgress(state: UpdateUiState) {
    val task = state.task ?: return
    val progress by animateFloatAsState(
        targetValue = (state.downloadedBytes.toFloat() / task.manifest.size).coerceIn(0f, 1f),
        label = "更新下载进度",
    )
    Text(when {
        state.cancelling -> "正在取消更新…"
        state.preparingInstall -> "正在准备安装，请稍候…"
        task.phase == UpdatePhase.DOWNLOADING -> state.downloadMessage ?: phaseLabel(task.phase)
        else -> phaseLabel(task.phase)
    }, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
    if (state.cancelling || state.preparingInstall || task.phase == UpdatePhase.ENQUEUING || task.phase == UpdatePhase.VERIFYING) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
    } else if (task.phase == UpdatePhase.DOWNLOADING) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
        val percent = (state.downloadedBytes * 100 / task.manifest.size).coerceIn(0, 100)
        Text("$percent% · ${formatUpdateSize(state.downloadedBytes)} / ${formatUpdateSize(task.manifest.size)}", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun UpdateSecondaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Line), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent, containerColor = Color.White)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun phaseLabel(phase: UpdatePhase) = when (phase) {
    UpdatePhase.ENQUEUING -> "正在创建下载"
    UpdatePhase.DOWNLOADING -> "正在下载更新"
    UpdatePhase.VERIFYING -> "正在校验安装文件"
    UpdatePhase.READY -> "等待安装"
    UpdatePhase.FAILED -> "需要重试"
}

private fun formatUpdateSize(bytes: Long) = String.format(Locale.CHINA, "%.1f MB", bytes.coerceAtLeast(0) / (1024.0 * 1024))
@Composable
private fun formatTime(time: Long) = remember(time) {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA).format(Date(time))
}
