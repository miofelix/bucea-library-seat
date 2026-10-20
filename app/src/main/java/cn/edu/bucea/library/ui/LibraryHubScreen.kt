package cn.edu.bucea.library.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.data.models.ReservationSchedule
import cn.edu.bucea.library.data.models.ReservationMapTarget
import java.time.LocalDate

enum class HubSection { RESERVATIONS, FAVORITES, FREQUENT, VIOLATIONS }

data class HubRecord(val id: String, val title: String, val status: String, val details: List<String>, val canCancel: Boolean = false,
    val schedule: ReservationSchedule = ReservationSchedule())
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
    val actionMessage: String = "",
    val requiresLibraryWifi: Boolean = false,
)

/** The hub shares the server-backed time picker without leaving its current destination. */
data class FavoriteBookingActions(
    val state: SeatSearchState,
    val onDate: (HubFavorite, String) -> Boolean,
    val onRetryDates: () -> Unit,
    val onStart: (String) -> Unit,
    val onEnd: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    val onVerify: () -> Unit,
    val onOpenReservations: () -> Unit,
)

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
    favoriteBooking: FavoriteBookingActions? = null,
    onLocateSeat: ((ReservationMapTarget) -> Unit)? = null,
    onLocateRecord: ((HubRecord) -> Unit)? = null,
) {
    val context = LocalContext.current
    var confirmingAction by remember(state.currentStatus, state.currentDetails) { mutableStateOf<HubAction?>(null) }
    var removingFavorite by remember(state.section) { mutableStateOf<HubFavorite?>(null) }
    var cancellingRecord by remember(state.section) { mutableStateOf<HubRecord?>(null) }
    var bookingFavorite by remember(state.section) { mutableStateOf<HubFavorite?>(null) }
    val maximumDialogHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.45f).coerceAtMost(360.dp)
    LaunchedEffect(state.actions, state.favorites, state.records) {
        if (confirmingAction?.id?.let { id -> state.actions.none { it.id == id } } == true) confirmingAction = null
        if (removingFavorite?.id?.let { id -> state.favorites.none { it.id == id && it.local } } == true) removingFavorite = null
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
        HubSection.RESERVATIONS -> "预约时间线"
        HubSection.FAVORITES -> "收藏座位"
        HubSection.FREQUENT -> "常用座位"
        HubSection.VIOLATIONS -> "违约记录"
    }
    val subtitle = when (state.section) {
        HubSection.RESERVATIONS -> "按时间，安排下一次到馆。"
        HubSection.FAVORITES -> "在选座页长按座位卡片即可收藏，再次长按可取消收藏。"
        HubSection.FREQUENT -> "熟悉的位置，轻松找到。"
        HubSection.VIOLATIONS -> "每一次按时赴约，都值得。"
    }
    val favoriteSection = state.section == HubSection.FAVORITES || state.section == HubSection.FREQUENT
    val empty = if (favoriteSection) state.favorites.isEmpty() else state.records.isEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Paper),
        contentPadding = PaddingValues(horizontal = LibrarySpacing.pageHorizontal, vertical = LibrarySpacing.pageVertical),
        verticalArrangement = Arrangement.spacedBy(LibrarySpacing.sectionGap),
    ) {
        item(key = "heading") {
            LibraryPageHeader("我的图书馆", subtitle, onRefresh = onRefresh, refreshDescription = "刷新预约与座位", refreshEnabled = !state.loading)
        }
        item(key = "sections") {
            HubSectionSwitch(state.section, !state.loading, onSection)
        }
        if (state.loading) item(key = "loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
        }
        state.error?.let { error ->
            item(key = "error") {
                LibraryCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
                        Text("暂时无法同步", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Text(error, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                    TextButton(enabled = !state.loading, onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
                }
            }
        }
        if (state.section == HubSection.RESERVATIONS) {
            item(key = "current") {
                HubCurrentReservation(state, onRefresh, onConfirmAction = { confirmingAction = it }, onLocateSeat = onLocateSeat)
            }
            if (state.requiresLibraryWifi || state.currentStatus == "LOC_RESTRICT") item(key = "wifi") {
                Surface(color = SoftAccent, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Wifi, null, tint = Accent, modifier = Modifier.size(20.dp))
                            Text("签到需连接图书馆 Wi-Fi", color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                        Text(
                            state.actionMessage.takeIf { it.isNotBlank() }
                                ?: "到馆后连接图书馆内部 Wi-Fi，再刷新预约状态。",
                            color = Muted, fontSize = 12.sp, lineHeight = 20.sp,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp),
                            ) { Text("打开 Wi-Fi 设置", fontSize = 12.sp) }
                            TextButton(
                                enabled = !state.loading, onClick = onRefresh,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp),
                            ) { Text("已连接，刷新状态", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        state.notice?.takeIf { it.isNotBlank() }?.let { notice ->
            item(key = "notice") { Text(notice, color = Muted, fontSize = 12.sp, lineHeight = 20.sp) }
        }
        item(key = "section-title") { LibrarySectionTitle(sectionTitle) }
        if (favoriteSection) {
            items(state.favorites, key = { "favorite_${it.id}" }) { favorite ->
                FavoriteCard(favorite, state.loading || favoriteBooking?.state?.reservationBusy == true, {
                    onFavorite(favorite)
                    if (favoriteBooking != null) bookingFavorite = favorite
                }, { removingFavorite = favorite })
            }
        } else if (state.section == HubSection.RESERVATIONS) {
            if (state.hasMore) item(key = "timeline-scope") {
                Text("已加载的预约按时间排列，加载更多可查看其余记录。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            reservationTimeline(state.records).forEach { group ->
                item(key = "timeline_${group.title}") {
                    Text(group.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Muted)
                }
                items(group.records, key = { "record_${it.id}" }) { record ->
                    HubReservationTimelineEntry(record, !state.loading, { onOpenRecord(record) },
                        if (record.canCancel) ({ cancellingRecord = record }) else null,
                        when {
                            onLocateRecord != null -> ({ onLocateRecord(record) })
                            onLocateSeat != null -> reservationMapTarget(record.details, record.schedule)?.let { target ->
                                { onLocateSeat(target) }
                            }
                            else -> null
                        })
                }
            }
        } else {
            items(state.records, key = { "record_${it.id}" }) { record ->
                HubRecordCard(
                    record, !state.loading, { onOpenRecord(record) },
                    if (state.section == HubSection.RESERVATIONS && record.canCancel) ({ cancellingRecord = record }) else null,
                )
            }
        }
        if (empty && !state.loading && state.error == null) item(key = "empty") {
            LibraryEmptyState(
                title = when (state.section) {
                    HubSection.RESERVATIONS -> "暂无预约记录"
                    HubSection.FAVORITES -> "还没有收藏座位"
                    HubSection.FREQUENT -> "暂无系统常用座位"
                    HubSection.VIOLATIONS -> "暂无违约记录"
                },
                message = when (state.section) {
                    HubSection.RESERVATIONS -> "找到合适的座位，开始一次专注时光。"
                    HubSection.FAVORITES -> "前往选座页，长按喜欢的座位卡片即可收藏。"
                    HubSection.FREQUENT -> "常用座位由图书馆系统根据使用记录生成。"
                    HubSection.VIOLATIONS -> "感谢你按时签到、合理使用座位。"
                },
                icon = when (state.section) {
                    HubSection.RESERVATIONS -> Icons.Outlined.CalendarMonth
                    HubSection.FAVORITES -> Icons.Outlined.BookmarkBorder
                    HubSection.FREQUENT -> Icons.Outlined.GridView
                    HubSection.VIOLATIONS -> Icons.Outlined.WarningAmber
                },
                actionLabel = if (state.section == HubSection.FAVORITES) "去选座" else null,
                onAction = if (state.section == HubSection.FAVORITES) onSearch else null,
            )
        }
        if (state.hasMore) item(key = "more") {
            OutlinedButton(
                enabled = !state.loading, onClick = onLoadMore,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Line),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("加载更多")
            }
        }
    }

    state.selectedRecord?.let { record ->
        BusinessAlertDialog(
            onDismissRequest = onDismissRecord,
            title = { Text(record.title.ifBlank { "记录详情" }) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = maximumDialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LibrarySpacing.itemGap)) {
                    if (state.detailsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    hubRecordStatus(record)?.let { HubStatusPill(it, paletteStatus = it) }
                    state.detailsError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp)
                        TextButton(onClick = { onOpenRecord(record) }, enabled = !state.loading && !state.detailsLoading, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("重新读取详情")
                        }
                    }
                    record.details.filter { it.isNotBlank() }.forEach { HubDetailLine(it) }
                }
            },
            confirmButton = { TextButton(onClick = onDismissRecord, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } },
            dismissButton = {
                if (record.canCancel && state.section == HubSection.RESERVATIONS) TextButton(
                    enabled = !state.loading && !state.detailsLoading,
                    onClick = { onDismissRecord(); cancellingRecord = record }, modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消预约", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    cancellingRecord?.let { record ->
        BusinessAlertDialog(
            onDismissRequest = { if (!state.loading) cancellingRecord = null }, title = { Text("取消这次预约？") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = maximumDialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.title, fontWeight = FontWeight.Medium)
                    record.details.filter { it.isNotBlank() }.forEach { HubDetailLine(it) }
                    Text("取消后该时段的座位将释放。", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = !state.loading, onClick = { cancellingRecord = null; onCancelRecord(record) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("确认取消预约", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(enabled = !state.loading, onClick = { cancellingRecord = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留预约") } },
        )
    }
    confirmingAction?.let { action ->
        BusinessAlertDialog(
            onDismissRequest = { if (!state.loading) confirmingAction = null }, title = { Text("确认${action.label}") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = maximumDialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(currentStatusLabel(state.currentStatus), fontWeight = FontWeight.Medium, color = Accent)
                    state.currentDetails.filter { it.isNotBlank() }.forEach { HubDetailLine(it) }
                    HorizontalDivider(color = Line)
                    Text(action.description.ifBlank { "确认对当前预约执行${action.label}？" }, fontSize = 13.sp, lineHeight = 21.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = !state.loading, onClick = { confirmingAction = null; onAction(action) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("确认${action.label}", color = if (action.destructive) MaterialTheme.colorScheme.error else Accent)
                }
            },
            dismissButton = { TextButton(enabled = !state.loading, onClick = { confirmingAction = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
        )
    }
    removingFavorite?.let { favorite ->
        BusinessAlertDialog(
            onDismissRequest = { if (!state.loading) removingFavorite = null }, title = { Text("移除收藏座位") },
            text = { Text("将${favorite.name}从本机收藏中移除？") },
            confirmButton = {
                TextButton(enabled = !state.loading, onClick = { removingFavorite = null; if (favorite.local) onRemoveFavorite(favorite) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(enabled = !state.loading, onClick = { removingFavorite = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
        )
    }
    if (favoriteBooking != null) bookingFavorite?.let { favorite ->
        HubFavoriteBooking(favorite, favoriteBooking) {
            favoriteBooking.onDismiss()
            bookingFavorite = null
        }
    }
}

@Composable
private fun HubFavoriteBooking(favorite: HubFavorite, booking: FavoriteBookingActions, onDismiss: () -> Unit) {
    val state = booking.state
    val dates = state.options["onDate"].orEmpty()
    var selectedDate by remember(favorite.id, dates) {
        mutableStateOf(state.filters["onDate"]?.takeIf { value -> dates.any { it.value == value } } ?: dates.firstOrNull()?.value)
    }
    var choosingTimes by remember(favorite.id) { mutableStateOf(false) }
    LaunchedEffect(choosingTimes, state.selectedSeat, state.reservationBusy) {
        if (choosingTimes && state.selectedSeat == null && !state.reservationBusy) onDismiss()
    }
    if (choosingTimes) {
        state.selectedSeat?.takeIf { it.id == favorite.id }?.let { seat ->
            NativeReservationDialog(state, seat, booking.onStart, booking.onEnd, booking.onConfirm, onDismiss,
                booking.onVerify, booking.onOpenReservations)
        }
    } else {
        BusinessSelectionSheet(
            onDismissRequest = onDismiss,
            title = { Text("选择预约日期") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LibrarySpacing.itemGap)) {
                    Text("${favorite.name} · ${favorite.location}", color = Muted, fontSize = 13.sp)
                    if (state.loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在读取可预约日期…", color = Muted, fontSize = 12.sp)
                    }
                    state.error?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        TextButton(onClick = booking.onRetryDates, enabled = !state.loading) { Text("重新读取日期") }
                    }
                    if (!state.loading && state.error == null && dates.isEmpty()) {
                        Text("图书馆暂未开放可预约日期。", color = Muted, fontSize = 13.sp)
                        TextButton(onClick = booking.onRetryDates) { Text("重新读取日期") }
                    }
                    dates.forEach { date ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selectedDate == date.value) SoftAccent else Color.Transparent)
                            .selectable(selected = selectedDate == date.value, enabled = !state.loading && state.error == null,
                                role = Role.RadioButton, onClick = { selectedDate = date.value })
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(date.label, modifier = Modifier.weight(1f), fontSize = 14.sp,
                                color = if (selectedDate == date.value) Accent else Ink)
                            RadioButton(selected = selectedDate == date.value, onClick = null,
                                enabled = !state.loading && state.error == null)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.loading && state.error == null && dates.any { it.value == selectedDate },
                    onClick = {
                        selectedDate?.let { date -> if (booking.onDate(favorite, date)) choosingTimes = true }
                    }) { Text("选择时间") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}

internal fun currentStatusLabel(value: String): String = when (value) {
    "" -> "预约状态待同步"
    "NO_RESERVATION" -> "暂无进行中的预约"
    "RESERVED", "RESERVE", "BOOKED" -> "已预约，等待签到"
    "IN_USE", "USING", "CHECKED_IN", "CHECK_IN" -> "正在使用座位"
    "AWAY", "LEAVE" -> "暂离中"
    "CANCELED", "CANCELLED" -> "预约已取消"
    "FINISHED", "STOPPED" -> "本次使用已结束"
    "COMPLETE" -> "预约时段已结束，请结束使用"
    "LOC_RESTRICT" -> "请连接图书馆 Wi-Fi 后刷新"
    else -> value
}

@Composable
private fun HubSectionSwitch(selected: HubSection, enabled: Boolean, onSection: (HubSection) -> Unit) {
    LibrarySegmentedTabs(
        labels = listOf("我的预约", "收藏座位", "常用座位", "违约记录"),
        selectedIndex = selected.ordinal,
        onSelect = { onSection(HubSection.entries[it]) },
        enabled = enabled,
    )
}

@Composable
internal fun HubCurrentReservation(state: LibraryHubState, onRefresh: () -> Unit, onConfirmAction: (HubAction) -> Unit,
    onOpenDetails: (() -> Unit)? = null, onLocateSeat: ((ReservationMapTarget) -> Unit)? = null) {
    val details = state.currentDetails.filter { it.isNotBlank() }.distinct()
    val summary = currentReservationSummary(details)
    val configuration = LocalConfiguration.current
    val actionsPerRow = if (LocalDensity.current.fontScale > 1.2f || configuration.screenWidthDp < 330) 2 else 3
    val primaryAction = state.actions.firstOrNull { !it.destructive }
    val mapTarget = if (state.currentStatus !in setOf("", "NO_RESERVATION")) reservationMapTarget(details) else null
    LibraryCard(highlighted = true) {
        Column(Modifier.fillMaxWidth().semantics { contentDescription = "当前预约卡片" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CalendarMonth, null, tint = Muted, modifier = Modifier.size(18.dp))
                Text("当前预约", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (summary.hasInformation) HubStatusPill(currentReservationBadge(state.currentStatus), Modifier.widthIn(max = 150.dp), state.currentStatus)
            }
            if (summary.seat != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary.seat, color = Accent, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("号座位", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
            } else if (!summary.hasInformation) {
                Text(currentStatusLabel(state.currentStatus), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
            }
            summary.location?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) }
            if (summary.date != null || summary.time != null) {
                Text(buildAnnotatedString {
                    summary.date?.let { withStyle(SpanStyle(color = Muted, fontSize = 12.sp)) { append(it) } }
                    if (summary.date != null && summary.time != null) append(" · ")
                    summary.time?.let { withStyle(SpanStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)) { append(it) } }
                }, color = Muted, lineHeight = 22.sp)
            }
            state.actionMessage.takeIf { it.isNotBlank() && !(state.requiresLibraryWifi || state.currentStatus == "LOC_RESTRICT") &&
                it !in setOf("座位操作以图书馆系统当前提供的选项为准", "当前没有可签到的预约，已预约座位请查看预约记录") }?.let { message ->
                Text(message, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            if (onOpenDetails == null) state.actions.chunked(actionsPerRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { action ->
                        if (action == primaryAction) Button(
                            onClick = { onConfirmAction(action) }, enabled = !state.loading,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) { Text(action.label, fontSize = 13.sp) }
                        else OutlinedButton(
                            onClick = { onConfirmAction(action) }, enabled = !state.loading,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Line),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (action.destructive) MaterialTheme.colorScheme.error else Accent),
                        ) { Text(action.label, fontSize = 13.sp) }
                    }
                }
            }
            if (onOpenDetails != null || (onLocateSeat != null && mapTarget != null)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onLocateSeat != null && mapTarget != null) TextButton(onClick = { onLocateSeat(mapTarget) }, enabled = !state.loading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("查看座位位置", fontSize = 13.sp)
                    }
                    if (onOpenDetails != null) TextButton(onClick = onOpenDetails, enabled = !state.loading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("查看我的预约", fontSize = 13.sp) }
                }
            }
            if (state.currentStatus.isBlank() && !state.loading) {
                TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) { Text("同步当前预约") }
            }
        }
    }
}

private fun currentReservationBadge(status: String): String = when (status) {
    "RESERVE", "RESERVED", "BOOKED" -> "待签到"
    "CHECK_IN", "IN_USE", "USING", "CHECKED_IN" -> "使用中"
    "AWAY", "LEAVE" -> "暂离中"
    "COMPLETE" -> "待结束使用"
    "LOC_RESTRICT" -> "位置受限"
    else -> currentStatusLabel(status)
}

@Composable
private fun HubStatusPill(status: String, modifier: Modifier = Modifier, paletteStatus: String = status) {
    val palette = reservationStatusPalette(paletteStatus)
    Surface(color = palette.background, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(status, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = palette.foreground, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

/** Format labels only for presentation; action requests always retain the original server data. */
internal fun hubDetailText(text: String): Pair<String?, String> {
    val separator = text.indexOfFirst { it == '：' || it == ':' }
    if (separator !in 1..12) return null to text
    val label = text.take(separator).replace(Regex("[\\s\\u00a0]"), "")
    if (label !in setOf("座位", "座位号", "位置", "地点", "房间", "区域", "日期", "预约日期", "时间", "时段", "状态", "凭证号", "预约编号")) return null to text
    return label to text.drop(separator + 1).trim()
}

/**
 * History pages have used both a status field and a status detail over time. Keep
 * the displayed label complete when one source is absent, and translate the
 * machine values emitted after ending a seat session into the user-facing label.
 */
private fun hubRecordStatus(record: HubRecord): String? {
    val statusValue = hubDetailText(record.status).let { (label, value) ->
        value.takeIf { label == "状态" } ?: record.status.trim()
    }
    val detailValue = record.details.asSequence().mapNotNull { detail ->
        val (label, value) = hubDetailText(detail)
        when {
            label == "状态" && value.isNotBlank() -> value
            detail.trim() in setOf("已预约", "待签到", "已签到", "使用中", "暂离", "已履约", "失约", "已失约",
                "已结束", "结束使用", "已取消", "已完成", "已违约") -> detail.trim()
            else -> null
        }
    }.firstOrNull()
    // A detail field is the server's explicit displayed status. Prefer it when
    // present so “结束使用” cannot be hidden behind a separate “已完成” code.
    val value = (detailValue ?: statusValue).trim()
    if (value.isBlank()) return null
    return when (value.uppercase()) {
        "STOPPED", "FINISHED" -> "结束使用"
        else -> value
    }
}

internal fun hubSeatNumber(details: List<String>): String? = details.firstNotNullOfOrNull { detail ->
    Regex("座位(?:号)?\\s*[：:]?\\s*([0-9A-Za-z][0-9A-Za-z-]{0,15})").find(detail)?.groupValues?.get(1)
        ?: Regex("([0-9A-Za-z]+)\\s*号座位").find(detail)?.groupValues?.get(1)
        ?: Regex("([0-9]{2,}[A-Za-z][0-9A-Za-z-]*|[0-9]{2,})\\s*号").find(detail)?.groupValues?.get(1)
}

@Composable
private fun HubDetailLine(text: String) {
    reservationLocationDetailText(text, listOf(text))?.let { location ->
        Text(location, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        return
    }
    val (label, value) = hubDetailText(text)
    if (label == null) Text(value, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 12.sp, lineHeight = 22.sp, modifier = Modifier.width(54.dp))
        Text(value, color = Ink, fontSize = if (label in setOf("时间", "时段")) 16.sp else 13.sp,
            fontWeight = if (label in setOf("时间", "时段")) FontWeight.Medium else FontWeight.Normal,
            lineHeight = 22.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HubReservationTimelineEntry(record: HubRecord, enabled: Boolean, onClick: () -> Unit, onCancel: (() -> Unit)?, onLocate: (() -> Unit)? = null) {
    val accent = reservationStatusPalette(hubRecordStatus(record).orEmpty()).foreground
    val line = Line
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics { contentDescription = "预约时间线 ${record.id}" },
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.width(14.dp).fillMaxHeight()) {
            val markerY = 24.dp.toPx()
            drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
            drawCircle(accent, 5.dp.toPx(), Offset(size.width / 2, markerY))
        }
        HubRecordCard(record, enabled, onClick, onCancel, timeline = true, modifier = Modifier.weight(1f), onLocate = onLocate)
    }
}

@Composable
private fun HubRecordCard(
    record: HubRecord,
    enabled: Boolean,
    onClick: () -> Unit,
    onCancel: (() -> Unit)? = null,
    timeline: Boolean = false,
    modifier: Modifier = Modifier,
    onLocate: (() -> Unit)? = null,
) {
    val seat = if (timeline) hubSeatNumber(record.details) else null
    val recordStatus = hubRecordStatus(record)
    val title = if (timeline && record.schedule.begin?.let(record.title::contains) == true)
        seat?.let { "$it 号座位" } ?: "座位预约" else record.title
    // Keep status readable independently and combine title/details into one
    // comfortably sized action so each record needs only one action row.
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (timeline) {
                val date = record.schedule.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val label = date?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" }
                    ?: record.schedule.dateLabel ?: "日期待确认"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    recordStatus?.let { HubStatusPill(it, paletteStatus = it) }
                }
                val time = when {
                    record.schedule.begin != null && record.schedule.end != null -> "${record.schedule.begin} — ${record.schedule.end}"
                    record.schedule.begin != null -> "开始 ${record.schedule.begin}"
                    record.schedule.end != null -> "结束 ${record.schedule.end}"
                    else -> "时段待确认"
                }
                Text(time, color = Accent, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
            } else {
                recordStatus?.let { HubStatusPill(it, paletteStatus = it) }
            }
            if (seat != null && title != "$seat 号座位") Text("$seat 号座位", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            record.details.filter { it.isNotBlank() && hubDetailText(it).first != "状态" &&
                (!timeline || hubDetailText(it).first !in setOf("日期", "预约日期", "时间", "时段", "凭证号", "预约编号", "座位", "座位号")) }.let {
                if (timeline) it else it.take(2)
            }.forEach { detail ->
                Text(reservationLocationDetailText(detail, record.details) ?: detail, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    // A zero-padded TextButton puts its first glyph under the rounded
                    // corner clip. Keep the compact 48dp target while protecting both
                    // lines from being cut at large font scales.
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, color = Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("查看详情", color = Accent, fontSize = 12.sp, lineHeight = 17.sp)
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Muted, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (onLocate != null) IconButton(onClick = onLocate, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.LocationOn, "查看座位位置 ${seat.orEmpty()}", tint = Accent, modifier = Modifier.size(22.dp))
                }
                if (onCancel != null) TextButton(enabled = enabled, onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("取消预约", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(favorite: HubFavorite, loading: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    LibraryCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(favorite.name, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = if (favorite.name.length <= 12) 22.sp else 16.sp, modifier = Modifier.weight(1f))
            if (favorite.local) IconButton(enabled = !loading, onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.DeleteOutline, "移除${favorite.name}", tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
        Text(favorite.location, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Text(if (favorite.local) "本机收藏 · 由你手动添加" else "系统常用 · 根据使用记录生成", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
        OutlinedButton(enabled = !loading, onClick = onSelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Line)) {
            Text("选择使用时间", fontSize = 13.sp)
            Spacer(Modifier.width(7.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(16.dp))
        }
    }
}
