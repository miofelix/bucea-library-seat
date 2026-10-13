package cn.edu.bucea.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.data.models.NativeAccountUiState

@Composable
fun PasswordChangeDialog(state: NativeAccountUiState, onSubmit: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    // Passwords stay only in this composition; rotation/process state never persists these fields.
    var current by remember(state.accountLabel) { mutableStateOf("") }
    var new by remember(state.accountLabel) { mutableStateOf("") }
    var confirmation by remember(state.accountLabel) { mutableStateOf("") }
    val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.52f).coerceAtMost(430.dp)
    LaunchedEffect(state.passwordChanged, state.outcomeUncertain) {
        if (state.passwordChanged || state.outcomeUncertain) { current = ""; new = ""; confirmation = "" }
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        icon = { Icon(Icons.Outlined.Lock, null) },
        title = { Text("修改图书馆密码") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(state.accountLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.takeIf { it.isNotBlank() }?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
                state.message?.takeIf { it.isNotBlank() && it != state.error }?.let { message ->
                    Text(message, fontSize = 13.sp, lineHeight = 20.sp)
                }
                if (!state.passwordChanged && !state.outcomeUncertain) {
                    PasswordField("当前密码", current, { current = it }, !state.busy)
                    PasswordField("新密码", new, { new = it }, !state.busy)
                    PasswordField("再次输入新密码", confirmation, { confirmation = it }, !state.busy,
                        mismatch = confirmation.isNotEmpty() && confirmation != new)
                    Text("确认后将修改图书馆账号密码，并更新本机保存的登录信息。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp)
                }
                if (state.outcomeUncertain) {
                    Text("此请求不会自动重试。请先确认当前有效密码，再在账号管理中更新保存的信息。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 20.sp)
                }
            }
        },
        confirmButton = {
            if (state.passwordChanged || state.outcomeUncertain) TextButton(onClick = onDismiss, enabled = !state.busy) { Text("返回账号管理") }
            else TextButton(onClick = { onSubmit(current, new, confirmation) }, enabled = !state.busy && current.isNotBlank() && new.isNotBlank() && new == confirmation && current != new) {
                Text(if (state.busy) "正在修改…" else "修改密码")
            }
        },
        dismissButton = {
            if (!state.passwordChanged && !state.outcomeUncertain) TextButton(onClick = onDismiss, enabled = !state.busy) { Text("取消") }
        },
    )
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, mismatch: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = mismatch,
        supportingText = if (mismatch) ({ Text("两次输入的新密码不一致") }) else null,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏$label" else "显示$label")
            }
        },
    )
}

@Composable
fun SignOutDialog(accountLabel: String, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Outlined.Logout, null) },
        title = { Text("退出图书馆登录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(accountLabel, style = MaterialTheme.typography.bodySmall)
                Text("退出后清除该账号的登录会话，并停止自动恢复登录。账号仍保存在本机，可重新登录。", fontSize = 13.sp, lineHeight = 21.sp)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(if (busy) "正在退出…" else "退出登录") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
