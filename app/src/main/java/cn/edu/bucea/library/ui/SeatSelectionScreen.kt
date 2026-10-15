package cn.edu.bucea.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/** The two seat selection modes share a fixed header and retain their own UI state. */
@Composable
fun SeatSelectionScreen(
    layoutSelected: Boolean,
    onSelectLayout: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    refreshEnabled: Boolean = true,
    searchContent: @Composable () -> Unit,
    layoutContent: @Composable () -> Unit,
) {
    val contentState = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxSize().background(Paper)) {
        LibraryPageHeader(
            title = "选座",
            subtitle = "为专注，找一个喜欢的位置。",
            onRefresh = onRefresh,
            refreshDescription = if (layoutSelected) "刷新座位地图" else "刷新座位筛选",
            refreshEnabled = refreshEnabled,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp),
        )
        LibrarySegmentedTabs(
            labels = listOf("自选座位", "布局选座"),
            selectedIndex = if (layoutSelected) 1 else 0,
            onSelect = { index ->
                if (layoutSelected != (index == 1)) {
                    focusManager.clearFocus()
                    onSelectLayout(index == 1)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            enabled = enabled,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            contentState.SaveableStateProvider(if (layoutSelected) "layout" else "search") {
                if (layoutSelected) layoutContent() else searchContent()
            }
        }
    }
}
