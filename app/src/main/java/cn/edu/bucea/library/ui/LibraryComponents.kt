package cn.edu.bucea.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared, theme-independent layout language for the 留白 interface. */
@Composable
internal fun LibraryPageHeader(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    eyebrow: String? = "BUCEA / LIBRARY",
    refreshDescription: String = "刷新",
    refreshEnabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) LibraryIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (eyebrow != null && onBack == null) Text(eyebrow, color = Accent, fontSize = 10.sp, letterSpacing = 1.2.sp)
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Ink, modifier = Modifier.semantics { heading() })
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (onRefresh != null) LibraryIconButton(Icons.Outlined.Refresh, refreshDescription, onRefresh, enabled = refreshEnabled)
    }
}

@Composable
internal fun LibraryIconButton(icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(modifier = modifier.size(48.dp), shape = CircleShape, color = Color.White,
        border = BorderStroke(1.dp, Line)) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, description, tint = if (enabled) Accent else Muted, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
internal fun LibrarySectionTitle(title: String, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null, actionEnabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f).semantics { heading() })
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel, fontSize = 12.sp) }
    }
}

@Composable
internal fun LibraryCard(modifier: Modifier = Modifier, highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = Color.White, border = BorderStroke(1.dp, if (highlighted) lerp(Line, Accent, .28f) else Line),
        tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun LibraryEmptyState(title: String, message: String, modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.AutoStories, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(SoftAccent), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(26.dp), tint = Accent)
        }
        Spacer(Modifier.height(2.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ink, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) Button(onClick = onAction,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
    }
}
