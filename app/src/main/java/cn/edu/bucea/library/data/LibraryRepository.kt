package cn.edu.bucea.library.data

import android.util.Base64
import cn.edu.bucea.library.LibraryOverview
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    private data class Reply(val code: Int, val headers: Headers, val body: String)
    private val sessions = mutableMapOf<String, Session>()

    private fun client(jar: SessionCookieJar? = null): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .apply { if (jar != null) cookieJar(jar) }
        .addInterceptor { chain ->
            requireCampusUrl(chain.request())
            chain.proceed(chain.request().newBuilder().header("User-Agent", "BUCEA-Library-Android/0.1").build())
        }.build()

    private fun requireCampusUrl(request: Request) {
        val url = request.url
        if (url.host != "10.1.20.7" || url.scheme != "http" || url.port != 80)
            throw LoginFailure(FailureKind.PROTOCOL, "已阻止离开校园内网的请求")
    }

    @Synchronized private fun session(id: String): Session = sessions.getOrPut(id) {
        val jar = SessionCookieJar(store, id)
        Session(jar, client(jar))
    }

    private suspend fun exchange(client: OkHttpClient, request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        // Keep cancellation attached to the Call until body reading finishes.
                        val body = it.body?.string() ?: throw LoginFailure(FailureKind.PROTOCOL, "服务返回了空内容")
                        if (continuation.isActive) continuation.resume(Reply(it.code, it.headers, body))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private suspend fun execute(client: OkHttpClient, request: Request): String {
        var next = request
        repeat(6) { hop ->
            requireCampusUrl(next)
            val response = exchange(client, next)
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val target = response.headers["Location"]?.let { next.url.resolve(it) }
                        ?: throw LoginFailure(FailureKind.PROTOCOL, "服务跳转地址无效")
                    if (hop == 5) throw LoginFailure(FailureKind.PROTOCOL, "服务跳转次数过多")
                    val builder = next.newBuilder().url(target)
                    if (response.code == 303 || (response.code in listOf(301, 302) && next.method == "POST"))
                        builder.get().removeHeader("Content-Type").removeHeader("Content-Length")
                    next = builder.build()
                    requireCampusUrl(next)
                } else {
                    if (response.code !in 200..299) throw LoginFailure(FailureKind.NETWORK, "服务暂不可用（HTTP ${response.code}）")
                    return response.body
                }
        }
        throw LoginFailure(FailureKind.PROTOCOL, "服务跳转次数过多")
    }

    private suspend fun get(client: OkHttpClient, path: String) = execute(client, Request.Builder().url(BASE_URL + path).build())

    /** Authenticated, cancellable campus-only GET for native library business pages/APIs. */
    suspend fun request(account: SavedAccount, path: String): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && !path.startsWith("//")) { "功能路径无效" }
        get(session(account.id).client, path)
    }

    suspend fun post(account: SavedAccount, path: String, fields: Map<String, String>): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && !path.startsWith("//")) { "功能路径无效" }
        val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        execute(session(account.id).client, Request.Builder().url(BASE_URL + path).post(form).build())
    }

    suspend fun publicOverview(): LibraryOverview = withContext(Dispatchers.IO) {
        LibraryHtml.overview(LibraryHtml.parse(get(client(), "/login?targetUri=%2F")))
    }

    suspend fun restore(account: SavedAccount): LibraryOverview? = withContext(Dispatchers.IO) {
        if (store.isSignedOut(account.id)) return@withContext null
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
        if (LibraryHtml.authenticated(document, account.username)) {
            store.setSignedOut(account.id, false)
            return@withContext LibraryHtml.overview(document)
        }
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

    /** The WebView has saved newer cookies; drop the in-memory session without erasing them. */
    @Synchronized fun releaseSession(id: String) {
        sessions.remove(id)?.let { it.jar.invalidate(clearSaved = false); it.client.dispatcher.cancelAll() }
    }

    fun userMessage(error: Throwable): String = when (error) {
        is LoginFailure -> error.message
        is IOException -> "无法连接图书馆，请确认已连接校园网或图书馆 Wi-Fi，再点击重试"
        else -> error.message?.take(120) ?: "操作失败，请重试"
    }
}
