package cn.edu.bucea.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SeatOption(val value: String, val label: String)
data class NativeSeat(
    val id: String,
    val name: String,
    val location: String,
    val power: Boolean? = null,
    val window: Boolean? = null,
    val status: String = "座位空闲",
    val available: Boolean = true,
    val localFavorite: Boolean = false,
)
data class SeatSearchState(
    val options: Map<String, List<SeatOption>> = emptyMap(),
    val filters: Map<String, String> = emptyMap(),
    val seats: List<NativeSeat> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val roomsLoading: Boolean = false,
    val roomsError: String? = null,
    val failedPage: Int? = null,
    val status: String? = null,
    val hasSearched: Boolean = false,
    val page: Int = 0,
    val hasNextPage: Boolean = false,
    val total: Int? = null,
    val selectedSeat: NativeSeat? = null,
    val startOptions: List<SeatOption> = emptyList(),
    val endOptions: List<SeatOption> = emptyList(),
    val selectedStart: String? = null,
    val selectedEnd: String? = null,
    val reservationBusy: Boolean = false,
    val verificationRequired: Boolean = false,
    val reservationOutcomeUncertain: Boolean = false,
    val verificationUserId: String = "",
    val accountLabel: String = "",
)

/** Native presentation only: all options, availability and reservation times come from the server. */
@Composable
fun SeatSearchScreen(
    state: SeatSearchState,
    onFilter: (String, String) -> Unit,
    onSearch: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onSelectSeat: (NativeSeat) -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: (NativeSeat) -> Unit = {},
    onSelectStart: (String) -> Unit = {},
    onSelectEnd: (String) -> Unit = {},
    onConfirmReservation: () -> Unit = {},
    onDismissReservation: () -> Unit = {},
    onVerify: () -> Unit = {},
    onOpenReservations: () -> Unit = {},
    onRetrySearch: () -> Unit = onSearch,
    onRetryRooms: () -> Unit = {},
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var onlyAvailable by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val shownSeats = remember(state.seats, onlyAvailable) { SeatPresentationPolicy.visibleSeats(state.seats, onlyAvailable) }
    val resultsRequester = remember { BringIntoViewRequester() }
    var lastResultsPage by rememberSaveable { mutableStateOf<Int?>(null) }
    val byNumber = state.filters["name"].orEmpty().isNotBlank()
    LaunchedEffect(state.page, state.hasSearched, state.loading) {
        if (!state.hasSearched || state.loading) lastResultsPage = null
        else if (lastResultsPage != state.page) {
            withFrameNanos { }
            resultsRequester.bringIntoView()
            lastResultsPage = state.page
        }
    }
    BackHandler {
        if (choosing != null) choosing = null
        else if (state.selectedSeat != null) {
            if (!state.reservationBusy) onDismissReservation()
        } else onBack()
    }
    val fields = mapOf(
        "onDate" to "预约日期", "building" to "场馆", "room" to "阅览区域",
        "hour" to "使用时长", "startMin" to "开始时间", "endMin" to "结束时间",
        "power" to "电源", "window" to "靠窗"
    )
    Column(
        Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val dates = state.options["onDate"].orEmpty()
                if (dates.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        dates.filterNot { it.value.isBlank() || it.value == "null" }.forEach { option ->
                            FilterChip(
                                selected = state.filters["onDate"] == option.value,
                                onClick = { onFilter("onDate", option.value) },
                                label = { Text(option.label, fontSize = 12.sp) },
                                enabled = !state.loading,
                                modifier = Modifier.heightIn(min = 48.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SoftAccent,
                                    selectedLabelColor = Accent,
                                    selectedLeadingIconColor = Accent,
                                ),
                                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterField("场馆", selectedLabel(state, "building", "不限场馆"), !state.loading && state.options["building"].orEmpty().isNotEmpty(), Modifier.weight(1f)) { choosing = "building" }
                    FilterField("阅览区域", if (state.roomsLoading) "正在加载区域…" else if (state.roomsError != null) "区域加载失败" else selectedLabel(state, "room", "不限区域"),
                        !state.loading && !state.roomsLoading && state.roomsError == null && state.options["room"].orEmpty().isNotEmpty(), Modifier.weight(1f)) { choosing = "room" }
                }
                if (state.roomsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在读取所选场馆的阅览区域…", fontSize = 12.sp, color = Muted)
                }
                state.roomsError?.let { error ->
                    Surface(color = Color(0xFFF8EAE4), shape = RoundedCornerShape(13.dp)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(error, color = Color(0xFF8E473A), fontSize = 12.sp, lineHeight = 19.sp)
                            TextButton(onClick = onRetryRooms, enabled = !state.loading && !state.roomsLoading) { Text("重新加载阅览区域") }
                        }
                    }
                }
                OutlinedTextField(
                    value = state.filters["name"].orEmpty(),
                    onValueChange = { onFilter("name", it) },
                    label = { Text("座位号（可选）") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                    singleLine = true,
                    enabled = !state.loading,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth().pauseUpdatesWhileFocused()
                )
                if (byNumber) Text("按编号查询仅使用日期和区域，点选座位后确认可用时段。", fontSize = 11.sp, color = Muted)
                if (advanced && !byNumber) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterField("开始时间", selectedLabel(state, "startMin", "不限时间"), !state.loading && state.options["startMin"].orEmpty().isNotEmpty(), Modifier.weight(1f)) { choosing = "startMin" }
                        FilterField("结束时间", selectedLabel(state, "endMin", "不限时间"), !state.loading && state.options["endMin"].orEmpty().isNotEmpty(), Modifier.weight(1f)) { choosing = "endMin" }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterField("使用时长", selectedLabel(state, "hour", "不限时长"), !state.loading && state.options["hour"].orEmpty().isNotEmpty(), Modifier.weight(1f)) { choosing = "hour" }
                        FilterField("电源", selectedLabel(state, "power", "不限电源"), !state.loading && state.options["power"].orEmpty().isNotEmpty(), Modifier.weight(1f)) { choosing = "power" }
                    }
                    FilterField("靠窗", selectedLabel(state, "window", "不限靠窗"), !state.loading && state.options["window"].orEmpty().isNotEmpty(), Modifier.fillMaxWidth()) { choosing = "window" }
                }
                TextButton(onClick = { advanced = !advanced }, enabled = !byNumber, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (advanced) "收起筛选条件" else "时间、电源与靠窗", fontSize = 12.sp)
                    Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp))
                }
                Button(onClick = { focusManager.clearFocus(); onSearch() }, enabled = !state.loading && !state.roomsLoading && state.roomsError == null && state.options.isNotEmpty() && !state.filters["onDate"].isNullOrBlank(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(17.dp), color = MaterialTheme.colorScheme.onTertiary, strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Search, null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("查找可用座位", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }



        state.error?.let { error ->
            Surface(color = Color(0xFFF8EAE4), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, color = Color(0xFF8E473A), fontSize = 13.sp, lineHeight = 21.sp)
                    TextButton(onClick = { focusManager.clearFocus(); onRetrySearch() }, enabled = !state.loading && !state.roomsLoading && state.roomsError == null) {
                        Text(state.failedPage?.let { "重试第 ${it + 1} 页" } ?: "重新查询")
                    }
                }
            }
        }
        state.status?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = Muted) }
        if (state.verificationRequired && state.selectedSeat == null) {
            OutlinedButton(onClick = onVerify, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("自动完成图片验证")
            }
        }
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(resultsRequester), verticalAlignment = Alignment.CenterVertically) {
            Text("座位结果", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            if (state.hasSearched) Text("第 ${state.page + 1} 页", fontSize = 12.sp, color = Muted)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("仅看可用", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
                Text("只筛选本页已加载座位", fontSize = 11.sp, color = Muted)
            }
            Switch(checked = onlyAvailable, onCheckedChange = { onlyAvailable = it },
                modifier = Modifier.semantics {
                    contentDescription = "仅看可用"
                    stateDescription = if (onlyAvailable) "仅显示本页可预约座位" else "显示本页全部座位"
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent))
        }
        if (state.hasSearched) Text("本页显示 ${shownSeats.size} / 已加载 ${state.seats.size} 个", fontSize = 12.sp, color = Muted)
        if (shownSeats.isEmpty() && !state.loading) {
            val noAvailableOnPage = onlyAvailable && state.seats.isNotEmpty()
            LibraryEmptyState(
                title = when { noAvailableOnPage -> "本页没有可预约座位"; state.hasSearched -> "暂时没有符合条件的座位"; else -> "先选择日期，再找到你的座位" },
                message = when {
                    noAvailableOnPage && state.hasNextPage -> "可继续翻页，或关闭“仅看可用”查看本页全部状态。"
                    noAvailableOnPage -> "关闭“仅看可用”可查看本页全部状态，或调整查询条件。"
                    state.hasSearched -> "试试其他阅览区域或时间条件。"
                    else -> "座位与可用时段将从图书馆实时查询。"
                },
                icon = Icons.Outlined.Chair,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 260.dp) 1 else 2
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shownSeats.chunked(columns).forEach { seats ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        seats.forEach { seat ->
                            SeatCard(seat, !state.loading && !state.reservationBusy, Modifier.weight(1f).fillMaxHeight(), { onToggleFavorite(seat) }) { onSelectSeat(seat) }
                        }
                        repeat(columns - seats.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (state.hasSearched && (state.page > 0 || state.hasNextPage)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPreviousPage, enabled = state.page > 0 && !state.loading, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp)); Text("上一页", fontSize = 12.sp)
                }
                Text("${state.page + 1}", color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = onNextPage, enabled = state.hasNextPage && !state.loading, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) {
                    Text("下一页", fontSize = 12.sp); Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(15.dp))
                }
            }
        }
    }

    choosing?.let { key ->
        OptionDialog(fields[key].orEmpty(), state.options[key].orEmpty(), state.filters[key], { choosing = null }) { value ->
            onFilter(key, value)
            choosing = null
        }
    }
    state.selectedSeat?.let { seat ->
        NativeReservationDialog(state, seat, onSelectStart, onSelectEnd, onConfirmReservation, onDismissReservation, onVerify, onOpenReservations)
    }
}

