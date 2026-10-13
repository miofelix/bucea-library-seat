package cn.edu.bucea.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HubSection { RESERVATIONS, FAVORITES, VIOLATIONS }

data class HubRecord(val id: String, val title: String, val status: String, val details: List<String>, val canCancel: Boolean = false)
data class HubFavorite(val id: String, val name: String, val location: String, val local: Boolean = false)
data class HubAction(val id: String, val label: String, val description: String, val destructive: Boolean = false)
data class LibraryHubState(
    val loading: Boolean = false,
    val error: String? = null,
    val section: HubSection = HubSection.RESERVATIONS,
    val records: List<HubRecord> = emptyList(),
    val favorites: List<HubFavorite> = emptyList(),
    val currentStatus: String = "",
    val currentDetails: List<String> = emptyList(),
    val actions: List<HubAction> = emptyList(),
    val hasMore: Boolean = false,
    val notice: String? = null,
    val selectedRecord: HubRecord? = null,
    val detailsLoading: Boolean = false,
    val detailsError: String? = null,
)

private val HubInk = Color(0xFF183334)
private val HubTeal = Color(0xFF28766C)
private val HubMuted = Color(0xFF778583)
private val HubSoft = Color(0xFFE5EFE8)
private val HubLine = Color(0xFFE2E8E1)

