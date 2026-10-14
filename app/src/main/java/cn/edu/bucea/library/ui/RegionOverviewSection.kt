package cn.edu.bucea.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.data.models.RegionOverviewState
import cn.edu.bucea.library.data.models.RegionSeatCounts
import cn.edu.bucea.library.data.models.RegionSeatOverview
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The cards describe separately fetched room layouts for the displayed date. */
@Composable
internal fun RegionOverviewSection(state: RegionOverviewState, onRefresh: () -> Unit,
    onVerify: () -> Unit, authenticated: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    onRegion: (RegionSeatOverview) -> Unit = {}) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("各区域座位", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(regionDateLabel(state.date), color = Muted, fontSize = 11.sp)
            }
            LibraryIconButton(Icons.Outlined.Refresh, "刷新各区域座位", onRefresh,
                enabled = authenticated && enabled && !state.loading && !state.verificationRequired)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (expanded) "收起" else "展开", fontSize = 12.sp)
            }
        }
        if (state.loading) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                if (state.totalRegions > 0 && (state.discoveryComplete || state.completedRegions > 0)) {
                    LinearProgressIndicator(progress = { state.completedRegions.toFloat().coerceIn(0f, state.totalRegions.toFloat()) / state.totalRegions },
                        modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
                    Text("正在同步 · 已查询 ${state.completedRegions} / ${state.totalRegions} 个区域", color = Muted, fontSize = 11.sp)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
                    Text("正在读取场馆与区域…", color = Muted, fontSize = 11.sp)
                }
            }
        } else if (state.updatedAt != null || state.stale || state.regions.any { it.counts != null }) {
            val loadedRegions = state.regions.filter { it.counts != null }
            val freshData = loadedRegions.any { !it.stale }
            val partial = state.stale || !state.discoveryComplete || state.error != null || state.verificationRequired ||
                state.discoveryErrors.isNotEmpty() || state.regions.any { it.stale || it.counts == null || it.error != null }
            val label = when {
                freshData && partial -> "部分区域已同步"
                freshData -> "区域数据已同步"
                loadedRegions.isNotEmpty() -> "上次区域数据"
                else -> "区域数据未同步"
            }
            val timestamp = state.updatedAt?.let {
                if (partial || !freshData) " · 上次完整同步 ${regionTimeLabel(it)}" else " · ${regionTimeLabel(it)} 更新"
            }.orEmpty()
            Text(label + timestamp, color = Muted, fontSize = 11.sp)
        }
        if (!expanded) return@Column
        if (state.verificationRequired) {
            LibraryCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Shield, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Text("区域同步需要额外验证", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text("完成学校要求的验证后，重新读取各区域座位。", color = Muted, fontSize = 12.sp)
                Button(onClick = onVerify, enabled = authenticated && enabled && !state.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("完成验证并继续同步", fontSize = 13.sp)
                }
            }
        }
        val errors = (listOfNotNull(state.error) + state.discoveryErrors).filter { it.isNotBlank() }.distinct()
        if (errors.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = Color(0xFF956013), modifier = Modifier.size(15.dp))
                    Text("部分区域尚未同步", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                errors.forEach { Text(it, color = Muted, fontSize = 11.sp, lineHeight = 18.sp) }
            }
        }
        val columns = if (LocalConfiguration.current.screenWidthDp >= 300) 2 else 1
        state.regions.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                row.forEach { region -> RegionOverviewCard(region, Modifier.weight(1f).fillMaxHeight(),
                    enabled = authenticated && enabled && !region.loading, onClick = { onRegion(region) }) }
                if (row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
        if (state.regions.isEmpty() && !state.loading && !state.verificationRequired) {
            LibraryCard {
                Text(when {
                    !authenticated -> "登录后自动同步各区域座位。"
                    errors.isNotEmpty() -> "暂未获取区域清单，请稍后刷新重试。"
                    state.discoveryComplete -> "该日期未返回座位区域。"
                    else -> "各区域数据待同步。"
                }, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RegionOverviewCard(region: RegionSeatOverview, modifier: Modifier = Modifier,
    enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "打开${region.name}布局选座" },
        color = Color.White, border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(region.name, color = Ink, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(region.buildingName, region.floorName).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (region.loading || region.stale || region.error != null || region.counts == null) Text(when {
                region.loading -> "更新中"
                region.stale -> "上次数据"
                region.error != null -> "未同步"
                region.counts != null -> "已同步"
                else -> "待同步"
            }, color = if (region.stale || region.error != null) Color(0xFF956013) else Muted, fontSize = 10.sp, lineHeight = 14.sp)
            val counts = region.counts
            if (counts == null) {
                Text("座位状态待同步", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                if (region.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
            } else {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = nativeMapSeatStyle("空闲", true).fill, fontWeight = FontWeight.SemiBold)) {
                        append("空闲 ${counts.free}")
                    }
                    withStyle(SpanStyle(color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Normal)) {
                        append(" / 总 ${counts.total}")
                    }
                }, fontSize = 14.sp, lineHeight = 19.sp)
                Text("占用 ${counts.occupied}", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                val metrics = regionMetrics(counts)
                if (counts.total > 0) {
                    Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).semantics {
                        contentDescription = "${region.name}座位状态：总席数${counts.total}，" + metrics.joinToString("，") { "${it.label}${it.count}" }
                    }) {
                        metrics.filter { it.count > 0 }.forEach { metric ->
                            Box(Modifier.weight(metric.count.toFloat()).fillMaxHeight().background(metric.color))
                        }
                    }
                }
                Text(metrics.drop(1).filterIndexed { index, metric -> index < 2 || metric.count > 0 }
                    .joinToString(" · ") { "${it.shortLabel}${it.count}" }, color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
            }
            region.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFF956013), fontSize = 10.sp, lineHeight = 15.sp) }
            Spacer(Modifier.weight(1f))
            if (region.updatedAt != null) Text(
                "${regionShortDateLabel(region.date)} · ${regionTimeLabel(region.updatedAt)}" + if (region.stale) " · 上次" else " 更新",
                color = Muted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class RegionMetric(val label: String, val count: Int, val color: Color, val shortLabel: String = label)

private fun regionMetrics(counts: RegionSeatCounts) = listOf(
    RegionMetric("空闲", counts.free, nativeMapSeatStyle("空闲", true).fill),
    RegionMetric("使用中", counts.inUse, nativeMapSeatStyle("使用中", true).fill, "使用"),
    RegionMetric("已预约", counts.reserved, nativeMapSeatStyle("已预约", true).fill, "预约"),
    RegionMetric("暂离", counts.away, nativeMapSeatStyle("暂离", true).fill),
    RegionMetric("不可用", counts.unavailable, nativeMapSeatStyle("不可用", false).fill),
    RegionMetric("未知", counts.unknown, nativeMapSeatStyle("", false).fill),
    RegionMetric("仅限现场", counts.localOnly, nativeMapSeatStyle("", false, true).fill, "现场"),
)

private fun regionDateLabel(date: String): String {
    val today = LocalDate.now(RegionTimeZone)
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: today
    return (if (parsed == today) "今天 · " else "") + "${parsed.monthValue} 月 ${parsed.dayOfMonth} 日"
}

private val RegionTimeZone = ZoneId.of("Asia/Shanghai")

private fun regionShortDateLabel(date: String): String = runCatching { LocalDate.parse(date) }.getOrNull()?.let {
    if (it == LocalDate.now(RegionTimeZone)) "今天" else "${it.monthValue}/${it.dayOfMonth}"
} ?: "日期待同步"

private fun regionTimeLabel(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(RegionTimeZone)
    .format(DateTimeFormatter.ofPattern("HH:mm"))