private fun selectedLabel(state: SeatSearchState, key: String, fallback: String): String =
    state.options[key].orEmpty().firstOrNull { it.value == state.filters[key] }?.label ?: fallback

@Composable
private fun FilterField(title: String, selected: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Paper).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 68.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 11.sp, color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selected, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, tint = if (enabled) Accent else Muted, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun SeatCard(seat: NativeSeat, enabled: Boolean, modifier: Modifier, onFavorite: () -> Unit, onSelect: () -> Unit) {
    val visualState = SeatPresentationPolicy.visualState(seat.status, seat.available)
    val palette = seatPalette(visualState)
    val status = seat.status.ifBlank { "状态未知" }
    val availability = SeatPresentationPolicy.availabilityLabel(seat.available)
    Card(
        modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "座位 ${seat.name}"
            stateDescription = "$status，$availability"
        }
            .clickable(enabled = enabled && seat.available, onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(seat.name, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = Ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onFavorite, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(if (seat.localFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (seat.localFavorite) "取消收藏 ${seat.name}" else "收藏 ${seat.name}", tint = if (seat.localFavorite) Accent else Muted, modifier = Modifier.size(20.dp))
                }
            }
            Text(seat.location, fontSize = 11.sp, lineHeight = 17.sp, color = Muted)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(color = palette.background, shape = RoundedCornerShape(7.dp)) {
                    Text(status, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 11.sp,
                        lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = palette.foreground)
                }
                Surface(color = palette.background, contentColor = palette.foreground, shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.clip(RoundedCornerShape(7.dp))
                        .clickable(enabled = enabled && seat.available, role = Role.Button, onClick = onSelect)
                        .semantics { contentDescription = if (seat.available) "选择 ${seat.name} 的使用时间" else "不可预约 ${seat.name}" }) {
                    Text(availability, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 12.sp,
                        lineHeight = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
            SeatPresentationPolicy.availabilityDetail(visualState, seat.available)?.let { detail ->
                Text(detail, fontSize = 11.sp, lineHeight = 17.sp, color = Muted)
            }
            if (seat.power == true || seat.window == true) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (seat.power == true) SeatCapability(Icons.Outlined.Power, "电源", palette.foreground)
                    if (seat.window == true) SeatCapability(Icons.Outlined.Window, "靠窗", palette.foreground)
                }
            }
        }
    }
}

