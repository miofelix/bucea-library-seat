package cn.edu.bucea.library.ui

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
) {
    var choosing by remember { mutableStateOf<String?>(null) }
    var selected by remember(state.layout?.roomId, state.filters["onDate"], state.layout) { mutableStateOf<NativeMapCell?>(null) }
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
    LazyColumn(modifier.fillMaxSize().background(Paper), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (showHeader) item {
            LibraryPageHeader(title = if (state.directRegionView) roomName else "布局选座",
                subtitle = if (state.directRegionView) listOfNotNull(
                    state.filters["onDate"],
                    state.options.buildings.find { it.value == state.filters["building"] }?.label,
                    state.options.floors.find { it.value == state.filters["floor"] }?.label,
                ).joinToString(" · ") else "沿着真实布局，找到喜欢的位置。",
                onBack = onBack, onRefresh = onRefresh, refreshDescription = "刷新座位地图", refreshEnabled = !state.loading)
        }
        else if (state.directRegionView) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LibrarySectionTitle(roomName)
                Text(listOfNotNull(
                    state.filters["onDate"],
                    state.options.buildings.find { it.value == state.filters["building"] }?.label,
                    state.options.floors.find { it.value == state.filters["floor"] }?.label,
                ).joinToString(" · "), color = Muted, fontSize = 12.sp)
            }
        }
        if (state.loading) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
                Text(if (state.layout == null) "正在加载区域与座位…" else "正在更新座位状态…", color = Muted, fontSize = 12.sp)
            }
        }
        if (!state.directRegionView) item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        state.error?.let { error -> item {
            Surface(color = Color(0xFFF8EAE4), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                    Text(error, color = Color(0xFF8E473A), fontSize = 13.sp)
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("重试") }
                }
            }
        } }
        state.notice?.let { notice -> item { Text(notice, color = Muted, fontSize = 13.sp, lineHeight = 21.sp) } }
        if (state.verificationRequired) item {
            Button(onClick = onVerify, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("完成验证") }
        }
        state.layout?.let { layout ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!state.directRegionView) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LibrarySectionTitle(roomName, Modifier.weight(1f))
                        Text("${layout.cells.count { it.seat?.available == true }} 个可选座位", color = Muted, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SoftAccent).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(false to "地图", true to "列表").forEach { (listMode, label) ->
                            val active = state.listMode == listMode
                            Surface(onClick = { onListMode(listMode) }, selected = active,
                                color = if (active) Color.White else Color.Transparent, shape = RoundedCornerShape(11.dp), modifier = Modifier.weight(1f)) {
                                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center) {
                                    Icon(if (listMode) Icons.Outlined.List else Icons.Outlined.GridView, null, Modifier.size(17.dp), tint = if (active) Accent else Muted)
                                    Spacer(Modifier.width(7.dp))
                                    Text(label, fontSize = 13.sp, color = if (active) Accent else Muted)
                                }
                            }
                        }
                    }
                }
            }
            if (state.listMode) {
                items(layout.cells.filter { it.seat != null }, key = { it.seat!!.id }) { cell ->
                    val seat = cell.seat!!
                    val style = cellStyle(cell)
                    Card(onClick = { selected = cell }, enabled = !state.loading, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = style.fill, shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Outlined.Chair, null, tint = Color.White, modifier = Modifier.padding(8.dp).size(24.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(seat.label, color = Ink, fontWeight = FontWeight.SemiBold)
                                Text("第 ${cell.row + 1} 行 · 第 ${cell.column + 1} 列", color = Muted, fontSize = 11.sp)
                                Text(SeatPresentationPolicy.availabilityLabel(seat.available && !cell.localOnly), color = Ink, fontSize = 11.sp)
                            }
                            Surface(color = style.fill, shape = RoundedCornerShape(8.dp)) {
                                Text(style.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.widthIn(max = 100.dp).padding(horizontal = 8.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
            } else item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.directRegionView) NativeRoomCanvas(layout, !state.loading, selected?.seat?.id) { selected = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    choosing?.let { key ->
        val options = if (key == "building") state.options.buildings else state.options.floors
        BusinessSelectionSheet(onDismissRequest = { choosing = null }, title = { Text(if (key == "building") "选择场馆" else "选择楼层") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = dialogHeight).verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    val active = state.filters[key] == option.value
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) SoftAccent else Color.Transparent)
                        .selectable(selected = active, role = Role.RadioButton, onClick = { choosing = null; onFilter(key, option.value) })
                        .heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                if (cell.power || cell.window) Text(listOfNotNull(if (cell.power) "有电源" else null, if (cell.window) "靠窗" else null).joinToString(" · "), color = Accent)
            }
        }, confirmButton = {
            if (seat.available && !cell.localOnly) Button(enabled = !state.loading,
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp),
                onClick = { selected = null; onSeat(seat, state.filters["onDate"].orEmpty()) }) { Text("选择使用时间") }
        }, dismissButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
}

@Composable
private fun MapFilterField(title: String, value: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Paper)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 68.dp).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value, Modifier.weight(1f), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
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
