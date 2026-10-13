package cn.edu.bucea.library.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/** Includes dialogs whose selection state is local to a child screen. */
class BusinessInteractions {
    val dialogs = mutableIntStateOf(0)
    val focusedInputs = mutableIntStateOf(0)
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
        dismissButton = dismissButton, icon = icon, title = title, text = text)
}
