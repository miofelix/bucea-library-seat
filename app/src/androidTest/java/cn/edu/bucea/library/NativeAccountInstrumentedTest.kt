package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bucea.library.data.LibraryHtml
import cn.edu.bucea.library.data.LoginFailure
import cn.edu.bucea.library.data.NativeAccountRepository
import cn.edu.bucea.library.data.PasswordChangeUncertain
import cn.edu.bucea.library.data.SavedAccount
import cn.edu.bucea.library.data.models.PasswordChangeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Synthetic forms/responses only. No password or account on the school service is changed. */
@RunWith(AndroidJUnit4::class)
class NativeAccountInstrumentedTest {
    private val account = SavedAccount("synthetic-id", "209999999001", "synthetic-old", "测试")
    private fun form(token: String = "synthetic-csrf") = """<a href='/logout'>退出</a>
        <form action='/user/updateCredentials' method='post'>
        <input type='hidden' name='SYNCHRONIZER_TOKEN' value='$token'>
        <input type='hidden' name='SYNCHRONIZER_URI' value='/user/password'>
        <input name='password'><input name='newPassword'><input name='newPasswordConfirm'></form>"""

    @Test fun freshFormAndExactPasswordFieldsAreSubmittedOnceAndSavedOnlyAfterSuccess() = runBlocking {
        var posts = 0
        var saved: String? = null
        val repository = NativeAccountRepository(
            request = { _, path -> assertEquals("/user/password", path); form("fresh-test-token") },
            post = { _, path, fields ->
                posts++
                assertEquals("/user/updateCredentials", path)
                assertEquals("fresh-test-token", fields["SYNCHRONIZER_TOKEN"])
                assertEquals("/user/password", fields["SYNCHRONIZER_URI"])
                assertEquals("synthetic-old", fields["password"])
                assertEquals("synthetic-new", fields["newPassword"])
                assertEquals("synthetic-new", fields["newPasswordConfirm"])
                assertFalse(fields.containsKey("oldPassword"))
                "<script>var flashMsg = '密码修改成功！';</script>"
            }, verifySession = { true }, saveCredentials = { _, password -> saved = password; true }, clearSession = {},
        )
        val result = repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new")
        assertEquals(1, posts)
        assertEquals(PasswordChangeOutcome.SUCCESS, result.outcome)
        assertTrue(result.credentialsSaved)
        assertEquals("synthetic-new", saved)
        assertFalse(result.message.contains("synthetic-new"))
    }

