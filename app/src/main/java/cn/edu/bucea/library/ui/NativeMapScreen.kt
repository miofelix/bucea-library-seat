package cn.edu.bucea.library.ui

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.data.models.*
import java.time.LocalDate

internal data class MapSeatStyle(val fill: Color, val label: String, val marker: String)

// Seat status must stay recognizable with every theme, including the yellow accent.
// Opaque fills give white seat numbers and markers at least 4.5:1 contrast.
private fun mapSeatStyle(state: SeatVisualState) = MapSeatStyle(
    fill = when (state) {
        SeatVisualState.FREE -> Color(0xFF087F6D)
        SeatVisualState.OCCUPIED -> Color(0xFFB33F32)
        SeatVisualState.RESERVED -> Color(0xFF94600A)
        SeatVisualState.AWAY -> Color(0xFF754BA5)
        SeatVisualState.UNAVAILABLE -> Color(0xFF586471)
        SeatVisualState.UNKNOWN -> Color(0xFF626C79)
    },
    label = SeatPresentationPolicy.statusLabel(state),
    marker = SeatPresentationPolicy.statusMarker(state),
)

private val MapLocalOnly = MapSeatStyle(Color(0xFF2756A4), "仅限现场", "现")

internal fun nativeMapSeatStyle(status: String, available: Boolean, localOnly: Boolean = false): MapSeatStyle =
    if (localOnly) MapLocalOnly else mapSeatStyle(SeatPresentationPolicy.visualState(status, available))

