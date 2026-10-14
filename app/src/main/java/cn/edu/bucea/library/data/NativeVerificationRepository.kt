package cn.edu.bucea.library.data

import android.util.Base64
import cn.edu.bucea.library.data.models.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** School ordered-click captcha protocol implemented without evaluating its JavaScript. */
class NativeVerificationRepository(
    private val request: suspend (SavedAccount, String) -> String,
    private val post: suspend (SavedAccount, String, Map<String, String>) -> String,
) {
    constructor(library: LibraryRepository) : this(library::request, library::post)

    suspend fun context(account: SavedAccount, userId: String = ""): VerificationContext {
        val page = LibraryHtml.parse(request(account, "/self"))
        val scripts = page.select("script:not([src])").joinToString("\n") { it.data() }
        fun values(name: String): List<String> {
            // Read only literal assignments, including nested school verification callbacks.
            // Empty declarations are placeholders until the school issues a verification token.
            val assignments = Regex("""(?:^|[;{},\r\n])\s*(?:(?:var|let|const)\s+)?$name\s*=\s*(["'])([^"'\r\n]*)\1\s*(?=[;,}\r\n]|$)""")
                .findAll(scripts).map { it.groupValues[2] }
            val inputs = page.select("input#$name").map { it.attr("value") }
            return (inputs.asSequence() + assignments).toList()
        }
        val usernames = values("sysUsername").filter(String::isNotEmpty).toSet()
        val tokenFields = values("sysToken")
        val tokens = tokenFields.filter(String::isNotEmpty).toSet()
        val token = tokens.singleOrNull().orEmpty()
        require(page.selectFirst("form[action='/auth/signIn']") == null &&
            usernames == setOf(account.username)) { "登录状态已变化，请重新登录" }
        require(tokenFields.isNotEmpty() && tokens.size == 1 && token.matches(Regex("[A-Za-z0-9-]+"))) {
            "学校暂未提供有效验证参数，请刷新区域后重试"
        }
        val username = usernames.single()
        return VerificationContext(account.id, userId = userId, username = username, sysToken = token)
    }

    suspend fun challenge(account: SavedAccount, context: VerificationContext): VerificationChallenge {
        require(context.accountId == account.id) { "账号已切换，请重新验证" }
        require(context.sysToken.matches(Regex("[A-Za-z0-9-]+"))) { "学校暂未提供有效验证参数，请刷新区域后重试" }
        val path = "/cap/captcha/" + context.sysToken
        val data = response(post(account, path, mapOf("username" to context.username)))
        requireSuccess(data)
        return parseChallenge(data, account.id)
    }

    suspend fun verify(account: SavedAccount, context: VerificationContext,
                       challenge: VerificationChallenge, points: List<VerificationPoint>): VerificationProof {
        require(account.id == context.accountId && account.id == challenge.accountId) { "账号已切换，请重新验证" }
        require(System.currentTimeMillis() - challenge.issuedAt in 0..120_000) { "验证码已过期，请刷新" }
        require(points.size == challenge.requiredPoints) { "请按提示顺序完成所有点击" }
        val encoded = encodePoints(points, challenge.width, challenge.height)
        val url = (LibraryRepository.BASE_URL + "/cap/checkCaptcha").toHttpUrl().newBuilder()
            .addQueryParameter("a", encoded).addQueryParameter("token", challenge.token)
            .addQueryParameter("userId", context.userId).build()
        val data = response(request(account, url.encodedPath + "?" + url.encodedQuery))
        requireSuccess(data)
        return VerificationProof(account.id, challenge.token, System.currentTimeMillis())
    }

    suspend fun automaticallyVerify(account: SavedAccount, context: VerificationContext,
                                    solve: suspend (VerificationChallenge) -> List<VerificationPoint>,
                                    progress: (VerificationChallenge) -> Unit = {}): VerificationProof {
        repeat(3) { attempt ->
            try {
                val challenge = challenge(account, context)
                progress(challenge)
                return verify(account, context, challenge, solve(challenge))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (error is LoginFailure && error.kind != FailureKind.CAPTCHA) throw error
                if (error is IOException && error !is LoginFailure) throw error
                if (error is IllegalStateException) throw error
                if (attempt == 2) throw error
            }
        }
        error("自动验证未完成，请重试")
    }

    companion object {
        fun encodePoints(points: List<VerificationPoint>, width: Int = 330, height: Int = 160): String {
            require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..width.toDouble() && it.y in 0.0..height.toDouble() }) {
                "点击位置超出验证码范围，请重试"
            }
            val array = JSONArray().apply { points.forEach { put(JSONObject().put("x", it.x).put("y", it.y)) } }
            return Base64.encodeToString(array.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }

        fun parseChallenge(data: JSONObject, accountId: String): VerificationChallenge {
            val token = data.optString("token")
            val count = data.optInt("wordCheckCount")
            require(token.isNotBlank() && count in 1..8) { "学校返回了不支持的验证类型，请刷新重试" }
            return VerificationChallenge(accountId, token, count, decodeImage(data.getString("image")),
                decodeImage(data.getString("wordImage")), System.currentTimeMillis())
        }

        private fun decodeImage(value: String): ByteArray {
            require(value.startsWith("data:image/") && value.contains(";base64,")) { "学校验证码图片格式不支持" }
            return Base64.decode(value.substringAfter(","), Base64.DEFAULT).also {
                require(it.isNotEmpty() && it.size <= 4 * 1024 * 1024) { "学校验证码图片无效" }
            }
        }

        private fun response(text: String): JSONObject = try { JSONObject(text) } catch (_: Exception) {
            throw LoginFailure(FailureKind.PROTOCOL, "学校验证服务返回格式异常，请稍后重试")
        }

        private fun requireSuccess(data: JSONObject) {
            if (data.optString("status") != "OK")
                throw LoginFailure(FailureKind.CAPTCHA, data.optString("message").ifBlank { "验证失败，请刷新验证码后重试" })
        }
    }
}
