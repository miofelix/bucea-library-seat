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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.models.LibrarySeat
import cn.edu.bucea.library.data.models.NativeMapCell
import cn.edu.bucea.library.data.models.NativeMapCellKind
import cn.edu.bucea.library.data.models.NativeRoomLayout
import cn.edu.bucea.library.data.models.NativeMapState
import cn.edu.bucea.library.ui.NativeMapScreen
import cn.edu.bucea.library.ui.LibraryTheme
import cn.edu.bucea.library.ui.NativeRoomCanvas
import cn.edu.bucea.library.ui.nativeMapSeatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** The production canvas renders synthetic seats; the only callback updates local selection. */
@RunWith(AndroidJUnit4::class)
class NativeMapContrastUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName

    @Test fun homepageEntryShowsTheSelectedLayoutImmediatelyWithoutRegionSelectors() {
        val layout = NativeRoomLayout("direct-room", "合成测试区域", 1, 1, listOf(
            NativeMapCell(row = 0, column = 0, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("direct-seat", "A101", "合成测试区域", "座位空闲", true)),
        ))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    LibraryTheme(AppThemeColor.BUCEA_PURPLE) {
                        NativeMapScreen(NativeMapState(layout = layout, directRegionView = true),
                            onFilter = { _, _ -> }, onRefresh = {}, onLoadRoom = {}, onListMode = {},
                            onSeat = { _, _ -> }, onBack = {})
                    }
                }
            }
            waitFor("Direct entry must display the requested map in the initial viewport") { findCanvas() != null }
            val area = Rect().apply { requireNotNull(findCanvas()).getBoundsInScreen(this) }
            val screen = instrumentation.targetContext.resources.displayMetrics
            assertTrue("The layout must start in the first viewport without scrolling", area.top < screen.heightPixels * .4f)
            val root = requireNotNull(automation.rootInActiveWindow)
            assertFalse("Direct entry must not show the area-selection form first",
                root.findAccessibilityNodeInfosByText("场馆").any { it.isVisibleToUser && it.text?.toString() == "场馆" })
            assertFalse("Direct entry must not show the floor-selection form first",
                root.findAccessibilityNodeInfosByText("楼层").any { it.isVisibleToUser && it.text?.toString() == "楼层" })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun everyStatusHasOpaquePixelsAndYellowThemeSelectionRetainsADarkOutline() {
        val cases = listOf("座位空闲", "正在使用", "已预约", "暂离", "不可用", "未知", "仅限现场预约")
        val cells = cases.mapIndexed { index, status ->
            NativeMapCell(row = index / 4, column = index % 4, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("fixture-$index", "A10$index", "合成测试区域", status, available = index < 2),
                localOnly = index == 6, power = index == 0)
        }
        val layout = NativeRoomLayout("fixture-room", "合成测试区域", 2, 4, cells)
        val selected = mutableStateOf<String?>(null)
        val selectedCallback = AtomicReference<String?>()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    LibraryTheme(AppThemeColor.NAILONG_YELLOW) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            NativeRoomCanvas(layout, enabled = true, selectedSeatId = selected.value) {
                                selectedCallback.set(it.seat?.id)
                                selected.value = it.seat?.id
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            waitFor("The synthetic native canvas must be visible") { findCanvas() != null }
            val bounds = Rect().apply { requireNotNull(findCanvas()).getBoundsInScreen(this) }
            val styles = cells.map { nativeMapSeatStyle(it.seat!!.status, it.seat.available, it.localOnly) }
            val before = captureAfterDraw(bounds) { screenshot ->
                styles.all { countColor(screenshot, it.fill.toArgb()) > 100 }
            }
            try {
                saveIfRequested("native-map-status-colors", before)
                styles.forEach { style ->
                    assertTrue("${style.label} must render an opaque, clearly visible seat fill",
                        countColor(before, style.fill.toArgb()) > 100)
                }
                val freePixels = colorBounds(before, styles.first().fill.toArgb())
                assertTrue("Free seat pixels must form a visible chair", freePixels.width() > 20 && freePixels.height() > 20)
                touch(bounds.left + freePixels.exactCenterX(), bounds.top + freePixels.exactCenterY())
                waitFor("Clicking the free fixture seat must select its ID") { selectedCallback.get() == "fixture-0" }
                instrumentation.waitForIdleSync()
                val outline = 0xFF172535.toInt()
                val initialOutlinePixels = countColor(before, outline)
                val after = captureAfterDraw(bounds) { screenshot ->
                    countColor(screenshot, outline) > initialOutlinePixels + 100 &&
                        countColor(screenshot, AppThemeColor.NAILONG_YELLOW.primaryArgb.toInt()) > 100
                }
                try {
                    saveIfRequested("native-map-yellow-selection", after)
                    assertTrue("Yellow selection must retain a visible dark outline",
                        countColor(after, outline) > initialOutlinePixels + 100)
                    assertTrue("Selection must render the theme's yellow inside the dark outline",
                        countColor(after, AppThemeColor.NAILONG_YELLOW.primaryArgb.toInt()) > 100)
                    assertEquals("The synthetic callback must receive the original seat ID", "fixture-0", selected.value)
                } finally { after.recycle() }
            } finally { before.recycle() }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun findCanvas(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.packageName?.toString() == appPackage && node.isVisibleToUser &&
                node.contentDescription?.toString()?.startsWith("合成测试区域座位地图，双指缩放") == true) return node
            repeat(node.childCount) { node.getChild(it)?.let(::find)?.let { found -> return found } }
            return null
        }
        return automation.rootInActiveWindow?.let(::find)
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun captureCanvas(bounds: Rect): Bitmap {
        val screenshot = automation.takeScreenshot() ?: error("Native screenshot is unavailable")
        return try {
            Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
                .copy(Bitmap.Config.ARGB_8888, false)
        } finally { screenshot.recycle() }
    }

    /** UI semantics and click callbacks can settle before the next frame reaches the compositor. */
    private fun captureAfterDraw(bounds: Rect, ready: (Bitmap) -> Boolean): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            val screenshot = captureCanvas(bounds)
            // Keep the last image on timeout so the unchanged assertions report the actual defect.
            if (ready(screenshot) || SystemClock.uptimeMillis() >= deadline) return screenshot
            screenshot.recycle()
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

    private fun touch(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Synthetic canvas tap must be delivered", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(35)
        }
    }

    private fun saveIfRequested(name: String, bitmap: Bitmap) {
        if (InstrumentationRegistry.getArguments().getString("captureMapContrast") != "true") return
        File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
