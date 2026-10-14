package cn.edu.bucea.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
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
    BusinessAlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        icon = {
            Surface(color = SoftAccent, shape = CircleShape) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Lock, null, tint = Accent, modifier = Modifier.size(22.dp))
                }
            }
        },
        title = { Text("修改图书馆密码", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.accountLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Muted)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
                state.error?.takeIf { it.isNotBlank() }?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = .07f), shape = RoundedCornerShape(14.dp)) {
                        Text(message, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
                state.message?.takeIf { it.isNotBlank() && it != state.error }?.let { message ->
                    Text(message, color = Ink, fontSize = 14.sp, lineHeight = 22.sp)
                }
                if (!state.passwordChanged && !state.outcomeUncertain) {
                    PasswordField("当前密码", current, { current = it }, !state.busy)
                    PasswordField("新密码", new, { new = it }, !state.busy)
                    PasswordField("再次输入新密码", confirmation, { confirmation = it }, !state.busy,
                        mismatch = confirmation.isNotEmpty() && confirmation != new)
                    Text("确认后将修改图书馆账号密码，并更新本机保存的登录信息。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                }
                if (state.outcomeUncertain) {
                    Text("此请求不会自动重试。请先确认当前有效密码，再在账号管理中更新保存的信息。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                }
            }
        },
        confirmButton = {
            if (state.passwordChanged || state.outcomeUncertain) Button(
                onClick = onDismiss, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            ) { Text("返回账号管理", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            else Button(
                onClick = { onSubmit(current, new, confirmation) },
                enabled = !state.busy && current.isNotBlank() && new.isNotBlank() && new == confirmation && current != new,
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            ) {
                Text(if (state.busy) "正在修改…" else "修改密码", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            if (!state.passwordChanged && !state.outcomeUncertain) TextButton(onClick = onDismiss, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消", color = Muted, fontSize = 14.sp) }
        },
    )
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, mismatch: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = mismatch,
        supportingText = if (mismatch) ({ Text("两次输入的新密码不一致") }) else null,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Line,
            focusedLabelColor = Accent, unfocusedLabelColor = Muted,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            disabledContainerColor = Paper,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏$label" else "显示$label", tint = Muted)
            }
        },
    )
}

@Composable
fun SignOutDialog(accountLabel: String, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BusinessAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = {
            Surface(color = SoftAccent, shape = CircleShape) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Logout, null, tint = Accent, modifier = Modifier.size(22.dp))
                }
            }
        },
        title = { Text("退出图书馆登录", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = Paper, shape = RoundedCornerShape(14.dp)) {
                    Text(accountLabel, Modifier.fillMaxWidth().padding(14.dp), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Text("退出后清除该账号的登录会话，并停止自动恢复登录。账号仍保存在本机，可重新登录。", color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) {
                Text(if (busy) "正在退出…" else "退出登录", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消", color = Muted, fontSize = 14.sp) } },
    )
}
