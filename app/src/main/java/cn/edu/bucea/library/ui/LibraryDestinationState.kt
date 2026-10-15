package cn.edu.bucea.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/** Keep each destination's reading position, with a fresh scope when the signed-in identity changes. */
@Composable
internal fun LibraryDestinationState(
    destination: Int,
    accountId: String?,
    authenticated: Boolean,
    content: @Composable () -> Unit,
) {
    key(accountId, authenticated) {
        val destinations = rememberSaveableStateHolder()
        // Leaving accounts closes an unfinished editor, including its in-memory credentials.
        if (destination == 3) content()
        else destinations.SaveableStateProvider(destination, content)
    }
}
