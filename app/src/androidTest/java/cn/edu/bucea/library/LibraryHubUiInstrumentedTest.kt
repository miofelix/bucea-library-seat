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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.ui.HubAction
import cn.edu.bucea.library.ui.FavoriteBookingActions
import cn.edu.bucea.library.ui.HubFavorite
import cn.edu.bucea.library.ui.HubRecord
import cn.edu.bucea.library.ui.HubSection
import cn.edu.bucea.library.ui.LibraryHubScreen
import cn.edu.bucea.library.ui.LibraryHubState
import cn.edu.bucea.library.ui.NativeSeat
import cn.edu.bucea.library.ui.SeatOption
import cn.edu.bucea.library.ui.SeatSearchState
import cn.edu.bucea.library.data.models.ReservationSchedule
import cn.edu.bucea.library.ui.reservationTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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

    @Test fun reservationTimelineDisplaysEveryScheduledSeatInOrderAndKeepsPaginationAndDetails() {
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)
        val evening = HubRecord("fixture-evening", "晚间预约 A101", "已预约", listOf("座位：A101", "位置：图书馆二层东区"),
            canCancel = true, schedule = ReservationSchedule(date.toString(), "18:00", "20:00"))
        val late = HubRecord("fixture-late", "晚间预约 B202", "待签到", listOf("座位：B202", "位置：图书馆三层西区"),
            canCancel = true, schedule = ReservationSchedule(date.toString(), "20:30", "22:00"))
        val tomorrow = HubRecord("fixture-following-day", "次日预约 C303", "已预约", listOf("座位：C303", "位置：图书馆一层南区"),
            canCancel = true, schedule = ReservationSchedule(date.plusDays(1).toString(), "08:00", "10:00"))
        val records = listOf(tomorrow, late, evening)
        assertEquals(listOf(evening.id, late.id, tomorrow.id),
            reservationTimeline(records, LocalDateTime.of(date, java.time.LocalTime.NOON)).single().records.map { it.id })
        val state = mutableStateOf(LibraryHubState(currentStatus = "NO_RESERVATION", records = records, hasMore = true))
        val opened = AtomicReference<String?>()
        val loads = AtomicInteger()
        withHub(state, onLoadMore = { loads.incrementAndGet() }, onOpenRecord = { record ->
            opened.set(record.id)
            state.value = state.value.copy(selectedRecord = record)
        }) {
            findWithScroll("The reservation screen must show its timeline") { findText("预约时间线") }
            assertFalse("My reservations must remove the redundant search shortcut", hasText("查找座位"))
            assertFalse("My reservations must remove the redundant map shortcut", hasText("布局选座"))
            val expected = listOf(evening to "18:00 — 20:00", late to "20:30 — 22:00", tomorrow to "08:00 — 10:00")
            expected.forEach { (record, time) ->
                findWithScroll("Each scheduled reservation must show its real time") { findText(time) }
                findWithScroll("Each scheduled reservation must show its seat") { findText(record.details.first().substringAfter('：') + " 号座位") }
                findWithScroll("Each scheduled reservation must show its location") { findText(record.details[1]) }
                findWithScroll("The timeline record remains available for details", backwards = true) { findText(record.title) }
            }
            click(findWithScroll("Pagination must remain available below the loaded timeline") { findText("加载更多") })
            waitFor("Pagination must invoke the existing callback") { loads.get() == 1 }
            click(findWithScroll("The following-day reservation must allow details", backwards = true) { findText(tomorrow.title) })
            waitFor("Timeline details must retain the original server record") { opened.get() == tomorrow.id && hasText("关闭") }
            assertTrue("An active timeline record may still expose server-authorized cancellation in details", hasText("取消预约"))
            click(requireNode("Timeline details must remain dismissible") { findText("关闭") })
            waitFor("Closing details must return to the timeline") { !hasText("关闭") }
        }
    }

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

    @Test fun compactReservationCardsKeepEndedLabelsDetailsAndTouchTargetsAtLargeFontScale() {
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val ended = HubRecord("fixture-ended", "测试预约 A101", "结束使用",
            listOf("座位：A101", "位置：图书馆二层东区"),
            schedule = ReservationSchedule(date.minusDays(1).toString(), "09:00", "11:00"))
        val state = mutableStateOf(LibraryHubState(currentStatus = "NO_RESERVATION", records = listOf(ended)))
        val cancellations = AtomicInteger()
        withHub(state, fontScale = 1.6f, onCancelRecord = { cancellations.incrementAndGet() }) {
            val endedLabel = findWithScroll("The complete server label must remain visible at large text size") { findText("结束使用") }
            val labelBounds = Rect().apply { endedLabel.getBoundsInScreen(this) }
            assertTrue("The status must grow with text rather than use a clipped fixed height",
                labelBounds.height() >= 11f * 1.6f * instrumentation.targetContext.resources.displayMetrics.density)
            findWithScroll("The compact card must preserve its scheduled time") { findText("09:00 — 11:00") }
            findWithScroll("The compact card must preserve its seat") { findText("A101 号座位") }
            findWithScroll("The compact card must preserve its location") { findText("位置：图书馆二层东区") }
            assertMinimumTouchHeight(findWithScroll("The compact details action must remain visible") { findText("查看详情") })
            click(findWithScroll("The real record title must still open its details") { findText(ended.title) })
            waitFor("Ended record details must retain the complete status") { hasText("关闭") && hasText("结束使用") }
            assertFalse("Ending use must not expose cancellation", hasText("取消预约"))
            click(requireNode("The large-text details dialog must close") { findText("关闭") })
            waitFor("Closing details must return to the compact card") { !hasText("关闭") }

            instrumentation.runOnMainSync {
                state.value = state.value.copy(records = listOf(ended.copy(status = "已履约")))
            }
            findWithScroll("Completed records must retain their own label at large text size", backwards = true) { findText("已履约") }
            assertFalse("Completed records must not expose cancellation", hasText("取消预约"))

            instrumentation.runOnMainSync {
                state.value = state.value.copy(records = listOf(ended.copy(id = "fixture-active", status = "已预约", canCancel = true,
                    schedule = ReservationSchedule(date.plusDays(1).toString(), "09:00", "11:00"))))
            }
            val cancel = findWithScroll("Active compact cards must retain cancellation") { findText("取消预约") }
            assertMinimumTouchHeight(cancel)
            click(cancel)
            waitFor("The compact cancel action must still require confirmation") { hasText("取消这次预约？") }
            click(requireNode("The confirmation must remain dismissible at large text size") { findText("保留预约") })
            waitFor("Keeping the reservation must return to the compact card") { !hasText("取消这次预约？") }
            assertEquals("The presentation regression must never submit a cancellation", 0, cancellations.get())
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

    @Test fun favoriteBookingSelectsServerDatesAndTimesInlineWithLoadingRetryDismissAndConfirmation() {
        val favorite = HubFavorite("fixture-favorite", "A101", "测试阅览区", local = true)
        val hub = mutableStateOf(LibraryHubState(section = HubSection.FAVORITES, favorites = listOf(favorite)))
        val reservation = mutableStateOf(SeatSearchState(loading = true, accountLabel = "测试账号"))
        val navigation = AtomicInteger()
        val retries = AtomicInteger()
        val preparations = AtomicInteger()
        val submissions = AtomicInteger()
        val dismissals = AtomicInteger()
        val chosenDate = AtomicReference<String?>()
        val dates = listOf(SeatOption("fixture-today", "今天（测试）"), SeatOption("fixture-tomorrow", "明天（测试）"))
        val seat = NativeSeat(favorite.id, favorite.name, favorite.location)
        withHub(hub,
            onSearch = { navigation.incrementAndGet() },
            onFavorite = { preparations.incrementAndGet() },
            favoriteBooking = {
                FavoriteBookingActions(
                    state = reservation.value,
                    onDate = { selected, date ->
                        assertEquals(favorite, selected)
                        assertTrue("Only returned server dates can be selected", dates.any { it.value == date })
                        chosenDate.set(date)
                        reservation.value = reservation.value.copy(filters = mapOf("onDate" to date), selectedSeat = seat,
                            startOptions = listOf(SeatOption("540", "09:00")), endOptions = emptyList(),
                            selectedStart = null, selectedEnd = null)
                        true
                    },
                    onRetryDates = {
                        retries.incrementAndGet()
                        reservation.value = reservation.value.copy(options = mapOf("onDate" to dates),
                            filters = mapOf("onDate" to dates.first().value), loading = false, error = null)
                    },
                    onStart = { value -> reservation.value = reservation.value.copy(selectedStart = value,
                        endOptions = listOf(SeatOption("600", "10:00"))) },
                    onEnd = { value -> reservation.value = reservation.value.copy(selectedEnd = value) },
                    onConfirm = {
                        submissions.incrementAndGet()
                        reservation.value = reservation.value.copy(selectedSeat = null, status = "预约成功")
                    },
                    onDismiss = {
                        dismissals.incrementAndGet()
                        reservation.value = reservation.value.copy(selectedSeat = null, error = null)
                    },
                    onVerify = {},
                    onOpenReservations = { hub.value = hub.value.copy(section = HubSection.RESERVATIONS) },
                )
            },
        ) {
            findWithScroll("Favorites must explain the long-press gesture") {
                findText("在选座页长按座位卡片即可收藏，再次长按可取消收藏。")
            }
            click(findWithScroll("A favorite must offer its inline booking flow") { findText("选择使用时间") })
            waitFor("The current page must display loading dates") { hasText("正在读取可预约日期…") }
            assertEquals(0, navigation.get())
            assertEquals(HubSection.FAVORITES, hub.value.section)
            assertNull(chosenDate.get())
            click(requireNode("The pending date selection must remain dismissible") { findText("取消") })
            waitFor("Closing pending selection must restore the favorites list") { !hasText("选择预约日期") }
            assertEquals("Dismiss must not submit a reservation", 0, submissions.get())

            click(findWithScroll("The favorite must reopen on the same page") { findText("选择使用时间") })
            instrumentation.runOnMainSync { reservation.value = reservation.value.copy(loading = false, error = "合成日期读取失败") }
            waitFor("A failed date read must show its reason") { hasText("合成日期读取失败") }
            click(requireNode("Dates must support a deliberate retry") { findText("重新读取日期") })
            waitFor("A successful retry must render returned dates") { hasText("明天（测试）") }
            assertEquals(1, retries.get())
            click(requireNode("The user must be able to choose a different server date") { findText("明天（测试）") })
            click(requireNode("Choosing a date must open times locally") { findText("选择时间") })
            waitFor("The favorite must reuse the native time sheet") { hasText("选择可用时段") }
            assertEquals("fixture-tomorrow", chosenDate.get())
            assertEquals(0, navigation.get())
            assertEquals(HubSection.FAVORITES, hub.value.section)

            click(requireNode("Start times must remain selectable") { findDescription("选择开始时间") })
            click(requireNode("Only a returned start time is used") { findText("09:00") })
            waitFor("The chosen start time must reach the native reservation sheet") {
                reservation.value.selectedStart == "540" && findDescription("关闭预约面板") != null
            }
            click(requireNode("End times must remain selectable") { findDescription("选择结束时间") })
            click(requireNode("Only a returned end time is used") { findText("10:00") })
            waitFor("The chosen end time must reach the native reservation sheet") {
                reservation.value.selectedEnd == "600" && findDescription("关闭预约面板") != null
            }
            instrumentation.waitForIdleSync()
            click(requireNode("A favorite must still require the confirmation step") { findText("下一步") })
            try {
                waitFor("Reservation details must be confirmed before submission") { hasText("确认这次预约") }
            } catch (failure: AssertionError) {
                throw AssertionError("${failure.message}; seat=${reservation.value.selectedSeat?.id}, " +
                    "start=${reservation.value.selectedStart}, end=${reservation.value.selectedEnd}, " +
                    "submissions=${submissions.get()}, dismissals=${dismissals.get()}", failure)
            }
            assertEquals(0, submissions.get())
            assertTrue("Confirmation must show the explicitly selected date", hasText("明天（测试）"))
            click(requireNode("Only explicit final confirmation may submit") { findText("确认预约") })
            waitFor("A successful inline reservation must return to favorites") {
                submissions.get() == 1 && !hasText("确认这次预约") && !hasText("选择预约日期")
            }
            assertEquals(HubSection.FAVORITES, hub.value.section)
            assertEquals(0, navigation.get())
            assertEquals(2, preparations.get())
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
        onLoadMore: () -> Unit = {},
        onOpenRecord: (HubRecord) -> Unit = { state.value = state.value.copy(selectedRecord = it) },
        onSearch: () -> Unit = {},
        favoriteBooking: () -> FavoriteBookingActions? = { null },
        fontScale: Float? = null,
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
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                        MaterialTheme {
                            LibraryHubScreen(
                                state = state.value,
                                onRefresh = onRefresh,
                                onSection = onSection,
                                onLoadMore = onLoadMore,
                                onSearch = onSearch,
                                onOpenMap = {},
                                onFavorite = onFavorite,
                                onRemoveFavorite = onRemoveFavorite,
                                onAction = onAction,
                                onCancelRecord = onCancelRecord,
                                onOpenRecord = onOpenRecord,
                                onDismissRecord = { state.value = state.value.copy(selectedRecord = null) },
                                favoriteBooking = favoriteBooking(),
                            )
                        }
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

    private fun assertMinimumTouchHeight(node: AccessibilityNodeInfo) {
        val target = requireNotNull(clickableParent(node)) { "The record action must be independently clickable" }
        val bounds = Rect().apply { target.getBoundsInScreen(this) }
        val minimum = 48f * instrumentation.targetContext.resources.displayMetrics.density
        assertTrue("Record actions must retain at least a 48 dp touch height: $bounds", bounds.height() >= minimum - 1f)
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