private data class SeatPalette(val background: Color, val foreground: Color)

private fun seatPalette(state: SeatVisualState): SeatPalette = when (state) {
    SeatVisualState.FREE -> SeatPalette(Color(0xFFEDF7F0), Color(0xFF216847))
    SeatVisualState.OCCUPIED -> SeatPalette(Color(0xFFFFF0ED), Color(0xFFA33C30))
    SeatVisualState.RESERVED -> SeatPalette(Color(0xFFFFF4E5), Color(0xFF956013))
    SeatVisualState.AWAY -> SeatPalette(Color(0xFFF3EEF9), Color(0xFF705097))
    SeatVisualState.UNAVAILABLE -> SeatPalette(Color(0xFFF0F2F3), Color(0xFF606C73))
    SeatVisualState.UNKNOWN -> SeatPalette(Color(0xFFF0F2F3), Color(0xFF606C73))
}

@Composable
private fun SeatCapability(icon: ImageVector, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun OptionDialog(title: String, options: List<SeatOption>, selected: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.48f).coerceAtMost(340.dp)
    BusinessSelectionSheet(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (selected == option.value) SoftAccent else Color.Transparent)
                        .selectable(selected = selected == option.value, role = Role.RadioButton, onClick = { onSelect(option.value) })
                        .heightIn(min = 56.dp).padding(vertical = 5.dp, horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = if (selected == option.value) Accent else Ink)
                        RadioButton(selected = selected == option.value, onClick = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeReservationDialog(state: SeatSearchState, seat: NativeSeat, onStart: (String) -> Unit, onEnd: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, onVerify: () -> Unit, onOpenReservations: () -> Unit) {
    var confirming by remember(seat.id, state.filters["onDate"], state.selectedStart, state.selectedEnd, state.accountLabel) { mutableStateOf(false) }
    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * .88f
    val start = state.startOptions.firstOrNull { it.value == state.selectedStart }?.label
    val end = state.endOptions.firstOrNull { it.value == state.selectedEnd }?.label
    val busy by rememberUpdatedState(state.reservationBusy)
    val interactions = LocalBusinessInteractions.current
    DisposableEffect(interactions) {
        interactions?.dialogs?.let { it.intValue++ }
        onDispose { interactions?.dialogs?.let { it.intValue-- } }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !busy },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Line) },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (confirming) "2 / 2 · 确认预约" else "1 / 2 · 选择时间", fontSize = 11.sp, color = Accent)
                    Text(if (confirming) "确认这次预约" else "选择可用时段", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("${seat.name} · ${seat.location}", fontSize = 12.sp, color = Muted)
                }
                IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭预约面板", tint = Muted)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.reservationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Surface(color = Color(0xFFF8EAE4), shape = RoundedCornerShape(12.dp)) {
                        Text(error, modifier = Modifier.fillMaxWidth().padding(13.dp), color = Color(0xFF8E473A), fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
                if (confirming) {
                    Surface(color = SoftAccent, shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(selectedLabel(state, "onDate", state.filters["onDate"].orEmpty()), fontSize = 14.sp, color = Ink)
                            if (state.accountLabel.isNotBlank()) Text("预约账号：${state.accountLabel}", fontSize = 12.sp, color = Muted)
                            Text("$start — $end", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                        }
                    }
                    Text("提交后将按图书馆规则生成预约，请确认日期和时间。", fontSize = 12.sp, lineHeight = 20.sp, color = Muted)
                } else {
                    TimeChoices("开始时间", state.startOptions, state.selectedStart, !state.reservationBusy,
                        if (state.reservationBusy) "正在获取可用时段…" else "当前没有可用开始时间。", onStart)
                    HorizontalDivider(color = Line)
                    TimeChoices("结束时间", state.endOptions, state.selectedEnd, !state.reservationBusy,
                        when {
                            state.reservationBusy -> "正在获取可用时段…"
                            state.selectedStart == null -> "选择开始时间后显示可用结束时间。"
                            else -> "当前没有可用结束时间，请选择其他开始时间。"
                        }, onEnd)
                    if (state.startOptions.isEmpty() && !state.reservationBusy && state.error == null) Text("可以返回选择其他日期或座位。", fontSize = 12.sp, color = Muted)
                }
                state.status?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = Muted) }
                if (state.verificationRequired) {
                    Text("图书馆要求图片验证，应用将自动识别并完成验证。", fontSize = 12.sp, lineHeight = 20.sp, color = Muted)
                    OutlinedButton(onClick = onVerify, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("自动完成图片验证") }
                }
                if (state.reservationOutcomeUncertain) {
                    Text("预约结果尚未确认，请先查看“我的预约”。核实前无法重复提交。", fontSize = 12.sp, lineHeight = 20.sp, color = Muted)
                    OutlinedButton(onClick = onOpenReservations, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看我的预约") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { if (confirming) confirming = false else onDismiss() }, enabled = !busy,
                    shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Line),
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp)) { Text(if (confirming) "修改时间" else "取消") }
                Button(onClick = { if (confirming) onConfirm() else confirming = true },
                    enabled = !busy && !state.verificationRequired && !state.reservationOutcomeUncertain && start != null && end != null,
                    shape = RoundedCornerShape(13.dp), modifier = Modifier.weight(1f).heightIn(min = 50.dp)) {
                    Text(if (busy) "正在处理…" else if (confirming) "确认预约" else "下一步")
                }
            }
        }
    }
}

@Composable
private fun TimeChoices(title: String, options: List<SeatOption>, selected: String?, enabled: Boolean, emptyMessage: String, onSelect: (String) -> Unit) {
    var choosing by remember(title) { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
        Surface(
            onClick = { choosing = true },
            enabled = enabled && options.isNotEmpty(),
            shape = RoundedCornerShape(13.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Line),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "选择${title}" },
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selectedLabel ?: "请选择", fontSize = 14.sp, color = if (selectedLabel != null) Ink else Muted,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(19.dp), tint = if (enabled && options.isNotEmpty()) Accent else Muted)
            }
        }
        if (options.isEmpty()) Text(emptyMessage, fontSize = 11.sp, color = Muted)
    }
    if (choosing) {
        OptionDialog(title, options, selected, onDismiss = { choosing = false }) { value ->
            choosing = false
            onSelect(value)
        }
    }
}
