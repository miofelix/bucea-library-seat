package cn.edu.bucea.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
    val reservationRequiresOfficial: Boolean = false,
    val reservationOutcomeUncertain: Boolean = false,
    val accountLabel: String = "",
)

private val SeatInk = Color(0xFF183334)
private val SeatTeal = Color(0xFF28766C)
private val SeatMuted = Color(0xFF778583)
private val SeatPaper = Color(0xFFF6F7F2)
private val SeatSoft = Color(0xFFE5EFE8)

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
    onOpenOfficial: () -> Unit,
    onToggleFavorite: (NativeSeat) -> Unit = {},
    onSelectStart: (String) -> Unit = {},
    onSelectEnd: (String) -> Unit = {},
    onConfirmReservation: () -> Unit = {},
    onDismissReservation: () -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val resultsRequester = remember { BringIntoViewRequester() }
    val byNumber = state.filters["name"].orEmpty().isNotBlank()
    LaunchedEffect(state.page, state.hasSearched, state.loading) {
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
    Scaffold(
        containerColor = SeatPaper,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回首页", tint = SeatInk) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("找一个好座位", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SeatInk)
                    Text("选好位置，安心专注", fontSize = 11.sp, color = SeatMuted)
                }
                IconButton(onClick = onRefresh, enabled = !state.loading && !state.reservationBusy) {
                    Icon(Icons.Outlined.Refresh, "刷新座位筛选", tint = SeatTeal)
                }
                TextButton(onClick = onOpenOfficial) { Text("布局选座", fontSize = 12.sp) }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.FilterAlt, null, tint = SeatTeal, modifier = Modifier.size(18.dp))
                        Text("按你的习惯选择", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val dates = state.options["onDate"].orEmpty()
                    if (dates.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            dates.filterNot { it.value.isBlank() || it.value == "null" }.forEach { option ->
                                FilterChip(
                                    selected = state.filters["onDate"] == option.value,
                                    onClick = { onFilter("onDate", option.value) },
                                    label = { Text(option.label, fontSize = 12.sp) },
                                    enabled = !state.loading,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SeatSoft,
                                        selectedLabelColor = SeatTeal,
                                        selectedLeadingIconColor = SeatTeal,
                                    ),
                                    leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterField("场馆", selectedLabel(state, "building", "不限场馆"), !state.loading && state.options["building"].orEmpty().isNotEmpty(), Modifier.weight(1f)) { choosing = "building" }
                        FilterField("阅览区域", selectedLabel(state, "room", "不限区域"), !state.loading && state.options["room"].orEmpty().isNotEmpty(), Modifier.weight(1f)) { choosing = "room" }
                    }
                    OutlinedTextField(
                        value = state.filters["name"].orEmpty(),
                        onValueChange = { onFilter("name", it) },
                        label = { Text("座位号（可选）") },
                        singleLine = true,
                        enabled = !state.loading,
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (byNumber) Text("按编号查询仅使用日期和区域，点选座位后确认可用时段。", fontSize = 11.sp, color = SeatMuted)
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
                    TextButton(onClick = { advanced = !advanced }, enabled = !byNumber, modifier = Modifier.fillMaxWidth()) {
                        Text(if (advanced) "收起筛选条件" else "时间、电源与靠窗", fontSize = 12.sp)
                        Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp))
                    }
                    Button(onClick = onSearch, enabled = !state.loading && state.options.isNotEmpty() && !state.filters["onDate"].isNullOrBlank(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        if (state.loading) CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
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
                        TextButton(onClick = onSearch, enabled = !state.loading) { Text("重新查询") }
                    }
                }
            }
            state.status?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = SeatMuted) }
            if (state.reservationRequiresOfficial && state.selectedSeat == null) {
                OutlinedButton(onClick = onOpenOfficial, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("进入完整功能中心验证")
                }
            }
            Row(Modifier.fillMaxWidth().bringIntoViewRequester(resultsRequester), verticalAlignment = Alignment.CenterVertically) {
                Text("可用座位", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SeatInk, modifier = Modifier.weight(1f))
                if (state.hasSearched) Text("已加载 ${state.seats.size} 个 · 第 ${state.page + 1} 页", fontSize = 12.sp, color = SeatMuted)
            }
            if (state.seats.isEmpty() && !state.loading) {
                Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Box(Modifier.size(62.dp).clip(RoundedCornerShape(20.dp)).background(SeatSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Chair, null, tint = SeatTeal, modifier = Modifier.size(30.dp))
                    }
                    Text(if (state.hasSearched) "暂时没有符合条件的座位" else "先选择日期，再找到你的座位", fontSize = 15.sp, color = SeatInk, fontWeight = FontWeight.Medium)
                    Text(if (state.hasSearched) "试试其他阅览区域或时间条件。" else "座位与可用时段将从图书馆实时查询。", fontSize = 12.sp, color = SeatMuted)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (LocalDensity.current.fontScale >= 1.3f) 2 else if (maxWidth >= 620.dp) 4 else if (maxWidth >= 410.dp) 3 else 2
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.seats.chunked(columns).forEach { seats ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            seats.forEach { seat ->
                                SeatCard(seat, !state.loading && !state.reservationBusy, Modifier.weight(1f), { onToggleFavorite(seat) }) { onSelectSeat(seat) }
                            }
                            repeat(columns - seats.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (state.hasSearched && (state.page > 0 || state.hasNextPage)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPreviousPage, enabled = state.page > 0 && !state.loading, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp)); Text("上一页", fontSize = 12.sp)
                    }
                    Text("${state.page + 1}", color = SeatMuted, fontSize = 12.sp)
                    OutlinedButton(onClick = onNextPage, enabled = state.hasNextPage && !state.loading, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                        Text("下一页", fontSize = 12.sp); Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(15.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onOpenOfficial) { Icon(Icons.Outlined.GridView, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("使用座位地图与完整功能", fontSize = 12.sp) }
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
        ReservationDialog(state, seat, onSelectStart, onSelectEnd, onConfirmReservation, onDismissReservation, onOpenOfficial)
    }
}

private fun selectedLabel(state: SeatSearchState, key: String, fallback: String): String =
    state.options[key].orEmpty().firstOrNull { it.value == state.filters[key] }?.label ?: fallback

@Composable
private fun FilterField(title: String, selected: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(SeatPaper).clickable(enabled = enabled, onClick = onClick).padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 10.sp, color = SeatMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selected, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SeatInk, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, tint = if (enabled) SeatTeal else SeatMuted, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun SeatCard(seat: NativeSeat, enabled: Boolean, modifier: Modifier, onFavorite: () -> Unit, onSelect: () -> Unit) {
    Card(modifier.clickable(enabled = enabled && seat.available, onClick = onSelect), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Chair, null, tint = SeatTeal, modifier = Modifier.size(23.dp))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onFavorite, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(if (seat.localFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (seat.localFavorite) "取消收藏 ${seat.name}" else "收藏 ${seat.name}", tint = if (seat.localFavorite) Color(0xFFB99345) else SeatMuted, modifier = Modifier.size(19.dp))
                }
            }
            Text(seat.name, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = SeatInk)
            Text(seat.location, fontSize = 11.sp, lineHeight = 17.sp, color = SeatMuted)
            Text(seat.status, fontSize = 10.sp, color = if (seat.available) SeatTeal else SeatMuted)
            if (seat.power == true || seat.window == true) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (seat.power == true) SeatCapability(Icons.Outlined.Power, "电源")
                    if (seat.window == true) SeatCapability(Icons.Outlined.Window, "靠窗")
                }
            }
        }
    }
}