private fun cellStyle(cell: NativeMapCell): MapSeatStyle =
    nativeMapSeatStyle(cell.seat?.status.orEmpty(), cell.seat?.available == true, cell.localOnly)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NativeMapScreen(
    state: NativeMapState,
    modifier: Modifier = Modifier,
    onFilter: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onLoadRoom: (String) -> Unit,
    onListMode: (Boolean) -> Unit,
    onSeat: (LibrarySeat, String) -> Unit,
    onBack: () -> Unit,
    onVerify: () -> Unit = {},
    showHeader: Boolean = true,
    favoriteSeatIds: Set<String> = emptySet(),
    onToggleFavorite: ((NativeSeat) -> Unit)? = null,
) {
    var choosing by remember { mutableStateOf<String?>(null) }
    var selected by remember(state.layout?.roomId, state.filters["onDate"], state.layout) { mutableStateOf<NativeMapCell?>(null) }
    var onlyAvailable by rememberSaveable { mutableStateOf(false) }
    val seatCells = remember(state.layout) { state.layout?.cells.orEmpty().filter { it.seat != null } }
    val visibleCells = remember(seatCells, onlyAvailable) {
        if (onlyAvailable) seatCells.filter { it.seat?.available == true && !it.localOnly } else seatCells
    }
    BackHandler {
        when {
            selected != null -> selected = null
            choosing != null -> choosing = null
            else -> onBack()
        }
    }
    val roomName = state.layout?.roomName ?: "选择阅览区域"
    val configuration = LocalConfiguration.current
    val dialogHeight = (configuration.screenHeightDp.dp * .45f).coerceIn(150.dp, 320.dp)
    val chipColors = FilterChipDefaults.filterChipColors(containerColor = Color.White,
        labelColor = Muted, selectedContainerColor = SoftAccent, selectedLabelColor = Accent,
        selectedLeadingIconColor = Accent)
    BoxWithConstraints(modifier.fillMaxSize().background(Paper)) {
        val seatColumns = compactSeatColumns(maxWidth - LibrarySpacing.pageHorizontal * 2)
        val seatRows = remember(visibleCells, seatColumns) { visibleCells.chunked(seatColumns) }
        val minimumBodyHeight = (maxHeight - 44.dp - 20.dp).coerceAtLeast(0.dp)
        val mapScroll = rememberLazyListState()
        val listScroll = rememberLazyListState()
        val activeScroll = if (state.listMode) listScroll else mapScroll
        val switchMode: (Int) -> Unit = { index ->
            val listMode = index == 1
            if (listMode != state.listMode) {
                val destination = if (listMode) listScroll else mapScroll
                // Keep partially visible shared controls at the same offset.
                // Once they are gone, retain each mode's own body position.
                if (activeScroll.firstVisibleItemIndex == 0) {
                    destination.requestScrollToItem(0, activeScroll.firstVisibleItemScrollOffset)
                } else if (destination.firstVisibleItemIndex == 0) {
                    destination.requestScrollToItem(1)
                }
                onListMode(listMode)
            }
        }
        key(state.listMode) {
            LazyColumn(Modifier.fillMaxSize(), state = activeScroll, contentPadding = PaddingValues(bottom = 20.dp)) {
                item(key = "map-controls") {
                    Column(Modifier.fillMaxWidth().padding(start = LibrarySpacing.pageHorizontal, end = LibrarySpacing.pageHorizontal,
                        top = LibrarySpacing.pageVertical, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(LibrarySpacing.sectionGap)) {
                        if (showHeader) {
                            LibraryPageHeader(title = if (state.directRegionView) roomName else "布局选座",
                                subtitle = if (state.directRegionView) listOfNotNull(
                                    state.filters["onDate"],
                                    state.options.buildings.find { it.value == state.filters["building"] }?.label,
                                    state.options.floors.find { it.value == state.filters["floor"] }?.label,
                                ).joinToString(" · ") else "沿着真实布局，找到喜欢的位置。",
                                onBack = onBack, onRefresh = onRefresh, refreshDescription = "刷新座位地图", refreshEnabled = !state.loading)
                        }
                        else if (state.directRegionView) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LibrarySectionTitle(roomName)
                                Text(listOfNotNull(
                                    state.filters["onDate"],
                                    state.options.buildings.find { it.value == state.filters["building"] }?.label,
                                    state.options.floors.find { it.value == state.filters["floor"] }?.label,
                                ).joinToString(" · "), color = Muted, fontSize = 12.sp)
                            }
                        }
                        if (state.loading) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
                                Text(if (state.layout == null) "正在加载区域与座位…" else "正在更新座位状态…", color = Muted, fontSize = 12.sp)
                            }
                        }
                        if (!state.directRegionView) {
                            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        state.options.dates.forEach { date -> FilterChip(selected = state.filters["onDate"] == date.value,
                                            onClick = { onFilter("onDate", date.value) }, enabled = !state.loading,
                                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = date.value }, colors = chipColors,
                                            label = { Text(friendlyDate(date), fontSize = 12.sp) }) }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        val building = state.options.buildings.find { it.value == state.filters["building"] }?.label ?: "选择场馆"
                                        val floor = state.options.floors.find { it.value == state.filters["floor"] }?.label ?: "选择楼层"
                                        MapFilterField("场馆", building, !state.loading && state.options.buildings.isNotEmpty(), Modifier.weight(1f)) { choosing = "building" }
                                        MapFilterField("楼层", floor, !state.loading && state.options.floors.isNotEmpty(), Modifier.weight(1f)) { choosing = "floor" }
                                    }
                                    if (state.rooms.isNotEmpty()) Text("阅览区域", fontSize = 11.sp, color = Muted)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        state.rooms.forEach { room -> FilterChip(selected = state.layout?.roomId == room.id,
                                            onClick = { onLoadRoom(room.id) }, enabled = !state.loading, colors = chipColors,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            label = { Text(room.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }) }
                                    }
                                }
                            }
                        }
                        state.error?.let { error ->
                            Surface(color = Color(0xFFF8EAE4), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(error, color = Color(0xFF8E473A), fontSize = 13.sp)
                                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("重试") }
                                }
                            }
                        }
                        state.notice?.let { notice -> Text(notice, color = Muted, fontSize = 13.sp, lineHeight = 21.sp) }
                        if (state.verificationRequired) {
                            Button(onClick = onVerify, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("完成验证") }
                        }
                        if (state.layout != null && !state.directRegionView) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(LibrarySpacing.itemGap)) {
                                LibrarySectionTitle(roomName, Modifier.weight(1f))
                                Text("${seatCells.count { it.seat?.available == true && !it.localOnly }} 个可选座位", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (state.layout != null) stickyHeader(key = "map-mode-tabs") {
                    // Keep padding outside the selectable bar's semantics.
                    Box(Modifier.fillMaxWidth().background(Paper).padding(horizontal = LibrarySpacing.pageHorizontal)
                        .semantics { contentDescription = "座位视图切换" }) {
                        LibrarySegmentedTabs(labels = listOf("地图", "列表"), selectedIndex = if (state.listMode) 1 else 0,
                            onSelect = switchMode, enabled = !state.loading)
                    }
                }
                state.layout?.let { layout ->
                    if (state.listMode) {
                        // A short result set still fills the body viewport, so
                        // switching modes cannot pull the sticky tabs down.
                        if (seatRows.isEmpty() || 70.dp * seatRows.size + 40.dp < minimumBodyHeight) {
                            item(key = "seat-list-short") {
                                Column(Modifier.fillMaxWidth().heightIn(min = minimumBodyHeight)
                                    .padding(horizontal = LibrarySpacing.pageHorizontal).padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MapSeatListHeader(onlyAvailable, visibleCells.size, seatCells.size, onToggleFavorite != null) { onlyAvailable = it }
                                    if (visibleCells.isEmpty()) LibraryEmptyState(
                                        title = if (onlyAvailable) "暂无可预约座位" else "暂无座位信息",
                                        message = if (onlyAvailable) "关闭“仅看可用”可查看全部座位状态。" else "请刷新或选择其他阅览区域。",
                                        icon = Icons.Outlined.GridView)
                                    seatRows.forEach { cells ->
                                        MapSeatCardRow(cells, seatColumns, !state.loading, favoriteSeatIds, onToggleFavorite) { selected = it }
                                    }
                                }
                            }
                        } else {
                            item(key = "seat-list-filter") {
                                Box(Modifier.padding(horizontal = LibrarySpacing.pageHorizontal).padding(top = 8.dp, bottom = 6.dp)) {
                                    MapSeatListHeader(onlyAvailable, visibleCells.size, seatCells.size, onToggleFavorite != null) { onlyAvailable = it }
                                }
                            }
                            items(seatRows, key = { "seat-row-${it.first().seat!!.id}" }, contentType = { "seat-cards" }) { cells ->
                                MapSeatCardRow(cells, seatColumns, !state.loading, favoriteSeatIds, onToggleFavorite,
                                    Modifier.padding(horizontal = LibrarySpacing.pageHorizontal).padding(bottom = 6.dp)) { selected = it }
                            }
                        }
                    } else item(key = "map-body") {
                        Column(Modifier.fillMaxWidth().heightIn(min = minimumBodyHeight)
                            .padding(horizontal = LibrarySpacing.pageHorizontal).padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(LibrarySpacing.itemGap)) {
                            if (state.directRegionView) NativeRoomCanvas(layout, !state.loading, selected?.seat?.id) { selected = it }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                (SeatVisualState.entries.map(::mapSeatStyle) + MapLocalOnly).forEach { style ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(style.fill), contentAlignment = Alignment.Center) {
                                            Text(style.marker, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text(style.label, color = Ink, fontSize = 11.sp)
                                    }
                                }
                            }
                            if (!state.directRegionView) NativeRoomCanvas(layout, !state.loading, selected?.seat?.id) { selected = it }
                            Text("拖动查看 · 双指缩放 · 点选座位\n颜色与座位内文字表示当前状态，可预约时段以点选查询为准。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                        }
                    }
                }
                if (state.layout == null && !state.loading && state.error == null) item {
                    LibraryEmptyState(title = "找到你喜欢的位置",
                        message = if (state.rooms.isEmpty()) "选择日期和楼层，查看阅览区域" else "选择上方阅览区域，加载座位布局",
                        icon = Icons.Outlined.GridView)
                }
            }
        }
    }
    choosing?.let { key ->
        val options = if (key == "building") state.options.buildings else state.options.floors
        BusinessSelectionSheet(onDismissRequest = { choosing = null }, title = { Text(if (key == "building") "选择场馆" else "选择楼层") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = dialogHeight).verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    val active = state.filters[key] == option.value
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) SoftAccent else Color.Transparent)
                        .selectable(selected = active, role = Role.RadioButton, onClick = { choosing = null; onFilter(key, option.value) })
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, Modifier.weight(1f), color = if (active) Accent else Ink, fontSize = 14.sp)
                        RadioButton(selected = active, onClick = null)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = null }) { Text("关闭") } })
    }
    selected?.let { cell ->
        val seat = cell.seat ?: return@let
        val style = cellStyle(cell)
        BusinessSelectionSheet(onDismissRequest = { selected = null }, title = { Text("${seat.label} · ${seat.room}") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = style.fill, shape = RoundedCornerShape(8.dp)) {
                        Text(style.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    Text(SeatPresentationPolicy.availabilityLabel(seat.available && !cell.localOnly), color = Ink, fontSize = 13.sp)
                }
                Text(if (cell.localOnly) "该座位仅支持现场预约" else seat.status, color = Ink)
                if (!cell.localOnly) SeatPresentationPolicy.availabilityDetail(
                    SeatPresentationPolicy.visualState(seat.status, seat.available), seat.available
                )?.let { Text(it, color = Muted, fontSize = 12.sp) }
                Text(state.filters["onDate"].orEmpty(), color = Muted)
                Text("第 ${cell.row + 1} 行 · 第 ${cell.column + 1} 列", color = Muted, fontSize = 12.sp)
                if (cell.power || cell.window) Text(listOfNotNull(if (cell.power) "有电源" else null, if (cell.window) "靠窗" else null).joinToString(" · "), color = Accent)
            }
        }, confirmButton = {
            if (seat.available && !cell.localOnly) Button(enabled = !state.loading,
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp),
                onClick = { selected = null; onSeat(seat, state.filters["onDate"].orEmpty()) }) { Text("选择使用时间") }
        }, dismissButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSeatListHeader(onlyAvailable: Boolean, visibleCount: Int, totalCount: Int, canFavorite: Boolean, onOnlyAvailable: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("仅看可用", color = Ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                Text("显示 $visibleCount / 全部 $totalCount 个", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                Switch(checked = onlyAvailable, onCheckedChange = onOnlyAvailable, modifier = Modifier.semantics {
                    contentDescription = "仅看可用"
                    stateDescription = if (onlyAvailable) "仅显示可预约座位" else "显示全部座位"
                }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent))
            }
        }
        if (visibleCount > 0) Text(if (canFavorite) "点按查看 · 长按收藏" else "点按查看座位与可用时间",
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun MapSeatCardRow(cells: List<NativeMapCell>, columns: Int, enabled: Boolean, favoriteSeatIds: Set<String>,
    onToggleFavorite: ((NativeSeat) -> Unit)?, modifier: Modifier = Modifier, onSelect: (NativeMapCell) -> Unit) {
    CompactSeatRow(cells.map { it.toNativeSeat(favoriteSeatIds) }, columns, enabled, modifier, onToggleFavorite,
        canFavorite = { seat -> cells.none { it.seat?.id == seat.id && it.localOnly } },
        showDetails = true, onSelectSeat = { seat -> onSelect(cells.first { it.seat?.id == seat.id }) })
}

private fun NativeMapCell.toNativeSeat(favoriteSeatIds: Set<String>): NativeSeat {
    val model = requireNotNull(seat)
    return NativeSeat(model.id, model.label, model.room, power, window,
        status = if (localOnly) "仅限现场预约" else model.status,
        available = model.available && !localOnly, localFavorite = model.id in favoriteSeatIds)
}

@Composable
private fun MapFilterField(title: String, value: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Paper)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 60.dp).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value, Modifier.weight(1f), color = Ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp), tint = if (enabled) Accent else Muted)
        }
    }
}

