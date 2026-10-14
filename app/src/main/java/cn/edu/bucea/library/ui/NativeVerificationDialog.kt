package cn.edu.bucea.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    BusinessAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(color = SoftAccent, shape = CircleShape) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Shield, null, tint = Accent, modifier = Modifier.size(22.dp))
                }
            }
        },
        title = { Text("自动完成学校验证", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
                    Text("正在自动识别并验证，请稍候。", color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
                }
                state.error?.let {
                    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = .07f), shape = RoundedCornerShape(14.dp)) {
                        Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (!state.loading && state.proof == null) Button(onClick = onRefresh,
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) {
                Text("自动重试", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消", color = Muted, fontSize = 14.sp) } },
    )
}
