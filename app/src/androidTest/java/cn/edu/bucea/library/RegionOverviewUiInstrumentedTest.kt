package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.models.RegionOverviewState
import cn.edu.bucea.library.data.models.RegionSeatCounts
import cn.edu.bucea.library.data.models.RegionSeatOverview
import cn.edu.bucea.library.ui.LibraryTheme
import cn.edu.bucea.library.ui.RegionOverviewSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** All region data and callbacks are synthetic; these tests never submit school mutations. */
@RunWith(AndroidJUnit4::class)
class RegionOverviewUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName
    private val today get() = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()

    @Test fun everyLoadedRegionShowsItsLocationAndActualStatusCountsWithoutAManualRefresh() {
        val first = region("one", "阅览一区", "演示馆 A", "二层",
            RegionSeatCounts(total = 12, free = 4, inUse = 3, reserved = 2, away = 1, unavailable = 1, localOnly = 1))
        val second = region("two", "阅览二区", "演示馆 B", "三层",
            RegionSeatCounts(total = 20, free = 0, inUse = 11, reserved = 5, away = 1, unavailable = 1, unknown = 2))
        val state = mutableStateOf(RegionOverviewState(date = today, regions = listOf(first, second),
            completedRegions = 2, totalRegions = 2, discoveryComplete = true, updatedAt = System.currentTimeMillis()))
        val refreshes = AtomicInteger()
        withRegions(state, onRefresh = { refreshes.incrementAndGet() }) {
            val firstCard = findWithScroll("The first compact region card must exist") { findDescription("区域卡片：one") }
            val secondCard = findWithScroll("The second compact region card must exist") { findDescription("区域卡片：two") }
            val firstBounds = Rect().apply { firstCard.getBoundsInScreen(this) }
            val secondBounds = Rect().apply { secondCard.getBoundsInScreen(this) }
            assertEquals("Two region cards must share the same row even with the device's current font size", firstBounds.top, secondBounds.top)
            assertTrue("Two region cards must occupy separate columns without overlap", firstBounds.right < secondBounds.left)
            assertTrue("Compact region cards must have similar column widths", kotlin.math.abs(firstBounds.width() - secondBounds.width()) <= 2)
            assertEquals("Paired compact region cards must share the same height", firstBounds.height(), secondBounds.height())
            if (InstrumentationRegistry.getArguments().getString("captureRegionScreenshots") == "true") {
                captureFixture("regions-fixture-two-columns")
            }
            findWithScroll("The first returned region must be displayed") { findText("阅览一区") }
            findWithScroll("Each room must identify its real building and floor") { findText("演示馆 A · 二层") }
            findWithScroll("The first room must display its actual free and total seats together") { findText("空闲 4 / 总 12") }
            findWithScroll("Occupancy must include use, reservation and absence only") { findText("占用 6") }
            findWithScroll("First room states must remain individually accessible") {
                findDescriptionPrefix("阅览一区座位状态：总席数12，空闲4，使用中3，已预约2，暂离1，不可用1，未知0，仅限现场1")
            }
            findWithScroll("Every returned region, including a full one, must be displayed") { findText("阅览二区") }
            findWithScroll("The second location must be independent of the first") { findText("演示馆 B · 三层") }
            findWithScroll("A real zero and the second room total must remain explicit") { findText("空闲 0 / 总 20") }
            findWithScroll("Unknown statuses must not be silently called occupied") { findText("占用 17") }
            findWithScroll("Second room must retain unknown status counts") {
                findDescriptionPrefix("阅览二区座位状态：总席数20，空闲0，使用中11，已预约5，暂离1，不可用1，未知2，仅限现场0")
            }
            assertEquals("Displaying automatically supplied layouts must not require a refresh tap", 0, refreshes.get())

            click(findWithScroll("Default expanded regions must offer manual collapse", backwards = true) { findText("收起") })
            waitFor("Collapsing must hide the area cards") { hasText("展开") && !hasText("阅览一区") && !hasText("阅览二区") }
            assertFalse("Collapsed region counts must disappear with the cards", hasRegionDistribution())
            update(state) { it.copy(updatedAt = System.currentTimeMillis()) }
            waitFor("A data update must preserve the user's collapsed preference") { hasText("展开") && !hasText("阅览一区") }
            click(findWithScroll("Collapsed regions must offer expansion") { findText("展开") })
            findWithScroll("Expanding must restore the same first-room counts") {
                findDescriptionPrefix("阅览一区座位状态：总席数12，空闲4，使用中3，已预约2，暂离1，不可用1，未知0，仅限现场1")
            }
            findWithScroll("Expanding must restore the same second-room counts") {
                findDescriptionPrefix("阅览二区座位状态：总席数20，空闲0，使用中11，已预约5，暂离1，不可用1，未知2，仅限现场0")
            }
            assertEquals("Collapse, data updates and expansion must not trigger region requests", 0, refreshes.get())
        }
    }

    @Test fun pendingErrorsStaleValuesAndVerificationNeverPretendToBeEmptyRooms() {
        val pending = region("pending", "待同步区域", "演示馆", "四层", null).copy(loading = true, updatedAt = null)
        val failed = region("failed", "读取失败区域", "演示馆", "五层", null)
            .copy(error = "合成测试：区域读取失败", updatedAt = null)
        val state = mutableStateOf(RegionOverviewState(date = today, regions = listOf(pending, failed),
            loading = true, completedRegions = 0, totalRegions = 2, discoveryComplete = true))
        val refreshes = AtomicInteger()
        val verifications = AtomicInteger()
        withRegions(state, onRefresh = { refreshes.incrementAndGet() }, onVerify = { verifications.incrementAndGet() }) {
            assertFalse("Refreshing while automatic loading is active must be disabled",
                enabled(findWithScroll("Refresh must stay discoverable") { findDescription("刷新各区域座位") }))
            findWithScroll("An unread room must explain that its values are pending") { findText("座位状态待同步") }
            findWithScroll("A room failure must remain visible with its location") { findText("合成测试：区域读取失败") }
            assertFalse("Unreadable rooms must not display fabricated zero capacity", hasText("空闲 0 / 总 0"))
            assertFalse("Unreadable rooms must not display a fabricated status bar", hasRegionDistribution())

            val newlyRead = region("pending", "待同步区域", "演示馆", "四层",
                RegionSeatCounts(total = 8, free = 2, inUse = 6))
            update(state) { it.copy(regions = listOf(newlyRead, failed), loading = false, stale = true, updatedAt = null) }
            findWithScroll("A partial first pass must identify newly synced rows", backwards = true) { findText("部分区域已同步") }
            assertFalse("Newly loaded rows must not be globally described as old data", hasText("上次区域数据"))

            val previous = region("pending", "待同步区域", "演示馆", "四层",
                RegionSeatCounts(total = 8, free = 2, inUse = 6)).copy(stale = true)
            update(state) { it.copy(regions = listOf(previous, failed), loading = false,
                verificationRequired = true, stale = true, updatedAt = System.currentTimeMillis()) }
            findWithScroll("A retained room must keep its previous counts", backwards = true) { findText("空闲 2 / 总 8") }
            findWithScroll("A retained room must identify stale data") { findText("上次数据") }
            findWithScroll("A challenge must be explicitly presented", backwards = true) { findText("区域同步需要额外验证") }
            val refresh = findWithScroll("Verification must retain the refresh affordance", backwards = true) { findDescription("刷新各区域座位") }
            assertFalse("A challenge must not encourage another unverified refresh", enabled(refresh))
            click(findWithScroll("Verification must offer its dedicated action") { findText("完成验证并继续同步") })
            waitFor("Verification action must invoke only the supplied callback") { verifications.get() == 1 }
            assertEquals(0, refreshes.get())

            update(state) { it.copy(verificationRequired = false, discoveryErrors = listOf("合成测试：二层区域清单读取失败")) }
            findWithScroll("Failed discovery must explain missing areas", backwards = true) { findText("合成测试：二层区域清单读取失败") }
            click(findWithScroll("Retry must become enabled when the challenge is cleared", backwards = true) { findDescription("刷新各区域座位") })
            waitFor("Retry must invoke only the supplied refresh callback") { refreshes.get() == 1 }
        }
    }

    private fun region(id: String, name: String, building: String, floor: String, counts: RegionSeatCounts?) =
        RegionSeatOverview(id = id, name = name, buildingId = "fixture-building-$id", buildingName = building,
            floorId = "fixture-floor-$id", floorName = floor, date = today, counts = counts,
            loading = false, updatedAt = System.currentTimeMillis())

    private fun withRegions(state: MutableState<RegionOverviewState>, onRefresh: () -> Unit = {},
        onVerify: () -> Unit = {}, assertions: () -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    LibraryTheme(AppThemeColor.NAILONG_YELLOW) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                            RegionOverviewSection(state.value, onRefresh, onVerify, authenticated = true, enabled = true)
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            waitFor("The synthetic region section must be in the foreground") { hasText("各区域座位") }
            if (InstrumentationRegistry.getArguments().getString("captureRegionScreenshots") == "true") {
                captureFixture(if (state.value.loading) "regions-fixture-pending" else "regions-fixture-loaded")
            }
            assertions()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun update(state: MutableState<RegionOverviewState>, change: (RegionOverviewState) -> RegionOverviewState) {
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

    private fun lines(node: AccessibilityNodeInfo) = node.text?.toString().orEmpty().lines().map { it.trim() }
    private fun findText(text: String) = nodes().firstOrNull { text in lines(it) }
    private fun hasText(text: String) = findText(text) != null
    private fun findDescription(text: String) = nodes().firstOrNull { it.contentDescription?.toString() == text }
    private fun findDescriptionPrefix(text: String) = nodes().firstOrNull { it.contentDescription?.toString()?.startsWith(text) == true }
    private fun hasRegionDistribution() = nodes().any { it.contentDescription?.toString()?.contains("座位状态：总席数") == true }

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
        assertEquals(appPackage, node.packageName?.toString())
        val control = requireNotNull(clickableParent(node))
        assertTrue("Fixture control must be enabled", control.isEnabled)
        assertTrue("Fixture control must accept its accessibility action", control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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

    private fun captureFixture(name: String) {
        val root = instrumentation.targetContext.filesDir
        File(root, "$name.txt").writeText(nodes().joinToString("\n") { node ->
            val bounds = Rect().apply { node.getBoundsInScreen(this) }
            "text=[${node.text}] description=[${node.contentDescription}] bounds=$bounds enabled=${node.isEnabled}"
        })
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            val screenshot = automation.takeScreenshot() ?: error("Synthetic region screenshot unavailable")
            val pixels = IntArray(screenshot.width * screenshot.height)
            screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
            val ready = pixels.count { it == 0xFFFFFFFF.toInt() || it == 0xFFFAFAFA.toInt() } > pixels.size / 2
            if (ready || SystemClock.uptimeMillis() >= deadline) {
                try { File(root, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { screenshot.recycle() }
                return
            }
            screenshot.recycle()
            SystemClock.sleep(100)
        }
    }
}
