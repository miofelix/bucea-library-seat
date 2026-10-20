package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.models.LibrarySeat
import cn.edu.bucea.library.data.models.NativeMapCell
import cn.edu.bucea.library.data.models.NativeMapCellKind
import cn.edu.bucea.library.data.models.NativeMapState
import cn.edu.bucea.library.data.models.NativeRoomLayout
import cn.edu.bucea.library.ui.LibraryTheme
import cn.edu.bucea.library.ui.NativeMapScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic map data only; callbacks never query or mutate school reservations. */
@RunWith(AndroidJUnit4::class)
class ReservationMapFocusUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName
    private val occupiedColor = 0xFFB33F32.toInt()
    private val freeColor = 0xFF087F6D.toInt()
    private val outlineColor = 0xFF172535.toInt()

    @Test fun occupiedReservationRemainsHighlightedAfterAnotherSeatIsInspectedAndNormalMapsStillWork() {
        val layout = NativeRoomLayout("focus-room", "合成定位区", 1, 2, listOf(
            NativeMapCell(0, 0, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("reserved-seat", "007D", "合成定位区", "正在使用", false)),
            NativeMapCell(0, 1, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("other-seat", "008D", "合成定位区", "座位空闲", true)),
        ))
        withMap(NativeMapState(layout = layout, directRegionView = true,
            filters = mapOf("onDate" to "2030-01-16"), focusSeatId = "reserved-seat", focusSeatLabel = "007D"),
            showHeader = false) { state, bookings, _ ->
            waitFor("The reservation banner must identify the actual highlighted seat") { hasText("预约座位 007D · 已高亮") }
            val canvas = requireNode("The reservation's occupied seat must be accessible as highlighted") {
                findCanvas()?.takeIf { it.stateDescription?.toString() == "预约座位007D，使用中，已高亮" }
            }
            assertFalse("Opening a reservation map must not open seat details", hasText("007D · 合成定位区"))
            assertFalse("Opening a reservation map must not offer booking automatically", hasText("选择使用时间"))
            val canvasBounds = bounds(canvas)
            val initial = captureAfterDraw(canvasBounds) {
                countColor(it, occupiedColor) > 100 && countColor(it, freeColor) > 100 && countColor(it, outlineColor) > 100
            }
            try {
                assertTrue("The occupied seat must retain its red status fill", countColor(initial, occupiedColor) > 100)
                assertTrue("The reservation must have a persistent dark outline and marker", countColor(initial, outlineColor) > 100)
                val otherSeat = colorBounds(initial, freeColor)
                assertFalse("The free comparison seat must remain visible", otherSeat.isEmpty)
                touch(canvasBounds.left + otherSeat.exactCenterX(), canvasBounds.top + otherSeat.exactCenterY())
            } finally { initial.recycle() }
            waitFor("A deliberate tap must still inspect a different seat") { hasText("008D · 合成定位区") && hasText("选择使用时间") }
            click(requireNode("Seat details must remain dismissible") { findText("关闭") })
            waitFor("Closing details must retain the original reservation focus") {
                !hasText("关闭") && findCanvas()?.stateDescription?.toString() == "预约座位007D，使用中，已高亮"
            }
            val after = captureAfterDraw(bounds(requireNotNull(findCanvas()))) {
                countColor(it, occupiedColor) > 100 && countColor(it, outlineColor) > 100
            }
            try {
                assertTrue("Reservation status fill must survive selection and dismissal", countColor(after, occupiedColor) > 100)
                assertTrue("Reservation outline must survive selection and dismissal", countColor(after, outlineColor) > 100)
            } finally { after.recycle() }
            assertEquals("Map inspection must never begin booking", 0, bookings.get())

            instrumentation.runOnMainSync { state.value = state.value.copy(focusSeatId = null, focusSeatLabel = null) }
            waitFor("Ordinary maps must have no reservation focus announcement") {
                findCanvas()?.stateDescription.isNullOrBlank() && !hasText("预约座位 007D · 已高亮")
            }
            val ordinaryBounds = bounds(requireNotNull(findCanvas()))
            val ordinary = captureAfterDraw(ordinaryBounds) { countColor(it, occupiedColor) > 100 && countColor(it, outlineColor) == 0 }
            try {
                assertEquals("An ordinary unselected map must not retain the reservation marker", 0, countColor(ordinary, outlineColor))
                val occupied = colorBounds(ordinary, occupiedColor)
                touch(ordinaryBounds.left + occupied.exactCenterX(), ordinaryBounds.top + occupied.exactCenterY())
            } finally { ordinary.recycle() }
            waitFor("Ordinary maps must still inspect an occupied seat") { hasText("007D · 合成定位区") && hasText("关闭") }
            assertFalse("An occupied seat must not offer booking", hasText("选择使用时间"))
            assertEquals(0, bookings.get())
        }
    }

    @Test fun directEntriesOfferRestoreDuringLoadingAndErrorsWithEitherHeaderMode() {
        for (showHeader in listOf(false, true)) for (focusLabel in listOf(null, "007D")) {
            val restores = AtomicInteger()
            withMap(NativeMapState(loading = true, directRegionView = true, focusSeatLabel = focusLabel),
                showHeader, restores) { state, bookings, returns ->
                val restore = requireNode("Every direct entry must offer restoring layout selection while loading") { findText("恢复布局选座") }
                assertTouchTarget(restore)
                click(restore)
                waitFor("Restoring must invoke its callback during loading") { restores.get() == 1 }
                if (focusLabel != null) {
                    assertTrue("The staged reservation must be identified before its layout loads", hasText("预约座位 007D"))
                    assertFalse("A pending target must not claim to be highlighted", hasText("预约座位 007D · 已高亮"))
                    val back = requireNode("A loading reservation locator must offer a return action") { findText("返回预约") }
                    assertTouchTarget(back)
                    click(back)
                    waitFor("Return must invoke the original destination callback") { returns.get() == 1 }
                } else assertFalse("A homepage region must not show reservation-only return text", hasText("返回预约"))
                instrumentation.runOnMainSync { state.value = state.value.copy(loading = false, error = "合成布局读取失败") }
                waitFor("A failed direct entry must preserve the restore action") {
                    hasText("合成布局读取失败") && hasText("恢复布局选座")
                }
                click(requireNotNull(findText("恢复布局选座")))
                waitFor("Restoring must remain usable after a layout failure") { restores.get() == 2 }
                if (focusLabel != null) {
                    assertTrue("A failed reservation locator must preserve its target", hasText("预约座位 007D"))
                    click(requireNotNull(findText("返回预约")))
                    waitFor("Return must remain usable after a layout failure") { returns.get() == 2 }
                }
                assertEquals("Loading and changing navigation must not start booking", 0, bookings.get())
            }
        }
    }

    @Test fun seatStatusLegendPrecedesCanvasForNormalHomepageAndReservationEntries() {
        val layout = NativeRoomLayout("focus-room", "合成定位区", 1, 1, listOf(
            NativeMapCell(0, 0, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("reserved-seat", "007D", "合成定位区", "正在使用", false)),
        ))
        val entries = listOf(
            NativeMapState(layout = layout),
            NativeMapState(layout = layout, directRegionView = true),
            NativeMapState(layout = layout, directRegionView = true, focusSeatId = "reserved-seat", focusSeatLabel = "007D"),
        )
        for (entry in entries) {
            val restores = AtomicInteger()
            withMap(entry, showHeader = false, restores = restores) { _, bookings, _ ->
                var legend: AccessibilityNodeInfo? = null
                var canvas: AccessibilityNodeInfo? = null
                for (attempt in 0 until 10) {
                    legend = nodes().firstOrNull { it.contentDescription?.toString() == "座位状态图例" }
                    canvas = findCanvas()
                    if (legend != null && canvas != null) break
                    nodes().firstOrNull { it.isScrollable && it.isEnabled }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(150)
                }
                val legendBounds = bounds(requireNotNull(legend) { "Every map entry must expose its status legend" })
                val canvasBounds = bounds(requireNotNull(canvas) { "Every map entry must expose its canvas" })
                assertTrue("The complete legend must precede the map for direct=${entry.directRegionView} focus=${entry.focusSeatId}: legend=$legendBounds map=$canvasBounds",
                    legendBounds.bottom <= canvasBounds.top)
                if (entry.directRegionView) {
                    // Finding both map elements can scroll the shared header
                    // away; restore navigation lives above this comparison.
                    for (attempt in 0 until 10) {
                        if (findText("恢复布局选座") != null) break
                        nodes().firstOrNull { it.isScrollable && it.isEnabled }
                            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        SystemClock.sleep(150)
                    }
                    val restore = requireNode("A loaded direct entry must offer restoring layout selection") { findText("恢复布局选座") }
                    assertTouchTarget(restore)
                    click(restore)
                    waitFor("Loaded direct entry restore must invoke its callback") { restores.get() == 1 }
                } else assertFalse("Normal layout selection must not offer a redundant restore action", hasText("恢复布局选座"))
                assertEquals("Comparing map layout order must not start booking", 0, bookings.get())
            }
        }
    }

    private fun withMap(initial: NativeMapState, showHeader: Boolean = true,
        restores: AtomicInteger = AtomicInteger(),
        assertions: (MutableState<NativeMapState>, AtomicInteger, AtomicInteger) -> Unit) {
        val state = mutableStateOf(initial)
        val bookings = AtomicInteger()
        val returns = AtomicInteger()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    LibraryTheme(AppThemeColor.NAILONG_YELLOW) {
                        NativeMapScreen(state.value, showHeader = showHeader,
                            onFilter = { _, _ -> }, onRefresh = {}, onLoadRoom = {},
                            onListMode = { state.value = state.value.copy(listMode = it) },
                            onSeat = { _, _ -> bookings.incrementAndGet() }, onBack = { returns.incrementAndGet() },
                            onRestoreLayout = { restores.incrementAndGet() })
                    }
                }
            }
            instrumentation.waitForIdleSync()
            assertions(state, bookings, returns)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == appPackage && node.isVisibleToUser) result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        automation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun findCanvas() = nodes().firstOrNull { it.contentDescription?.toString()?.startsWith("合成定位区座位地图，双指缩放") == true }
    private fun findText(text: String) = nodes().firstOrNull { text in it.text?.toString().orEmpty().lines().map { line -> line.trim() } }
    private fun hasText(text: String) = findText(text) != null
    private fun bounds(node: AccessibilityNodeInfo) = Rect().apply { node.getBoundsInScreen(this) }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            current?.let { if (it.isClickable) return it }
            current = current?.parent
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        assertEquals(appPackage, node.packageName?.toString())
        val target = requireNotNull(clickableParent(node))
        assertTrue("Synthetic fixture control must be enabled", target.isEnabled)
        assertTrue("Synthetic fixture control must accept a deliberate click", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun assertTouchTarget(node: AccessibilityNodeInfo) {
        val target = requireNotNull(clickableParent(node))
        assertTrue("Navigation must stay enabled while map data loads", target.isEnabled)
        val minimum = 48f * instrumentation.targetContext.resources.displayMetrics.density
        assertTrue("Compact navigation must retain a 48 dp touch height", bounds(target).height() >= minimum - 1f)
    }

    private fun touch(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(35)
        }
    }

    private fun requireNode(message: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitFor(message) { result = find(); result != null }
        return requireNotNull(result)
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun captureAfterDraw(bounds: Rect, ready: (Bitmap) -> Boolean): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous: Bitmap? = null
        while (true) {
            val screen = requireNotNull(automation.takeScreenshot())
            val current = try { Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height()).copy(Bitmap.Config.ARGB_8888, false) }
                finally { screen.recycle() }
            if (ready(current) && previous?.sameAs(current) == true || SystemClock.uptimeMillis() >= deadline) {
                previous?.recycle()
                return current
            }
            previous?.recycle()
            previous = current
            SystemClock.sleep(100)
        }
    }

    private fun countColor(bitmap: Bitmap, color: Int): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it == color }
    }

    private fun colorBounds(bitmap: Bitmap, color: Int): Rect {
        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) == color) {
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
        }
        return Rect(left, top, right, bottom)
    }
}
