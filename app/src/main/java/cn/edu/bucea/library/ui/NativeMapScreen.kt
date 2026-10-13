package cn.edu.bucea.library.ui

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.data.models.*
import java.time.LocalDate

private val MapInk = Color(0xFF183334)
private val MapTeal = Color(0xFF28766C)
private val MapMuted = Color(0xFF778583)
private val MapPaper = Color(0xFFF6F7F2)

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
        labelColor = MapMuted, selectedContainerColor = Color(0xFFE5EFE8), selectedLabelColor = MapTeal,
        selectedLeadingIconColor = MapTeal)
    LazyColumn(modifier.fillMaxSize().background(MapPaper), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回选座", tint = MapInk) }
                Column(Modifier.weight(1f)) {
                    Text("布局选座", color = MapInk, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("沿着真实布局，找到喜欢的位置", color = MapMuted, fontSize = 11.sp)
                }
                IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "刷新座位地图", tint = MapTeal) }
            }
        }
        if (state.loading) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = MapTeal, trackColor = Color(0xFFE5EFE8))
                Text(if (state.layout == null) "正在加载区域与座位…" else "正在更新座位状态…", color = MapMuted, fontSize = 12.sp)
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.options.dates.forEach { date -> FilterChip(selected = state.filters["onDate"] == date.value,
                            onClick = { onFilter("onDate", date.value) }, enabled = !state.loading,
                            modifier = Modifier.semantics { contentDescription = date.value }, colors = chipColors,
                            label = { Text(friendlyDate(date), fontSize = 12.sp) }) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val building = state.options.buildings.find { it.value == state.filters["building"] }?.label ?: "选择场馆"
                        val floor = state.options.floors.find { it.value == state.filters["floor"] }?.label ?: "选择楼层"
                        OutlinedButton(enabled = !state.loading && state.options.buildings.isNotEmpty(), onClick = { choosing = "building" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) { Text(building, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                        OutlinedButton(enabled = !state.loading && state.options.floors.isNotEmpty(), onClick = { choosing = "floor" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) { Text(floor, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        state.rooms.forEach { room -> FilterChip(selected = state.layout?.roomId == room.id,
                            onClick = { onLoadRoom(room.id) }, enabled = !state.loading, colors = chipColors,
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
        state.notice?.let { notice -> item { Text(notice, color = MapMuted, fontSize = 13.sp, lineHeight = 21.sp) } }
        if (state.verificationRequired) item {
            Button(onClick = onVerify, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("完成验证") }
        }
        state.layout?.let { layout ->
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(roomName, color = MapInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${layout.cells.count { it.seat?.available == true }} 个可选座位", color = MapMuted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { onListMode(!state.listMode) }) {
                        Icon(if (state.listMode) Icons.Outlined.GridView else Icons.Outlined.List, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp)); Text(if (state.listMode) "地图" else "列表", fontSize = 12.sp)
                    }
                }
            }
            if (state.listMode) {
                items(layout.cells.filter { it.seat != null }, key = { it.seat!!.id }) { cell ->
                    val seat = cell.seat!!
                    Card(onClick = { selected = cell }, enabled = !state.loading, shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Chair, null, tint = cellColor(cell), modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(seat.label, color = MapInk, fontWeight = FontWeight.SemiBold)
                                Text("第 ${cell.row + 1} 行 · 第 ${cell.column + 1} 列", color = MapMuted, fontSize = 11.sp)
                            }
                            Text(if (cell.localOnly) "仅限现场" else seat.status, color = cellColor(cell), fontSize = 11.sp)
                        }
                    }
                }
            } else item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("空闲" to MapTeal, "已预约" to Color(0xFFD4A853), "使用中" to Color(0xFF9AACC0), "不可用" to Color(0xFFADB5B4)).forEach { (text, color) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(color)); Text(text, color = MapMuted, fontSize = 10.sp)
                            }
                        }
                    }
                    NativeRoomCanvas(layout, !state.loading) { selected = it }
                    Text("拖动查看 · 双指缩放 · 点选座位\n切换列表可以逐项查看座位和状态。", color = MapMuted, fontSize = 11.sp, lineHeight = 18.sp)
                }
            }
        }
        if (state.layout == null && !state.loading && state.error == null) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.GridView, null, tint = MapTeal, modifier = Modifier.size(36.dp))
                Text(if (state.rooms.isEmpty()) "选择日期和楼层，查看阅览区域" else "选择上方阅览区域，加载座位布局", color = MapMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
    choosing?.let { key ->
        val options = if (key == "building") state.options.buildings else state.options.floors
        AlertDialog(onDismissRequest = { choosing = null }, title = { Text(if (key == "building") "选择场馆" else "选择楼层") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = dialogHeight).verticalScroll(rememberScrollState())) {
                options.forEach { option -> TextButton(onClick = { choosing = null; onFilter(key, option.value) }, modifier = Modifier.fillMaxWidth()) { Text(option.label) } }
            }
        }, confirmButton = { TextButton(onClick = { choosing = null }) { Text("关闭") } })
    }
    selected?.let { cell ->
        val seat = cell.seat ?: return@let
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("${seat.label} · ${seat.room}") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (cell.localOnly) "该座位仅支持现场预约" else seat.status)
                Text(state.filters["onDate"].orEmpty(), color = MapMuted)
                if (cell.power || cell.window) Text(listOfNotNull(if (cell.power) "有电源" else null, if (cell.window) "靠窗" else null).joinToString(" · "), color = MapTeal)
            }
        }, confirmButton = {
            if (seat.available && !cell.localOnly) TextButton(enabled = !state.loading, onClick = { selected = null; onSeat(seat, state.filters["onDate"].orEmpty()) }) { Text("选择使用时间") }
        }, dismissButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
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

