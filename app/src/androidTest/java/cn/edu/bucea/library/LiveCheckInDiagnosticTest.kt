package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryFunctionsRepository
import cn.edu.bucea.library.data.LibraryHtml
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.ocr.DdddOcr
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in check-in diagnostics for the saved active account. Prints the school's reservation
 * state flags on each page using GETs only, unless a single check-in is explicitly requested.
 *
 * adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveCheckInDiagnosticTest \
 *   -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
 * Optional: -e captureCheckInDiagnostic true keeps raw pages in app-private storage.
 * Optional: -e live_checkin_submit true sends one /user/checkIn for a RESERVE state.
 * Optional: -e live_extension_read true reads the offered 续约 end times twice.
 */
@RunWith(AndroidJUnit4::class)
class LiveCheckInDiagnosticTest {
    @Test fun summarizesCurrentReservationStateWithoutSeatActions() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live diagnostics require -e live_library true", arguments.getString("live_library") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context)
        val account = store.accounts().firstOrNull { it.id == store.activeAccountId }
        assumeTrue("A saved active account is required", account != null)
        val current = requireNotNull(account)
        assumeTrue("Explicit logout must be preserved", !store.isSignedOut(current.id))
        val capture = arguments.getString("captureCheckInDiagnostic") == "true"
        val directory = File(context.filesDir, "checkin-diagnostic").apply { if (capture) mkdirs() }
        DdddOcr(context).use { ocr ->
            val library = LibraryRepository(store, ocr)
            try {
                if (library.restore(current) == null) {
                    assumeTrue("No valid session and automatic login is disabled", store.autoLogin)
                    library.login(current) { /* Never log account/session data. */ }
                }
                assertNotNull("The saved account session must authenticate", library.restore(current))
                listOf("/", "/self", "/map", "/history?type=SEAT").forEach { endpoint ->
                    val response = library.request(current, endpoint)
                    val name = endpoint.removePrefix("/").substringBefore('?').ifBlank { "home" }
                    if (capture) File(directory, "$name.html").writeText(response)
                    val page = LibraryHtml.parse(response)
                    val scripts = page.select("script").joinToString("\n") { it.data() }
                    val userInfo = Regex("""var\s+userInfo\s*=\s*'([^']+)'""").find(scripts)?.groupValues?.get(1)
                    println("CHECKIN_DIAGNOSTIC endpoint=$endpoint userInfo=$userInfo " +
                        "handleButtons=${page.select(".handleBtns a[action]").map { it.attr("action") }} " +
                        "loginForm=${page.selectFirst("form[action='/auth/signIn']") != null}")
                }
                val state = LibraryFunctionsRepository(library).userState(current)
                println("CHECKIN_DIAGNOSTIC parsed status=${state.status} softMode=${state.softMode} " +
                    "actions=${state.actions} requiresLibraryWifi=${state.requiresLibraryWifi} message=${state.actionMessage}")
                if (arguments.getString("live_extension_read") == "true") {
                    // Same read the school's 续约 button performs before any end time is chosen.
                    repeat(2) { attempt ->
                        val response = library.request(current, "/user/getTimeExtend")
                        if (capture) File(directory, "extension-times-$attempt.json").writeText(response)
                        val data = runCatching { JSONObject(response) }.getOrNull()
                        println("CHECKIN_DIAGNOSTIC extension attempt=$attempt json=${data != null} " +
                            "status=${data?.optString("status")} message=${data?.optString("message")} " +
                            "keys=${data?.keys()?.asSequence()?.toList()} dataKeys=${data?.optJSONObject("data")?.keys()?.asSequence()?.toList()} " +
                            "endTimes=${data?.optJSONObject("data")?.optJSONArray("endTimes")}")
                    }
                }
                // A single explicit mutation, only for a reservation currently awaiting check-in.
                if (arguments.getString("live_checkin_submit") == "true" && state.status == "RESERVE") {
                    val response = library.request(current, "/user/checkIn")
                    if (capture) File(directory, "checkin-response.txt").writeText(response)
                    // Receipts can embed account digits; print only result fields.
                    val data = runCatching { JSONObject(response) }.getOrNull()
                    println("CHECKIN_DIAGNOSTIC submitted json=${data != null} status=${data?.optString("status")} " +
                        "message=${data?.optString("message")} reservationStatus=${data?.optJSONObject("data")?.optString("status")}")
                    val after = LibraryFunctionsRepository(library).userState(current)
                    println("CHECKIN_DIAGNOSTIC after status=${after.status} softMode=${after.softMode} actions=${after.actions}")
                }
            } finally { library.releaseSession(current.id) }
        }
    }
}
