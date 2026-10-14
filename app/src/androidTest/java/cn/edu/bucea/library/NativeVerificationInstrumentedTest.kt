package cn.edu.bucea.library

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.data.LoginFailure
import cn.edu.bucea.library.data.NativeVerificationRepository
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.data.models.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class NativeVerificationInstrumentedTest {
    private val account = SavedAccount("fake-A", "209999990001", "fake", "测试")
    private val context = VerificationContext(account.id, username = account.username, sysToken = "synthetic-context")
    private val image = "data:image/png;base64,AQID"

    @Test
    fun contextReadsCurrentSchoolIdentityTokenAndRequestedVerificationUser() = runBlocking {
        var requests = 0
        val repository = NativeVerificationRepository(
            request = { requestedAccount, endpoint ->
                assertEquals(account, requestedAccount)
                assertEquals("/self", endpoint)
                requests++
                """<input id="sysUsername" value="${account.username}"><input id="sysToken" value="school-context-token">"""
            }, post = { _, _, _ -> error("Reading verification context must not post") },
        )
        val result = repository.context(account, "limited-school-user")
        assertEquals(account.id, result.accountId)
        assertEquals(account.username, result.username)
        assertEquals("school-context-token", result.sysToken)
        assertEquals("limited-school-user", result.userId)
        assertEquals(1, requests)
    }

    @Test
    fun contextRejectsLoginPageAnotherAccountAndMissingTokenBeforeChallenge() = runBlocking {
        val pages = listOf(
            """<form action="/auth/signIn"><input name="username"></form>""",
            """<input id="sysUsername" value="209999990002"><input id="sysToken" value="other-token">""",
            """<input id="sysUsername" value="${account.username}">""",
            """<form action="/auth/signIn"><input id="sysUsername" value="${account.username}"><input id="sysToken" value=""></form>""",
        )
        pages.forEach { page ->
            val repository = NativeVerificationRepository(request = { _, _ -> page },
                post = { _, _, _ -> error("An invalid context must not request a challenge") })
            try {
                repository.context(account)
                fail("Invalid verification context must be rejected")
            } catch (error: IllegalArgumentException) {
                assertEquals(if (page.contains("/auth/signIn") || page.contains("209999990002"))
                    "登录状态已变化，请重新登录" else "学校暂未提供有效验证参数，请刷新区域后重试", error.message)
            }
        }
    }

    @Test
    fun emptySchoolPlaceholdersExplainMissingIssuedTokenWithoutRequestingCaptcha() = runBlocking {
        val pages = listOf(
            """<input id="sysUsername" value="${account.username}"><input id="sysToken" value="">""",
            """<input id="sysUsername" value="${account.username}"><script>var sysUsername = ""; var sysToken = "";</script>""",
            """<input id="sysUsername" value="${account.username}"><input id="sysToken" value=""><script>
                function firstSearch(data) { if (data.limit) { USER_ID = data.userId;
                    setTimeout(function () { sysUsername = ""; sysToken = ""; clickVerifyCode(""); }, 500); } }
            </script>""",
        )
        pages.forEach { page ->
            var posts = 0
            val repository = NativeVerificationRepository(request = { _, endpoint ->
                assertEquals("/self", endpoint)
                page
            }, post = { _, _, _ ->
                posts++
                error("An unissued token must never request a challenge")
            })
            try { repository.context(account, "school-user"); fail("Empty token is only a placeholder") }
            catch (error: IllegalArgumentException) {
                assertEquals("学校暂未提供有效验证参数，请刷新区域后重试", error.message)
            }
            try { repository.challenge(account, context.copy(sysToken = "")); fail("Empty manual context must fail before POST") }
            catch (error: IllegalArgumentException) {
                assertEquals("学校暂未提供有效验证参数，请刷新区域后重试", error.message)
            }
            assertEquals(0, posts)
        }
    }

    @Test
    fun contextReadsStaticTokenFromActualNestedSchoolCallbackWithoutEvaluatingJavaScript() = runBlocking {
        val actualWiring = """
            <input id="sysUsername" value="${account.username}">
            <script>
                var sysUsername = "";
                var sysToken = "";
                function firstSearch(data) {
                    if (data.limit) {
                        USER_ID = data.userId;
                        setTimeout(function () {
                            sysUsername = "${account.username}";
                            sysToken = "nested-school-token-2026";
                            clickVerifyCode("");
                        }, 500);
                    }
                }
            </script>
        """.trimIndent()
        var requests = 0
        val repository = NativeVerificationRepository(request = { _, endpoint ->
            assertEquals("/self", endpoint)
            requests++
            actualWiring
        }, post = { _, _, _ -> error("Context must never evaluate JavaScript or request a challenge") })
        val result = repository.context(account, "nested-user")
        assertEquals("nested-school-token-2026", result.sysToken)
        assertEquals(account.username, result.username)
        assertEquals("nested-user", result.userId)
        assertEquals(1, requests)
    }

    @Test
    fun contextRejectsConflictingAssignmentsIdentityAndUnsupportedTokenCharacters() = runBlocking {
        fun page(script: String, inputs: String = "") = """
            <input id="sysUsername" value="${account.username}">$inputs<script>$script</script>
        """.trimIndent()
        val pages = listOf(
            page("""sysUsername = "209999990002"; sysToken = "school-token";"""),
            page("""sysUsername = "${account.username}"; sysToken = "first-token"; sysToken = "second-token";"""),
            page("""sysToken = "script-token";""", """<input id="sysToken" value="input-token">"""),
            page("""var unrelated = "";"""),
            page("""sysToken = "token/with/slashes";"""),
            page("""sysToken = "token with spaces";"""),
            page("""sysToken = " ";"""),
            page("""sysToken = "token\\u002Dencoded";"""),
        )
        pages.forEach { response ->
            val repository = NativeVerificationRepository(request = { _, _ -> response },
                post = { _, _, _ -> error("Invalid parameters must not request a challenge") })
            try { repository.context(account); fail("Untrusted verification credentials must fail") }
            catch (error: IllegalArgumentException) {
                assertEquals(if (response.contains("209999990002")) "登录状态已变化，请重新登录"
                    else "学校暂未提供有效验证参数，请刷新区域后重试", error.message)
            }
        }
        val repeatedSameValue = NativeVerificationRepository(request = { _, _ ->
            page("""sysToken = 'same-token'; sysToken = "same-token";""", """<input id="sysToken" value="same-token">""")
        }, post = { _, _, _ -> error("Context read must not post") })
        assertEquals("same-token", repeatedSameValue.context(account).sysToken)
    }

    @Test
    fun orderedClickCoordinatesUseSchoolCanvasAndProofRequiresServerApproval() = runBlocking {
        var verifiedPoints: JSONArray? = null
        val repository = NativeVerificationRepository(
            request = { _, endpoint ->
                val url = ("http://10.1.20.7" + endpoint).toHttpUrl()
                assertEquals("/cap/checkCaptcha", url.encodedPath)
                assertEquals("synthetic-challenge", url.queryParameter("token"))
                verifiedPoints = JSONArray(String(Base64.decode(url.queryParameter("a"), Base64.DEFAULT), Charsets.UTF_8))
                """{"status":"OK"}"""
            },
            post = { _, endpoint, fields ->
                assertEquals("/cap/captcha/synthetic-context", endpoint)
                assertEquals(account.username, fields["username"])
                """{"status":"OK","token":"synthetic-challenge","wordCheckCount":2,"image":"$image","wordImage":"$image"}"""
            },
        )
        val challenge = repository.challenge(account, context)
        val points = listOf(VerificationPoint(20.5, 88.0), VerificationPoint(250.0, 42.25))
        val proof = repository.verify(account, context, challenge, points)
        assertEquals(account.id, proof.accountId)
        assertEquals(challenge.token, proof.authId)
        assertEquals(20.5, verifiedPoints!!.getJSONObject(0).getDouble("x"), 0.0)
        assertEquals(42.25, verifiedPoints!!.getJSONObject(1).getDouble("y"), 0.0)
    }

    @Test
    fun failedVerificationNeverCreatesProofAndSwitchedAccountNeverRequests() = runBlocking {
        var requests = 0
        val repository = NativeVerificationRepository(
            request = { _, _ -> requests++; """{"status":"FAIL","message":"请重试"}""" },
            post = { _, _, _ -> error("Unexpected challenge request") },
        )
        val challenge = VerificationChallenge(account.id, "test", 1, byteArrayOf(1), byteArrayOf(1), System.currentTimeMillis())
        try { repository.verify(account.copy(id = "fake-B"), context, challenge, listOf(VerificationPoint(1.0, 1.0))); fail("Switched account must fail") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, requests)
        try { repository.verify(account, context, challenge, listOf(VerificationPoint(1.0, 1.0))); fail("Failed response must not yield proof") }
        catch (error: LoginFailure) { assertEquals("请重试", error.message) }
        assertEquals(1, requests)
    }

    @Test
    fun automaticVerificationUsesThreeFreshChallengesAndDoesNotRetryNetworkFailure() = runBlocking {
        var challenges = 0
        var checks = 0
        val repository = NativeVerificationRepository(
            request = { _, _ -> checks++; """{"status":"FAIL","message":"验证失败"}""" },
            post = { _, _, _ -> challenges++
                """{"status":"OK","token":"fresh-$challenges","wordCheckCount":1,"image":"$image","wordImage":"$image"}"""
            },
        )
        val tokens = mutableListOf<String>()
        try {
            repository.automaticallyVerify(account, context, solve = { tokens += it.token; listOf(VerificationPoint(1.0, 1.0)) })
            fail("Three failures must stop automatic verification")
        } catch (_: LoginFailure) { }
        assertEquals(listOf("fresh-1", "fresh-2", "fresh-3"), tokens)
        assertEquals(3, challenges)
        assertEquals(3, checks)
        var failedRequests = 0
        val offline = NativeVerificationRepository(request = { _, _ -> error("No check expected") },
            post = { _, _, _ -> failedRequests++; throw IOException("synthetic network failure") })
        try { offline.automaticallyVerify(account, context, solve = { error("No solve expected") }); fail("Network failure must stop") }
        catch (_: IOException) { }
        assertEquals(1, failedRequests)
    }

    @Test
    fun staleChallengeAndInvalidCoordinatesAreRejectedBeforeNetwork() = runBlocking {
        val repository = NativeVerificationRepository(
            request = { _, _ -> error("Invalid challenge must not reach network") },
            post = { _, _, _ -> error("Unexpected request") },
        )
        val challenge = VerificationChallenge(account.id, "test", 1, byteArrayOf(1), byteArrayOf(1), System.currentTimeMillis() - 121_000)
        try { repository.verify(account, context, challenge, listOf(VerificationPoint(1.0, 1.0))); fail("Expired challenge must fail") }
        catch (_: IllegalArgumentException) { }
        try { NativeVerificationRepository.encodePoints(listOf(VerificationPoint(Double.NaN, 0.0))); fail("Nonfinite coordinate must fail") }
        catch (_: IllegalArgumentException) { }
    }
}
