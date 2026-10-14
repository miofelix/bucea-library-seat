package cn.edu.bucea.library

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real two-finger native map interaction; creates no reservation or account mutation. */
@RunWith(AndroidJUnit4::class)
class LiveNativeMapGestureTest {
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage = "cn.edu.bucea.library"

    @Test
    fun twoFingerExpansionChangesCanvasPixelsAndFitRestoresTheNativeMap() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Native map gestures are opt-in: pass -e live_map_gesture true",
            arguments.getString("live_map_gesture") == "true")
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clickText("首页")
        clickText("选座")
        waitFor("Native search must finish loading on the campus network") { findText("查找可用座位") != null }
        clickText("布局选座")
        waitFor("Native map must finish loading on the campus network") { findDescription("刷新座位地图", exact = true)?.isEnabled == true }
        assertNull("Map must not be blocked by failed data reads", findText("重试"))
        assertNull("Complete school verification before testing map gestures", findText("完成验证"))
        val room = waitForNode("The school must return a native room choice") { firstRoomChoice() }
        clickNode(room)
        waitFor("The selected room must finish loading") { findDescription("刷新座位地图", exact = true)?.isEnabled == true }

        val canvas = findWithScroll("The server room must expose a native Canvas") {
            findDescription("座位地图，双指缩放", exact = false)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val rectangle = visibleBounds(canvas)
        assertTrue("Canvas must have a visible touch area", rectangle.width() >= 160 && rectangle.height() >= 160)
        val before = captureCanvas(rectangle)
        var after: Bitmap? = null
        var restored: Bitmap? = null
        try {
            expandTwoPointers(rectangle)
            SystemClock.sleep(400)
            val updated = findDescription("座位地图，双指缩放", exact = false)
            assertNotNull("The native map must remain visible after pinch", updated)
            assertEquals("A pinch must transform the canvas content, not scroll its parent", rectangle, visibleBounds(requireNotNull(updated)))
            val zoomed = captureCanvas(rectangle)
            after = zoomed
            assertTrue("Two real pointers must visibly zoom native seat geometry", changedFraction(before, zoomed) > .01)
            saveCropIfRequested("before", before)
            saveCropIfRequested("after-pinch", zoomed)
        } finally {
            // Only restore this presentation state. No seat, booking, login or account action is clicked.
            val fit = findWithScroll("The native fit control must remain reachable", backward = true) { findText("适应区域") }
            clickNode(fit)
            val restoredCanvas = findWithScroll("The native map must remain reachable after fitting") {
                findDescription("座位地图，双指缩放", exact = false)
            }
            SystemClock.sleep(300)
            val restoredBounds = visibleBounds(restoredCanvas)
            val fitImage = captureCanvas(restoredBounds)
            restored = fitImage
            saveCropIfRequested("restored-fit", fitImage)
            if (restoredBounds == rectangle) assertTrue("Fit must restore the original geometry framing", changedFraction(before, fitImage) < .01)
            before.recycle()
            after?.recycle()
            restored?.recycle()
        }
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == appPackage && node.isVisibleToUser) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return result
    }

    private fun findText(value: String): AccessibilityNodeInfo? = allNodes().firstOrNull {
        it.text?.toString() == value && it.isEnabled
    }

    private fun findDescription(value: String, exact: Boolean): AccessibilityNodeInfo? = allNodes().firstOrNull {
        val description = it.contentDescription?.toString().orEmpty()
        if (exact) description == value else description.contains(value)
    }

    private fun firstRoomChoice(): AccessibilityNodeInfo? = allNodes().firstOrNull { node ->
        if (!node.isCheckable || !node.isClickable || !node.isEnabled) return@firstOrNull false
        val description = node.contentDescription?.toString().orEmpty()
        if (description.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return@firstOrNull false
        var hasDate = false
        fun inspect(child: AccessibilityNodeInfo) {
            if (child.contentDescription?.toString()?.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) == true) hasDate = true
            for (index in 0 until child.childCount) child.getChild(index)?.let(::inspect)
        }
        inspect(node)
        !hasDate
    }

    private fun clickText(value: String) = clickNode(waitForNode("Required native navigation control is unavailable") { findText(value) })

    private fun clickNode(node: AccessibilityNodeInfo) {
        assertTrue("The verified native control must be enabled", node.isEnabled)
        assertEquals("Only the native application can receive test touches", appPackage, node.packageName?.toString())
        val text = node.text?.toString().orEmpty()
        val navigation = text in setOf("首页", "选座", "自选座位", "布局选座", "适应区域")
        val roomChoice = node.isCheckable && node.isClickable &&
            node.contentDescription?.toString()?.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) != true
        assertTrue("Only verified navigation, room or fit controls may be clicked", navigation || roomChoice)
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.isEnabled) {
                if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            current = candidate.parent
        }
        // Some Compose semantics omit ACTION_CLICK even when the rendered
        // navigation text remains an actual touch target. Inject only into the
        // already verified control, never a seat or transaction button.
        val rectangle = visibleBounds(node)
        assertTrue("The verified native control must be visible", node.isVisibleToUser && !rectangle.isEmpty)
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                rectangle.exactCenterX(), rectangle.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("The verified native touch must be injected", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(60)
        touch(MotionEvent.ACTION_UP)
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(200)
        }
        fail(message)
    }

    private fun waitForNode(message: String, predicate: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var matched: AccessibilityNodeInfo? = null
        waitFor(message) { matched = predicate(); matched != null }
        return requireNotNull(matched)
    }

    private fun findWithScroll(message: String, backward: Boolean = false, predicate: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        repeat(6) {
            predicate()?.let { return it }
            val scrollable = allNodes().firstOrNull { it.isScrollable && it.isEnabled }
            val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (scrollable == null || !scrollable.performAction(action)) return@repeat
            SystemClock.sleep(250)
        }
        return waitForNode(message, predicate)
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().apply { node.getBoundsInScreen(this) }

    private fun visibleBounds(node: AccessibilityNodeInfo): Rect {
        val result = bounds(node)
        val screen = instrumentation.targetContext.resources.displayMetrics
        if (!result.intersect(Rect(0, 0, screen.widthPixels, screen.heightPixels))) result.setEmpty()
        automation.rootInActiveWindow?.let { if (!result.intersect(bounds(it))) result.setEmpty() }
        return result
    }

    private fun captureCanvas(rectangle: Rect): Bitmap {
        val screen = automation.takeScreenshot() ?: error("Native screenshot is unavailable")
        try {
            assertTrue("Canvas crop must fit the physical display", rectangle.left >= 0 && rectangle.top >= 0 && rectangle.right <= screen.width && rectangle.bottom <= screen.height)
            return Bitmap.createBitmap(screen, rectangle.left, rectangle.top, rectangle.width(), rectangle.height()).copy(Bitmap.Config.ARGB_8888, false)
        } finally { screen.recycle() }
    }

    private fun changedFraction(first: Bitmap, second: Bitmap): Double {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val pixels = first.width * first.height
        val before = IntArray(pixels)
        val after = IntArray(pixels)
        first.getPixels(before, 0, first.width, 0, 0, first.width, first.height)
        second.getPixels(after, 0, second.width, 0, 0, second.width, second.height)
        return before.indices.count { before[it] != after[it] }.toDouble() / pixels
    }

    private fun saveCropIfRequested(name: String, bitmap: Bitmap) {
        if (InstrumentationRegistry.getArguments().getString("live_map_artifacts") != "true") return
        val directory = File(instrumentation.targetContext.filesDir, "live-map").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun expandTwoPointers(rectangle: Rect) {
        val down = SystemClock.uptimeMillis()
        val centerX = rectangle.exactCenterX()
        val centerY = rectangle.exactCenterY()
        val start = rectangle.width() * .12f
        val finish = rectangle.width() * .34f
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun event(action: Int, count: Int, distance: Float) {
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                x = centerX + if (index == 0) -distance else distance
                y = centerY
                pressure = 1f
                size = 1f
            } }
            val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties,
                coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue("Real multi-pointer injection must succeed", automation.injectInputEvent(motion, true)) }
            finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 1, start)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, start)
        for (step in 1..18) {
            event(MotionEvent.ACTION_MOVE, 2, start + (finish - start) * step / 18)
            SystemClock.sleep(16)
        }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, finish)
        event(MotionEvent.ACTION_UP, 1, finish)
    }
}
