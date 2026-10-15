package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.models.LibrarySeat
import cn.edu.bucea.library.data.models.NativeMapCell
import cn.edu.bucea.library.data.models.NativeMapCellKind
import cn.edu.bucea.library.data.models.NativeMapState
import cn.edu.bucea.library.data.models.NativeMapOptions
import cn.edu.bucea.library.data.models.NativeMapRoom
import cn.edu.bucea.library.data.models.NativeRoomLayout
import cn.edu.bucea.library.data.models.LibraryOption
import cn.edu.bucea.library.ui.LibraryTheme
import cn.edu.bucea.library.ui.NativeMapScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic callbacks inspect the real list, filter and guarded time-selection flow. */
@RunWith(AndroidJUnit4::class)
class NativeMapCardsUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName

    @Test fun wholePageScrollsTabsAwayAndKeepsModeSwitchAnchorsInNarrowAndLargeTextLayouts() {
        val cells = (0 until 100).map { index ->
            NativeMapCell(index / 4, index % 4, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("seat-$index", "S${index.toString().padStart(3, '0')}", "测试阅览区", "座位空闲", true))
        }
        val initial = NativeMapState(layout = NativeRoomLayout("fixture", "测试阅览区", 25, 4, cells),
            rooms = listOf(NativeMapRoom("fixture", "测试阅览区")),
            filters = mapOf("onDate" to "2026-10-16", "building" to "1", "floor" to "2"),
            options = NativeMapOptions(dates = listOf(LibraryOption("2026-10-16", "10月16日")),
                buildings = listOf(LibraryOption("1", "大兴校区")), floors = listOf(LibraryOption("2", "二层"))))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            listOf(1f, 1.6f).forEach { fontScale ->
                val screen = mutableStateOf(initial)
                instrumentation.runOnMainSync {
                    activity.setContent {
                        val density = LocalDensity.current.density
                        key(fontScale) {
                            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                                LibraryTheme(AppThemeColor.BUCEA_PURPLE) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                        NativeMapScreen(screen.value, modifier = Modifier.width(300.dp).height(580.dp),
                                            onFilter = { _, _ -> }, onRefresh = {}, onLoadRoom = {},
                                            onListMode = { screen.value = screen.value.copy(listMode = it) },
                                            onSeat = { _, _ -> }, onBack = {})
                                    }
                                }
                            }
                        }
                    }
                }
                waitFor("The fixture must show shared controls at font scale $fontScale") {
                    textNode("布局选座") != null && tabBarNode() != null
                }
                val header = boundsOf(requireNotNull(textNode("布局选座")))
                val tabs = stableTabBounds()
                val density = instrumentation.targetContext.resources.displayMetrics.density
                recordGeometry(fontScale)
                assertTrue("The visual tab bar must stay within 44dp; bounds=$tabs density=$density font=$fontScale",
                    tabs.height() <= 44 * density + 1)
                click(requireNotNull(textNode("列表")))
                waitFor("Switching to cards must retain the shared header anchor") { screen.value.listMode }
                assertSharedBounds(header, tabs, fontScale)
                click(requireNotNull(textNode("地图")))
                waitFor("Switching back to the map must retain the shared header anchor") { !screen.value.listMode }
                assertSharedBounds(header, tabs, fontScale)

                repeat(4) {
                    if (textNode("布局选座") != null || tabBarNode() != null) swipePageForward()
                }
                waitFor("Page swipes must scroll both the controls and mode tabs out of view") {
                    textNode("布局选座") == null && tabBarNode() == null && canvasNode() != null
                }
                val mapPosition = stableCanvasBounds()

                // External view restoration can change mode while its tabs are
                // offscreen; it must retain the independent browsing positions.
                instrumentation.runOnMainSync { screen.value = screen.value.copy(listMode = true) }
                waitFor("Restoring the card view must show its own page") { visibleSeatNames().isNotEmpty() || tabBarNode() != null }
                repeat(4) {
                    if (seatTile("S000") != null || tabBarNode() != null) swipePageForward()
                }
                waitFor("Whole-page downward scrolling reaches later cards and scrolls the tabs away") {
                    seatTile("S000") == null && tabBarNode() == null && visibleSeatNames().any { it != "座位 S000" }
                }
                val listPosition = stableSeatNames()

                instrumentation.runOnMainSync { screen.value = screen.value.copy(listMode = false) }
                waitFor("Restoring the map keeps its own position") { canvasNode() != null }
                assertEquals("Card scrolling must not alter the map position", mapPosition, stableCanvasBounds())
                assertTrue("The map's tabs remain scrolled out of view", tabBarNode() == null)
                instrumentation.runOnMainSync { screen.value = screen.value.copy(listMode = true) }
                waitFor("Restoring cards keeps their own position") { visibleSeatNames().isNotEmpty() }
                assertEquals("Map scrolling must not alter the card-list position", listPosition, stableSeatNames())
                assertTrue("The list's tabs remain scrolled out of view", tabBarNode() == null)

                repeat(6) {
                    if (textNode("布局选座") == null || tabBarNode() == null) {
                        assertTrue("The full page must also scroll back to its controls",
                            requireNotNull(pageScroll()).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                        SystemClock.sleep(350)
                    }
                }
                waitFor("The original controls and tabs remain reachable after browsing seats") { textNode("布局选座") != null && tabBarNode() != null }
                val restoredHeader = boundsOf(requireNotNull(textNode("布局选座")))
                val restoredTabs = stableTabBounds()
                click(requireNotNull(textNode("地图")))
                waitFor("Switching after scrolling back retains the common anchor") { !screen.value.listMode }
                assertSharedBounds(restoredHeader, restoredTabs, fontScale)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun compactRowsFilterServerAvailabilityAndKeepLocalOnlySeatsOutOfOnlineBooking() {
        val cells = listOf(
            NativeMapCell(0, 0, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("free", "A101", "测试阅览区", "座位空闲", true), power = true),
            NativeMapCell(0, 1, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("busy", "B202", "测试阅览区", "正在使用", false)),
            NativeMapCell(0, 2, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("local", "C303", "测试阅览区", "座位空闲", true), localOnly = true),
            NativeMapCell(0, 3, kind = NativeMapCellKind.SEAT,
                seat = LibrarySeat("future", "D404", "测试阅览区", "当前正在使用，可预约其他时段", true), window = true),
        )
        val layout = NativeRoomLayout("fixture", "测试阅览区", 1, 4, cells)
        val favorites = AtomicInteger()
        val selected = AtomicReference<String?>()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    LibraryTheme(AppThemeColor.BUCEA_PURPLE) {
                        NativeMapScreen(NativeMapState(layout = layout, listMode = true, directRegionView = true,
                            filters = mapOf("onDate" to "2026-10-16")), showHeader = false,
                            onFilter = { _, _ -> }, onRefresh = {}, onLoadRoom = {}, onListMode = {},
                            onSeat = { seat, date -> selected.set("${seat.id}:$date") }, onBack = {},
                            onToggleFavorite = { favorites.incrementAndGet() })
                    }
                }
            }
            waitFor("All four compact map cards must appear") { cells.all { seatTile(it.seat!!.label) != null } }
            assertTrue(hasText("显示 4 / 全部 4 个"))
            val bounds = cells.map { cell -> Rect().apply { requireNotNull(seatTile(cell.seat!!.label)).getBoundsInScreen(this) } }
            bounds.forEach {
                assertEquals("Map list uses one row of four cards", bounds.first().top, it.top)
                assertEquals("Cards share the same compact row height", bounds.first().bottom, it.bottom)
            }
            bounds.zipWithNext().forEach { (left, right) -> assertTrue("Cards must remain separate", left.right < right.left) }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertTrue("Compact map cards must not become long list rows", bounds.all { it.height() <= 90 * density })
            val tabs = Rect().apply { clickableParent(requireNotNull(textNode("列表"))).getBoundsInScreen(this) }
            assertTrue("Map/list tabs retain the compact shared height", tabs.height() <= 44 * density + 1)

            val local = requireNotNull(seatTile("C303"))
            assertTrue("Local-only status overrides an available flag", local.stateDescription.toString().contains("仅限现场预约，不可预约"))
            assertFalse("Local-only seats must not enter favorites through the map", local.isLongClickable)
            val future = requireNotNull(seatTile("D404"))
            assertTrue("Future availability stays distinct from current occupancy",
                future.stateDescription.toString().contains("当前正在使用，可预约其他时段，可预约"))
            assertTrue("Capabilities remain accessible on compact cards", future.stateDescription.toString().contains("靠窗"))
            val busy = requireNotNull(seatTile("B202"))
            assertTrue("Unavailable online seats remain eligible for favorites", busy.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            waitFor("A map long press must only favorite the seat") { favorites.get() == 1 }
            assertEquals(null, selected.get())

            click(local)
            waitFor("Local-only details must remain inspectable") { hasText("该座位仅支持现场预约") }
            val closeDetails = awaitDialogAction("关闭", "The seat detail sheet must expose its close action after entering")
            assertFalse("Local-only detail must not offer online time selection", hasText("选择使用时间"))
            click(closeDetails)
            waitFor("Closing details returns to the card list") { seatTile("C303") != null }
            captureIfRequested()

            waitFor("The card filter must return after closing seat details") { filterSwitch() != null }
            val toggle = requireNotNull(filterSwitch())
            assertFalse("The fixture initially displays all seats", toggle.isChecked)
            click(toggle)
            waitFor("Filter must exclude unavailable and local-only seats without querying") {
                hasText("显示 2 / 全部 4 个") && seatTile("A101") != null && seatTile("D404") != null &&
                    seatTile("B202") == null && seatTile("C303") == null
            }
            assertTrue("Filter semantics must report only-available mode", filterSwitch()?.isChecked == true)
            click(requireNotNull(seatTile("D404")))
            val chooseTime = awaitDialogAction("选择使用时间", "A currently occupied but bookable seat must expose its available-time action")
            click(chooseTime)
            waitFor("Only the explicit time-selection action passes the original seat and date") { selected.get() == "future:2026-10-16" }
            assertEquals("Selection does not change favorites", 1, favorites.get())
        } catch (failure: Throwable) {
            runCatching {
                File(instrumentation.targetContext.filesDir, "native-map-cards-failure.txt").writeText(nodes().joinToString("\n") {
                    "text=[${it.text}] description=[${it.contentDescription}] state=[${it.stateDescription}] clickable=${it.isClickable}"
                })
            }
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == appPackage && node.isVisibleToUser) found += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        automation.rootInActiveWindow?.let(::visit)
        return found
    }

    private fun textNode(value: String): AccessibilityNodeInfo? = nodes().firstOrNull { it.text?.toString() == value }
    private fun hasText(value: String) = textNode(value) != null
    private fun seatTile(name: String): AccessibilityNodeInfo? =
        nodes().firstOrNull { it.contentDescription?.toString() == "座位 $name" }?.let(::clickableParent)

    private fun canvasNode() = nodes().firstOrNull {
        it.contentDescription?.toString()?.startsWith("测试阅览区座位地图，双指缩放") == true
    }

    private fun boundsOf(node: AccessibilityNodeInfo) = Rect().apply { node.getBoundsInScreen(this) }

    private fun pageScroll() = nodes().firstOrNull { it.isScrollable && it.isEnabled }

    private fun tabBarNode() = nodes().firstOrNull { it.contentDescription?.toString() == "座位视图切换" }

    private fun stableTabBounds(): Rect {
        var previous = Rect()
        repeat(20) {
            tabBarNode()?.let(::boundsOf)?.let { current ->
                if (!current.isEmpty && current == previous) return current
                previous = current
            }
            SystemClock.sleep(100)
        }
        return previous
    }

    private fun recordGeometry(fontScale: Float) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        File(instrumentation.targetContext.filesDir, "native-map-switch-geometry.txt").appendText(
            "font=$fontScale density=$density bar=${tabBarNode()?.let(::boundsOf)} " +
                "touch=${textNode("列表")?.let(::clickableParent)?.let(::boundsOf)}\n")
    }

    private fun swipePageForward() {
        val page = boundsOf(requireNotNull(pageScroll()))
        val density = instrumentation.targetContext.resources.displayMetrics.density
        // The page margin stays outside the canvas's independent pan gesture.
        val x = page.left + 6 * density
        val startY = page.bottom - 40 * density
        val endY = page.top + 60 * density
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("The synthetic whole-page swipe must be delivered", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, startY)
        repeat(12) { step ->
            SystemClock.sleep(20)
            inject(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (step + 1) / 12)
        }
        inject(MotionEvent.ACTION_UP, endY)
        SystemClock.sleep(400)
    }

    private fun visibleSeatNames() = nodes().mapNotNull { it.contentDescription?.toString() }
        .filter { it.startsWith("座位 S") }.distinct().sorted()

    private fun stableSeatNames(): List<String> {
        var previous = emptyList<String>()
        repeat(20) {
            val current = visibleSeatNames()
            if (current.isNotEmpty() && current == previous) return current
            previous = current
            SystemClock.sleep(100)
        }
        return previous
    }

    private fun stableCanvasBounds(): Rect {
        var previous = Rect()
        repeat(20) {
            canvasNode()?.let(::boundsOf)?.let { current ->
                if (!current.isEmpty && current == previous) return current
                previous = current
            }
            SystemClock.sleep(100)
        }
        return previous
    }

    private fun assertSharedBounds(header: Rect, tabs: Rect, fontScale: Float) {
        // State callbacks precede the new lazy layout and its accessibility tree.
        // Wait for that tree before reading the restored shared anchor.
        instrumentation.waitForIdleSync()
        waitFor("The shared controls must settle at the same anchor at font scale $fontScale") {
            textNode("布局选座")?.let(::boundsOf) == header && tabBarNode()?.let(::boundsOf) == tabs
        }
        assertEquals("Body changes must not move the header at font scale $fontScale", header,
            boundsOf(requireNotNull(textNode("布局选座"))))
        assertEquals("Switching modes must not move or resize tabs at font scale $fontScale", tabs, stableTabBounds())
    }

    private fun filterSwitch(): AccessibilityNodeInfo? {
        val current = nodes()
        // Compose can expose the explicit description through a child proxy;
        // its ancestor owns the toggle state and click action.
        current.firstOrNull { it.contentDescription?.toString() == "仅看可用" }?.let { label ->
            var ancestor: AccessibilityNodeInfo? = label
            repeat(5) {
                ancestor?.let { if (it.isCheckable) return it }
                ancestor = ancestor?.parent
            }
        }
        return current.firstOrNull { it.isCheckable && it.stateDescription?.toString()?.let { state ->
            state.contains("全部座位") || state.contains("可预约座位")
        } == true }
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            current?.let { if (it.isClickable) return it }
            current = current?.parent
        }
        return node
    }

    private fun click(node: AccessibilityNodeInfo) {
        val action = clickableParent(node)
        assertEquals("Only synthetic content receives actions", appPackage, action.packageName?.toString())
        assertTrue("The fixture action must be enabled", action.isEnabled)
        assertTrue("The fixture action must be delivered", action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitDialogAction(label: String, message: String): AccessibilityNodeInfo {
        var ready: AccessibilityNodeInfo? = null
        var previousBounds: Rect? = null
        waitFor(message) {
            // The sheet body may enter the accessibility tree before its
            // footer. Its close icon uses a description instead of text.
            val action = nodes().firstOrNull {
                it.text?.toString() == label || it.contentDescription?.toString() == label
            }?.let(::clickableParent)?.takeIf { it.isEnabled && it.isClickable && it.isVisibleToUser }
            val bounds = action?.let(::boundsOf)
            val settled = bounds != null && !bounds.isEmpty && bounds == previousBounds
            previousBounds = bounds
            ready = action.takeIf { settled }
            ready != null
        }
        return requireNotNull(ready)
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun captureIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("captureMapCards") != "true") return
        val screenshot = requireNotNull(automation.takeScreenshot())
        try {
            File(instrumentation.targetContext.filesDir, "native-map-compact-cards.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }
}
