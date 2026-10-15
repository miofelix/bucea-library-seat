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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.ui.SeatSelectionScreen
import cn.edu.bucea.library.ui.LibraryDestinationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic slot content exercises navigation without searching, booking or writing school data. */
@RunWith(AndroidJUnit4::class)
class SeatSelectionTabsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName

    @Test fun bottomNavigationRetainsBothSeatModesButAnotherIdentityStartsFresh() {
        val destination = mutableStateOf(1)
        val identity = mutableStateOf("fixture-account-one")
        val authenticated = mutableStateOf(true)
        val layout = mutableStateOf(false)
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        LibraryDestinationState(destination.value, identity.value, authenticated.value) {
                            if (destination.value == 1) SeatSelectionScreen(
                                layoutSelected = layout.value,
                                onSelectLayout = { layout.value = it },
                                onRefresh = {},
                                searchContent = { SearchFixture() },
                                layoutContent = { LayoutFixture() },
                            ) else Text("其他页面")
                        }
                    }
                }
            }
            waitFor("Search must appear") { findText("自选计数 0") != null }
            click(requireNotNull(findText("自选计数 0")))
            scrollForward()
            val searchRows = visibleRows("自选条目 ")
            assertTrue(searchRows.isNotEmpty() && "自选条目 0" !in searchRows)
            click(tab("布局选座"))
            waitFor("Layout must appear") { findText("布局计数 0") != null }
            click(requireNotNull(findText("布局计数 0")))
            scrollForward()
            val layoutRows = visibleRows("布局条目 ")
            assertTrue(layoutRows.isNotEmpty() && "布局条目 0" !in layoutRows)

            instrumentation.runOnMainSync { destination.value = 4 }
            waitFor("Navigating away must remove seat content") { findText("其他页面") != null }
            instrumentation.runOnMainSync { destination.value = 1 }
            waitFor("The nested layout scroll position must survive bottom navigation") {
                visibleRows("布局条目 ") == layoutRows
            }
            scrollToStart("布局计数 1")
            click(tab("自选座位"))
            waitFor("The other nested mode must also retain its previous position") {
                visibleRows("自选条目 ") == searchRows
            }
            scrollToStart("自选计数 1")

            instrumentation.runOnMainSync { identity.value = "fixture-account-two" }
            waitFor("Another account must get fresh destination state") { findText("自选计数 0") != null }
            click(requireNotNull(findText("自选计数 0")))
            waitFor("The second account's local state must update") { findText("自选计数 1") != null }
            instrumentation.runOnMainSync { authenticated.value = false }
            waitFor("Signing out must discard personalized UI state") { findText("自选计数 0") != null }
        } catch (failure: Throwable) {
            captureFailure(failure)
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun topTabsStayFixedAndRetainEachModesStateAndScrollPosition() {
        val layoutSelected = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val switches = AtomicInteger()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        SeatSelectionScreen(
                            layoutSelected = layoutSelected.value,
                            onSelectLayout = { layoutSelected.value = it; switches.incrementAndGet() },
                            onRefresh = {},
                            enabled = enabled.value,
                            searchContent = { SearchFixture() },
                            layoutContent = { LayoutFixture() },
                        )
                    }
                }
            }
            waitFor("Both peer tabs must appear in the initial viewport") {
                findText("自选座位") != null && findText("布局选座") != null && findText("自选计数 0") != null
            }
            val searchBounds = visualTabBounds("自选座位")
            val layoutBounds = visualTabBounds("布局选座")
            val headerBounds = bounds(requireNotNull(findText("选座")))
            assertFalse("The selection header must not expose a back arrow",
                nodes().any { it.contentDescription?.toString() == "返回" })
            assertEquals("The two modes must occupy the same tab row", searchBounds.top, layoutBounds.top)
            assertTrue("The modes must be separate adjacent tab targets", searchBounds.right <= layoutBounds.left)
            assertTrue("Tabs must be near the top of the screen",
                searchBounds.bottom < instrumentation.targetContext.resources.displayMetrics.heightPixels * .4f)
            assertSelected(layout = false)

            click(requireNotNull(findText("自选计数 0")))
            waitFor("Search fixture state must update") { findText("自选计数 1") != null }
            scrollForward()
            val searchRows = visibleRows("自选条目 ")
            assertFalse("The search content must actually scroll", searchRows.contains("自选条目 0"))
            assertTrue("Scrolled search rows must remain visible", searchRows.isNotEmpty())
            assertFixedControls(headerBounds, searchBounds, layoutBounds)

            click(tab("布局选座"))
            waitFor("The top layout tab must switch directly to layout content") { findText("布局计数 0") != null }
            assertSelected(layout = true)
            click(requireNotNull(findText("布局计数 0")))
            waitFor("Layout fixture state must update") { findText("布局计数 1") != null }
            scrollForward()
            val layoutRows = visibleRows("布局条目 ")
            assertFalse("The lazy layout content must actually scroll", layoutRows.contains("布局条目 0"))
            assertTrue("Scrolled layout rows must remain visible", layoutRows.isNotEmpty())
            assertFixedControls(headerBounds, searchBounds, layoutBounds)

            click(tab("自选座位"))
            waitFor("Returning to search must retain its scroll position") { visibleRows("自选条目 ") == searchRows }
            assertSelected(layout = false)
            assertFixedControls(headerBounds, searchBounds, layoutBounds)
            scrollToStart("自选计数 1")
            assertTrue("Search local state must survive leaving its tab", findText("自选计数 1") != null)

            click(tab("布局选座"))
            waitFor("Returning to layout must retain its lazy-list position") { visibleRows("布局条目 ") == layoutRows }
            assertSelected(layout = true)
            scrollToStart("布局计数 1")
            assertTrue("Layout local state must survive leaving its tab", findText("布局计数 1") != null)
            assertEquals("Each mode change must deliver one callback", 3, switches.get())

            instrumentation.runOnMainSync { enabled.value = false }
            waitFor("Both tabs must expose the disabled state") {
                !tab("自选座位").isEnabled && !tab("布局选座").isEnabled
            }
            touch(tab("自选座位"))
            instrumentation.waitForIdleSync()
            assertEquals("Disabled tabs must not deliver a mode change", 3, switches.get())
            assertSelected(layout = true)
            assertTrue("Disabled switching must retain the active content", findText("布局计数 1") != null)
        } catch (failure: Throwable) {
            captureFailure(failure)
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Composable private fun SearchFixture() {
        var count by rememberSaveable { mutableStateOf(0) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Button(onClick = { count++ }) { Text("自选计数 $count") }
            repeat(80) { index ->
                Text("自选条目 $index", Modifier.fillMaxWidth().padding(vertical = 24.dp))
            }
        }
    }

    @Composable private fun LayoutFixture() {
        var count by rememberSaveable { mutableStateOf(0) }
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item { Button(onClick = { count++ }) { Text("布局计数 $count") } }
            items(80) { index ->
                Text("布局条目 $index", Modifier.fillMaxWidth().padding(vertical = 24.dp))
            }
        }
    }

    private fun assertSelected(layout: Boolean) {
        assertEquals("Search tab must expose its selected state", !layout, tab("自选座位").isSelected)
        assertEquals("Layout tab must expose its selected state", layout, tab("布局选座").isSelected)
    }

    private fun assertFixedControls(header: Rect, search: Rect, layout: Rect) {
        assertEquals("The common header must stay fixed while content scrolls", header, bounds(requireNotNull(findText("选座"))))
        assertEquals("Search tab must remain fixed and available", search, visualTabBounds("自选座位"))
        assertEquals("Layout tab must remain fixed and available", layout, visualTabBounds("布局选座"))
    }

    private fun visualTabBounds(text: String): Rect {
        val control = tab(text)
        val tabRole = instrumentation.targetContext.getString(androidx.compose.ui.R.string.tab)
        // Compact tabs retain Compose's expanded touch area. Its clipping can
        // depend on the neighbouring scroll content; compare the visible tab
        // surface to verify that the fixed row itself never moves.
        repeat(control.childCount) { index ->
            control.getChild(index)?.let { child ->
                if (AccessibilityNodeInfoCompat.wrap(child).roleDescription?.toString() == tabRole) return bounds(child)
            }
        }
        return bounds(control)
    }

    private fun scrollForward() {
        repeat(2) {
            val scroll = requireNotNull(nodes().firstOrNull { it.isScrollable && it.isEnabled })
            assertTrue("Synthetic content must accept scrolling", scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
        }
    }

    private fun scrollToStart(counter: String) {
        repeat(8) {
            if (findText(counter) != null) return
            val scroll = requireNotNull(nodes().firstOrNull { it.isScrollable && it.isEnabled })
            scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
        }
        fail("Saved counter must return when scrolling to the top: $counter")
    }

    private fun visibleRows(prefix: String): List<String> = nodes().mapNotNull { node ->
        node.text?.toString()?.takeIf { it.startsWith(prefix) }
    }.distinct().sorted()

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

    private fun findText(text: String) = nodes().firstOrNull { it.text?.toString() == text }

    private fun tab(text: String): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = requireNotNull(findText(text))
        val tabRole = instrumentation.targetContext.getString(androidx.compose.ui.R.string.tab)
        fun hasTabRole(node: AccessibilityNodeInfo) =
            AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString() == tabRole
        repeat(5) {
            current?.let {
                // Compose removes the click action from the selected tab; disabled tabs may
                // also have no action. An unmerged tab may expose its role on a separate child.
                if (it.isSelected || it.isClickable || hasTabRole(it) ||
                    (0 until it.childCount).any { index ->
                        it.getChild(index)?.let { child -> hasTabRole(child) && bounds(child) == bounds(it) } == true
                    }) return it
            }
            current = current?.parent
        }
        error("The fixture label must have an accessible tab ancestor: $text")
    }

    private fun control(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            current?.let { if (it.isClickable) return it }
            current = current?.parent
        }
        return node
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().apply { node.getBoundsInScreen(this) }

    private fun click(node: AccessibilityNodeInfo) {
        val target = control(node)
        assertEquals("Only synthetic application content may receive clicks", appPackage, target.packageName?.toString())
        assertTrue("The fixture control must be enabled", target.isEnabled)
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) touch(target)
    }

    private fun touch(node: AccessibilityNodeInfo) {
        val target = bounds(node)
        assertEquals("Only synthetic application content may receive touches", appPackage, node.packageName?.toString())
        assertTrue("The fixture target must be visible", node.isVisibleToUser && !target.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Fixture touch must be delivered", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(35)
        }
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun captureFailure(failure: Throwable) {
        runCatching {
            File(instrumentation.targetContext.filesDir, "seat-selection-tabs-fixture-failure.txt").writeText(
                nodes().joinToString("\n", prefix = "Synthetic tab fixture: ${failure.message}\n") { node ->
                    "bounds=${bounds(node)} class=${node.className}" +
                        " role=${AccessibilityNodeInfoCompat.wrap(node).roleDescription}" +
                        " enabled=${node.isEnabled} selected=${node.isSelected}" +
                        " clickable=${node.isClickable} scrollable=${node.isScrollable} text=[${node.text}]"
                },
            )
            automation.takeScreenshot()?.let { screenshot ->
                try {
                    File(instrumentation.targetContext.filesDir, "seat-selection-tabs-fixture-failure.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
        }.onFailure { failure.addSuppressed(it) }
    }
}
