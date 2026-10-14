package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.ui.HubAction
import cn.edu.bucea.library.ui.HubFavorite
import cn.edu.bucea.library.ui.HubRecord
import cn.edu.bucea.library.ui.HubSection
import cn.edu.bucea.library.ui.LibraryHubScreen
import cn.edu.bucea.library.ui.LibraryHubState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Synthetic presentation fixtures only. MainActivity can make its normal startup reads,
 * but these callbacks never access the repository, account vault or school mutation APIs.
 * Wi-Fi settings are checked as an enabled affordance without leaving the fixture.
 */
@RunWith(AndroidJUnit4::class)
class LibraryHubUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName

    @Test fun restrictedLocationExplainsLibraryWifiAndOffersRefreshWithoutAnUnsupportedMessage() {
        val refreshes = AtomicInteger()
        val actions = AtomicInteger()
        val state = mutableStateOf(LibraryHubState(currentStatus = "LOC_RESTRICT", requiresLibraryWifi = true))
        withHub(state, onRefresh = { refreshes.incrementAndGet() }, onAction = { actions.incrementAndGet() }) {
            waitFor("A location restriction must explain how to reconnect") {
                hasText("请连接图书馆 Wi-Fi 后刷新")
            }
            assertFalse("A known Wi-Fi requirement must not be described as unsupported", hasText("当前网络暂不支持操作座位"))
            assertFalse("A known Wi-Fi requirement must not be described as unsupported", hasText("当前网络不支持操作座位"))
            findWithScroll("Check-in must explicitly require library Wi-Fi") { findText("签到需连接图书馆 Wi-Fi") }
            val settings = findWithScroll("The user must be able to find Wi-Fi settings") { findText("打开 Wi-Fi 设置") }
            assertTrue("Wi-Fi settings must be an enabled action", clickableParent(settings)?.isEnabled == true)
            click(findWithScroll("Reconnect must offer a refresh action") { findText("已连接，刷新状态") })
            waitFor("Refresh must call the synthetic refresh callback once") { refreshes.get() == 1 }
            assertEquals("Connectivity guidance must not submit a seat action", 0, actions.get())
        }
    }

    @Test fun reservationCancellationRequiresConfirmationAndInactiveRecordsOfferNoCancelAction() {
        val cancellations = AtomicInteger()
        val cancelledId = AtomicReference<String?>()
        val record = HubRecord("fixture-reservation", "测试预约 A101", "已预约", listOf("明天 09:00 — 10:00"), canCancel = true)
        val state = mutableStateOf(LibraryHubState(currentStatus = "LOC_RESTRICT", records = listOf(record)))
        withHub(state, onCancelRecord = { cancellations.incrementAndGet(); cancelledId.set(it.id) }) {
            click(findWithScroll("An active reservation must expose cancellation directly") { findText("取消预约") })
            waitFor("Cancelling an active reservation must open confirmation") { hasText("取消这次预约？") }
            assertEquals("Opening the confirmation must not cancel", 0, cancellations.get())
            click(requireNode("The confirmation must let the user keep the reservation") { findText("保留预约") })
            waitFor("Keeping the reservation must close confirmation") { !hasText("取消这次预约？") }
            assertEquals("Keeping the reservation must not cancel", 0, cancellations.get())

            click(findWithScroll("The preserved reservation must still be cancellable") { findText("取消预约") })
            waitFor("The cancellation confirmation must reopen") { hasText("取消这次预约？") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Back must dismiss cancellation confirmation") { !hasText("取消这次预约？") }
            assertEquals("Dismissing confirmation must not cancel", 0, cancellations.get())

            click(findWithScroll("The reservation must remain cancellable after dismissal") { findText("取消预约") })
            click(requireNode("An explicit confirmation must be available") { findText("确认取消预约") })
            waitFor("Confirmation must submit the synthetic cancellation exactly once") {
                cancellations.get() == 1 && !hasText("取消这次预约？")
            }
            assertEquals(record.id, cancelledId.get())

            instrumentation.runOnMainSync {
                state.value = state.value.copy(records = listOf(record.copy(status = "已取消", canCancel = false)))
            }
            findWithScroll("The inactive record must remain visible") { findText("已取消") }
            assertFalse("An inactive record must not expose list cancellation", hasText("取消预约"))
            click(findWithScroll("Inactive records must still allow details") { findText(record.title) })
            waitFor("Inactive record details must open") { hasText("关闭") && hasText("已取消") }
            assertFalse("Inactive details must not expose cancellation", hasText("取消预约"))
            assertEquals("Inactive records must not trigger another cancellation", 1, cancellations.get())
        }
    }

    @Test fun manualFavoritesAndSystemFrequentSeatsStayDistinctWhenTheSameSeatIsRemovedLocally() {
        val removals = AtomicInteger()
        val selections = AtomicInteger()
        val selectedSeat = AtomicReference<HubFavorite?>()
        val favorite = HubFavorite("fixture-seat", "测试座位 A101", "测试阅览区", local = true)
        val frequent = favorite.copy(local = false)
        var localFavorites = listOf(favorite)
        val state = mutableStateOf(LibraryHubState(section = HubSection.FAVORITES, favorites = localFavorites))
        withHub(state,
            onSection = { section ->
                state.value = state.value.copy(section = section, favorites = when (section) {
                    HubSection.FAVORITES -> localFavorites
                    HubSection.FREQUENT -> listOf(frequent)
                    else -> emptyList()
                })
            },
            onFavorite = { selections.incrementAndGet(); selectedSeat.set(it) },
            onRemoveFavorite = {
                assertTrue("Only a manually added favorite may be removed", it.local)
                removals.incrementAndGet()
                localFavorites = localFavorites.filterNot { saved -> saved.id == it.id }
                state.value = state.value.copy(favorites = localFavorites)
            },
        ) {
            findWithScroll("Manual favorites must describe who added them") { findText("本机收藏 · 由你手动添加") }
            click(findWithScroll("A manual favorite must expose a remove action") { findDescription("移除${favorite.name}") })
            waitFor("Removing a local favorite must require confirmation") { hasText("移除收藏座位") }
            assertEquals("Opening local removal must not change favorites", 0, removals.get())
            click(requireNode("Local favorite removal must be dismissible") { findText("取消") })
            waitFor("Dismissed removal must preserve the favorite") { !hasText("移除收藏座位") }
            assertEquals(0, removals.get())

            click(findWithScroll("The preserved manual favorite must remain removable") { findDescription("移除${favorite.name}") })
            click(requireNode("Local removal must require an explicit click") { findText("移除") })
            waitFor("Confirmed removal must affect local favorites once") { removals.get() == 1 && !hasText("移除收藏座位") }
            findWithScroll("Confirmed removal must leave manual favorites empty") { findText("还没有收藏座位") }

            click(findWithScroll("System frequent seats must have their own section", backwards = true) { findControl("常用座位") })
            findWithScroll("The same seat must remain in the system frequent list") { findText(frequent.name) }
            findWithScroll("System frequent seats must explain their source") { findText("系统常用 · 根据使用记录生成") }
            assertFalse("The client must not offer manual deletion of a system frequent seat", findDescription("移除${frequent.name}") != null)
            assertEquals("Opening the system list must not remove another favorite", 1, removals.get())
            click(findWithScroll("System frequent seats must still allow choosing a time") { findText("选择使用时间") })
            waitFor("Selecting the system seat must call only the synthetic selection callback") { selections.get() == 1 }
            assertEquals(frequent, selectedSeat.get())

            click(findWithScroll("Manual favorites must remain a separate section", backwards = true) { findControl("收藏座位") })
            findWithScroll("The removed seat must stay absent from manual favorites") { findText("还没有收藏座位") }
            assertFalse("A system entry must not reappear as a manual favorite", hasText(favorite.name))
            assertEquals(1, removals.get())
        }
    }

    private fun withHub(
        state: MutableState<LibraryHubState>,
        onRefresh: () -> Unit = {},
        onSection: (HubSection) -> Unit = { state.value = state.value.copy(section = it) },
        onFavorite: (HubFavorite) -> Unit = {},
        onRemoveFavorite: (HubFavorite) -> Unit = {},
        onAction: (HubAction) -> Unit = {},
        onCancelRecord: (HubRecord) -> Unit = {},
        assertions: () -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        var fixtureReady = false
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        LibraryHubScreen(
                            state = state.value,
                            onRefresh = onRefresh,
                            onSection = onSection,
                            onLoadMore = {},
                            onSearch = {},
                            onOpenMap = {},
                            onFavorite = onFavorite,
                            onRemoveFavorite = onRemoveFavorite,
                            onAction = onAction,
                            onCancelRecord = onCancelRecord,
                            onOpenRecord = { state.value = state.value.copy(selectedRecord = it) },
                            onDismissRecord = { state.value = state.value.copy(selectedRecord = null) },
                        )
                    }
                }
            }
            instrumentation.waitForIdleSync()
            waitFor("The synthetic library hub must be in the foreground") { hasText("我的图书馆") }
            fixtureReady = true
            assertions()
        } catch (failure: Throwable) {
            if (fixtureReady) captureHubFailure(state.value, failure)
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    /** Capture only the synthetic fixture, before withHub restores or finishes the activity. */
    private fun captureHubFailure(state: LibraryHubState, failure: Throwable) {
        runCatching {
            val root = automation.rootInActiveWindow ?: return
            if (root.packageName?.toString() != appPackage) return
            val dump = StringBuilder().apply {
                appendLine("Synthetic LibraryHub fixture failure: ${failure.message}")
                appendLine("section=${state.section} loading=${state.loading} currentStatus=${state.currentStatus}")
                state.records.forEach { appendLine("record id=${it.id} title=${it.title} status=${it.status} canCancel=${it.canCancel}") }
                appendLine("Accessibility nodes from target package only:")
            }
            fun value(text: CharSequence?): String = text?.toString().orEmpty()
                .replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (node.packageName?.toString() == appPackage) {
                    val bounds = Rect().apply { node.getBoundsInScreen(this) }
                    dump.append("  ".repeat(depth))
                        .append("class=${node.className} bounds=$bounds visible=${node.isVisibleToUser}")
                        .append(" enabled=${node.isEnabled} clickable=${node.isClickable} scrollable=${node.isScrollable}")
                        .append(" text=[${value(node.text)}] description=[${value(node.contentDescription)}]")
                        .appendLine(" actions=${node.actionList.map { it.id }}")
                }
                repeat(node.childCount) { node.getChild(it)?.let { visit(it, depth + 1) } }
            }
            visit(root, 0)
            File(instrumentation.targetContext.filesDir, "whitespace-hub-failure.txt").writeText(dump.toString())
            automation.takeScreenshot()?.let { bitmap ->
                try {
                    File(instrumentation.targetContext.filesDir, "whitespace-hub-failure.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
        }.onFailure { failure.addSuppressed(it) }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        // Some Android builds retain old Compose text/enabled nodes after a visual update.
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == appPackage && node.isVisibleToUser) found += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        automation.rootInActiveWindow?.let(::visit)
        return found
    }

    private fun matchesText(node: AccessibilityNodeInfo, value: String): Boolean =
        node.text?.toString().orEmpty().lines().any { it.trim() == value }

    private fun findText(value: String) = nodes().firstOrNull { matchesText(it, value) }
    private fun findDescription(value: String) = nodes().firstOrNull { it.contentDescription?.toString() == value }
    private fun findControl(value: String) = nodes().firstOrNull { matchesText(it, value) && clickableParent(it) != null }
    private fun hasText(value: String) = findText(value) != null

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun requireNode(message: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitFor(message) { result = find(); result != null }
        return requireNotNull(result)
    }

    private fun findWithScroll(message: String, backwards: Boolean = false, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        repeat(10) {
            find()?.let { return it }
            nodes().firstOrNull { it.isScrollable && it.isEnabled }?.performAction(
                if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            SystemClock.sleep(150)
        }
        return requireNode(message, find)
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
        assertEquals("Only synthetic application content may receive clicks", appPackage, node.packageName?.toString())
        val clickable = clickableParent(node)
        assertTrue("The verified fixture control must be enabled", (clickable ?: node).isEnabled)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        val bounds = Rect().apply { node.getBoundsInScreen(this) }
        val window = Rect().apply { automation.rootInActiveWindow?.getBoundsInScreen(this) }
        assertTrue("The fixture target must intersect the active window", bounds.intersect(window) && !bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("The fixture touch must be delivered", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(35)
        }
    }
}