/** Account-specific records, details and actions rendered entirely in Compose. */
@Composable
fun LibraryHubScreen(
    state: LibraryHubState,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onSection: (HubSection) -> Unit,
    onLoadMore: () -> Unit,
    onSearch: () -> Unit,
    onOpenMap: () -> Unit,
    onFavorite: (HubFavorite) -> Unit,
    onRemoveFavorite: (HubFavorite) -> Unit,
    onAction: (HubAction) -> Unit,
    onCancelRecord: (HubRecord) -> Unit,
    onOpenRecord: (HubRecord) -> Unit,
    onDismissRecord: () -> Unit,
) {
    var confirmingAction by remember(state.currentStatus, state.currentDetails) { mutableStateOf<HubAction?>(null) }
    var removingFavorite by remember(state.section) { mutableStateOf<HubFavorite?>(null) }
    var cancellingRecord by remember(state.section) { mutableStateOf<HubRecord?>(null) }
    val maximumDialogHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.45f).coerceAtMost(360.dp)
    LaunchedEffect(state.actions, state.favorites, state.records) {
        if (confirmingAction?.id?.let { id -> state.actions.none { it.id == id } } == true) confirmingAction = null
        if (removingFavorite?.id?.let { id -> state.favorites.none { it.id == id } } == true) removingFavorite = null
        if (cancellingRecord?.id?.let { id -> state.records.none { it.id == id && it.canCancel } } == true) cancellingRecord = null
    }
    BackHandler(enabled = state.selectedRecord != null || cancellingRecord != null || confirmingAction != null || removingFavorite != null) {
        when {
            cancellingRecord != null -> cancellingRecord = null
            confirmingAction != null && !state.loading -> confirmingAction = null
            removingFavorite != null && !state.loading -> removingFavorite = null
            state.selectedRecord != null -> onDismissRecord()
        }
    }
    val sectionTitle = when (state.section) {
        HubSection.RESERVATIONS -> "我的预约"
        HubSection.FAVORITES -> "常用座位"
        HubSection.VIOLATIONS -> "违约记录"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的图书馆", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = HubInk)
                    Text("座位与预约，一目了然。", color = HubMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                }
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Outlined.Refresh, "刷新预约与座位", tint = HubTeal)
                }
            }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = HubTeal) }
        state.error?.let { error ->
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0EA))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Info, null, tint = Color(0xFF945344), modifier = Modifier.size(19.dp))
                            Text("暂时无法同步", color = Color(0xFF945344), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(error, color = HubInk, style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !state.loading, onClick = onRefresh) { Text("重试") }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = HubInk)) {
                Column(Modifier.fillMaxWidth().padding(21.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CalendarMonth, null, tint = Color(0xFFD4B87D), modifier = Modifier.size(21.dp))
                        Text("当前预约", color = Color.White.copy(alpha = .75f), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(currentStatusLabel(state.currentStatus), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    state.currentDetails.filter { it.isNotBlank() }.forEach { detail ->
                        Text(detail, color = Color.White.copy(alpha = .82f), fontSize = 13.sp, lineHeight = 21.sp)
                    }
                    if (state.actions.isNotEmpty()) {
                        HorizontalDivider(color = Color.White.copy(alpha = .15f))
                        state.actions.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { action ->
                                    OutlinedButton(
                                        onClick = { confirmingAction = action },
                                        enabled = !state.loading,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = .28f)),
                                    ) { Text(action.label, color = Color.White, fontSize = 13.sp) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (state.currentStatus.isBlank() && !state.loading) {
                        TextButton(onClick = onRefresh) { Text("同步当前预约", color = Color(0xFFD4B87D)) }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HubShortcut("查找座位", "按日期与房间筛选", Icons.Outlined.Search, !state.loading, Modifier.weight(1f), onSearch)
                HubShortcut("布局选座", "查看房间座位地图", Icons.Outlined.GridView, !state.loading, Modifier.weight(1f), onOpenMap)
            }
        }
        state.notice?.takeIf { it.isNotBlank() }?.let { notice ->
            item {
                Surface(color = HubSoft, shape = RoundedCornerShape(14.dp)) {
                    Text(notice, Modifier.fillMaxWidth().padding(14.dp), color = HubTeal, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
        }
        item {
            ScrollableTabRow(
                selectedTabIndex = state.section.ordinal,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                contentColor = HubTeal,
                divider = { HorizontalDivider(color = HubLine) },
            ) {
                HubSection.entries.forEach { section ->
                    Tab(
                        selected = state.section == section,
                        onClick = { onSection(section) },
                        enabled = !state.loading,
                        text = { Text(when (section) {
                            HubSection.RESERVATIONS -> "我的预约"
                            HubSection.FAVORITES -> "常用座位"
                            HubSection.VIOLATIONS -> "违约记录"
                        }, fontWeight = FontWeight.SemiBold) },
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(sectionTitle, fontWeight = FontWeight.SemiBold, color = HubInk, modifier = Modifier.weight(1f))
            }
        }
        if (state.section == HubSection.FAVORITES) {
            items(state.favorites, key = { "favorite_${it.id}" }) { favorite ->
                FavoriteCard(favorite, state.loading, { onFavorite(favorite) }, { removingFavorite = favorite })
            }
        } else {
            items(state.records, key = { "record_${it.id}" }) { record -> HubRecordCard(record, !state.loading) { onOpenRecord(record) } }
        }
        val empty = if (state.section == HubSection.FAVORITES) state.favorites.isEmpty() else state.records.isEmpty()
        if (empty && !state.loading && state.error == null) item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(when (state.section) {
                        HubSection.RESERVATIONS -> Icons.Outlined.CalendarMonth
                        HubSection.FAVORITES -> Icons.Outlined.BookmarkBorder
                        HubSection.VIOLATIONS -> Icons.Outlined.WarningAmber
                    }, null, tint = HubTeal, modifier = Modifier.size(30.dp))
                    Text(when (state.section) {
                        HubSection.RESERVATIONS -> "暂无预约记录"
                        HubSection.FAVORITES -> "还没有常用座位"
                        HubSection.VIOLATIONS -> "暂无违约记录"
                    }, color = HubInk, fontWeight = FontWeight.Medium)
                    Text(when (state.section) {
                        HubSection.RESERVATIONS -> "找到合适的座位，开始一次专注时光。"
                        HubSection.FAVORITES -> "在选座页面收藏喜欢的座位，下次更快找到。"
                        HubSection.VIOLATIONS -> "按时签到，结束使用后记得释放座位。"
                    }, color = HubMuted, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
        }
        if (state.hasMore) item {
            OutlinedButton(enabled = !state.loading, onClick = onLoadMore, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("加载更多")
            }
        }
        item {
            OutlinedButton(onClick = onSearch, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("查找可用座位")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp))
            }
        }
    }

    state.selectedRecord?.let { record ->
        AlertDialog(onDismissRequest = onDismissRecord, title = { Text(record.title.ifBlank { "记录详情" }) },
            text = { Column(Modifier.fillMaxWidth().heightIn(max = maximumDialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.detailsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                record.status.takeIf { it.isNotBlank() }?.let { Text(it, color = HubTeal, fontWeight = FontWeight.Medium) }
                state.detailsError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onOpenRecord(record) }, enabled = !state.loading && !state.detailsLoading) { Text("重新读取详情") }
                }
                record.details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            } }, confirmButton = { TextButton(onClick = onDismissRecord) { Text("关闭") } },
            dismissButton = { if (record.canCancel && state.section == HubSection.RESERVATIONS)
                TextButton(enabled = !state.loading && !state.detailsLoading, onClick = { onDismissRecord(); cancellingRecord = record }) { Text("取消预约", color = MaterialTheme.colorScheme.error) } })
    }
    cancellingRecord?.let { record ->
        AlertDialog(onDismissRequest = { cancellingRecord = null }, title = { Text("取消这次预约？") },
            text = { Column(Modifier.fillMaxWidth().heightIn(max = maximumDialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.title, fontWeight = FontWeight.Medium)
                record.details.forEach { Text(it) }; Text("取消后该时段的座位将释放。")
            } }, confirmButton = { TextButton(enabled = !state.loading, onClick = { cancellingRecord = null; onCancelRecord(record) }) { Text("确认取消预约") } },
            dismissButton = { TextButton(onClick = { cancellingRecord = null }) { Text("保留预约") } })
    }

    confirmingAction?.let { action ->
        val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.45f).coerceAtMost(360.dp)
        AlertDialog(
            onDismissRequest = { if (!state.loading) confirmingAction = null },
            title = { Text("确认${action.label}") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(currentStatusLabel(state.currentStatus), fontWeight = FontWeight.Medium, color = HubTeal)
                    state.currentDetails.filter { it.isNotBlank() }.forEach { detail ->
                        Text(detail, fontSize = 13.sp, lineHeight = 21.sp, color = HubMuted)
                    }
                    HorizontalDivider(color = HubLine)
                    Text(action.description.ifBlank { "确认对当前预约执行${action.label}？" })
                }
            },
            confirmButton = { TextButton(enabled = !state.loading, onClick = { confirmingAction = null; onAction(action) }) {
                Text("确认${action.label}", color = if (action.destructive) MaterialTheme.colorScheme.error else HubTeal)
            } },
            dismissButton = { TextButton(enabled = !state.loading, onClick = { confirmingAction = null }) { Text("取消") } },
        )
    }
    removingFavorite?.let { favorite ->
        AlertDialog(
            onDismissRequest = { if (!state.loading) removingFavorite = null },
            title = { Text("移除常用座位") },
            text = { Text("将${favorite.name}从常用座位中移除？") },
            confirmButton = { TextButton(enabled = !state.loading, onClick = { removingFavorite = null; onRemoveFavorite(favorite) }) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(enabled = !state.loading, onClick = { removingFavorite = null }) { Text("取消") } },
        )
    }
}

private fun currentStatusLabel(value: String): String = when (value) {
    "" -> "预约状态待同步"
    "NO_RESERVATION" -> "暂无进行中的预约"
    "RESERVED", "RESERVE", "BOOKED" -> "已预约，等待签到"
    "IN_USE", "USING", "CHECKED_IN", "CHECK_IN" -> "正在使用座位"
    "AWAY", "LEAVE" -> "暂离中"
    "CANCELED", "CANCELLED" -> "预约已取消"
    "FINISHED", "STOPPED" -> "本次使用已结束"
    "COMPLETE" -> "预约时段已结束，请结束使用"
    "LOC_RESTRICT" -> "当前网络暂不支持操作座位"
    else -> value
}

@Composable
private fun HubShortcut(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = HubTeal, modifier = Modifier.size(24.dp))
            Text(title, color = HubInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(detail, color = HubMuted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun HubRecordCard(record: HubRecord, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(record.title, color = HubInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            record.status.takeIf { it.isNotBlank() }?.let { status ->
                Surface(color = HubSoft, shape = RoundedCornerShape(7.dp)) { Text(status, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = HubTeal, fontSize = 11.sp) }
            }
            record.details.filter { it.isNotBlank() }.forEach { Text(it, color = HubMuted, fontSize = 13.sp, lineHeight = 21.sp) }
        }
    }
}

@Composable
private fun FavoriteCard(favorite: HubFavorite, loading: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BookmarkBorder, null, tint = HubTeal, modifier = Modifier.size(22.dp))
                Text(favorite.name, color = HubInk, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 9.dp))
                if (favorite.local) {
                    IconButton(enabled = !loading, onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, "移除${favorite.name}", tint = HubMuted) }
                }
            }
            Text(favorite.location, color = HubMuted, fontSize = 13.sp, lineHeight = 21.sp)
            Text(if (favorite.local) "本机收藏" else "网站常用座位", color = HubTeal, fontSize = 12.sp)
            Button(enabled = !loading, onClick = onSelect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("选择使用时间") }
        }
    }
}