private fun cellColor(cell: NativeMapCell): Color = when {
    cell.localOnly -> Color(0xFFAA8BBA)
    cell.seat?.available == true -> MapTeal
    cell.seat?.status?.contains("预约") == true -> Color(0xFFD4A853)
    cell.seat?.status?.contains("使用") == true || cell.seat?.status?.contains("暂离") == true -> Color(0xFF8C9EB5)
    else -> Color(0xFFADB5B4)
}

@Composable
private fun NativeRoomCanvas(layout: NativeRoomLayout, enabled: Boolean, onSelect: (NativeMapCell) -> Unit) {
    var zoom by remember(layout) { mutableFloatStateOf(1f) }
    var pan by remember(layout) { mutableStateOf(Offset.Zero) }
    var reset by remember(layout) { mutableIntStateOf(0) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelect by rememberUpdatedState(onSelect)
    val viewportHeight = (LocalConfiguration.current.screenHeightDp.dp * .5f).coerceIn(200.dp, 430.dp)
    val meaningful = remember(layout) { layout.cells.filter { it.kind != NativeMapCellKind.EMPTY } }
    val leftColumn = meaningful.minOfOrNull { it.column } ?: 0
    val topRow = meaningful.minOfOrNull { it.row } ?: 0
    val rightColumn = meaningful.maxOfOrNull { it.column + it.columnSpan } ?: maxOf(1, layout.columns)
    val bottomRow = meaningful.maxOfOrNull { it.row + it.rowSpan } ?: maxOf(1, layout.rows)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { reset++ }) { Text("适应区域", fontSize = 12.sp) }
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
                                val color = cellColor(cell)
                                drawRoundRect(color.copy(alpha = .16f), Offset(left + cellSize * .07f, top + cellSize * .12f), Size(width - cellSize * .14f, height - cellSize * .23f), androidx.compose.ui.geometry.CornerRadius(cellSize * .12f))
                                drawRoundRect(color, Offset(left + cellSize * .13f, top + cellSize * .08f), Size(width - cellSize * .26f, cellSize * .1f), androidx.compose.ui.geometry.CornerRadius(cellSize * .04f))
                                drawLine(color, Offset(left + cellSize * .16f, top + height * .86f), Offset(left + width - cellSize * .16f, top + height * .86f), cellSize * .05f)
                                paint.color = android.graphics.Color.rgb(24, 51, 52)
                                paint.textSize = cellSize * .21f
                                drawContext.canvas.nativeCanvas.drawText(cell.seat?.label.orEmpty(), left + width / 2, top + height * .59f, paint)
                                if (cell.power) drawCircle(Color(0xFFD4A853), cellSize * .04f, Offset(left + width - cellSize * .12f, top + height - cellSize * .15f))
                            } else if (cell.kind != NativeMapCellKind.EMPTY) {
                                val color = when (cell.kind) { NativeMapCellKind.WINDOW -> Color(0xFFCADFE5); NativeMapCellKind.WALL -> Color(0xFF889694); else -> Color(0xFFE3E8E3) }
                                drawRoundRect(color, Offset(left + 3f, top + 3f), Size(width - 6f, height - 6f), androidx.compose.ui.geometry.CornerRadius(5f))
                                if (cell.label.isNotBlank()) {
                                    paint.color = android.graphics.Color.rgb(99, 116, 124)
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
