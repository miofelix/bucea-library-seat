package cn.edu.bucea.library.data

import cn.edu.bucea.library.data.models.PasswordChangeOutcome
import cn.edu.bucea.library.data.models.PasswordChangeResult
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Document
import java.io.IOException

/** Account mutations are sent once; an HTTP 200 alone never confirms a password change. */
class NativeAccountRepository(
    private val request: suspend (SavedAccount, String) -> String,
    private val post: suspend (SavedAccount, String, Map<String, String>) -> String,
    private val verifySession: suspend (SavedAccount) -> Boolean,
    private val saveCredentials: (SavedAccount, String) -> Boolean,
    private val clearSession: (SavedAccount) -> Unit,
) {
    private val accountsChangingPassword = mutableSetOf<String>()
    private val submittedPasswordTokens = mutableSetOf<String>()
    constructor(repository: LibraryRepository, store: AccountStore) : this(
        request = repository::request,
        post = repository::post,
        verifySession = { repository.restore(it) != null },
        saveCredentials = { account, newPassword ->
            val previouslyActive = store.activeAccountId
            try {
                val saved = store.save(account.username, newPassword, account.label)
                check(saved.id == account.id) { "账号已改变" }
                repository.forget(account.id)
                true
            } catch (_: Exception) {
                repository.forget(account.id)
                store.setSignedOut(account.id, true)
                false
            } finally {
                store.activeAccountId = previouslyActive
            }
        },
        clearSession = { account ->
            repository.forget(account.id)
            store.setSignedOut(account.id, true)
        },
    )

    suspend fun changePassword(account: SavedAccount, currentPassword: String, newPassword: String, confirmation: String): PasswordChangeResult {
        synchronized(accountsChangingPassword) {
            require(accountsChangingPassword.add(account.id)) { "正在修改此账号的密码，请等待结果" }
        }
        return try { changePasswordOnce(account, currentPassword, newPassword, confirmation) }
        finally { synchronized(accountsChangingPassword) { accountsChangingPassword.remove(account.id) } }
    }

    private suspend fun changePasswordOnce(account: SavedAccount, currentPassword: String, newPassword: String, confirmation: String): PasswordChangeResult {
        require(currentPassword.isNotBlank()) { "请输入当前密码" }
        require(newPassword.isNotBlank()) { "请输入新密码" }
        require(newPassword == confirmation) { "两次输入的新密码不一致" }
        require(currentPassword != newPassword) { "新密码与当前密码相同" }
        if (!verifySession(account)) throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录后修改密码")
        val fields = passwordForm(account, LibraryHtml.parse(request(account, "/user/password"))) + mapOf(
            "password" to currentPassword,
            "newPassword" to newPassword,
            "newPasswordConfirm" to confirmation,
        )
        synchronized(submittedPasswordTokens) {
            require(submittedPasswordTokens.add("${account.id}|${fields.getValue("SYNCHRONIZER_TOKEN")}")) {
                "此修改请求已提交，请先核实密码，避免重复提交"
            }
        }
        val response = try { post(account, "/user/updateCredentials", fields) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw PasswordChangeUncertain() }
        val result = parsePasswordResult(LibraryHtml.parse(response))
        if (result.outcome != PasswordChangeOutcome.SUCCESS) return result
        val saved = try { saveCredentials(account, newPassword) } catch (_: Exception) { false }
        return result.copy(credentialsSaved = saved, message = if (saved) "密码修改成功，已更新本机保存的密码，请重新登录"
            else "密码已修改，但本机保存失败，请在账号管理中更新新密码后重新登录")
    }

    /** Explicit sign-out stays signed out locally even when the campus server cannot be reached. */
    suspend fun logout(account: SavedAccount): String {
        var acknowledged = false
        try {
            val document = LibraryHtml.parse(request(account, "/logout"))
            acknowledged = document.selectFirst("form[action='/auth/signIn']") != null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A server failure must not restore the saved local session.
        } finally {
            clearSession(account)
        }
        return if (acknowledged) "已退出登录" else "已退出本机登录；图书馆未确认服务器退出结果"
    }

    companion object {
        fun passwordForm(account: SavedAccount, document: Document): Map<String, String> {
            val username = document.selectFirst("#sysUsername")?.attr("value")
            if (document.selectFirst("form[action='/auth/signIn']") != null ||
                (username != null && username != account.username) || document.selectFirst("a[href='/logout']") == null)
                throw LoginFailure(FailureKind.CREDENTIALS, "登录已失效，请重新登录后修改密码")
            val form = document.selectFirst("form[action='/user/updateCredentials'][method=post]")
                ?: throw LoginFailure(FailureKind.PROTOCOL, "修改密码接口已变更，请稍后重试")
            val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
            if (fields["SYNCHRONIZER_TOKEN"].isNullOrBlank() || fields["SYNCHRONIZER_URI"] != "/user/password" ||
                listOf("password", "newPassword", "newPasswordConfirm").any { form.selectFirst("input[name=$it]") == null })
                throw LoginFailure(FailureKind.PROTOCOL, "修改密码表单校验失败，请刷新后重试")
            return fields
        }

        fun parsePasswordResult(document: Document): PasswordChangeResult {
            val serverFailure = document.title().trim() == "出错了" &&
                document.select("h1").any { it.text().startsWith("Oops", ignoreCase = true) } &&
                document.select("h3").any { it.text().contains("暂时无法处理您的请求") }
            if (serverFailure) return PasswordChangeResult(PasswordChangeOutcome.UNKNOWN,
                "学校服务器处理出错，修改结果未确认，请先核实密码")
            val scripts = document.select("script").joinToString("\n") { it.data() }
            val flash = Regex("""flashMsg\s*=\s*['"]([^'"]+)['"]""").find(scripts)?.groupValues?.get(1)
            val messages = document.select(".errors, .error, .alert-danger, .message, #msgBoxDIV .msg")
                .map { it.text().trim() }.filter { it.isNotBlank() } + listOfNotNull(flash)
            val normalized = messages.map { it.trim().trimEnd('！', '!', '。', '.', '；', ';') }
            val success = setOf("密码修改成功", "修改密码成功", "密码更新成功", "更新密码成功", "您的密码已修改成功", "您的密码已经更新")
            val rejected = normalized.any { message ->
                message in setOf("密码修改失败", "修改密码失败", "密码更新失败", "两次输入的密码不一致", "两次输入的新密码不一致") ||
                    (listOf("当前密码", "原密码", "旧密码").any(message::contains) && listOf("错误", "不正确", "不匹配").any(message::contains)) ||
                    (message.contains("密码") && listOf("不能为空", "至少", "不一致").any(message::contains))
            }
            val explicitError = document.select(".errors, .error, .alert-danger").any { it.text().isNotBlank() }
            return when {
                rejected -> PasswordChangeResult(PasswordChangeOutcome.REJECTED, "修改未成功，请检查当前密码、新密码格式及两次输入是否一致")
                !explicitError && normalized.any { it in success } -> PasswordChangeResult(PasswordChangeOutcome.SUCCESS, "密码修改成功")
                else -> PasswordChangeResult(PasswordChangeOutcome.UNKNOWN, "图书馆未明确确认修改结果，请先核实密码，避免重复提交")
            }
        }
    }
}

class PasswordChangeUncertain : IOException("修改请求的结果尚未确认，请先核实密码，避免重复提交")
