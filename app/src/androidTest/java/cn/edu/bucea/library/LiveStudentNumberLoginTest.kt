package cn.edu.bucea.library

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.AccountStore
import cn.edu.bucea.library.data.LibraryFunctionsRepository
import cn.edu.bucea.library.data.LibraryHtml
import cn.edu.bucea.library.data.LibraryRepository
import cn.edu.bucea.library.ocr.DdddOcr
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in campus login using runtime credentials in an isolated, temporary encrypted vault. */
@RunWith(AndroidJUnit4::class)
class LiveStudentNumberLoginTest {
    @Test
    fun logsInWithTheExactStudentNumberAndReadsHistory() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live login is opt-in", arguments.getString("live_student_login") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val credentialName = requireNotNull(arguments.getString("live_credentials_file"))
        require(credentialName.matches(Regex("live-login-[A-Za-z0-9-]+\\.json")))
        val credentialFile = File(target.filesDir, credentialName)
        val production = target.getSharedPreferences("library_vault", Context.MODE_PRIVATE)
        val original = production.all.toMap()
        val preferenceFile = "instrumentation_student_login_${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(preferenceFile, mode).also { require(name == "library_vault") }
        }
        val store = AccountStore(context)
        try {
            val credentials = JSONObject(credentialFile.readText())
            val username = credentials.getString("username")
            val password = credentials.getString("password")
            assertTrue("The temporary credential file must be removed before login", credentialFile.delete())
            val account = store.save(username, password, "Login verification")
            assertEquals(username, AccountStore(context).accounts().single().username)
            DdddOcr(target).use { ocr ->
                val repository = LibraryRepository(store, ocr)
                try {
                    repository.login(account) { /* Do not log credentials or session data. */ }
                    assertTrue("The server must authenticate the exact supplied identity", repository.restore(account) != null)
                    val html = repository.request(account, "/history?type=SEAT")
                    val document = LibraryHtml.parse(html)
                    // History pages can omit the identity controls present on the home page.
                    val historyIdentity = document.selectFirst("#sysUsername")?.attr("value")
                    assertTrue("History must not redirect to login or identify another account",
                        document.selectFirst("form[action='/auth/signIn']") == null &&
                            (historyIdentity == null || historyIdentity == username))
                    assertTrue("The history session must still authenticate the exact supplied identity", repository.restore(account) != null)
                    val history = LibraryFunctionsRepository.parseHistory(document)
                    val sourceStatuses = document.select("dl a.normal:not([href]):not([onclick])")
                        .map { it.text() }.filter { it.isNotBlank() }.toSet()
                    assertTrue("Every server history status must survive parsing",
                        sourceStatuses.all { status -> history.items.any { it.status == status } })
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("login_verification", "Authenticated exact identity; read-only history loaded")
                        putInt("history_count", history.items.size)
                        putString("history_statuses", history.items.map { it.status }.distinct().joinToString(", "))
                    })
                } finally {
                    repository.forget(account.id)
                }
            }
        } finally {
            try {
                if (credentialFile.exists()) assertTrue("Temporary credentials must be removed", credentialFile.delete())
            } finally {
                assertTrue("Temporary encrypted credentials must be cleared",
                    context.getSharedPreferences("library_vault", Context.MODE_PRIVATE).edit().clear().commit())
                assertTrue("The temporary vault must be removed", target.deleteSharedPreferences(preferenceFile))
                assertTrue("Live login must preserve the user's saved accounts and preferences", original == production.all)
            }
        }
    }
}