@Composable
private fun SeatCapability(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, tint = SeatTeal, modifier = Modifier.size(12.dp))
        Text(label, fontSize = 10.sp, color = SeatTeal)
    }
}

@Composable
private fun OptionDialog(title: String, options: List<SeatOption>, selected: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.48f).coerceAtMost(340.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).selectable(selected = selected == option.value, role = Role.RadioButton, onClick = { onSelect(option.value) }).padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = if (selected == option.value) SeatTeal else SeatInk)
                        RadioButton(selected = selected == option.value, onClick = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ReservationDialog(state: SeatSearchState, seat: NativeSeat, onStart: (String) -> Unit, onEnd: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, onOpenOfficial: () -> Unit) {
    var confirming by remember(seat.id, state.filters["onDate"], state.selectedStart, state.selectedEnd, state.accountLabel) { mutableStateOf(false) }
    val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.5f).coerceAtMost(420.dp)
    val start = state.startOptions.firstOrNull { it.value == state.selectedStart }?.label
    val end = state.endOptions.firstOrNull { it.value == state.selectedEnd }?.label
    AlertDialog(
        onDismissRequest = { if (!state.reservationBusy) onDismiss() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (confirming) "确认这次预约" else "选择可用时段", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("${seat.name} · ${seat.location}", fontSize = 12.sp, color = SeatMuted)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.reservationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Surface(color = Color(0xFFF8EAE4), shape = RoundedCornerShape(12.dp)) {
                        Text(error, modifier = Modifier.fillMaxWidth().padding(13.dp), color = Color(0xFF8E473A), fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
                if (confirming) {
                    Surface(color = SeatSoft, shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(selectedLabel(state, "onDate", state.filters["onDate"].orEmpty()), fontSize = 14.sp, color = SeatInk)
                            if (state.accountLabel.isNotBlank()) Text("预约账号：${state.accountLabel}", fontSize = 12.sp, color = SeatMuted)
                            Text("$start — $end", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = SeatTeal)
                        }
                    }
                    Text("提交后将按图书馆规则生成预约，请确认日期和时间。", fontSize = 12.sp, lineHeight = 20.sp, color = SeatMuted)
                } else {
                    TimeChoices("开始时间", state.startOptions, state.selectedStart, !state.reservationBusy,
                        if (state.reservationBusy) "正在获取可用时段…" else "当前没有可用开始时间。", onStart)
                    HorizontalDivider()
                    TimeChoices("结束时间", state.endOptions, state.selectedEnd, !state.reservationBusy,
                        when {
                            state.reservationBusy -> "正在获取可用时段…"
                            state.selectedStart == null -> "选择开始时间后显示可用结束时间。"
                            else -> "当前没有可用结束时间，请选择其他开始时间。"
                        }, onEnd)
                    if (state.startOptions.isEmpty() && !state.reservationBusy && state.error == null) Text("可以返回选择其他日期或座位。", fontSize = 12.sp, color = SeatMuted)
                }
                state.status?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = SeatMuted) }
                if (state.reservationRequiresOfficial) {
                    Text("图书馆需要额外验证，请在完整功能中心完成验证后继续。", fontSize = 12.sp, lineHeight = 20.sp, color = SeatMuted)
                    OutlinedButton(onClick = onOpenOfficial, modifier = Modifier.fillMaxWidth()) { Text("进入完整功能中心验证") }
                }
                if (state.reservationOutcomeUncertain) {
                    Text("预约结果尚未确认，请先查看“我的预约”。核实前无法重复提交。", fontSize = 12.sp, lineHeight = 20.sp, color = SeatMuted)
                    OutlinedButton(onClick = onOpenOfficial, modifier = Modifier.fillMaxWidth()) { Text("查看我的预约") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (confirming) onConfirm() else confirming = true }, enabled = !state.reservationBusy && !state.reservationRequiresOfficial && !state.reservationOutcomeUncertain && start != null && end != null) { Text(if (state.reservationBusy) "正在处理…" else if (confirming) "确认预约" else "下一步") } },
        dismissButton = { TextButton(onClick = { if (confirming) confirming = false else onDismiss() }, enabled = !state.reservationBusy) { Text(if (confirming) "修改时间" else "取消") } }
    )
}

@Composable
private fun TimeChoices(title: String, options: List<SeatOption>, selected: String?, enabled: Boolean, emptyMessage: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SeatInk)
        if (options.isEmpty()) Text(emptyMessage, fontSize = 11.sp, color = SeatMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option.value,
                    onClick = { onSelect(option.value) },
                    label = { Text(option.label, fontSize = 12.sp) },
                    enabled = enabled,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SeatSoft,
                        selectedLabelColor = SeatTeal,
                        selectedLeadingIconColor = SeatTeal,
                    ),
                )
            }
        }
    }
}
