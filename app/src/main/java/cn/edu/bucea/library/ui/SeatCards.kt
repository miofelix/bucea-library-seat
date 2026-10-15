package cn.edu.bucea.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Four compact cards on normal phone widths, with usable targets in narrower panes. */
internal fun compactSeatColumns(width: Dp): Int = when {
    width < 102.dp -> 1
    width < 210.dp -> 2
    else -> 4
}

/** A shared row lets each screen lazily compose its entire result set. */
@Composable
internal fun CompactSeatRow(
    seats: List<NativeSeat>,
    columns: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggleFavorite: ((NativeSeat) -> Unit)? = null,
    canFavorite: (NativeSeat) -> Boolean = { true },
    showDetails: Boolean = false,
    onSelectSeat: (NativeSeat) -> Unit,
) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        seats.forEach { seat ->
            CompactSeatCard(seat, enabled, Modifier.weight(1f).fillMaxHeight(),
                onToggleFavorite?.takeIf { canFavorite(seat) }?.let { toggle -> { toggle(seat) } }, showDetails) { onSelectSeat(seat) }
        }
        repeat((columns - seats.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun CompactSeatCard(seat: NativeSeat, enabled: Boolean, modifier: Modifier, onFavorite: (() -> Unit)?, showDetails: Boolean, onSelect: () -> Unit) {
    val currentState = SeatPresentationPolicy.visualState(seat.status, available = true)
    val currentPalette = seatPalette(currentState)
    val availabilityColor = if (seat.available) Color(0xFF21865A) else Color(0xFFC9504C)
    val status = seat.status.ifBlank { "状态未知" }
    val availability = SeatPresentationPolicy.availabilityLabel(seat.available)
    val compactStatus = if (seat.status.contains("仅限现场")) "现场" else when (currentState) {
        SeatVisualState.FREE -> "空闲"
        SeatVisualState.OCCUPIED -> "在用"
        SeatVisualState.RESERVED -> "已约"
        SeatVisualState.AWAY -> "暂离"
        SeatVisualState.UNAVAILABLE -> "停用"
        SeatVisualState.UNKNOWN -> "未知"
    }
    Card(
        modifier.heightIn(min = 70.dp).semantics {
            contentDescription = "座位 ${seat.name}"
            stateDescription = listOfNotNull(status, availability, seat.location,
                "有电源".takeIf { seat.power == true }, "靠窗".takeIf { seat.window == true },
                if (seat.localFavorite) "已收藏" else "未收藏").joinToString("，")
        }
            .combinedClickable(enabled = enabled, role = Role.Button,
                onClickLabel = if (showDetails) "查看 ${seat.name} 的座位信息" else if (seat.available) "选择 ${seat.name} 的使用时间" else "不可预约 ${seat.name}",
                onLongClickLabel = onFavorite?.let { if (seat.localFavorite) "取消收藏 ${seat.name}" else "收藏 ${seat.name}" },
                onLongClick = onFavorite, onClick = { if (seat.available || showDetails) onSelect() }),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, availabilityColor),
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(seat.name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(seat.location, fontSize = 10.sp, lineHeight = 13.sp, color = Muted,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (seat.localFavorite) Icon(Icons.Outlined.Star, null, Modifier.size(10.dp), tint = Accent)
                if (seat.power == true) Icon(Icons.Outlined.Power, null, Modifier.size(10.dp), tint = Muted)
                if (seat.window == true) Icon(Icons.Outlined.Window, null, Modifier.size(10.dp), tint = Muted)
            }
            // Status describes the seat now; availability describes whether the
            // server allows choosing a time, including a future free period.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(compactStatus, fontSize = 10.sp, lineHeight = 16.sp, color = currentPalette.foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(color = if (seat.available) Color(0xFFEDF7F0) else Color(0xFFFDEDEC),
                    contentColor = availabilityColor, shape = RoundedCornerShape(4.dp)) {
                    Text(if (seat.available) "可约" else "不可约", modifier = Modifier.padding(horizontal = 1.dp, vertical = 1.dp),
                        fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
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

