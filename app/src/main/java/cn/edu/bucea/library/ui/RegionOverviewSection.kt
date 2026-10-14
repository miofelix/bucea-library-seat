package cn.edu.bucea.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    onVerify: () -> Unit, authenticated: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("各区域座位", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(regionDateLabel(state.date), color = Muted, fontSize = 11.sp)
            }
            LibraryIconButton(Icons.Outlined.Refresh, "刷新各区域座位", onRefresh,
                enabled = authenticated && enabled && !state.loading && !state.verificationRequired)
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
        state.regions.forEach { region -> RegionOverviewCard(region) }
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
private fun RegionOverviewCard(region: RegionSeatOverview) {
    LibraryCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(region.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(listOf(region.buildingName, region.floorName).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Muted, fontSize = 11.sp)
            }
            Text(when {
                region.loading -> "更新中"
                region.stale -> "上次数据"
                region.error != null -> "未同步"
                region.counts != null -> "已同步"
                else -> "待同步"
            }, color = if (region.stale || region.error != null) Color(0xFF956013) else Muted, fontSize = 10.sp)
        }
        val counts = region.counts
        if (counts == null) {
            Text("座位状态待同步", color = Muted, fontSize = 12.sp)
            if (region.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = SoftAccent)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("总席数 ${counts.total}", color = Muted, fontSize = 11.sp)
                Text("已确认占用 ${counts.occupied}", color = Muted, fontSize = 11.sp)
            }
            val metrics = regionMetrics(counts)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.filterIndexed { index, metric -> index < 3 || metric.count > 0 }.forEach { metric ->
                    Surface(color = Paper, shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp).semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(metric.color))
                            Text(metric.label, color = Muted, fontSize = 11.sp)
                            Text(metric.count.toString(), color = metric.color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (counts.total > 0) {
                Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).semantics {
                    contentDescription = "${region.name}座位状态：总席数${counts.total}，" + metrics.joinToString("，") { "${it.label}${it.count}" }
                }) {
                    metrics.filter { it.count > 0 }.forEach { metric ->
                        Box(Modifier.weight(metric.count.toFloat()).fillMaxHeight().background(metric.color))
                    }
                }
            }
            if (region.updatedAt != null) Text(
                "${regionDateLabel(region.date)} · ${regionTimeLabel(region.updatedAt)} 更新" + if (region.stale) " · 上次数据" else "",
                color = Muted, fontSize = 10.sp)
        }
        region.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFF956013), fontSize = 11.sp, lineHeight = 18.sp) }
    }
}

private data class RegionMetric(val label: String, val count: Int, val color: Color)

private fun regionMetrics(counts: RegionSeatCounts) = listOf(
    RegionMetric("空闲", counts.free, nativeMapSeatStyle("空闲", true).fill),
    RegionMetric("使用中", counts.inUse, nativeMapSeatStyle("使用中", true).fill),
    RegionMetric("已预约", counts.reserved, nativeMapSeatStyle("已预约", true).fill),
    RegionMetric("暂离", counts.away, nativeMapSeatStyle("暂离", true).fill),
    RegionMetric("不可用", counts.unavailable, nativeMapSeatStyle("不可用", false).fill),
    RegionMetric("未知", counts.unknown, nativeMapSeatStyle("", false).fill),
    RegionMetric("仅限现场", counts.localOnly, nativeMapSeatStyle("", false, true).fill),
)

private fun regionDateLabel(date: String): String {
    val today = LocalDate.now(RegionTimeZone)
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: today
    return (if (parsed == today) "今天 · " else "") + "${parsed.monthValue} 月 ${parsed.dayOfMonth} 日"
}

private val RegionTimeZone = ZoneId.of("Asia/Shanghai")

private fun regionTimeLabel(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(RegionTimeZone)
    .format(DateTimeFormatter.ofPattern("HH:mm"))
