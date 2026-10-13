package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryFunctionsRepository
import cn.edu.bucea.library.data.LibraryHtml
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.data.NativeVerificationRepository
import cn.edu.bucea.library.data.models.SeatQuery
import cn.edu.bucea.library.ocr.DdddClickSolver
import cn.edu.bucea.library.ocr.DdddOcr
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnoses same-day "start now" choices using GETs only; never submits a reservation. */
@RunWith(AndroidJUnit4::class)
class LiveCurrentDayReadOnlyTest {
    @Test
    fun captureCurrentDayStartNowValuesAndAvailableEndTimes() = runBlocking {
        assumeTrue("Current-day checks are opt-in", InstrumentationRegistry.getArguments().getString("live_library") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context)
        val account = store.accounts().firstOrNull { it.id == store.activeAccountId }
        assumeTrue("A stored active account is required", account != null)
        val current = requireNotNull(account)
        assumeTrue("Preserve explicit sign-out", !store.isSignedOut(current.id))
        val directory = File(context.filesDir, "live-current-day").apply { mkdirs() }
        DdddOcr(context).use { ocr ->
            val library = LibraryRepository(store, ocr)
            try {
                assertTrue("Log in through the app before same-day diagnostics", library.restore(current) != null)
                val functions = LibraryFunctionsRepository(library)
                val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
                val options = functions.options(current)
                assertTrue("School must offer the current date for this diagnostic", options.dates.any { it.value == today })
                var page = functions.search(current, SeatQuery(today))
                var verificationUsed = false
                DdddClickSolver(context).use { solver ->
                    suspend fun verifyOnce(userId: String) {
                        assertFalse("A second verification stops the bounded diagnostic", verificationUsed)
                        verificationUsed = true
                        val verification = NativeVerificationRepository(library)
                        verification.automaticallyVerify(current, verification.context(current, userId), solver::solve)
                    }
                    if (page.verificationRequired) {
                        verifyOnce(page.verificationUserId)
                        page = functions.search(current, SeatQuery(today))
                    }
                    assertFalse("Current-day search must complete", page.verificationRequired)
                    val seat = page.seats.firstOrNull { it.available }
                    assumeTrue("No current-day free seat is available", seat != null)
                    val selectedSeat = requireNotNull(seat)
                    val startPath = "/freeBook/ajaxGetTime?id=${selectedSeat.id}&date=$today"
                    var rawStarts = library.request(current, startPath)
                    var starts = LibraryFunctionsRepository.parseTimes(rawStarts)
                    if (starts.verificationRequired) {
                        verifyOnce(starts.verificationUserId)
                        rawStarts = library.request(current, startPath)
                        starts = LibraryFunctionsRepository.parseTimes(rawStarts)
                    }
                    File(directory, "start.html").writeText(rawStarts)
                    assertFalse("Start-time request must complete", starts.verificationRequired)
                    // Inspect raw anchors so a new server sentinel is not hidden by client filtering.
                    val choices = LibraryHtml.parse(rawStarts).select("a[time]").map {
                        it.attr("time") to it.text()
                    }
                    choices.forEach { (value, label) -> println("LIVE_CURRENT_DAY startLabel=$label startValue=$value") }
                    val immediate = choices.firstOrNull { (_, label) -> listOf("现在", "立即", "当前").any(label::contains) }
                    val selected = immediate ?: choices.firstOrNull()
                    if (selected != null) {
                        val (value, label) = selected
                        val encoded = java.net.URLEncoder.encode(value, "UTF-8")
                        var rawEnds = library.request(current, "/freeBook/ajaxGetEndTime?seat=${selectedSeat.id}&date=$today&start=$encoded")
                        var ends = LibraryFunctionsRepository.parseTimes(rawEnds)
                        if (ends.verificationRequired) {
                            verifyOnce(ends.verificationUserId)
                            rawEnds = library.request(current, "/freeBook/ajaxGetEndTime?seat=${selectedSeat.id}&date=$today&start=$encoded")
                            ends = LibraryFunctionsRepository.parseTimes(rawEnds)
                        }
                        File(directory, "end.html").writeText(rawEnds)
                        assertFalse("End-time request must complete", ends.verificationRequired)
                        println("LIVE_CURRENT_DAY selectedStartLabel=$label selectedStartValue=$value endCount=${ends.options.size}")
                    }
                    val state = functions.userState(current)
                    val history = functions.history(current)
                    val active = history.items.count { it.status in setOf("预约", "已预约", "待签到", "已签到", "使用中", "暂离") }
                    val summary = JSONObject().put("onDate", today).put("startNowPresent", immediate != null)
                        .put("startChoices", JSONArray().apply { choices.forEach { (value, label) -> put(JSONObject().put("value", value).put("label", label)) } })
                        .put("currentReservationStatus", state.status).put("activeHistoryRecords", active)
                    File(directory, "summary.json").writeText(summary.toString(2))
                    println("LIVE_CURRENT_DAY completed currentStatus=${state.status} activeHistoryRecords=$active startNowPresent=${immediate != null}")
                }
            } finally { library.releaseSession(current.id) }
        }
    }
}
