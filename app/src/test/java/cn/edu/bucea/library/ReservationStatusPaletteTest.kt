package cn.edu.bucea.library

import androidx.compose.ui.graphics.luminance
import cn.edu.bucea.library.ui.reservationStatusPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationStatusPaletteTest {
    @Test fun statusMeaningControlsColorInsteadOfTheSchoolCssClassOrTheme() {
        val groups = listOf(
            listOf("已预约", "预约", "待签到", "RESERVED", "RESERVE", "BOOKED"),
            listOf("使用中", "已签到", "IN_USE", "USING", "CHECKED_IN", "CHECK_IN"),
            listOf("暂离", "AWAY", "LEAVE"),
            listOf("已履约", "履约", "已完成", "结束使用", "FINISHED", "STOPPED"),
            listOf("失约", "已失约", "违约", "已违约"),
            listOf("已结束", "已取消", "取消", "COMPLETE", "CANCELED", "CANCELLED"),
        )
        groups.forEach { labels ->
            labels.forEach { label -> assertEquals(label, reservationStatusPalette(labels.first()), reservationStatusPalette(label)) }
        }
        assertEquals("Different meanings must keep distinct colors", groups.size,
            groups.map { reservationStatusPalette(it.first()).foreground }.toSet().size)
        assertEquals(reservationStatusPalette("失约"), reservationStatusPalette(" 状态：失约 "))
        assertEquals(reservationStatusPalette("已取消"), reservationStatusPalette("学校新增状态"))
    }

    @Test fun everyBadgeHasReadableOpaqueColors() {
        for (label in listOf("已预约", "使用中", "暂离", "已履约", "失约", "已取消")) {
            val palette = reservationStatusPalette(label)
            val first = palette.foreground.luminance().toDouble()
            val second = palette.background.luminance().toDouble()
            val contrast = (maxOf(first, second) + .05) / (minOf(first, second) + .05)
            assertTrue("$label badge text needs 4.5:1 contrast; got $contrast", contrast >= 4.5)
            assertEquals(1f, palette.background.alpha, .001f)
            assertEquals(1f, palette.foreground.alpha, .001f)
        }
    }
}
