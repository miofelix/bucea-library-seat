package cn.edu.bucea.library.update

import okhttp3.Authenticator
import okhttp3.CookieJar
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val packageName = "cn.edu.bucea.library"
    private fun valid() = JSONObject("""{
        "schemaVersion":1,"packageName":"$packageName","versionCode":9,"versionName":"0.3.5",
        "minSdk":26,"notes":"新功能","prerelease":true,"releaseTag":"v0.3.5-beta.1",
        "apk":{"assetId":123,"name":"BUCEA-Library-Seat-v0.3.5.apk","size":1000,"sha256":"${"a".repeat(64)}",
        "url":"$UpdateBaseUrl/releases/v0.3.5-beta.1/BUCEA-Library-Seat-v0.3.5.apk"}}
    """)
    private fun rejects(json: JSONObject) {
        assertThrows(Exception::class.java) { UpdateManifest.parse(json, packageName) }
    }

    @Test fun validManifestRoundTripsAndLabelsBeta() {
        val parsed = UpdateManifest.parse(valid(), packageName)
        assertTrue(parsed.displayVersion.contains("测试版"))
        assertEquals(parsed, UpdateManifest.parse(parsed.toJson(), packageName))
    }

    @Test fun releaseUrlUsesTheExactTagAndFilename() {
        val json = valid().put("versionName", "1.0.0").put("releaseTag", "v1.0.0")
        val filename = "BUCEA-Library-Seat-v1.0.0.apk"
        json.getJSONObject("apk").put("name", filename)
            .put("url", updateApkUrl("v1.0.0", filename))
        val parsed = UpdateManifest.parse(json, packageName)
        assertEquals(filename, parsed.apkName)
        assertEquals(123L, parsed.assetId)
        assertEquals("$UpdateBaseUrl/releases/v1.0.0/$filename", parsed.url)
        assertEquals(parsed, UpdateManifest.parse(parsed.toJson(), packageName))
    }

    @Test fun selectsTheFirstPreferredAbiAndFallsBackToUniversal() {
        val arm64Name = "BUCEA-Library-Seat-v0.3.5-arm64-v8a.apk"
        val arm64 = JSONObject().put("abi", "arm64-v8a").put("minSdk", 26)
            .put("assetId", 124).put("name", arm64Name).put("size", 900)
            .put("sha256", "b".repeat(64)).put("url", updateApkUrl("v0.3.5-beta.1", arm64Name))
        val json = valid().put("apkVariants", JSONArray().put(arm64))
        assertEquals(arm64Name, UpdateManifest.parse(json, packageName, listOf("arm64-v8a")).apkName)
        assertEquals("BUCEA-Library-Seat-v0.3.5.apk", UpdateManifest.parse(json, packageName, listOf("x86_64")).apkName)
    }

    @Test fun invalidAbiVariantsAreRejectedEvenWhenDeviceWouldUseUniversal() {
        val variant = JSONObject().put("abi", "mips").put("minSdk", 26)
            .put("assetId", 124).put("name", "variant.apk").put("size", 900)
            .put("sha256", "b".repeat(64)).put("url", updateApkUrl("v0.3.5-beta.1", "variant.apk"))
        rejects(valid().put("apkVariants", JSONArray().put(variant)))
    }

    @Test fun abiVariantMustUseItsCanonicalVersionedFilename() {
        val variant = JSONObject().put("abi", "arm64-v8a").put("minSdk", 26)
            .put("assetId", 124).put("name", "BUCEA-Library-Seat-v0.3.5.apk").put("size", 900)
            .put("sha256", "b".repeat(64)).put("url", updateApkUrl("v0.3.5-beta.1", "BUCEA-Library-Seat-v0.3.5.apk"))
        rejects(valid().put("apkVariants", JSONArray().put(variant)))
    }

    @Test fun urlsRejectHttpRedirectTargetsPortsCredentialsQueriesAndEncodedPaths() {
        val url = valid().getJSONObject("apk").getString("url")
        for (invalid in listOf(url.replace("https:", "http:"), "https://github.com/file.apk",
            url.replace(UpdateOrigin, "$UpdateOrigin:443"), "$url?foo=1", "$url#fragment",
            url.replace("/releases/", "/%72eleases/"),
            url.replace("download.miofelix.com", "download.miofelix.com@evil.example"),
            url.replace("v0.3.5-beta.1/", "v0.3.5-beta.2/"),
            url.replace(".apk", "-other.apk"))) {
            val json = valid()
            json.getJSONObject("apk").put("url", invalid)
            rejects(json)
        }
    }

    @Test fun releaseTagsCannotEscapeTheProjectDirectory() {
        for (tag in listOf("", "../other", "v1/../../other", "v1%2Fother", "v1?query", "v1#fragment")) {
            val json = valid().put("releaseTag", tag)
            json.getJSONObject("apk").put("url", updateApkUrl(tag, json.getJSONObject("apk").getString("name")))
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
