package cn.edu.bucea.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private data class LicenseDocument(
    val title: String,
    val detail: String,
    val assetPath: String,
)

private val licenseDocuments = listOf(
    LicenseDocument("本项目 · MIT", "© 2026 Yu Fan（樊彧）", "LICENSE.txt"),
    LicenseDocument("第三方组件说明", "组件来源、授权范围与版权声明", "licenses/THIRD_PARTY_NOTICES.txt"),
    LicenseDocument("AndroidX 与 Jetpack Compose", "Apache License 2.0", "licenses/AndroidX-LICENSE.txt"),
    LicenseDocument("Kotlin", "Apache License 2.0", "licenses/Kotlin-LICENSE.txt"),
    LicenseDocument("Kotlin Coroutines", "Apache License 2.0", "licenses/Kotlin-Coroutines-LICENSE.txt"),
    LicenseDocument("OkHttp", "Apache License 2.0", "licenses/OkHttp-LICENSE.txt"),
    LicenseDocument("Okio", "Apache License 2.0", "licenses/Okio-LICENSE.txt"),
    LicenseDocument("jsoup", "MIT · Jonathan Hedley", "licenses/jsoup-MIT.txt"),
    LicenseDocument("ONNX Runtime", "MIT · Microsoft Corporation", "licenses/ONNXRuntime-MIT.txt"),
    LicenseDocument("ONNX Runtime 第三方声明", "上游随附的第三方组件许可", "licenses/ONNXRuntime-ThirdPartyNotices.txt"),
    LicenseDocument("ddddocr 模型与字符集", "MIT · sml2h3", "ocr/LICENSE.ddddocr.txt"),
    LicenseDocument("Public Suffix List 声明", "OkHttp 所使用的公共后缀列表", "licenses/Public-Suffix-List-NOTICE.txt"),
    LicenseDocument("Mozilla Public License 2.0", "Public Suffix List 的完整许可证", "licenses/MPL-2.0.txt"),
)

/** All license documents are packaged with the app and can be read without a network connection. */
@Composable
internal fun OpenSourceLicensesSheet(onDismiss: () -> Unit) {
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = licenseDocuments.firstOrNull { it.assetPath == selectedPath }
    BusinessSelectionSheet(
        onDismissRequest = onDismiss,
        title = { Text(selected?.title ?: "开源许可") },
        scrollContent = false,
        text = {
            key(selectedPath) {
                if (selected == null) {
                    LazyColumn {
                        item {
                            Text("北建大图书馆选座由 Yu Fan（樊彧）开发，原创代码采用 MIT 许可。感谢以下开源项目。",
                                style = MaterialTheme.typography.bodyMedium, color = Muted)
                            Text("全部许可均可离线阅读。", style = MaterialTheme.typography.bodySmall,
                                color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                        }
                        itemsIndexed(licenseDocuments) { index, document ->
                            if (index > 0) HorizontalDivider(color = Line)
                            LicenseDocumentEntry(document) { selectedPath = document.assetPath }
                        }
                    }
                } else {
                    LicenseDocumentText(selected)
                }
            }
        },
        dismissButton = if (selected != null) {
            { TextButton(onClick = { selectedPath = null }) { Text("返回列表") } }
        } else null,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun LicenseDocumentEntry(document: LicenseDocument, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 64.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(document.title, style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(document.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun LicenseDocumentText(document: LicenseDocument) {
    val assets = LocalContext.current.assets
    val paragraphs by produceState<List<String>?>(initialValue = null, document.assetPath, assets) {
        value = withContext(Dispatchers.IO) {
            try {
                assets.open(document.assetPath).bufferedReader(Charsets.UTF_8).use {
                    it.readText().trim().split(Regex("\\r?\\n\\s*\\r?\\n"))
                }
            } catch (_: IOException) {
                listOf("暂时无法读取许可文件，请重新打开。")
            }
        }
    }
    SelectionContainer {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(paragraphs ?: listOf("正在读取…")) { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodySmall, color = Ink)
            }
        }
    }
}
