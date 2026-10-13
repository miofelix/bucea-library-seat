package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native notice acceptance. Never selects an account or sends a password write. */
@RunWith(AndroidJUnit4::class)
class LiveNativeAccountNoticeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val app = "cn.edu.bucea.library"

    @Test fun passwordEntryEmitsUnimplementedToastWithoutOpeningAForm() {
        assumeTrue("Native account notice check is opt-in", InstrumentationRegistry.getArguments().getString("live_account_notice") == "true")
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click(waitForNode("Native account navigation must be available") { nodes().firstOrNull { it.text?.toString() == "账号" && it.isEnabled } }, "账号")
        val deadline = SystemClock.uptimeMillis() + 20_000
        var entry: AccessibilityNodeInfo? = null
        while (entry == null && SystemClock.uptimeMillis() < deadline) {
            entry = nodes().firstOrNull { it.text?.toString() == "修改图书馆密码" && it.isEnabled }
            if (entry == null) {
                nodes().firstOrNull { it.isScrollable && it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(250)
            }
        }
        assertNotNull("The native password notice entry must be available", entry)
        val toast = automation.executeAndWaitForEvent({ click(requireNotNull(entry), "修改图书馆密码") }, { event ->
            event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.packageName?.toString() == app &&
                event.text.any { it.toString().contains("修改密码当前未实现") }
        }, 8_000)
        try { assertTrue("The app must announce that password changing is unimplemented", toast.text.any { it.toString().contains("修改密码当前未实现") }) }
        finally { toast.recycle() }
        instrumentation.waitForIdleSync()
        val labels = nodes().map { it.text?.toString().orEmpty() }
        assertTrue("The notice must keep the native account screen open", "账号管理" in labels || "修改图书馆密码" in labels)
        assertFalse("The notice must not open a password form or submit button", labels.any { it in setOf("当前密码", "新密码", "再次输入新密码", "修改密码") })
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == app && node.isVisibleToUser) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        automation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun waitForNode(message: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) { find()?.let { return it }; SystemClock.sleep(200) }
        error(message)
    }

    private fun click(node: AccessibilityNodeInfo, expected: String) {
        require(expected in setOf("账号", "修改图书馆密码"))
        assertEquals(expected, node.text?.toString())
        assertEquals(app, node.packageName?.toString())
        assertTrue("The verified notice/navigation target must be enabled and visible", node.isEnabled && node.isVisibleToUser)
        var candidate: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = candidate
            if (current?.isClickable == true && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            candidate = current?.parent
        }
        val area = Rect().apply { node.getBoundsInScreen(this) }
        val metrics = instrumentation.targetContext.resources.displayMetrics
        assertTrue("The verified target must intersect the display", area.intersect(Rect(0, 0, metrics.widthPixels, metrics.heightPixels)) && !area.isEmpty)
        val rootBounds = Rect().apply { automation.rootInActiveWindow?.getBoundsInScreen(this) }
        assertTrue("The verified target must intersect the active window", area.intersect(rootBounds) && !area.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, area.exactCenterX(), area.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("The verified native touch must succeed", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }
}
