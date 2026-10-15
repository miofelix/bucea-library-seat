package cn.edu.bucea.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
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
    val resultsComplete: Boolean = false,
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val byNumber = state.filters["name"].orEmpty().isNotBlank()
    val advancedVisible = advanced && !byNumber
    // The server may return many chunks; scroll to results once the query settles.
    LaunchedEffect(state.hasSearched, state.loading) {
        if (state.hasSearched && !state.loading) {
            withFrameNanos { }
            resultsRequester.bringIntoView()
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Paper)) {
        val columns = compactSeatColumns(maxWidth - LibrarySpacing.pageHorizontal * 2)
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = LibrarySpacing.pageHorizontal, end = LibrarySpacing.pageHorizontal,
                top = LibrarySpacing.pageVertical, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "search-controls") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LibrarySpacing.sectionGap)) {
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(error, color = Color(0xFF8E473A), fontSize = 12.sp, lineHeight = 19.sp)
                                        TextButton(onClick = onRetryRooms, enabled = !state.loading && !state.roomsLoading) { Text("重新加载阅览区域") }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !byNumber, role = Role.Button) { advanced = !advanced }
                                .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 4.dp)
                                .semantics { stateDescription = when {
                                    byNumber -> "按编号查询时不使用这些筛选条件"
                                    advancedVisible -> "已展开"
                                    else -> "已收起"
                                } },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Text(if (advancedVisible) "收起筛选条件" else "时间、电源与靠窗", fontSize = 12.sp, lineHeight = 16.sp,
                                    color = if (byNumber) Muted else Accent)
                                Icon(if (advancedVisible) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                                    modifier = Modifier.size(16.dp), tint = if (byNumber) Muted else Accent)
                            }
                            if (advancedVisible) {
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
                            CompactSeatNumberField(
                                value = state.filters["name"].orEmpty(),
                                onValueChange = { onFilter("name", it) },
                                enabled = !state.loading,
                            )
                            if (byNumber) Text("按编号查询仅使用日期和区域，点选座位后确认可用时段。", fontSize = 11.sp, color = Muted)
                            Button(onClick = { focusManager.clearFocus(); onSearch() }, enabled = !state.loading && !state.roomsLoading && state.roomsError == null && state.options.isNotEmpty() && !state.filters["onDate"].isNullOrBlank(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(error, color = Color(0xFF8E473A), fontSize = 13.sp, lineHeight = 21.sp)
                                TextButton(onClick = { focusManager.clearFocus(); onRetrySearch() }, enabled = !state.loading && !state.roomsLoading && state.roomsError == null) {
                                    Text(if (state.failedPage != null) "继续加载座位" else "重新查询")
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
                        if (state.hasSearched) Text(if (state.resultsComplete) "全部 ${state.seats.size} 个" else "已加载 ${state.seats.size} 个", fontSize = 12.sp, color = Muted)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LibrarySpacing.itemGap)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("仅看可用", fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = Ink)
                            Text(if (state.hasSearched) "显示 ${shownSeats.size} / 已加载 ${state.seats.size} 个"
                                else "即时筛选已加载的全部座位", fontSize = 11.sp, lineHeight = 16.sp, color = Muted)
                        }
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                            Switch(checked = onlyAvailable, onCheckedChange = { onlyAvailable = it },
                                modifier = Modifier.semantics {
                                    contentDescription = "仅看可用"
                                    stateDescription = if (onlyAvailable) "仅显示可预约座位" else "显示全部座位"
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent))
                        }
                    }
                    if (shownSeats.isEmpty() && !state.loading) {
                        val noAvailable = onlyAvailable && state.seats.isNotEmpty()
                        LibraryEmptyState(
                            title = when {
                                noAvailable -> "当前没有可预约座位"
                                state.hasSearched && !state.resultsComplete -> "座位尚未读取完成"
                                state.hasSearched -> "暂时没有符合条件的座位"
                                else -> "先选择日期，再找到你的座位"
                            },
                            message = when {
                                noAvailable && !state.resultsComplete -> "可继续加载座位，或关闭“仅看可用”查看已加载的全部状态。"
                                noAvailable -> "关闭“仅看可用”可查看全部状态，或调整查询条件。"
                                state.hasSearched && !state.resultsComplete -> "请按上方提示继续加载或完成验证。"
                                state.hasSearched -> "试试其他阅览区域或时间条件。"
                                else -> "座位与可用时段将从图书馆实时查询。"
                            },
                            icon = Icons.Outlined.Chair,
                        )
                    }
                    if (shownSeats.isNotEmpty()) {
                        Text("点按选时间 · 长按收藏", fontSize = 10.sp, lineHeight = 14.sp, color = Muted)
                    }
                }
            }
            items(shownSeats.chunked(columns), key = { "seat-row-${it.first().id}" }, contentType = { "seat-cards" }) { seats ->
                CompactSeatRow(seats, columns, !state.loading && !state.reservationBusy,
                    onToggleFavorite = onToggleFavorite, onSelectSeat = onSelectSeat)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSeatNumberField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()
    val textColor = when {
        !enabled -> colors.disabledTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            interactionSource = interactionSource,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor, fontSize = 14.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(colors.cursorColor),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) { contentDescription = "座位号（可选）" }
                .pauseUpdatesWhileFocused(),
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value, innerTextField = innerTextField, enabled = enabled, singleLine = true,
                    visualTransformation = VisualTransformation.None, interactionSource = interactionSource,
                    placeholder = { Text("座位号（可选）", fontSize = 14.sp, lineHeight = 20.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                    colors = colors,
                    contentPadding = OutlinedTextFieldDefaults.contentPadding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    container = {
                        OutlinedTextFieldDefaults.Container(enabled = enabled, isError = false, interactionSource = interactionSource,
                            colors = colors, shape = RoundedCornerShape(13.dp))
                    },
                )
            },
        )
    }
}

@Composable
private fun FilterField(title: String, selected: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Paper).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 60.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 11.sp, lineHeight = 14.sp, color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selected, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, tint = if (enabled) Accent else Muted, modifier = Modifier.size(17.dp))
        }
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
                        .heightIn(min = 48.dp).padding(vertical = 4.dp, horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(LibrarySpacing.sectionGap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (confirming) "2 / 2 · 确认预约" else "1 / 2 · 选择时间", fontSize = 11.sp, color = Accent)
                    Text(if (confirming) "确认这次预约" else "选择可用时段", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("${seat.name} · ${seat.location}", fontSize = 12.sp, color = Muted)
                }
                IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭预约面板", tint = Muted)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LibrarySpacing.sectionGap)) {
                if (state.reservationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Surface(color = Color(0xFFF8EAE4), shape = RoundedCornerShape(12.dp)) {
                        Text(error, modifier = Modifier.fillMaxWidth().padding(12.dp), color = Color(0xFF8E473A), fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
                if (confirming) {
                    Surface(color = SoftAccent, shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxWidth().padding(LibrarySpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(LibrarySpacing.itemGap)) {
                            Text(selectedLabel(state, "onDate", state.filters["onDate"].orEmpty()), fontSize = 14.sp, color = Ink)
                            if (state.accountLabel.isNotBlank()) Text("预约账号：${state.accountLabel}", fontSize = 12.sp, color = Muted)
                            Text("$start — $end", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = Accent)
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
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (confirming) "修改时间" else "取消") }
                Button(onClick = { if (confirming) onConfirm() else confirming = true },
                    enabled = !busy && !state.verificationRequired && !state.reservationOutcomeUncertain && start != null && end != null,
                    shape = RoundedCornerShape(13.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
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
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
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
