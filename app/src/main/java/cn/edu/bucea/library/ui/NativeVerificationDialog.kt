package cn.edu.bucea.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.bucea.library.data.models.NativeVerificationState
import cn.edu.bucea.library.data.models.VerificationProof

/** All challenge recognition and ordered points come from local ddddocr models. */
@Composable
fun NativeVerificationDialog(
    state: NativeVerificationState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onVerified: (VerificationProof) -> Unit,
) {
    if (state.context == null) return
    LaunchedEffect(state.proof) { state.proof?.let(onVerified) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动完成学校验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在自动识别并验证，请稍候。", style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { if (!state.loading && state.proof == null) TextButton(onClick = onRefresh) { Text("自动重试") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
