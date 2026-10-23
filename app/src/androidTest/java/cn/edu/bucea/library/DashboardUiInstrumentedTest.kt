package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.ui.Dashboard
import cn.edu.bucea.library.ui.HubAction
import cn.edu.bucea.library.ui.HubRecord
import cn.edu.bucea.library.ui.LibraryHubState
import cn.edu.bucea.library.ui.LibraryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic dashboard only: every action records a local callback without school mutations. */
@RunWith(AndroidJUnit4::class)
class DashboardUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName
    private val account = AccountSummary("fixture-dashboard", "209999999001", "演示同学")

    @Test fun overviewIsVisibleWithoutOpeningASheetAndPreservesUnknownVersusZeroCounts() {
        val state = mutableStateOf(fixtureState())
        withDashboard(state) {
            requireMetric("空闲座位", "286")
            requireMetric("正在使用", "910")
            requireMetric("等待签到", "84")
            assertTrue("Fresh overview must report its sync status", hasText("最新同步"))
            assertTrue("Complete reported states may show their distribution", hasDistribution())
            assertFalse("Overview must no longer require opening a sheet", hasText("刷新图书馆数据"))
            assertRemovedHomeActions()

            update(state) { it.copy(overview = LibraryOverview(available = 0, inUse = null, awaitingSignIn = 4)) }
            requireMetric("空闲座位", "0")
            requireMetric("正在使用", "—")
            requireMetric("等待签到", "4")
            assertTrue("A missing category must be explained", hasText("部分数据待同步"))
            assertFalse("Partial categories must not invent a total or distribution", hasDistribution())

            update(state) { it.copy(overview = LibraryOverview(available = 0, inUse = 0, awaitingSignIn = 0)) }
            listOf("空闲座位", "正在使用", "等待签到").forEach { requireMetric(it, "0") }
            assertFalse("All-zero categories must not draw a fake filled distribution", hasDistribution())

            update(state) { it.copy(overview = null) }
            listOf("空闲座位", "正在使用", "等待签到").forEach { requireMetric(it, "—") }
            assertTrue("Unsynced overview must be explicit", hasText("尚未同步"))
            assertFalse("Unsynced overview must not show a stale distribution", hasDistribution())
        }
    }

    @Test fun staleOverviewRefreshAndNavigationUseTheExistingCallbacks() {
        val state = mutableStateOf(fixtureState().copy(overviewStale = true))
        val callbacks = Callbacks()
        val hub = LibraryHubState(currentStatus = "RESERVED",
            currentDetails = listOf("座位：A042", "位置：合成测试区域", "时间：14:00 — 18:00"))
        withDashboard(state, callbacks, hub) {
            requireMetric("空闲座位", "286")
            assertTrue("Previous values must be marked stale", hasText("上次数据"))
            assertTrue("Refresh guidance must accompany stale counts", hasText("上次同步的数据，刷新后查看最新情况。"))
            click(findWithScroll("Overview refresh must be immediately available", backwards = true) {
                findDescription("刷新馆内概况")
            })
            waitFor("Refresh must invoke the existing synthetic callback once") { callbacks.refresh.get() == 1 }
            click(findWithScroll("A real current-reservation presentation must offer details") { findText("查看我的预约") })
            waitFor("Current reservation must open the reservation section") { callbacks.reservations.get() == 1 }
            assertRemovedHomeActions()
            assertEquals("Removed home seat actions must not call seat search", 0, callbacks.search.get())
            assertEquals("Removed map shortcut must not call map navigation", 0, callbacks.map.get())
            assertEquals("Removed favorites shortcut must not call favorites navigation", 0, callbacks.favorites.get())
            assertEquals("Navigation must not initiate login", 0, callbacks.login.get())
            assertEquals("Navigation must not initiate account edits", 0, callbacks.accounts.get())
        }
    }

    @Test fun dashboardOmitsCheckInAndWifiGuidanceButPreservesReservationNavigation() {
        val details = listOf("座位：A101", "位置：图书馆二层东区", "时间：09:00 — 11:00")
        val record = HubRecord("fixture-home-wifi", "测试预约 A101", "已预约", details)
        for (connected in listOf(false, true)) {
            val state = mutableStateOf(fixtureState())
            val callbacks = Callbacks()
            val hub = LibraryHubState(currentStatus = if (connected) "RESERVE" else "LOC_RESTRICT",
                currentDetails = details, currentReservation = record, requiresLibraryWifi = !connected,
                actions = if (connected) listOf(HubAction("checkIn", "签到", "合成签到操作")) else emptyList())
            withDashboard(state, callbacks, hub) {
                findWithScroll("The home reservation must retain its seat information") { findText("A101") }
                val detailsAction = findWithScroll("The home reservation must retain its details link") { findText("查看我的预约") }
                for (text in listOf("签到", "签到需连接图书馆 Wi-Fi", "打开 Wi-Fi 设置", "已连接，刷新状态")) {
                    assertFalse("Home must omit check-in controls and Wi-Fi guidance regardless of connection: $text", hasText(text))
                }
                assertFalse("Home must remove the location-restricted status badge", hasText("位置受限"))
                if (InstrumentationRegistry.getArguments().getString("captureWifiCheckInScreenshots") == "true") {
                    captureFixture("dashboard-current-reservation-${if (connected) "connected" else "restricted"}")
                }
                click(detailsAction)
                waitFor("Home must preserve navigation to the reservation flow") { callbacks.reservations.get() == 1 }
                assertEquals("Home reservation navigation must not initiate a network refresh", 0, callbacks.refresh.get())
            }
        }
    }

    @Test fun accountNetworkAndBusyGatesRemainIntact() {
        val state = mutableStateOf(fixtureState().copy(authenticated = false))
        val callbacks = Callbacks()
        val hub = LibraryHubState(currentStatus = "RESERVED",
            currentDetails = listOf("座位：A042", "位置：合成测试区域", "时间：14:00 — 18:00"))
        withDashboard(state, callbacks, hub) {
            click(findWithScroll("A saved signed-out account must offer login") { findText("登录图书馆") })
            waitFor("The existing session card must route to saved-account login") { callbacks.login.get() == 1 }
            update(state) { it.copy(campusAccess = CampusAccess.UNREACHABLE) }
            waitFor("Offline state must finish updating the login control") {
                findText("登录图书馆")?.let { !enabled(it) } == true
            }
            val offlineLogin = findWithScroll("Offline login must remain discoverable", backwards = true) { findText("登录图书馆") }
            assertFalse("A saved account must not login without campus access", enabled(offlineLogin))
            assertEquals(1, callbacks.login.get())

            update(state) { it.copy(accounts = emptyList(), activeAccountId = null) }
            click(findWithScroll("An empty account list must route to account setup", backwards = true) { findDescription("管理账号") })
            waitFor("Account setup must use its existing callback") { callbacks.accounts.get() == 1 }

            update(state) { fixtureState().copy(busy = true) }
            waitFor("The busy dashboard must finish updating its status") { hasText("正在同步") }
            val refresh = findWithScroll("A busy refresh must remain discoverable", backwards = true) { findDescription("刷新馆内概况") }
            assertFalse("Busy state must disable overview refresh", enabled(refresh))
            assertFalse("Busy state must disable account management", enabled(findWithScroll("Busy account action must remain discoverable", backwards = true) { findDescription("管理账号") }))
            assertFalse("Busy state must disable current reservation details", enabled(findWithScroll("Busy current reservation details must remain discoverable") { findText("查看我的预约") }))
            assertRemovedHomeActions()
            assertEquals("Busy and offline controls must not begin seat search", 0, callbacks.search.get())
            assertEquals("Busy controls must not refresh", 0, callbacks.refresh.get())
        }
    }

    @Test fun nailongYellowOverviewUsesBrightStatusAccentAndReadableMetricText() {
        val state = mutableStateOf(fixtureState().copy(themeColor = AppThemeColor.NAILONG_YELLOW))
        withDashboard(state) {
            requireMetric("空闲座位", "286")
            val value = findWithScroll("Yellow theme must retain the actual metric") { findText("286") }
            val valueBounds = Rect().apply { value.getBoundsInScreen(this) }
            val strip = findWithScroll("Yellow status distribution must remain visible") {
                nodes().firstOrNull { it.contentDescription?.toString()?.startsWith("馆内状态分布：") == true }
            }
            val stripBounds = Rect().apply { strip.getBoundsInScreen(this) }
            val yellow = AppThemeColor.NAILONG_YELLOW.primaryArgb.toInt()
            val readable = AppThemeColor.NAILONG_YELLOW.readableAccentArgb.toInt()
            val screenshot = captureAfterDraw { bitmap ->
                countColor(bitmap, stripBounds, yellow) > 100 && countColor(bitmap, valueBounds, readable) > 20
            }
            try {
                assertTrue("The rendered state strip must use the bright yellow accent", countColor(screenshot, stripBounds, yellow) > 100)
                assertTrue("The actual metric must use the readable dark theme color", countColor(screenshot, valueBounds, readable) > 20)
                assertRemovedHomeActions()
                if (InstrumentationRegistry.getArguments().getString("captureDashboardScreenshots") == "true") {
                    File(instrumentation.targetContext.filesDir, "dashboard-a-nailong-yellow.png").outputStream().use {
                        assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            } finally { screenshot.recycle() }
        }
    }

    private fun fixtureState() = LibraryUiState(accounts = listOf(account), activeAccountId = account.id,
        authenticated = true, campusAccess = CampusAccess.REACHABLE, overviewStale = false,
        overview = LibraryOverview(available = 286, inUse = 910, awaitingSignIn = 84), status = "合成界面测试")

    private class Callbacks {
        val refresh = AtomicInteger()
        val search = AtomicInteger()
        val login = AtomicInteger()
        val accounts = AtomicInteger()
        val reservations = AtomicInteger()
        val map = AtomicInteger()
        val favorites = AtomicInteger()
    }

    private fun withDashboard(state: MutableState<LibraryUiState>, callbacks: Callbacks = Callbacks(),
        hub: LibraryHubState = LibraryHubState(), assertions: () -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    LibraryTheme(state.value.themeColor) {
                        Dashboard(state.value, hub, Modifier,
                            onLogin = { callbacks.login.incrementAndGet() },
                            onRefresh = { callbacks.refresh.incrementAndGet() },
                            onOpenLibrary = { callbacks.search.incrementAndGet() },
                            onReservations = { callbacks.reservations.incrementAndGet() },
                            onAccounts = { callbacks.accounts.incrementAndGet() },
                            onOpenMap = { callbacks.map.incrementAndGet() },
                            onFavorites = { callbacks.favorites.incrementAndGet() })
                    }
                }
            }
            instrumentation.waitForIdleSync()
            waitFor("The synthetic A dashboard must be in the foreground") { hasText("当前馆内概况") }
            if (InstrumentationRegistry.getArguments().getString("captureDashboardScreenshots") == "true") {
                captureFixture("dashboard-fixture-${state.value.themeColor.preferenceValue}-${if (state.value.overviewStale) "stale" else "fresh"}")
            }
            assertions()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun update(state: MutableState<LibraryUiState>, change: (LibraryUiState) -> LibraryUiState) {
        instrumentation.runOnMainSync { state.value = change(state.value) }
        instrumentation.waitForIdleSync()
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

    private fun textLines(node: AccessibilityNodeInfo) = node.text?.toString().orEmpty().lines().map { it.trim() }
    private fun findText(value: String) = nodes().firstOrNull { value in textLines(it) }
    private fun findDescription(value: String) = nodes().firstOrNull { it.contentDescription?.toString() == value }
    private fun hasText(value: String) = findText(value) != null
    private fun hasDistribution() = nodes().any { it.contentDescription?.toString()?.startsWith("馆内状态分布：") == true }

    private fun assertRemovedHomeActions() {
        val removed = setOf("找一个好座位", "布局选座", "收藏座位", "预约记录", "随时查看")
        fun inspect(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == appPackage) {
                assertFalse("Deleted home actions must be absent from the complete fixture hierarchy",
                    textLines(node).any { it in removed })
            }
            repeat(node.childCount) { node.getChild(it)?.let(::inspect) }
        }
        automation.rootInActiveWindow?.let(::inspect)
    }

    private fun requireMetric(title: String, value: String) {
        findWithScroll("$title must display $value directly on the dashboard", backwards = true) {
            nodes().firstOrNull { title in textLines(it) && pairedValueVisible(it, value) }
        }
    }

    /** Some Android versions expose merged Compose descendants as separate sibling text nodes. */
    private fun pairedValueVisible(label: AccessibilityNodeInfo, value: String): Boolean {
        fun descendantTexts(node: AccessibilityNodeInfo, depth: Int): List<String> {
            val texts = if (node.packageName?.toString() == appPackage && node.isVisibleToUser) textLines(node) else emptyList()
            if (depth == 0) return texts
            return texts + (0 until node.childCount).flatMap { index ->
                node.getChild(index)?.let { descendantTexts(it, depth - 1) }.orEmpty()
            }
        }
        var container = label
        // Horizontal metrics share one Row; vertical metrics wrap the label Row in a Column.
        repeat(3) {
            val texts = descendantTexts(container, 3)
            val categories = texts.filter { it in setOf("空闲座位", "正在使用", "等待签到") }.distinct()
            if (categories.size == 1 && value in texts) return true
            container = container.parent ?: return false
        }
        return false
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            current?.let { if (it.isClickable) return it }
            current = current?.parent
        }
        return null
    }

    private fun enabled(node: AccessibilityNodeInfo) = (clickableParent(node) ?: node).isEnabled

    private fun click(node: AccessibilityNodeInfo) {
        assertEquals("Only fixture app nodes may be clicked", appPackage, node.packageName?.toString())
        val control = requireNotNull(clickableParent(node))
        assertTrue("Fixture action must be enabled", control.isEnabled)
        assertTrue("Fixture action must accept an accessibility click", control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun findWithScroll(message: String, backwards: Boolean = false,
        find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        repeat(10) {
            find()?.let { return it }
            nodes().firstOrNull { it.isScrollable && it.isEnabled }?.performAction(
                if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        var result: AccessibilityNodeInfo? = null
        waitFor(message) { result = find(); result != null }
        return requireNotNull(result)
    }

    private fun countColor(bitmap: Bitmap, bounds: Rect, color: Int): Int {
        var count = 0
        for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height)) {
            for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                if (bitmap.getPixel(x, y) == color) count++
            }
        }
        return count
    }

    private fun captureFixture(name: String) {
        val root = instrumentation.targetContext.filesDir
        File(root, "$name.txt").writeText(nodes().joinToString("\n") { node ->
            val bounds = Rect().apply { node.getBoundsInScreen(this) }
            "text=[${node.text}] description=[${node.contentDescription}] bounds=$bounds enabled=${node.isEnabled}"
        })
        val screenshot = captureAfterDraw { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count { it == 0xFFFFFFFF.toInt() || it == 0xFFFAFAFA.toInt() } > pixels.size / 2
        }
        try { File(root, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
    }

    /** Semantics may settle before the rendered frame reaches the compositor. */
    private fun captureAfterDraw(ready: (Bitmap) -> Boolean): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            val screenshot = automation.takeScreenshot() ?: error("Dashboard screenshot is unavailable")
            // Preserve the last image on timeout so the unchanged assertions report the defect.
            if (ready(screenshot) || SystemClock.uptimeMillis() >= deadline) return screenshot
            screenshot.recycle()
            SystemClock.sleep(100)
        }
    }
}
