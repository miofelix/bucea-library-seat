package cn.edu.bucea.library.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Includes dialogs whose selection state is local to a child screen. */
class BusinessInteractions {
    val dialogs = mutableIntStateOf(0)
    val focusedInputs = mutableIntStateOf(0)
}

/** Non-destructive selection and editing use a full-width, keyboard-aware sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessSelectionSheet(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val interactions = LocalBusinessInteractions.current
    DisposableEffect(interactions) {
        interactions?.dialogs?.let { it.intValue++ }
        onDispose { interactions?.dialogs?.let { it.intValue-- } }
    }
    val contentHeight = (LocalConfiguration.current.screenHeightDp.dp * .55f).coerceAtMost(480.dp)
    ModalBottomSheet(onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)
            .imePadding().padding(horizontal = 24.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) icon()
                Box(Modifier.weight(1f)) { ProvideTextStyle(MaterialTheme.typography.titleLarge) { title?.invoke() } }
                IconButton(onClick = onDismissRequest, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, "关闭", tint = Muted, modifier = Modifier.size(21.dp))
                }
            }
            if (text != null) Box(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = contentHeight)
                .verticalScroll(rememberScrollState())) { text() }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

val LocalBusinessInteractions = staticCompositionLocalOf<BusinessInteractions?> { null }

@Composable
fun Modifier.pauseUpdatesWhileFocused(): Modifier {
    val interactions = LocalBusinessInteractions.current
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(interactions, focused) {
        val counted = focused
        if (counted) interactions?.focusedInputs?.let { it.intValue++ }
        onDispose { if (counted) interactions?.focusedInputs?.let { it.intValue-- } }
    }
    return onFocusChanged { focused = it.hasFocus }
}

@Composable
fun BusinessAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val interactions = LocalBusinessInteractions.current
    DisposableEffect(interactions) {
        interactions?.dialogs?.let { it.intValue++ }
        onDispose { interactions?.dialogs?.let { it.intValue-- } }
    }
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        dismissButton = dismissButton, icon = icon, title = title, text = text,
        shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp, titleContentColor = Ink, textContentColor = Muted,
        iconContentColor = Accent)
}