private fun friendlyDate(option: LibraryOption): String {
    val parsed = runCatching { LocalDate.parse(option.value) }.getOrNull() ?: return option.label
    val shortDate = "${parsed.monthValue}/${parsed.dayOfMonth}"
    return when (parsed) {
        LocalDate.now() -> "今天 $shortDate"
        LocalDate.now().plusDays(1) -> "明天 $shortDate"
        else -> shortDate
    }
}

@Composable
internal fun NativeRoomCanvas(layout: NativeRoomLayout, enabled: Boolean, selectedSeatId: String?, onSelect: (NativeMapCell) -> Unit) {
    var zoom by remember(layout) { mutableFloatStateOf(1f) }
    var pan by remember(layout) { mutableStateOf(Offset.Zero) }
    var reset by remember(layout) { mutableIntStateOf(0) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelect by rememberUpdatedState(onSelect)
    val structureLabelColor = Muted
    val selectionColor = MaterialTheme.colorScheme.tertiary
    val viewportHeight = (LocalConfiguration.current.screenHeightDp.dp * .5f).coerceIn(200.dp, 430.dp)
    val meaningful = remember(layout) { layout.cells.filter { it.kind != NativeMapCellKind.EMPTY } }
    val leftColumn = meaningful.minOfOrNull { it.column } ?: 0
    val topRow = meaningful.minOfOrNull { it.row } ?: 0
    val rightColumn = meaningful.maxOfOrNull { it.column + it.columnSpan } ?: maxOf(1, layout.columns)
    val bottomRow = meaningful.maxOfOrNull { it.row + it.rowSpan } ?: maxOf(1, layout.rows)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { reset++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("适应区域", fontSize = 12.sp) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(viewportHeight).clip(RoundedCornerShape(18.dp)).background(Color.White)) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val viewWidth = with(density) { maxWidth.toPx() }
            val viewHeight = with(density) { maxHeight.toPx() }
            val cellSize = with(density) { 49.dp.toPx() }
            // Focus only the meaningful outer bounds. Internal aisles and every
            // server row/column remain intact for drawing and hit testing.
            val contentLeft = leftColumn * cellSize
            val contentTop = topRow * cellSize
            val roomWidth = maxOf(1, rightColumn - leftColumn) * cellSize
            val roomHeight = maxOf(1, bottomRow - topRow) * cellSize
            val fit = minOf((viewWidth - 24f) / roomWidth, (viewHeight - 24f) / roomHeight).coerceIn(.01f, 2.5f)
            val basePan = Offset((viewWidth - roomWidth * fit) / 2 - contentLeft * fit, (viewHeight - roomHeight * fit) / 2 - contentTop * fit)
            LaunchedEffect(layout, viewWidth, viewHeight, reset) { zoom = fit; pan = basePan }
            fun constrain(value: Offset, scale: Float): Offset {
                val margin = cellSize * scale
                val leftEdge = value.x + contentLeft * scale
                val topEdge = value.y + contentTop * scale
                return Offset(leftEdge.coerceIn(minOf(viewWidth - roomWidth * scale - margin, margin), maxOf(margin, viewWidth - roomWidth * scale + margin)) - contentLeft * scale,
                    topEdge.coerceIn(minOf(viewHeight - roomHeight * scale - margin, margin), maxOf(margin, viewHeight - roomHeight * scale + margin)) - contentTop * scale)
            }
            val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) } }
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "${layout.roomName}座位地图，双指缩放。使用列表按钮逐项浏览座位。" }
                .pointerInput(layout, cellSize, viewWidth, viewHeight) {
                    detectTransformGestures { centroid, move, multiplier, _ ->
                        if (latestEnabled) {
                            val nextZoom = (zoom * multiplier).coerceIn(fit, 2.5f)
                            val ratio = nextZoom / zoom
                            pan = constrain(centroid - (centroid - pan) * ratio + move, nextZoom)
                            zoom = nextZoom
                        }
                    }
                }.pointerInput(layout, cellSize) {
                    detectTapGestures { tap ->
                        if (latestEnabled) {
                            val position = (tap - pan) / zoom
                            val column = (position.x / cellSize).toInt()
                            val row = (position.y / cellSize).toInt()
                            if (position.x >= 0 && position.y >= 0) layout.cells.firstOrNull {
                                it.seat != null && row in it.row until it.row + it.rowSpan && column in it.column until it.column + it.columnSpan
                            }?.let(latestSelect)
                        }
                    }
                }) {
                clipRect {
                    withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset.Zero) }) {
                        layout.cells.forEach { cell ->
                            val left = cell.column * cellSize
                            val top = cell.row * cellSize
                            val width = cell.columnSpan * cellSize
                            val height = cell.rowSpan * cellSize
                            if (cell.kind == NativeMapCellKind.SEAT) {
                                val style = cellStyle(cell)
                                if (cell.seat?.id == selectedSeatId) {
                                    val ringPosition = Offset(left + cellSize * .025f, top + cellSize * .035f)
                                    val ringSize = Size(width - cellSize * .05f, height - cellSize * .11f)
                                    val ringRadius = androidx.compose.ui.geometry.CornerRadius(cellSize * .15f)
                                    drawRoundRect(Color(0xFF172535), ringPosition, ringSize, ringRadius, style = Stroke(cellSize * .08f))
                                    drawRoundRect(selectionColor, ringPosition, ringSize, ringRadius, style = Stroke(cellSize * .035f))
                                }
                                drawRoundRect(style.fill, Offset(left + cellSize * .07f, top + cellSize * .12f), Size(width - cellSize * .14f, height - cellSize * .23f), androidx.compose.ui.geometry.CornerRadius(cellSize * .12f))
                                drawRoundRect(style.fill, Offset(left + cellSize * .13f, top + cellSize * .055f), Size(width - cellSize * .26f, cellSize * .1f), androidx.compose.ui.geometry.CornerRadius(cellSize * .04f))
                                drawLine(style.fill, Offset(left + cellSize * .16f, top + height * .86f), Offset(left + width - cellSize * .16f, top + height * .86f), cellSize * .05f)
                                paint.color = Color.White.toArgb()
                                paint.textSize = cellSize * .23f
                                drawContext.canvas.nativeCanvas.drawText(cell.seat?.label.orEmpty(), left + width / 2, top + height * .47f, paint)
                                paint.textSize = cellSize * .17f
                                drawContext.canvas.nativeCanvas.drawText(style.marker, left + width / 2, top + height * .70f, paint)
                                if (cell.power) {
                                    val powerPosition = Offset(left + width - cellSize * .16f, top + height * .68f)
                                    drawCircle(Color.White, cellSize * .055f, powerPosition)
                                    drawCircle(Color(0xFF172535), cellSize * .025f, powerPosition)
                                }
                            } else if (cell.kind != NativeMapCellKind.EMPTY) {
                                val color = when (cell.kind) { NativeMapCellKind.WINDOW -> Color(0xFFE1E9F0); NativeMapCellKind.WALL -> Color(0xFFB7B8BC); else -> Color(0xFFEEEEF0) }
                                drawRoundRect(color, Offset(left + 3f, top + 3f), Size(width - 6f, height - 6f), androidx.compose.ui.geometry.CornerRadius(5f))
                                if (cell.label.isNotBlank()) {
                                    paint.color = structureLabelColor.toArgb()
                                    paint.textSize = cellSize * .19f
                                    drawContext.canvas.nativeCanvas.drawText(cell.label.take(12), left + width / 2, top + height / 2, paint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