    @Test fun loginOrChangedFormNeverSubmitsCredentials() = runBlocking {
        for (html in listOf("<form action='/auth/signIn'></form>", "<a href='/logout'>退出</a><form method='post' action='/unexpected'></form>", form().replace("SYNCHRONIZER_TOKEN", "MISSING_TOKEN"))) {
            var posts = 0
            val repository = NativeAccountRepository({ _, _ -> html }, { _, _, _ -> posts++; "" }, { true }, { _, _ -> false }, {})
            try { repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new"); fail("Changed/login form must fail") }
            catch (_: LoginFailure) { }
            assertEquals(0, posts)
        }
    }

    @Test fun mismatchedConfirmationIsRejectedBeforeAnyNetworkOperation() = runBlocking {
        val repository = NativeAccountRepository({ _, _ -> fail("Must not fetch invalid input"); "" },
            { _, _, _ -> fail("Must not post invalid input"); "" }, { fail("Must not verify invalid input"); false }, { _, _ -> false }, {})
        try { repository.changePassword(account, "synthetic-old", "synthetic-new", "different-new"); fail("Mismatched passwords must fail") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun http200WithoutAnExplicitPasswordReceiptCannotOverwriteSavedCredentials() = runBlocking {
        for (html in listOf(form(), "<div class='resultMessage'>签到成功</div>", "<div class='message'>已连接图书馆</div>")) {
            var saves = 0
            val repository = NativeAccountRepository({ _, _ -> form() }, { _, _, _ -> html }, { true }, { _, _ -> saves++; true }, {})
            val result = repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new")
            assertEquals(PasswordChangeOutcome.UNKNOWN, result.outcome)
            assertFalse(result.credentialsSaved)
            assertEquals(0, saves)
        }
        assertEquals(PasswordChangeOutcome.REJECTED, NativeAccountRepository.parsePasswordResult(
            LibraryHtml.parse("<div class='errors'>当前密码不正确</div>")).outcome)
        assertEquals(PasswordChangeOutcome.REJECTED, NativeAccountRepository.parsePasswordResult(LibraryHtml.parse(
            "<div class='message'>密码修改成功</div><div class='errors'>当前密码不正确</div>")).outcome)
    }

    @Test fun timeoutAfterPasswordSubmissionNeverRetriesOrClaimsSuccess() = runBlocking {
        var posts = 0
        val repository = NativeAccountRepository({ _, _ -> form() }, { _, _, _ -> posts++; throw IOException("synthetic timeout") }, { true }, { _, _ -> fail("Must not save unknown result"); false }, {})
        try { repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new"); fail("Ambiguous submission must fail") }
        catch (_: PasswordChangeUncertain) { }
        assertEquals(1, posts)
    }

    @Test fun observedSchoolHttp200ErrorPagePreservesUnknownOutcomeAndSavedCredentials() = runBlocking {
        var saves = 0
        var posts = 0
        val errorPage = """<html><head><title>出错了</title></head><body>
            <h1>Oops...</h1><h3>对不起，我们暂时无法处理您的请求，请稍候再试。</h3></body></html>"""
        val repository = NativeAccountRepository({ _, _ -> form() }, { _, _, _ -> posts++; errorPage },
            { true }, { _, _ -> saves++; true }, {})
        val result = repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new")
        assertEquals(1, posts)
        assertEquals(0, saves)
        assertEquals(PasswordChangeOutcome.UNKNOWN, result.outcome)
        assertFalse(result.credentialsSaved)
        assertEquals("学校服务器处理出错，修改结果未确认，请先核实密码", result.message)
    }

    @Test fun serverSuccessAndLocalStorageFailureRemainACompletedChange() = runBlocking {
        var posts = 0
        val repository = NativeAccountRepository({ _, _ -> form() },
            { _, _, _ -> posts++; "<div class='message'>密码更新成功</div>" }, { true },
            { _, _ -> throw IllegalStateException("synthetic vault failure") }, {})
        val result = repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new")
        assertEquals(1, posts)
        assertEquals(PasswordChangeOutcome.SUCCESS, result.outcome)
        assertFalse(result.credentialsSaved)
        assertTrue(result.message.contains("本机保存失败"))
        assertFalse(result.message.contains("synthetic-new"))
    }

    @Test fun unavailableServerStillClearsLocalSessionOnExplicitSignOut() = runBlocking {
        var clears = 0
        var requests = 0
        val repository = NativeAccountRepository({ _, path -> requests++; assertEquals("/logout", path); throw IOException("synthetic offline") }, { _, _, _ -> fail("Logout must not post"); "" }, { true }, { _, _ -> false }, { clears++ })
        val message = repository.logout(account)
        assertEquals(1, requests)
        assertEquals(1, clears)
        assertTrue(message.contains("已退出本机"))
    }

    @Test fun concurrentAndAmbiguousPasswordChangeCannotResubmitSameToken() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var posts = 0
        val repository = NativeAccountRepository({ _, _ -> form() }, { _, _, _ ->
            posts++; entered.complete(Unit); release.await(); throw IOException("synthetic timeout")
        }, { true }, { _, _ -> fail("Unknown result must not save a password"); false }, {})
        val first = async {
            try { repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new"); fail("Timeout must remain unknown") }
            catch (_: PasswordChangeUncertain) { }
        }
        entered.await()
        try { repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new"); fail("Concurrent change must be blocked") }
        catch (_: IllegalArgumentException) { }
        release.complete(Unit)
        first.await()
        try { repository.changePassword(account, "synthetic-old", "synthetic-new", "synthetic-new"); fail("Used token must not be sent after timeout") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, posts)
    }
}
