package cn.edu.bucea.library.ui

import androidx.compose.ui.graphics.Color

internal data class ReservationStatusPalette(val background: Color, val foreground: Color)

private val ReservedPalette = ReservationStatusPalette(Color(0xFFEFF6FF), Color(0xFF1D4ED8))
private val InUsePalette = ReservationStatusPalette(Color(0xFFECFEFF), Color(0xFF0E7490))
private val AwayPalette = ReservationStatusPalette(Color(0xFFFFFBEB), Color(0xFF92400E))
private val FulfilledPalette = ReservationStatusPalette(Color(0xFFF0FDF4), Color(0xFF166534))
private val MissedPalette = ReservationStatusPalette(Color(0xFFFEF2F2), Color(0xFFB91C1C))
private val NeutralPalette = ReservationStatusPalette(Color(0xFFF3F4F6), Color(0xFF4B5563))

/** Use displayed status meaning: the school's historical CSS classes do not consistently match it. */
internal fun reservationStatusPalette(status: String): ReservationStatusPalette {
    val label = status.trim().replace(Regex("^状态\\s*[：:]\\s*"), "")
    return when (label) {
        "已预约", "预约", "待签到", "RESERVED", "RESERVE", "BOOKED" -> ReservedPalette
        "使用中", "已签到", "IN_USE", "USING", "CHECKED_IN", "CHECK_IN" -> InUsePalette
        "暂离", "AWAY", "LEAVE" -> AwayPalette
        "已履约", "履约", "已完成", "结束使用", "FINISHED", "STOPPED" -> FulfilledPalette
        "失约", "已失约", "违约", "已违约" -> MissedPalette
        // A time slot ending alone does not establish that the reservation was fulfilled.
        "已结束", "已取消", "取消", "COMPLETE", "CANCELED", "CANCELLED" -> NeutralPalette
        else -> NeutralPalette
    }
}
