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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in account inventory: GET only; private artifacts may contain tokens and personal data. */
@RunWith(AndroidJUnit4::class)
class LiveLibraryInventoryTest {
    @Test
    fun captureCurrentLibraryRecordsWithoutChangingAccountOrReservations() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live inventory is opt-in", arguments.getString("live_library") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context)
        val requestedId = arguments.getString("live_account_id") ?: store.activeAccountId
        val account = store.accounts().firstOrNull { it.id == requestedId }
        assumeTrue("A stored active account is required", account != null)
        val current = requireNotNull(account)
        assumeTrue("Explicit logout must be preserved", !store.isSignedOut(current.id))
        val label = arguments.getString("inventory_label", "current")
        require(label.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "Inventory label must be alphanumeric" }
        val directory = File(context.filesDir, "live-inventory/$label").apply { mkdirs() }
        DdddOcr(context).use { ocr ->
            val library = LibraryRepository(store, ocr)
            try {
                // No automatic login POST here: the root task owns all account mutations.
                assertTrue("Inventory requires the selected account's authenticated session", library.restore(current) != null)
                val endpoints = linkedMapOf("self" to "/self", "history" to "/history?type=SEAT",
                    "password" to "/user/password", "violations" to "/user/violations")
                val pages = endpoints.mapValues { (name, path) ->
                    val html = library.request(current, path)
                    val document = LibraryHtml.parse(html)
                    assertTrue("Inventory endpoint must remain authenticated", document.selectFirst("form[action='/auth/signIn']") == null)
                    document.selectFirst("#sysUsername")?.attr("value")?.let { identity ->
                        assertTrue("Inventory response identity must match selected account", identity == current.username)
                    }
                    val requiredMarker = when (name) {
                        "self" -> "#options_onDate, #reserveForm"
                        "history", "violations" -> ".myReserveList, #myReserveList"
                        "password" -> "form[action='/user/updateCredentials']"
                        else -> error("Unknown inventory page")
                    }
                    assertTrue("Inventory endpoint must return its business page, not an HTTP 200 error document",
                        document.selectFirst(requiredMarker) != null)
                    File(directory, "$name.html").writeText(html)
                    println("LIVE_INVENTORY phase=$label endpoint=${path.substringBefore('?')} saved")
                    document
                }
                val options = LibraryFunctionsRepository.parseOptions(pages.getValue("self"))
                val user = LibraryFunctionsRepository.parseUserState(pages.getValue("self"))
                assertTrue("Inventory must include actual server booking dates", options.dates.isNotEmpty())
                assertTrue("Inventory must include the actual server reservation state", user.status.isNotBlank())
                val history = LibraryFunctionsRepository.parseHistory(pages.getValue("history"))
                pages.getValue("history").select(".myReserveList a[href^=/view?]").take(3).forEachIndexed { index, link ->
                    val path = link.attr("href")
                    val html = library.request(current, path)
                    val detail = LibraryHtml.parse(html)
                    assertTrue("Inventory details must remain authenticated", detail.selectFirst("form[action='/auth/signIn']") == null)
                    assertTrue("Inventory details must expose a real school receipt", LibraryFunctionsRepository.parseReservationResult(detail).receipt != null)
                    File(directory, "view_$index.html").writeText(html)
                    println("LIVE_INVENTORY phase=$label endpoint=/view saved")
                }
                val violations = LibraryFunctionsRepository.parseHistory(pages.getValue("violations"))
                fun records(items: List<cn.edu.bucea.library.data.models.LibraryHistoryItem>) = JSONArray().apply {
                    items.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("status", it.status)
                        .put("details", JSONArray(it.details)).put("cancelPath", it.cancelPath)) }
                }
                val summary = JSONObject()
                    .put("dates", JSONArray().apply { options.dates.forEach { put(JSONObject().put("value", it.value).put("label", it.label)) } })
                    .put("userState", JSONObject().put("status", user.status).put("softMode", user.softMode)
                        .put("details", JSONArray(user.details)).put("actions", JSONArray(user.actions)))
                    .put("history", records(history.items)).put("historyNextOffset", history.nextOffset)
                    .put("violations", records(violations.items))
                File(directory, "summary.json").writeText(summary.toString(2))
                println("LIVE_INVENTORY phase=$label completed pages=${pages.size} historyRecords=${history.items.size} violationRecords=${violations.items.size}")
            } finally { library.releaseSession(current.id) }
        }
    }
}
