package cn.edu.bucea.library.update

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Never reuse the campus repository's client, cookie jar, credentials or interceptors. */
class UpdateHttpClient(
    private val packageName: String,
    private val preferredAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
) {
    internal val client = secureUpdateClient()

    suspend fun fetch(preferredAbis: List<String> = this.preferredAbis): UpdateManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(UpdateManifestUrl)
            .header("Accept", "application/json").header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) throw IOException("Update manifest HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty update manifest")
            if (body.contentLength() > 128 * 1024) throw IOException("Oversized update manifest")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 128 * 1024) throw IOException("Oversized update manifest")
                    output.write(buffer, 0, count)
                }
            }
            UpdateManifest.parse(JSONObject(output.toString("UTF-8")), packageName, preferredAbis)
        }
    }
}

internal fun secureUpdateClient(): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
    .followRedirects(false).followSslRedirects(false).cache(null)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).build()
