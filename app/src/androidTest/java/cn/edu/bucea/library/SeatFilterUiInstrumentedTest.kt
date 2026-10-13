package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.ui.NativeSeat
import cn.edu.bucea.library.ui.SeatSearchScreen
import cn.edu.bucea.library.ui.SeatSearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Presentation-only fixture replaces MainActivity's content with synthetic seat pages.
 * Initial MainActivity creation may perform its normal public/session restore; this test does
 * not claim to suppress those startup reads. Fixture callbacks never use a VM, vault or server,
 * and never submit a search, booking, account operation or password mutation.
 */
@RunWith(AndroidJUnit4::class)
class SeatFilterUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName

    @Test fun toggleImmediatelyFiltersTheLoadedPageAndAppliesToNewPagesWithoutAnotherQuery() {
        val selected = AtomicInteger()
        val searches = AtomicInteger()
        val nextPages = AtomicInteger()
        val mixedPage = SeatSearchState(hasSearched = true, hasNextPage = true, seats = listOf(
            NativeSeat("fixture-free", "A101", "测试区域", status = "座位空闲", available = true),
            NativeSeat("fixture-used", "B202", "测试区域", status = "正在使用", available = false),
        ))
        val screen = mutableStateOf(mixedPage)
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        SeatSearchScreen(
                            state = screen.value,
                            onFilter = { key, value -> screen.value = screen.value.copy(filters = screen.value.filters + (key to value)) },
                            onSearch = { searches.incrementAndGet() },
                            onNextPage = {
                                nextPages.incrementAndGet()
                                screen.value = mixedPage.copy(page = 1, hasNextPage = true, seats = listOf(
                                    NativeSeat("fixture-unavailable", "C303", "测试区域", status = "不可用", available = false),
                                ))
                            },
                            onPreviousPage = { screen.value = mixedPage },
                            onSelectSeat = { selected.incrementAndGet() },
                            onBack = {},
                            onOpenMap = {},
                        )
                    }
                }
            }
            instrumentation.waitForIdleSync()
            waitFor("Mixed fixture page must show both loaded seats") { hasText("本页显示 2 / 已加载 2 个") }
            val blocked = findWithScroll("The unavailable fixture seat must be visible") { findText("B202") }
            touch(blocked)
            instrumentation.waitForIdleSync()
            assertEquals("An unavailable card body must not select a seat", 0, selected.get())

            val switch = findWithScroll("The immediate page filter must be visible", backwards = true) { filterSwitch() }
            click(switch)
            waitFor("Switch must immediately hide the unavailable seat without querying") {
                hasText("本页显示 1 / 已加载 2 个") && !hasText("B202")
            }
            assertEquals("Changing the local filter must not issue a new query", 0, searches.get())
            assertTrue("Filter semantics must report enabled", filterSwitch()?.isChecked == true)

            val next = findWithScroll("Pagination must stay available under the local filter") { findText("下一页") }
            assertTrue("Next page must remain enabled", (clickableParent(next) ?: next).isEnabled)
            click(next)
            waitFor("A newly loaded unavailable page must be filtered immediately") {
                hasText("本页显示 0 / 已加载 1 个") && hasText("本页没有可预约座位") && !hasText("C303")
            }
            assertEquals(1, nextPages.get())
            val nextAgain = findWithScroll("An empty filtered page must still allow the next page") { findText("下一页") }
            assertTrue("Filtering all loaded seats must not disable server pagination", (clickableParent(nextAgain) ?: nextAgain).isEnabled)

            instrumentation.runOnMainSync {
                screen.value = mixedPage.copy(page = 2, seats = listOf(
                    NativeSeat("fixture-currently-used", "D404", "测试区域", status = "当前正在使用，可预约其他时段", available = true),
                    NativeSeat("fixture-idle-disabled", "E505", "测试区域", status = "座位空闲", available = false),
                ))
            }
            waitFor("The enabled filter must immediately apply to subsequent state updates") {
                hasText("本页显示 1 / 已加载 2 个") && !hasText("E505")
            }
            findWithScroll("Server-available future-time seat must remain visible") { findText("D404") }
            assertFalse("A disabled seat with an idle label must stay filtered out", hasText("E505"))
            click(findWithScroll("The filter must remain available after paging", backwards = true) { filterSwitch() })
            waitFor("Disabling the filter must immediately restore all loaded seats") { hasText("本页显示 2 / 已加载 2 个") }
            findWithScroll("The hidden unavailable seat must return without another query") { findText("E505") }
            assertEquals("No fixture state change may require another search", 0, searches.get())
            assertEquals("The UI test must not select or reserve any seat", 0, selected.get())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == appPackage && node.isVisibleToUser) found += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        automation.rootInActiveWindow?.let(::visit)
        return found
    }

    private fun findText(value: String) = nodes().firstOrNull { node ->
        val text = node.text?.toString().orEmpty()
        text == value || text.lines().any { it.trim() == value } ||
            (value.matches(Regex("[A-E][0-9]{3}")) && Regex("(?:^|\\s|,)${Regex.escape(value)}(?:$|\\s|,)").containsMatchIn(text))
    }

    private fun hasText(value: String) = findText(value) != null

    private fun filterSwitch(): AccessibilityNodeInfo? {
        val current = nodes()
        return current.firstOrNull { it.isCheckable && it.contentDescription?.toString() == "仅看可用" }
            ?: current.firstOrNull { it.isCheckable && Build.VERSION.SDK_INT >= 30 && it.stateDescription?.toString()?.contains("本页") == true }
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun findWithScroll(message: String, backwards: Boolean = false, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        repeat(8) {
            find()?.let { return it }
            val scroll = nodes().firstOrNull { it.isScrollable && it.isEnabled }
            if (scroll != null) scroll.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        var found: AccessibilityNodeInfo? = null
        waitFor(message) { found = find(); found != null }
        return requireNotNull(found)
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            current?.let { if (it.isClickable) return it }
            current = current?.parent
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        assertEquals("Only synthetic native content receives clicks", appPackage, node.packageName?.toString())
        val clickable = clickableParent(node)
        assertTrue("The verified fixture control must be enabled", (clickable ?: node).isEnabled)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        touch(node)
    }

    private fun touch(node: AccessibilityNodeInfo) {
        val bounds = Rect().apply { node.getBoundsInScreen(this) }
        val window = Rect().apply { automation.rootInActiveWindow?.getBoundsInScreen(this) }
        assertEquals("Only synthetic application nodes may be touched", appPackage, node.packageName?.toString())
        assertTrue("The fixture target must be visible", bounds.intersect(window) && !bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Fixture touch must be delivered", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(35)
        }
    }
}
