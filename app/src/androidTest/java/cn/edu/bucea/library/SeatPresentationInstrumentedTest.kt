package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.graphics.luminance
import cn.edu.bucea.library.ui.NativeSeat
import cn.edu.bucea.library.ui.SeatPresentationPolicy
import cn.edu.bucea.library.ui.SeatSearchState
import cn.edu.bucea.library.ui.SeatVisualState
import cn.edu.bucea.library.ui.nativeMapSeatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Status and availability are independent server values; the local filter covers one loaded page. */
@RunWith(AndroidJUnit4::class)
class SeatPresentationInstrumentedTest {
    @Test fun schoolTextAndCssStatusesRetainDistinctVisualMeaning() {
        val cases = listOf(
            Triple("座位空闲", true, SeatVisualState.FREE),
            Triple("free power", true, SeatVisualState.FREE),
            Triple("idle_both", true, SeatVisualState.FREE),
            Triple("正在使用", false, SeatVisualState.OCCUPIED),
            Triple("usre", false, SeatVisualState.OCCUPIED),
            Triple("inuse_power", false, SeatVisualState.OCCUPIED),
            Triple("已被预约", false, SeatVisualState.RESERVED),
            Triple("待签到", false, SeatVisualState.RESERVED),
            Triple("agreement", false, SeatVisualState.RESERVED),
            Triple("暂离中", false, SeatVisualState.AWAY),
            Triple("leave", false, SeatVisualState.AWAY),
            Triple("不可使用", false, SeatVisualState.UNAVAILABLE),
            Triple("noUsre", false, SeatVisualState.UNAVAILABLE),
            Triple("school_status_v2", true, SeatVisualState.UNKNOWN),
            Triple("", false, SeatVisualState.UNKNOWN),
        )
        cases.forEach { (text, available, expected) ->
            assertEquals("Status classification for $text", expected, SeatPresentationPolicy.visualState(text, available))
        }
    }

    @Test fun anIdleDescriptionNeverMakesAServerDisabledSeatGreenOrSelectable() {
        for (status in listOf("座位空闲", "可用", "free", "idle_window")) {
            assertEquals(SeatVisualState.UNAVAILABLE, SeatPresentationPolicy.visualState(status, false))
            assertEquals("不可预约", SeatPresentationPolicy.availabilityLabel(false))
        }
        assertEquals(SeatVisualState.UNAVAILABLE, SeatPresentationPolicy.visualState("不可预约", true))
        assertEquals(SeatVisualState.UNAVAILABLE, SeatPresentationPolicy.visualState("仅限现场预约", false))
    }

    @Test fun currentlyUsedSeatsMayStillOfferOtherTimesWithoutChangingTheirStatus() {
        val original = NativeSeat("synthetic", "001A", "测试区域", status = "当前正在使用，可预约其他时段", available = true)
        val visual = SeatPresentationPolicy.visualState(original.status, original.available)
        assertEquals(SeatVisualState.OCCUPIED, visual)
        assertEquals("可预约", SeatPresentationPolicy.availabilityLabel(original.available))
        assertNotNull(SeatPresentationPolicy.availabilityDetail(visual, original.available))
        assertTrue(SeatPresentationPolicy.availabilityDetail(visual, original.available)!!.contains("时段"))
        assertEquals("当前正在使用，可预约其他时段", original.status)
        assertEquals("使用中", nativeMapSeatStyle(original.status, original.available).label)
        assertEquals(nativeMapSeatStyle(original.status, false), nativeMapSeatStyle(original.status, true))
    }

    @Test fun mapSeatNumbersHaveAccessibleContrastAndStatesHaveTextMarkers() {
        val styles = listOf("空闲", "正在使用", "已预约", "暂离", "不可用", "未知")
            .map { nativeMapSeatStyle(it, true) } + nativeMapSeatStyle("空闲", true, localOnly = true)
        styles.forEach { style ->
            val whiteTextContrast = 1.05f / (style.fill.luminance() + .05f)
            assertTrue("${style.label} seat numbers need 4.5:1 contrast; got $whiteTextContrast", whiteTextContrast >= 4.5f)
            assertEquals("Seat fills must remain opaque", 1f, style.fill.alpha, .001f)
            assertTrue("Every map state needs a visible text marker", style.marker.isNotBlank())
        }
        assertEquals("Map states need distinct text markers", styles.size, styles.map { it.marker }.toSet().size)
        assertEquals("仅限现场", nativeMapSeatStyle("空闲", true, localOnly = true).label)
        assertEquals("不可用", nativeMapSeatStyle("空闲", false).label)
    }

    @Test fun availabilityFilterUsesTheServerFlagAndOnlyTheLoadedPage() {
        val page = SeatSearchState(page = 2, hasSearched = true, hasNextPage = true, seats = listOf(
            NativeSeat("a", "001A", "测试区域", status = "正在使用", available = true),
            NativeSeat("b", "001B", "测试区域", status = "空闲", available = false),
            NativeSeat("c", "001C", "测试区域", status = "未知", available = true),
        ))
        val shown = SeatPresentationPolicy.visibleSeats(page.seats, true)
        assertEquals(listOf("a", "c"), shown.map { it.id })
        assertEquals(3, page.seats.size)
        assertSame(page.seats, SeatPresentationPolicy.visibleSeats(page.seats, false))
        assertTrue(page.hasNextPage)
        assertEquals(2, page.page)
        val unavailablePage = page.copy(seats = page.seats.filterNot { it.available })
        assertTrue(SeatPresentationPolicy.visibleSeats(unavailablePage.seats, true).isEmpty())
        assertTrue(unavailablePage.hasNextPage)
        assertFalse(unavailablePage.seats.isEmpty())
    }
}
