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
import cn.edu.bucea.library.data.models.ReservationMapTarget
import cn.edu.bucea.library.data.LibraryFunctionsRepository
import cn.edu.bucea.library.data.LibraryHtml
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.ui.reservationTimeline
import kotlinx.coroutines.runBlocking
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
            val expected = listOf(Triple(evening, "18:00 — 20:00", "图书馆2层东区A101号"),
                Triple(late, "20:30 — 22:00", "图书馆3层西区B202号"),
                Triple(tomorrow, "08:00 — 10:00", "图书馆1层南区C303号"))
            expected.forEach { (record, time, location) ->
                findWithScroll("Each scheduled reservation must show its real time") { findText(time) }
                findWithScroll("Each scheduled reservation must show its seat") { findText(record.details.first().substringAfter('：') + " 号座位") }
                findWithScroll("Each scheduled reservation must show its compact location") { findText(location) }
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
                hasText("签到需连接图书馆 Wi-Fi")
            }
            assertFalse("A location restriction must not be shown as a reservation status", hasText("位置受限"))
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

    @Test fun restrictedPendingCheckInIsDisabledUntilRefreshAndStaysReadableAtLargeTextSize() {
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val details = listOf("座位：A101", "位置：图书馆二层东区", "日期：$date", "时间：09:00 — 11:00")
        val record = HubRecord("fixture-wifi-checkin", "测试预约 A101", "已预约", details, canCancel = true)
        val checkIn = HubAction("checkIn", "签到", "合成签到操作")
        for (fontScale in listOf(1f, 1.6f)) {
            val refreshes = AtomicInteger()
            val submissions = AtomicInteger()
            val state = mutableStateOf(LibraryHubState(currentStatus = "LOC_RESTRICT", requiresLibraryWifi = true,
                currentDetails = details, currentReservation = record))
            withHub(state, fontScale = fontScale, onAction = { submissions.incrementAndGet() }, onRefresh = {
                refreshes.incrementAndGet()
                state.value = state.value.copy(currentStatus = "RESERVE", requiresLibraryWifi = false, actions = listOf(checkIn))
            }) {
                val disabled = findWithScroll("A pending reservation must retain its check-in button outside library Wi-Fi") { findText("签到") }
                val target = clickableParent(disabled) ?: disabled
                assertFalse("Check-in must be disabled outside library Wi-Fi", target.isEnabled)
                assertTrue("Accessibility must explain why check-in is disabled",
                    target.stateDescription?.contains("需连接图书馆 Wi-Fi") == true)
                val buttonBounds = Rect().apply { target.getBoundsInScreen(this) }
                val minimum = 48f * instrumentation.targetContext.resources.displayMetrics.density
                assertTrue("Disabled check-in must retain its normal button height", buttonBounds.height() >= minimum - 1f)
                assertFalse("The removed status badge must not remain", hasText("位置受限"))
                assertFalse("The removed status badge must not be replaced by Wi-Fi status text", hasText("需连接馆内 Wi-Fi"))
                tap(target)
                instrumentation.waitForIdleSync()
                assertFalse("Tapping disabled check-in must not open confirmation", hasText("确认签到"))
                assertEquals("Disabled check-in must not submit any action", 0, submissions.get())
                val title = findWithScroll("The disabled button must have prominent Wi-Fi guidance") { findText("签到需连接图书馆 Wi-Fi") }
                val titleBounds = Rect().apply { title.getBoundsInScreen(this) }
                val windowBounds = Rect().apply { requireNotNull(automation.rootInActiveWindow).getBoundsInScreen(this) }
                assertTrue("The complete Wi-Fi title must remain within the screen at $fontScale font scale", windowBounds.contains(titleBounds))
                val settings = findWithScroll("Wi-Fi settings must remain fully available at $fontScale font scale") { findText("打开 Wi-Fi 设置") }
                val settingsBounds = Rect().apply { requireNotNull(clickableParent(settings)).getBoundsInScreen(this) }
                val refresh = findWithScroll("The reconnect refresh must remain fully available at $fontScale font scale") { findText("已连接，刷新状态") }
                val refreshBounds = Rect().apply { requireNotNull(clickableParent(refresh)).getBoundsInScreen(this) }
                if (fontScale > 1.2f) {
                    assertTrue("Large text must stack the two Wi-Fi actions without crowding", refreshBounds.top >= settingsBounds.bottom)
                } else {
                    assertEquals("Normal text must keep the Wi-Fi actions on one row", settingsBounds.top, refreshBounds.top)
                }
                if (InstrumentationRegistry.getArguments().getString("captureWifiCheckInScreenshots") == "true") {
                    captureCurrentReservation("wifi-${if (fontScale > 1.2f) "large" else "normal"}")
                }
                click(findWithScroll("Reconnecting must offer a refresh") { findText("已连接，刷新状态") })
                waitFor("Refreshing after connection must enable the same check-in button") {
                    findText("签到")?.let { (clickableParent(it) ?: it).isEnabled } == true
                }
                assertEquals("Reconnect must refresh once", 1, refreshes.get())
                assertFalse("A permitted check-in must remove the Wi-Fi guidance", hasText("签到需连接图书馆 Wi-Fi"))
                if (InstrumentationRegistry.getArguments().getString("captureWifiCheckInScreenshots") == "true") {
                    captureCurrentReservation("connected-${if (fontScale > 1.2f) "large" else "normal"}")
                }
                click(requireNode("Connected check-in must remain available") { findText("签到") })
                waitFor("Connected check-in must retain its confirmation") { hasText("确认签到") }
                assertEquals("Opening check-in confirmation must not submit", 0, submissions.get())
                click(requireNode("Connected check-in must require an explicit confirmation") { findControl("确认签到") })
                waitFor("Confirmed synthetic check-in must submit exactly once") { submissions.get() == 1 }
            }
        }
    }

    @Test fun wifiRestrictionDismissesAnOpenCheckInConfirmationAndDisablesItsButton() {
        val submissions = AtomicInteger()
        val state = mutableStateOf(LibraryHubState(currentStatus = "RESERVE",
            actions = listOf(HubAction("checkIn", "签到", "合成签到操作"))))
        withHub(state, onAction = { submissions.incrementAndGet() }) {
            click(findWithScroll("An authorized reservation must offer check-in") { findText("签到") })
            waitFor("Authorized check-in must open confirmation") { hasText("确认签到") }
            instrumentation.runOnMainSync { state.value = state.value.copy(requiresLibraryWifi = true) }
            waitFor("A new Wi-Fi restriction must close an obsolete confirmation") { !hasText("确认签到") }
            val disabled = findWithScroll("A refused check-in must remain visible") { findText("签到") }
            assertFalse("An explicit Wi-Fi refusal must disable a previously authorized check-in", (clickableParent(disabled) ?: disabled).isEnabled)
            tap(disabled)
            instrumentation.waitForIdleSync()
            assertFalse("A disabled check-in must not reopen confirmation", hasText("确认签到"))
            assertEquals("Losing Wi-Fi authorization must not submit check-in", 0, submissions.get())
        }
    }

    @Test fun restrictedInUseOrUnknownReservationsDoNotOfferAnotherCheckIn() {
        val state = mutableStateOf(LibraryHubState(currentStatus = "LOC_RESTRICT", requiresLibraryWifi = true))
        withHub(state) {
            for (status in listOf("使用中", "暂离", "结束使用", "已取消", "")) {
                instrumentation.runOnMainSync {
                    state.value = state.value.copy(currentReservation = HubRecord("fixture-not-pending", "测试预约", status, emptyList()))
                }
                instrumentation.waitForIdleSync()
                assertFalse("A $status reservation must not imply a pending check-in", hasText("签到"))
            }
        }
    }

    @Test fun actionLocationRefusalShowsWifiCardAndFreshAuthorizedStatesRemoveIt() {
        val refusal = runBlocking {
            LibraryFunctionsRepository(request = { _, endpoint ->
                when (endpoint) {
                    "/self" -> """<script>var userInfo='{"currentReservationStatus":"RESERVE","softMode":false}';</script>"""
                    "/user/checkIn" -> """{"status":"fail","message":"位置限制","data":{"status":"LOC_RESTRICT"}}"""
                    else -> error("Unexpected synthetic request")
                }
            }, post = { _, _, _ -> error("Unexpected mutation") })
                .performAction(SavedAccount("fixture", "209999990001", "synthetic", "测试"), "checkIn")
        }
        assertTrue(refusal.requiresLibraryWifi)
        // The action's explicit location refusal still applies when /self repeats RESERVE.
        val state = mutableStateOf(LibraryHubState(currentStatus = "RESERVE",
            requiresLibraryWifi = refusal.requiresLibraryWifi, actionMessage = refusal.message))
        withHub(state) {
            findWithScroll("An action location refusal must show reconnect guidance") { findText("签到需连接图书馆 Wi-Fi") }
            for (status in listOf("RESERVE", "CHECK_IN", "IN_USE", "USING", "CHECKED_IN", "AWAY", "COMPLETE")) {
                val user = LibraryFunctionsRepository.parseUserState(LibraryHtml.parse(
                    """<script>var userInfo='{"currentReservationStatus":"$status","softMode":false}';</script>"""))
                instrumentation.runOnMainSync {
                    state.value = LibraryHubState(currentStatus = user.status, requiresLibraryWifi = user.requiresLibraryWifi,
                        actionMessage = user.actionMessage, actions = user.actions.map { action ->
                            HubAction(action, when (action) {
                                "checkIn" -> "签到"
                                "extend" -> "续约"
                                "leave" -> "暂离"
                                "resume" -> "返回座位"
                                else -> "结束使用"
                            }, "合成操作")
                        })
                }
                findWithScroll("$status must render its current reservation", backwards = true) { findText("当前预约") }
                findWithScroll("$status must keep the server-authorized action visible") { findText(state.value.actions.first().label) }
                assertWifiGuidanceAbsent(status)
            }
        }
    }

    @Test fun inUseCurrentCardKeepsTheEndUseActionVisible() {
        val actions = AtomicInteger()
        val state = mutableStateOf(LibraryHubState(
            currentStatus = "IN_USE",
            currentDetails = listOf("座位：A101", "位置：图书馆二层东区"),
            actions = listOf(HubAction("stopUsing", "结束使用", "结束当前座位使用。", destructive = true)),
        ))
        withHub(state, onAction = { action ->
            assertEquals("stopUsing", action.id)
            actions.incrementAndGet()
        }) {
            findWithScroll("The in-use current card must display the end-use action") { findText("结束使用") }
            click(findWithScroll("The end-use action must be tappable") { findText("结束使用") })
            waitFor("The end-use action must require confirmation") { hasText("确认结束使用") }
            click(requireNode("The confirmation must remain dismissible") { findText("取消") })
            assertEquals("Dismissing the end-use confirmation must not submit the action", 0, actions.get())
        }
    }

    @Test fun awayCurrentCardPreservesSeatInformationAndItsLocatorWithoutSubmittingAnAction() {
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val location = "图书馆2层二层内环区，座位号007D"
        val state = mutableStateOf(LibraryHubState(
            currentStatus = "AWAY",
            currentDetails = listOf("座位：007D", "位置：$location", "日期：$date", "时间：09:00—11:00"),
            actions = listOf(HubAction("resume", "返回座位", "合成返回操作")),
        ))
        val located = AtomicReference<ReservationMapTarget?>()
        val submissions = AtomicInteger()
        withHub(state, onAction = { submissions.incrementAndGet() }, onLocateSeat = { located.set(it) }) {
            findWithScroll("A temporarily away reservation must retain its seat number") { findText("007D") }
            findWithScroll("A temporarily away reservation must retain its compact location") { findText("图书馆2层内环区") }
            findWithScroll("A temporarily away reservation must retain its scheduled interval") { findText("今天 · 09:00 — 11:00") }
            findWithScroll("The current card must identify a temporarily away reservation") { findText("暂离中") }
            findWithScroll("Returning to the seat must remain available") { findText("返回座位") }
            val locator = findWithScroll("A temporarily away reservation must still offer seat location") { findText("查看座位位置") }
            assertMinimumTouchHeight(locator)
            click(locator)
            waitFor("The away-state locator must forward the reservation's exact date, seat and location") { located.get() != null }
            assertEquals(ReservationMapTarget(date.toString(), "007D", location), located.get())
            assertEquals("Locating a temporarily away seat must not resume or submit a seat action", 0, submissions.get())
        }
    }

    @Test fun pendingCheckInCurrentReservationCancellationRequiresConfirmationAndServerPermission() {
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val record = HubRecord("fixture-current-cancellable", "当前预约 A101", "待签到",
            listOf("座位：A101", "位置：图书馆二层东区", "日期：$date", "时间：09:00—11:00"),
            canCancel = true, schedule = ReservationSchedule(date.toString(), "09:00", "11:00"))
        val state = mutableStateOf(LibraryHubState(
            currentStatus = "RESERVE", currentDetails = record.details, currentReservation = record,
            actions = listOf(HubAction("checkIn", "签到", "合成签到操作")),
        ))
        val cancelled = AtomicReference<HubRecord?>()
        val cancellations = AtomicInteger()
        val submissions = AtomicInteger()
        withHub(state, onAction = { submissions.incrementAndGet() }, onCancelRecord = {
            cancelled.set(it)
            cancellations.incrementAndGet()
        }) {
            val cancel = findWithScroll("An unsigned current reservation with server permission must offer cancellation") { findText("取消预约") }
            assertMinimumTouchHeight(cancel)
            click(cancel)
            waitFor("Current reservation cancellation must open the existing confirmation") { hasText("取消这次预约？") }
            assertEquals("Opening confirmation must not cancel the current reservation", 0, cancellations.get())
            click(requireNode("The current reservation must be kept without submitting") { findText("保留预约") })
            waitFor("Keeping the current reservation must dismiss confirmation") { !hasText("取消这次预约？") }
            assertEquals("Keeping the current reservation must not cancel it", 0, cancellations.get())

            click(findWithScroll("The preserved current reservation must remain cancellable") { findText("取消预约") })
            click(requireNode("Cancelling the current reservation must require explicit confirmation") { findText("确认取消预约") })
            waitFor("Confirming must submit the current record exactly once and dismiss confirmation") {
                cancellations.get() == 1 && !hasText("取消这次预约？")
            }
            assertEquals(record, cancelled.get())
            assertEquals("Cancelling the current reservation must not invoke check-in", 0, submissions.get())

            instrumentation.runOnMainSync {
                state.value = state.value.copy(currentReservation = record.copy(canCancel = false))
            }
            findWithScroll("The current card must remain visible without cancellation permission", backwards = true) { findText("当前预约") }
            waitFor("An unsigned current reservation without server permission must hide cancellation") { !hasText("取消预约") }

            instrumentation.runOnMainSync {
                state.value = state.value.copy(currentStatus = "CHECK_IN", currentReservation = null,
                    actions = listOf(HubAction("leave", "暂离", "合成暂离操作")))
            }
            findWithScroll("A checked-in current reservation must show its updated status", backwards = true) { findText("使用中") }
            findWithScroll("A checked-in current reservation must retain its seat", backwards = true) { findText("A101") }
            assertFalse("A checked-in current reservation must hide cancellation", hasText("取消预约"))
            assertEquals("State changes must not resubmit cancellation", 1, cancellations.get())
        }
    }

    @Test fun currentUpcomingAndHistoricalReservationLocatorsKeepTheirExactTargetsWithoutOpeningDetailsOrSubmittingActions() {
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val currentLocation = "图书馆2层二层内环区，座位号007D"
        val upcomingLocation = "图书馆三层西区"
        val upcoming = HubRecord("fixture-locator-upcoming", "次日预约 A101", "已预约",
            listOf("座位：A101", "位置：$upcomingLocation"), canCancel = true,
            schedule = ReservationSchedule(today.plusDays(1).toString(), "09:00", "11:00"))
        val ended = HubRecord("fixture-locator-ended", "历史预约 B202", "结束使用",
            listOf("座位：B202", "位置：图书馆二层东区"),
            schedule = ReservationSchedule(today.minusDays(1).toString(), "13:00", "15:00"))
        val cancelledLocation = "图书馆四层期刊阅"
        val cancelled = HubRecord("fixture-locator-cancelled", "已取消预约 C303", "已取消",
            listOf("座位：C303", "位置：$cancelledLocation"),
            schedule = ReservationSchedule(today.minusDays(2).toString(), "10:00", "12:00"))
        val state = mutableStateOf(LibraryHubState(
            currentStatus = "CHECK_IN",
            currentDetails = listOf("座位：007D", "位置：$currentLocation", "日期：$today", "时间：17:58—19:00"),
            actions = listOf(HubAction("stopUsing", "结束使用", "合成结束操作", destructive = true)),
            records = listOf(cancelled, ended, upcoming),
        ))
        val locations = AtomicReference<List<ReservationMapTarget>>(emptyList())
        val submissions = AtomicInteger()
        val cancellations = AtomicInteger()
        val openedDetails = AtomicInteger()
        withHub(state, onAction = { submissions.incrementAndGet() },
            onCancelRecord = { cancellations.incrementAndGet() }, onOpenRecord = { openedDetails.incrementAndGet() },
            onLocateSeat = { target -> locations.set(locations.get() + target) }) {
            val currentLocator = findWithScroll("The current reservation must offer its own seat location") { findText("查看座位位置") }
            assertMinimumTouchHeight(currentLocator)
            click(currentLocator)
            waitFor("The current locator must forward the exact absolute booking date and seat") { locations.get().size == 1 }
            assertEquals(ReservationMapTarget(today.toString(), "007D", currentLocation), locations.get().single())

            findWithScroll("The future record must remain in the upcoming reservation group") { findText("接下来的预约") }
            val upcomingLocator = findWithScroll("The upcoming seat must have an independently labeled locator") {
                findDescription("查看座位位置 A101")
            }
            assertMinimumTouchHeight(upcomingLocator)
            click(upcomingLocator)
            waitFor("The upcoming locator must use its own date rather than the current reservation date") { locations.get().size == 2 }
            assertEquals(ReservationMapTarget(today.plusDays(1).toString(), "A101", upcomingLocation), locations.get()[1])

            findWithScroll("The ended reservation must remain available as history") { findText("历史预约") }
            val endedLocator = findWithScroll("An ended historical reservation must retain its seat locator") {
                findDescription("查看座位位置 B202")
            }
            assertMinimumTouchHeight(endedLocator)
            click(endedLocator)
            waitFor("The ended seat locator must retain the original historical booking date") { locations.get().size == 3 }
            assertEquals(ReservationMapTarget(today.minusDays(1).toString(), "B202", "图书馆二层东区"), locations.get()[2])

            val cancelledLocator = findWithScroll("A cancelled historical reservation must retain its seat locator") {
                findDescription("查看座位位置 C303")
            }
            assertMinimumTouchHeight(cancelledLocator)
            click(cancelledLocator)
            waitFor("The cancelled seat locator must retain the original historical booking date") { locations.get().size == 4 }
            assertEquals(ReservationMapTarget(today.minusDays(2).toString(), "C303", cancelledLocation), locations.get()[3])
            assertEquals("Locating a seat must not submit a reservation action", 0, submissions.get())
            assertEquals("Locating a seat must not cancel a reservation", 0, cancellations.get())
            assertEquals("Locating a seat must not open the reservation detail action", 0, openedDetails.get())
            assertNull("Locator callbacks must leave record details closed", state.value.selectedRecord)
        }
    }

    @Test fun sparseHistoricalReservationLocatorForwardsItsRecordWithoutOpeningDetailsOrSubmittingActions() {
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(3)
        val record = HubRecord("fixture-locator-sparse", "历史预约 D404", "结束使用", listOf("座位：D404"),
            schedule = ReservationSchedule(date.toString(), "08:00", "10:00"))
        val state = mutableStateOf(LibraryHubState(currentStatus = "NO_RESERVATION", records = listOf(record)))
        val locatedRecord = AtomicReference<HubRecord?>()
        val submissions = AtomicInteger()
        val cancellations = AtomicInteger()
        val openedDetails = AtomicInteger()
        withHub(state, onAction = { submissions.incrementAndGet() },
            onCancelRecord = { cancellations.incrementAndGet() }, onOpenRecord = { openedDetails.incrementAndGet() },
            onLocateRecord = { locatedRecord.set(it) }) {
            val locator = findWithScroll("A sparse record must offer its locator before missing details are read") {
                findDescription("查看座位位置 D404")
            }
            assertMinimumTouchHeight(locator)
            click(locator)
            waitFor("The sparse locator must forward the same historical record to the detail resolver") { locatedRecord.get() != null }
            assertEquals(record, locatedRecord.get())
            assertEquals(date.toString(), locatedRecord.get()?.schedule?.date)
            assertEquals("Locating a sparse record must not submit a reservation action", 0, submissions.get())
            assertEquals("Locating a sparse record must not cancel a reservation", 0, cancellations.get())
            assertEquals("The locator resolver must stay separate from opening record details", 0, openedDetails.get())
            assertNull("Resolving a sparse locator must leave record details closed", state.value.selectedRecord)
        }
    }

    @Test fun currentReservationShowsItsDetailsCompactlyAndPreservesActionsAtLargeTextSize() {
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val state = mutableStateOf(LibraryHubState(
            currentStatus = "IN_USE",
            currentDetails = listOf("座位：007D", "位置：图书馆2层二层内环区，座位号007D",
                "日期：$date", "时间：17:58—19:00", "凭证号：fixture-current-receipt"),
            actions = listOf(HubAction("extend", "续约", "合成续约操作"),
                HubAction("leave", "暂离", "合成暂离操作"),
                HubAction("stopUsing", "结束使用", "合成结束操作", destructive = true)),
        ))
        val submissions = AtomicInteger()
        for (fontScale in listOf(1f, 1.6f)) {
            val locations = AtomicReference<List<ReservationMapTarget>>(emptyList())
            withHub(state, fontScale = fontScale, onAction = { submissions.incrementAndGet() },
                onLocateSeat = { target -> locations.set(locations.get() + target) }) {
                findWithScroll("The current card must show the actual seat") { findText("007D") }
                findWithScroll("The location must omit the repeated floor and seat suffix") { findText("图书馆2层内环区") }
                findWithScroll("The actual reservation date and interval must share one compact line") { findText("今天 · 17:58 — 19:00") }
                findWithScroll("An in-use reservation must show its concise status") { findText("使用中") }
                assertFalse("The summary must omit the repeated seat suffix", hasText("图书馆2层二层内环区，座位号007D"))
                assertFalse("The summary must hide receipt details", nodes().any { it.text?.contains("fixture-current-receipt") == true })
                assertFalse("The summary must not need expansion to reveal its reservation information", hasText("查看完整预约信息"))
                assertFalse("The summary must not render an expanded detail list", hasText("收起预约信息"))

                val actionBounds = state.value.actions.map { action ->
                    val label = findWithScroll("The current card must preserve the ${action.label} action") { findText(action.label) }
                    assertMinimumTouchHeight(label)
                    val target = requireNotNull(clickableParent(label))
                    assertTrue("The ${action.label} action must remain enabled", target.isEnabled)
                    val labelBounds = Rect().apply { label.getBoundsInScreen(this) }
                    val targetBounds = Rect().apply { target.getBoundsInScreen(this) }
                    assertTrue("The complete ${action.label} label must stay inside its button: label=$labelBounds target=$targetBounds",
                        targetBounds.contains(labelBounds))
                    if (labelBounds != targetBounds) {
                        val inset = 4f * instrumentation.targetContext.resources.displayMetrics.density
                        assertTrue("The ${action.label} label must retain horizontal padding at $fontScale text size",
                            labelBounds.left - targetBounds.left >= inset - 1f && targetBounds.right - labelBounds.right >= inset - 1f)
                    }
                    targetBounds
                }
                for (first in actionBounds.indices) for (second in first + 1 until actionBounds.size) {
                    assertFalse("Current reservation actions must have separate touch targets", Rect.intersects(actionBounds[first], actionBounds[second]))
                }
                val locator = findWithScroll("The current seat location must remain available at $fontScale text size") { findText("查看座位位置") }
                assertMinimumTouchHeight(locator)
                val locatorTarget = requireNotNull(clickableParent(locator))
                val locatorBounds = Rect().apply { locatorTarget.getBoundsInScreen(this) }
                val locatorLabelBounds = Rect().apply { locator.getBoundsInScreen(this) }
                assertTrue("The complete location label must fit its enabled target at $fontScale text size",
                    locatorTarget.isEnabled && locatorBounds.contains(locatorLabelBounds))
                actionBounds.forEach { action ->
                    assertFalse("The location button must not overlap a seat operation", Rect.intersects(locatorBounds, action))
                }
                click(locator)
                waitFor("The location button must invoke only the locator callback at $fontScale text size") { locations.get().size == 1 }
                assertEquals(ReservationMapTarget(date.toString(), "007D", "图书馆2层二层内环区，座位号007D"), locations.get().single())
                if (fontScale > 1.2f) {
                    assertTrue("Large text must move the third action onto a second row",
                        actionBounds[2].top >= maxOf(actionBounds[0].bottom, actionBounds[1].bottom))
                } else {
                    val card = findWithScroll("The current card must expose its presentation bounds", backwards = true) { findDescription("当前预约卡片") }
                    val bounds = Rect().apply { card.getBoundsInScreen(this) }
                    val maximum = 300f * instrumentation.targetContext.resources.displayMetrics.density
                    assertTrue("The normal current card must remain compact: $bounds", bounds.height() in 1..maximum.toInt())
                }
                assertEquals("Presentation checks must never submit a seat operation", 0, submissions.get())
                if (InstrumentationRegistry.getArguments().getString("captureCurrentReservationScreenshots") == "true") {
                    captureCurrentReservation(if (fontScale > 1.2f) "large" else "normal")
                }
            }
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
            findWithScroll("The compact card must preserve its compact location") { findText("图书馆2层东区A101号") }
            val detailsLabel = findWithScroll("The compact details action must remain visible") { findText("查看详情") }
            val detailsTarget = requireNotNull(clickableParent(detailsLabel))
            val detailsBounds = Rect().apply { detailsLabel.getBoundsInScreen(this) }
            val targetBounds = Rect().apply { detailsTarget.getBoundsInScreen(this) }
            assertMinimumTouchHeight(detailsLabel)
            // TextButton content must stay inside its rounded clip. A zero-padded
            // button previously cut the first glyph from both card action lines.
            val minimumInset = 4f * instrumentation.targetContext.resources.displayMetrics.density
            if (detailsBounds != targetBounds) {
                assertTrue("The details label must have a safe inset from the action edge: label=$detailsBounds target=$targetBounds",
                    detailsBounds.left - targetBounds.left >= minimumInset - 1f)
            }
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
                state.value = state.value.copy(records = listOf(ended.copy(status = "STOPPED")))
            }
            findWithScroll("The ended-use server code must render its complete Chinese label", backwards = true) { findText("结束使用") }
            assertFalse("Ended-use records must not expose cancellation", hasText("取消预约"))

            instrumentation.runOnMainSync {
                state.value = state.value.copy(records = listOf(ended.copy(status = "已完成")))
            }
            findWithScroll("The school's completed history label must remain distinct", backwards = true) { findText("已完成") }
            assertFalse("Completed records must not expose cancellation", hasText("取消预约"))

            instrumentation.runOnMainSync {
                state.value = state.value.copy(records = listOf(ended.copy(status = "", details = listOf(
                    "状态：结束使用", "座位：A101", "位置：图书馆二层东区",
                ))))
            }
            findWithScroll("An explicit ended-use detail must appear on the collapsed card", backwards = true) { findText("结束使用") }
            assertFalse("Ended-use records must not expose cancellation", hasText("取消预约"))

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
        onLocateSeat: ((ReservationMapTarget) -> Unit)? = null,
        onLocateRecord: ((HubRecord) -> Unit)? = null,
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
                                onLocateSeat = onLocateSeat,
                                onLocateRecord = onLocateRecord,
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
    private fun captureCurrentReservation(size: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val screenshot = requireNotNull(automation.takeScreenshot()) { "The synthetic current reservation screenshot must be available" }
        try {
            File(instrumentation.targetContext.filesDir, "current-reservation-$size.png").outputStream().use {
                assertTrue("The synthetic current reservation screenshot must be written", screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
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

    private fun assertWifiGuidanceAbsent(status: String) {
        // Scan to the footer so a lazy item below the first viewport cannot escape this check.
        for (attempt in 0 until 10) {
            for (text in listOf("签到需连接图书馆 Wi-Fi", "打开 Wi-Fi 设置", "已连接，刷新状态")) {
                assertFalse("$status must hide the reconnect card: $text", hasText(text))
            }
            if (hasText("暂无预约记录")) return
            nodes().firstOrNull { it.isScrollable && it.isEnabled }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        fail("$status fixture footer must be visible before checking card absence")
    }

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
        tap(node)
    }

    /** Physical taps also verify that disabled Compose controls reject touch input. */
    private fun tap(node: AccessibilityNodeInfo) {
        assertEquals("Only synthetic application content may receive taps", appPackage, node.packageName?.toString())
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
