package cn.edu.bucea.library.data

import android.util.Base64
import cn.edu.bucea.library.LibraryOverview
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class FailureKind { NETWORK, CREDENTIALS, CAPTCHA, PROTOCOL }
class LoginFailure(val kind: FailureKind, override val message: String) : IOException(message)
data class CaptchaChallenge(val fields: Map<String, String>, val id: String, val image: ByteArray)
data class LoginResult(val overview: LibraryOverview?, val challenge: CaptchaChallenge? = null)

class LibraryRepository(private val store: AccountStore, private val ocr: DdddOcr) {
    companion object { const val BASE_URL = "http://10.1.20.7" }
    private data class Session(val jar: SessionCookieJar, val client: OkHttpClient)
    private val sessions = mutableMapOf<String, Session>()

    private fun client(jar: SessionCookieJar? = null): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .apply { if (jar != null) cookieJar(jar) }
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (url.host != "10.1.20.7" || url.scheme != "http" || url.port != 80)
                throw LoginFailure(FailureKind.PROTOCOL, "已阻止离开校园内网的请求")
            chain.proceed(chain.request().newBuilder().header("User-Agent", "BUCEA-Library-Android/0.1").build())
        }.build()

    @Synchronized private fun session(id: String): Session = sessions.getOrPut(id) {
        val jar = SessionCookieJar(store, id)
        Session(jar, client(jar))
    }

    private fun execute(client: OkHttpClient, request: Request): String = client.newCall(request).execute().use {
        if (!it.isSuccessful) throw LoginFailure(FailureKind.NETWORK, "服务暂不可用（HTTP ${it.code}）")
        it.body?.string() ?: throw LoginFailure(FailureKind.PROTOCOL, "服务返回了空内容")
    }

    private fun get(client: OkHttpClient, path: String) = execute(client, Request.Builder().url(BASE_URL + path).build())

    suspend fun publicOverview(): LibraryOverview = withContext(Dispatchers.IO) {
        LibraryHtml.overview(LibraryHtml.parse(get(client(), "/login?targetUri=%2F")))
    }

    suspend fun restore(account: SavedAccount): LibraryOverview? = withContext(Dispatchers.IO) {
        val document = LibraryHtml.parse(get(session(account.id).client, "/"))
        if (LibraryHtml.authenticated(document, account.username)) LibraryHtml.overview(document) else null
    }

    suspend fun challenge(account: SavedAccount): CaptchaChallenge = withContext(Dispatchers.IO) {
        val client = session(account.id).client
        val fields = LibraryHtml.hiddenFields(LibraryHtml.parse(get(client, "/login?targetUri=%2F")))
        val json = JSONObject(get(client, "/auth/createCaptcha?_=${System.currentTimeMillis()}"))
        val data = json.getString("captchaImage")
        if (!data.startsWith("data:image/") || !data.contains(";base64,"))
            throw LoginFailure(FailureKind.PROTOCOL, "验证码格式已变更")
        CaptchaChallenge(fields, json.getString("captchaId"), Base64.decode(data.substringAfter(","), Base64.DEFAULT))
    }

    suspend fun submit(account: SavedAccount, challenge: CaptchaChallenge, answer: String): LibraryOverview = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
        challenge.fields.forEach { (name, value) -> if (name != "captchaId") form.add(name, value) }
        form.add("username", account.username).add("password", account.password)
            .add("captchaId", challenge.id).add("answer", answer.trim()).add("targetUri", "/")
        val document = LibraryHtml.parse(execute(session(account.id).client,
            Request.Builder().url("$BASE_URL/auth/signIn").post(form.build()).build()))
        if (LibraryHtml.authenticated(document, account.username)) return@withContext LibraryHtml.overview(document)
        val error = LibraryHtml.error(document)
        val kind = when {
            error.contains("验证码") -> FailureKind.CAPTCHA
            listOf("密码", "不存在", "用户", "账号", "帐号").any { error.contains(it) } -> FailureKind.CREDENTIALS
            else -> FailureKind.PROTOCOL
        }
        throw LoginFailure(kind, error)
    }

    suspend fun login(account: SavedAccount, progress: (String) -> Unit): LoginResult {
        progress("正在检查已保存的登录状态…")
        restore(account)?.let { return LoginResult(it) }
        repeat(3) { attempt ->
            progress("正在识别验证码（${attempt + 1}/3）…")
            val challenge = challenge(account)
            val answer = try { ocr.recognize(challenge.image) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return LoginResult(null, challenge) }
            if (answer.isBlank()) return LoginResult(null, challenge)
            progress("正在登录…")
            try { return LoginResult(submit(account, challenge, answer)) }
            catch (e: LoginFailure) { if (e.kind != FailureKind.CAPTCHA) throw e }
        }
        // Failed submissions can invalidate tokens: manual fallback always gets a fresh challenge.
        return LoginResult(null, challenge(account))
    }

    @Synchronized fun forget(id: String) {
        sessions.remove(id)?.let { it.jar.invalidate(); it.client.dispatcher.cancelAll() }
        store.clearCookies(id)
    }

    fun userMessage(error: Throwable): String = when (error) {
        is LoginFailure -> error.message
        is IOException -> "无法连接图书馆，请确认已连接校园网或图书馆 Wi-Fi，再点击重试"
        else -> error.message?.take(120) ?: "操作失败，请重试"
    }
}
