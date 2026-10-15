package cn.edu.bucea.library.update

import okhttp3.Authenticator
import okhttp3.CookieJar
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val packageName = "cn.edu.bucea.library"
    private fun valid() = JSONObject("""{
        "schemaVersion":1,"packageName":"$packageName","versionCode":9,"versionName":"0.3.5",
        "minSdk":26,"notes":"新功能","prerelease":true,"releaseTag":"v0.3.5-beta.1",
        "apk":{"assetId":123,"name":"app.apk","size":1000,"sha256":"${"a".repeat(64)}",
        "url":"$UpdateOrigin/updates/apks/123.apk"}}
    """)
    private fun rejects(json: JSONObject) {
        assertThrows(Exception::class.java) { UpdateManifest.parse(json, packageName) }
    }

    @Test fun validManifestRoundTripsAndLabelsBeta() {
        val parsed = UpdateManifest.parse(valid(), packageName)
        assertTrue(parsed.displayVersion.contains("测试版"))
        assertEquals(parsed, UpdateManifest.parse(parsed.toJson(), packageName))
    }

    @Test fun chineseReleaseDisplayNamePreservesTheLegacyDownloadContract() {
        val json = valid().put("versionName", "0.3.8").put("releaseTag", "v0.3.8-beta.1")
        json.getJSONObject("apk").put("name", "BUCEA-Library-Seat-v0.3.8.apk")
            .put("displayName", "北建大图书馆选座-v0.3.8.apk")
        val parsed = UpdateManifest.parse(json, packageName)
        assertEquals("BUCEA-Library-Seat-v0.3.8.apk", parsed.apkName)
        assertEquals(123L, parsed.assetId)
        assertEquals("$UpdateOrigin/updates/apks/123.apk", parsed.url)
        assertEquals(parsed, UpdateManifest.parse(parsed.toJson(), packageName))
    }

    @Test fun urlsRejectHttpRedirectTargetsPortsCredentialsQueriesAndEncodedPaths() {
        for (url in listOf("http://bucea-library-seat.miofelix.com/updates/apks/123.apk",
            "https://github.com/file.apk", "$UpdateOrigin:443/updates/apks/123.apk",
            "$UpdateOrigin/updates/apks/123.apk?foo=1", "$UpdateOrigin/updates/apks/%31%32%33.apk",
            "https://bucea-library-seat.miofelix.com@evil.example/updates/apks/123.apk",
            "$UpdateOrigin/updates/apks/124.apk", "$UpdateOrigin/updates/apks/123.apk#fragment")) {
            val json = valid()
            json.getJSONObject("apk").put("url", url)
            rejects(json)
        }
    }

    @Test fun packageSchemaNumbersSizesDigestAndPrereleaseMustBeValid() {
        rejects(valid().put("packageName", "another.app"))
        rejects(valid().put("schemaVersion", 2))
        rejects(valid().put("versionCode", "9"))
        rejects(valid().put("versionCode", 9.1))
        rejects(valid().put("versionCode", -1))
        rejects(valid().put("versionName", 9))
        rejects(valid().put("notes", false))
        rejects(valid().put("minSdk", 0))
        rejects(valid().put("prerelease", "true"))
        for ((key, value) in listOf("size" to 0, "size" to MaxUpdateApkBytes + 1, "sha256" to "xyz", "name" to "../app.apk")) {
            val json = valid()
            json.getJSONObject("apk").put(key, value)
            rejects(json)
        }
    }

    @Test fun updateClientHasNoCookieAuthenticationInterceptorsCacheOrRedirects() {
        val client = secureUpdateClient()
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertNull(client.cache)
    }

    @Test fun signaturesMustMatchEveryCurrentSignerAndRejectEmptyOrChangedKeys() {
        val original = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        assertTrue(sameSigningCertificates(original, original.reversed()))
        assertFalse(sameSigningCertificates(original, original.take(1)))
        assertFalse(sameSigningCertificates(original, listOf(byteArrayOf(7))))
        assertFalse(sameSigningCertificates(emptyList(), emptyList()))
    }
}
