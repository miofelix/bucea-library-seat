package cn.edu.bucea.library

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Presentation-only fixture replaces MainActivity's content with synthetic seat results.
 * Initial MainActivity creation may perform its normal public/session restore; this test does
 * not claim to suppress those startup reads. Fixture callbacks never use a VM, vault or server,
 * and never submit a search, booking, account operation or password mutation.
 */
@RunWith(AndroidJUnit4::class)
class SeatFilterUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val appPackage get() = instrumentation.targetContext.packageName

    @Test fun fourCompactCardsFitLongLabelsAndKeepLongPressFavoritesSeparateFromSelection() {
        val selected = AtomicInteger()
        val favorites = AtomicInteger()
        val seats = listOf(
            NativeSeat("compact-free", "A101", "阅览室", status = "座位空闲", available = true),
            NativeSeat("compact-used", "B202", "大兴校区三层自然科学阅览区域靠窗长名称",
                status = "当前正在使用，可预约其他时段", available = true, power = true, window = true),
            NativeSeat("compact-blocked", "C303", "阅览室", status = "座位空闲", available = false),
            NativeSeat("compact-long", "D404-长座位编号", "大兴校区三层自然科学阅览区域靠窗长名称",
                status = "服务器返回的未知座位状态长名称", available = false, localFavorite = true),
        )
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1f)) {
                        MaterialTheme {
                            SeatSearchScreen(
                                state = SeatSearchState(hasSearched = true, resultsComplete = true, seats = seats),
                                onFilter = { _, _ -> }, onSearch = {}, onNextPage = {}, onPreviousPage = {},
                                onSelectSeat = { selected.incrementAndGet() },
                                onToggleFavorite = { favorites.incrementAndGet() }, onBack = {},
                            )
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            findWithScroll("All four compact result cards must be visible") { findSeatTile("D404-长座位编号") }
            val cardBounds = assertFourSeatRow(seats.map { it.name }, "standard text scale")
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertTrue("Standard text cards should stay approximately square even with long labels", cardBounds.all { it.height() / density <= 82f })
            assertAvailabilityBorders(cardBounds, seats.map { it.available })
            assertTrue("The favorite gesture must be discoverable", hasText("点按选时间 · 长按收藏"))
            seats.zip(cardBounds).forEach { (seat, card) ->
                val tile = requireNotNull(findSeatTile(seat.name))
                assertTrue("The whole tile retains at least a 48dp touch target",
                    card.width() / density >= 47.9f && card.height() / density >= 47.9f)
                assertTrue("Long press exposes a labeled accessible favorite action", tile.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK &&
                        it.label?.toString() == if (seat.localFavorite) "取消收藏 ${seat.name}" else "收藏 ${seat.name}"
                })
                if (seat == seats.first()) touch(tile, holdMillis = 700) else longClick(tile)
            }
            waitFor("Long press must favorite each available or unavailable tile") { favorites.get() == 4 }
            assertEquals("Long-press favorites must never open reservation times", 0, selected.get())
            click(requireNotNull(findSeatTile("A101")))
            waitFor("One available-card click must immediately select its time") { selected.get() == 1 }
            touch(requireNotNull(findSeatTile("C303")))
            instrumentation.waitForIdleSync()
            assertEquals("An unavailable tile must not open reservation times", 1, selected.get())
            captureSeatCardsIfRequested("-normal")
        } catch (failure: Throwable) {
            captureFailure(failure)
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun toggleImmediatelyFiltersAllLoadedResultsAndAutomaticallyAppliesToIncomingSeats() {
        val selected = AtomicInteger()
        val favorites = AtomicInteger()
        val searches = AtomicInteger()
        val nextPages = AtomicInteger()
        val mixedResults = SeatSearchState(hasSearched = true, hasNextPage = true, seats = listOf(
            NativeSeat("fixture-free", "A101", "测试区域", status = "座位空闲", available = true),
            NativeSeat("fixture-used", "B202", "测试区域", status = "正在使用", available = false),
            NativeSeat("fixture-future", "C303", "大兴校区三层自然科学阅览区域靠窗长名称", power = true, window = true,
                status = "当前正在使用，可预约其他时段", available = true),
            NativeSeat("fixture-idle", "D404", "长名称阅览区域", status = "座位空闲", available = false, localFavorite = true),
        ))
        val screen = mutableStateOf(mixedResults)
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f)) {
                        MaterialTheme {
                            SeatSearchScreen(
                                state = screen.value,
                                onFilter = { key, value -> screen.value = screen.value.copy(filters = screen.value.filters + (key to value)) },
                                onSearch = { searches.incrementAndGet() },
                                onNextPage = { nextPages.incrementAndGet() },
                                onPreviousPage = { nextPages.incrementAndGet() },
                                onSelectSeat = { selected.incrementAndGet() },
                                onToggleFavorite = { favorites.incrementAndGet() },
                                onBack = {},
                            )
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            findWithScroll("Mixed fixture results must show all four loaded seats") { findText("显示 4 / 已加载 4 个") }
            val blocked = findWithScroll("The unavailable fixture seat must be visible") { findSeatTile("B202") }
            val screenWidthDp = instrumentation.targetContext.resources.configuration.screenWidthDp
            val rowBounds = assertFourSeatRow(listOf("A101", "B202", "C303", "D404"), "1.6 font scale at ${screenWidthDp}dp")
            assertTrue("Compact cards must use approximately one quarter of the results width", rowBounds.all {
                it.width() <= instrumentation.targetContext.resources.displayMetrics.widthPixels / 4
            })
            val futureSeat = requireNotNull(findSeatTile("C303"))
            assertTrue("Full current status and future availability must remain independently accessible",
                futureSeat.stateDescription.toString().contains("当前正在使用，可预约其他时段，可预约"))
            assertTrue("Truncated area and compact capability icons retain the full accessible description",
                futureSeat.stateDescription.toString().contains("大兴校区三层自然科学阅览区域靠窗长名称，有电源，靠窗"))
            val idleSeat = requireNotNull(findSeatTile("D404"))
            assertTrue("An idle seat may still be unavailable according to the server",
                idleSeat.stateDescription.toString().contains("座位空闲，不可预约"))
            longClick(requireNotNull(findSeatTile("A101")))
            waitFor("The long-press favorite callback must be delivered") { favorites.get() == 1 }
            assertEquals("A long press must invoke only its favorite callback", 1, favorites.get())
            assertEquals("Favoriting must not choose a reservation time", 0, selected.get())
            val unavailableTile = requireNotNull(findSeatTile("B202"))
            assertTrue("Unavailable seats retain an enabled favorite gesture", unavailableTile.isEnabled && unavailableTile.isLongClickable)
            longClick(unavailableTile)
            waitFor("Unavailable-seat favorites must still be actionable") { favorites.get() == 2 }
            assertEquals("Favoriting an unavailable seat must not select it", 0, selected.get())
            touch(blocked)
            instrumentation.waitForIdleSync()
            assertEquals("An unavailable card body must not select a seat", 0, selected.get())
            assertFalse("Seat cards must not retain a separate time-selection footer", hasText("选时间"))
            captureSeatCardsIfRequested()
            val availableTile = requireNotNull(findSeatTile("A101"))
            assertTrue("Available tile exposes a labeled time-selection action", availableTile.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "选择 A101 的使用时间"
            })
            touch(availableTile)
            waitFor("One physical tap must choose a time through the existing callback") { selected.get() == 1 }
            click(findWithScroll("The whole available card must remain selectable") { findSeatTile("A101") })
            waitFor("The available card body must choose a time through the same callback") { selected.get() == 2 }
            assertEquals("Seat selection must not toggle a favorite", 2, favorites.get())

            val switch = findWithScroll("The immediate availability filter must be visible", backwards = true) { filterSwitch() }
            click(switch)
            waitFor("Switch must immediately hide the unavailable seat without querying") {
                hasText("显示 2 / 已加载 4 个") && !hasText("B202") && !hasText("D404")
            }
            assertEquals("Changing the local filter must not issue a new query", 0, searches.get())
            assertTrue("Filter semantics must report enabled", filterSwitch()?.isChecked == true)

            assertFalse("All results must stay on one screen without a next-page control", hasText("下一页"))
            assertFalse("All results must stay on one screen without a previous-page control", hasText("上一页"))
            instrumentation.runOnMainSync {
                screen.value = mixedResults.copy(hasNextPage = true, seats = mixedResults.seats +
                    NativeSeat("fixture-incoming-unavailable", "E505", "测试区域", status = "不可用", available = false))
            }
            waitFor("An incoming unavailable result must be filtered without dropping earlier results") {
                hasText("显示 2 / 已加载 5 个") && !hasText("E505")
            }
            assertEquals("Automatic loading must not invoke a manual page action", 0, nextPages.get())

            instrumentation.runOnMainSync {
                screen.value = screen.value.copy(hasNextPage = false, resultsComplete = true,
                    seats = screen.value.seats + listOf(
                        NativeSeat("fixture-incoming-future", "F606", "测试区域", status = "当前正在使用，可预约其他时段", available = true),
                        NativeSeat("fixture-idle-disabled", "G707", "测试区域", status = "座位空闲", available = false),
                    ))
            }
            waitFor("The enabled filter must apply to the final aggregate without another query") {
                hasText("显示 3 / 已加载 7 个")
            }
            findWithScroll("A server-available future-time seat must remain visible") { findText("F606") }
            assertFalse("A disabled seat with an idle label must stay filtered out", hasText("G707"))
            click(findWithScroll("The filter must remain available after automatic loading", backwards = true) { filterSwitch() })
            waitFor("Disabling the filter must immediately restore all loaded seats") { hasText("显示 7 / 已加载 7 个") }
            findWithScroll("The hidden unavailable seat must return without another query") { findText("E505") }
            click(findWithScroll("The availability filter must remain accessible", backwards = true) { filterSwitch() })
            instrumentation.runOnMainSync {
                screen.value = mixedResults.copy(resultsComplete = true, hasNextPage = false, seats = listOf(
                    NativeSeat("fixture-only-unavailable", "Z999", "测试区域", status = "不可用", available = false),
                ))
            }
            waitFor("Filtering every seat must show an accurate empty state") {
                hasText("显示 0 / 已加载 1 个") && hasText("当前没有可预约座位") && !hasText("Z999")
            }
            assertEquals("No fixture state change may require another search", 0, searches.get())
            assertEquals("Only explicit available-card taps may select a time", 2, selected.get())
        } catch (failure: Throwable) {
            captureFailure(failure)
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

    private fun findText(value: String) = nodes().firstOrNull { node ->
        val text = node.text?.toString().orEmpty()
        text.replace(Regex("\\s+"), " ").trim() == value || text.lines().any { it.trim() == value } ||
            (value.matches(Regex("[A-E][0-9]{3}")) && Regex("(?:^|\\s|,)${Regex.escape(value)}(?:$|\\s|,)").containsMatchIn(text))
    }

    private fun hasText(value: String) = findText(value) != null

    private fun captureFailure(failure: Throwable) {
        runCatching {
            val fixtureNodes = nodes()
            val dump = fixtureNodes.joinToString("\n", prefix = "Synthetic seat fixture: ${failure.message}\n") { node ->
                val bounds = Rect().apply { node.getBoundsInScreen(this) }
                "bounds=$bounds enabled=${node.isEnabled} clickable=${node.isClickable} scrollable=${node.isScrollable}" +
                    " text=[${node.text}] description=[${node.contentDescription}] state=[${node.stateDescription}]"
            }
            File(instrumentation.targetContext.filesDir, "seat-filter-fixture-failure.txt").writeText(dump)
            automation.takeScreenshot()?.let { screenshot ->
                try {
                    File(instrumentation.targetContext.filesDir, "seat-filter-fixture-failure.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
        }.onFailure { failure.addSuppressed(it) }
    }

    private fun captureSeatCardsIfRequested(suffix: String = "") {
        if (InstrumentationRegistry.getArguments().getString("captureSeatCards") != "true") return
        automation.takeScreenshot()?.let { screenshot ->
            try {
                File(instrumentation.targetContext.filesDir, "seat-filter-four-column-cards$suffix.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
        }
        val dump = nodes().joinToString("\n") { node ->
            val bounds = Rect().apply { node.getBoundsInScreen(this) }
            "bounds=$bounds enabled=${node.isEnabled} clickable=${node.isClickable}" +
                " text=[${node.text}] description=[${node.contentDescription}] state=[${node.stateDescription}]"
        }
        File(instrumentation.targetContext.filesDir, "seat-filter-four-column-cards$suffix.txt").writeText(dump)
    }

    private fun assertFourSeatRow(names: List<String>, configuration: String): List<Rect> {
        val bounds = names.map { name ->
            Rect().apply { requireNotNull(findSeatTile(name)).getBoundsInScreen(this) }
        }
        assertEquals("The fixture must exercise a full four-seat row", 4, bounds.size)
        bounds.forEach { card ->
            assertEquals("Four seat cards must share a top edge at $configuration", bounds.first().top, card.top)
            assertEquals("Four seat cards must share a bottom edge at $configuration", bounds.first().bottom, card.bottom)
        }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("Adjacent cards must remain separate without overlap at $configuration", left.right < right.left)
        }
        return bounds
    }

    private fun assertAvailabilityBorders(bounds: List<Rect>, available: List<Boolean>) {
        val screenshot = requireNotNull(automation.takeScreenshot())
        try {
            val edgeBand = (3 * instrumentation.targetContext.resources.displayMetrics.density).toInt().coerceAtLeast(2)
            bounds.zip(available).forEach { (card, bookable) ->
                val colors = (card.left until (card.left + edgeBand).coerceAtMost(card.right)).flatMap { x ->
                    (card.centerY() - 2..card.centerY() + 2).map { y -> screenshot.getPixel(x, y) }
                }
                val hasExpectedColor = colors.any { pixel ->
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)
                    if (bookable) green > red * 1.2f && green > blue * 1.2f
                    else red > green * 1.3f && red > blue * 1.3f
                }
                assertTrue("${if (bookable) "Bookable" else "Unbookable"} seats must have a visible ${if (bookable) "green" else "red"} border", hasExpectedColor)
            }
        } finally { screenshot.recycle() }
    }

    private fun findDescription(value: String) = nodes().firstOrNull { it.contentDescription?.toString() == value }

    private fun findSeatTile(name: String): AccessibilityNodeInfo? = findDescription("座位 $name")?.let { label ->
        // Compose exposes the explicit description as a child proxy; the
        // enclosing clickable tile owns state and labeled gesture actions.
        clickableParent(label) ?: label
    }

    private fun filterSwitch(): AccessibilityNodeInfo? {
        val current = nodes()
        return current.firstOrNull { it.isCheckable && it.contentDescription?.toString() == "仅看可用" }
            ?: current.firstOrNull { it.isCheckable && Build.VERSION.SDK_INT >= 30 && it.stateDescription?.toString()?.contains("显示") == true }
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

    private fun longClick(node: AccessibilityNodeInfo) {
        assertEquals("Only synthetic native content receives long presses", appPackage, node.packageName?.toString())
        assertTrue("The verified fixture tile must support long press", node.isEnabled && node.isLongClickable)
        assertTrue("The accessible long press must invoke its callback", node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
    }

    private fun touch(node: AccessibilityNodeInfo, holdMillis: Long = 35) {
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
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(holdMillis)
        }
    }
}
