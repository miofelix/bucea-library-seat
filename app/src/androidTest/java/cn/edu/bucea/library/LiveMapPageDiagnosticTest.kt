package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
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

/** Opt-in two authenticated GETs; prints presence/counts only, never a page, identity or token value. */
@RunWith(AndroidJUnit4::class)
class LiveMapPageDiagnosticTest {
    @Test fun summarizesMapAndVerificationContextWithoutSensitiveValues() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Live diagnostics require -e live_library true",
            InstrumentationRegistry.getArguments().getString("live_library") == "true")
        val store = AccountStore(instrumentation.targetContext)
        val account = store.accounts().firstOrNull { it.id == store.activeAccountId }
        assumeTrue("A saved active account is required", account != null)
        val selected = requireNotNull(account)
        assumeTrue("Explicit logout must be preserved", !store.isSignedOut(selected.id))
        DdddOcr(instrumentation.targetContext).use { ocr ->
            val library = LibraryRepository(store, ocr)
            try {
                assertNotNull("The saved account session must authenticate", library.restore(selected))
                listOf("/map", "/self").forEach { endpoint ->
                    val response = library.request(selected, endpoint)
                    if (InstrumentationRegistry.getArguments().getString("captureMapPageDiagnostic") == "true") {
                        // Explicit diagnostic opt-in only. Sensitive source stays in app-private storage.
                        val directory = File(instrumentation.targetContext.filesDir, "map-page-diagnostic").apply { mkdirs() }
                        File(directory, endpoint.removePrefix("/") + ".html").writeText(response)
                    }
                    val page = LibraryHtml.parse(response)
                    if (endpoint == "/self" && InstrumentationRegistry.getArguments()
                            .getString("captureMapPageDiagnostic") == "true") {
                        val source = page.selectFirst("script[src*='/assets/chCaptcha/']")?.attr("src")
                        if (source != null && source.startsWith("/assets/chCaptcha/")) {
                            java.io.File(instrumentation.targetContext.filesDir,
                                "map-page-diagnostic/captcha.js").writeText(library.request(selected, source))
                            val captchaPage = library.request(selected, "/simpleCaptcha/chCaptcha")
                            java.io.File(instrumentation.targetContext.filesDir,
                                "map-page-diagnostic/captcha.html").writeText(captchaPage)
                            val transportSource = LibraryHtml.parse(captchaPage)
                                .selectFirst("script[src*='/assets/chCaptcha/kaptcha-']")?.attr("src")
                            if (transportSource != null && transportSource.startsWith("/assets/chCaptcha/")) {
                                java.io.File(instrumentation.targetContext.filesDir,
                                    "map-page-diagnostic/captcha-transport.js")
                                    .writeText(library.request(selected, transportSource))
                            }
                        }
                    }
                    val data = if (response.trimStart().startsWith("{"))
                        runCatching { JSONObject(response) }.getOrNull() else null
                    val scripts = page.select("script").map { it.data().trim() }
                    val direct = Regex("""^clickVerifyCode\s*\([\s\S]*?\)\s*;?\s*$""")
                    println("MAP_PAGE_DIAGNOSTIC endpoint=$endpoint " +
                        "identityPresent=${page.selectFirst("#sysUsername") != null} " +
                        "identityMatches=${page.selectFirst("#sysUsername")?.attr("value") == selected.username} " +
                        "tokenPresent=${page.selectFirst("#sysToken")?.attr("value")?.isNotBlank() == true} " +
                        "loginForm=${page.selectFirst("form[action='/auth/signIn']") != null} " +
                        "dates=${page.select("#options_onDate a[value]").size} " +
                        "buildings=${page.select("#options_building a[value]").size} " +
                        "floors=${page.select("#options_floor a[value]").size} " +
                        "jsonLimit=${data?.optBoolean("limit") == true} " +
                        "visibleRateLimit=${page.body().text().contains("您操作的频率太快")} " +
                        "scripts=${scripts.size} " +
                        "markerScripts=${scripts.count { it.contains("clickVerifyCode(") }} " +
                        "definitionScripts=${scripts.count { Regex("""function\s+clickVerifyCode\s*\(""").containsMatchIn(it) }} " +
                        "standaloneCallScripts=${scripts.count { direct.matches(it) }} " +
                        "embeddedWiringScripts=${scripts.count { it.contains("clickVerifyCode(") && !direct.matches(it) }}")
                }
            } finally { library.releaseSession(selected.id) }
        }
    }
}
