package cn.edu.bucea.library.update

import org.json.JSONObject

private val SupportedUpdateAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

const val UpdateOrigin = "https://download.miofelix.com"
const val UpdateBaseUrl = "$UpdateOrigin/bucea-library-seat"
const val UpdateManifestUrl = "$UpdateBaseUrl/updates/latest.json"
const val UpdateIntervalMillis = 24 * 60 * 60 * 1000L
const val MaxUpdateApkBytes = 512 * 1024 * 1024L

/** A snapshot of one release. A confirmed download never follows a newer latest.json. */
data class UpdateManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val notes: String,
    val assetId: Long,
    val apkName: String,
    val size: Long,
    val sha256: String,
    val url: String,
    val prerelease: Boolean,
    val releaseTag: String,
) {
    val identity: String get() = "$versionCode:$assetId:$sha256"
    val displayVersion: String get() = versionName + if (prerelease) "（测试版）" else ""

    fun toJson(): JSONObject = JSONObject().put("schemaVersion", 1).put("packageName", packageName)
        .put("versionCode", versionCode).put("versionName", versionName).put("minSdk", minSdk)
        .put("notes", notes).put("prerelease", prerelease).put("releaseTag", releaseTag)
        .put("apk", JSONObject().put("assetId", assetId).put("name", apkName).put("size", size)
            .put("sha256", sha256).put("url", url))

    companion object {
        /**
         * Parse the release metadata and select the first APK matching the device ABI order.
         * Older manifests only contain [apk], which remains the universal fallback.
         */
        fun parse(
            json: JSONObject,
            expectedPackage: String,
            preferredAbis: List<String> = emptyList(),
        ): UpdateManifest {
            require(json.strictLong("schemaVersion") == 1L) { "更新清单格式不受支持" }
            val packageName = json.strictString("packageName")
            val versionCode = json.strictLong("versionCode")
            val versionName = json.strictString("versionName")
            val minSdk = json.strictLong("minSdk").also { require(it in 1..1000) }.toInt()
            val notes = json.strictString("notes")
            val prerelease = (json.get("prerelease") as? Boolean) ?: error("更新清单缺少版本类型")
            val releaseTag = json.strictString("releaseTag")
            val universal = parseArtifact(json.getJSONObject("apk"), releaseTag)
            val variants = parseVariants(json, releaseTag, versionName, minSdk)
            val selected = preferredAbis.firstNotNullOfOrNull { variants[it] } ?: universal
            val manifest = UpdateManifest(
                packageName, versionCode, versionName, minSdk, notes,
                selected.assetId, selected.name, selected.size, selected.sha256, selected.url,
                prerelease, releaseTag,
            )
            require(manifest.packageName == expectedPackage) { "更新包名不匹配" }
            require(manifest.versionCode in 1..2_100_000_000L && manifest.assetId > 0) { "更新版本无效" }
            require(manifest.versionName.length in 1..100 &&
                manifest.releaseTag.matches(Regex("v[0-9][A-Za-z0-9._-]{0,198}")) &&
                manifest.notes.length <= 16000) { "更新说明无效" }
            return manifest
        }

        private fun parseVariants(json: JSONObject, releaseTag: String, versionName: String, minSdk: Int): Map<String, ApkArtifact> {
            val raw = json.opt("apkVariants") ?: return emptyMap()
            val array = raw as? org.json.JSONArray ?: error("更新清单 ABI 变体格式无效")
            require(array.length() <= SupportedUpdateAbis.size) { "更新清单 ABI 变体过多" }
            val variants = linkedMapOf<String, ApkArtifact>()
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: error("更新清单 ABI 变体格式无效")
                val abi = entry.strictString("abi")
                val artifact = parseArtifact(entry, releaseTag)
                require(abi in SupportedUpdateAbis && artifact.name == "BUCEA-Library-Seat-v$versionName-$abi.apk" &&
                    variants.put(abi, artifact) == null) {
                    "更新清单 ABI 变体无效"
                }
                require(entry.strictLong("minSdk") == minSdk.toLong()) { "更新清单 ABI 最低版本不一致" }
            }
            return variants
        }

        private fun parseArtifact(apk: JSONObject, releaseTag: String): ApkArtifact {
            val artifact = ApkArtifact(
                apk.strictLong("assetId"), apk.strictString("name"), apk.strictLong("size"),
                apk.strictString("sha256"), apk.strictString("url"),
            )
            require(artifact.assetId > 0) { "更新版本无效" }
            require(artifact.size in 1..MaxUpdateApkBytes && artifact.sha256.matches(Regex("[0-9a-f]{64}"))) {
                "更新文件校验信息无效"
            }
            require(artifact.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.apk"))) { "更新文件名无效" }
            // Exact comparison also rejects user-info, alternate ports, queries, fragments,
            // encoded paths and lookalike/subdomains. Only the project HTTPS origin is allowed.
            require(artifact.url == updateApkUrl(releaseTag, artifact.name)) { "更新下载地址无效" }
            return artifact
        }

        private data class ApkArtifact(
            val assetId: Long,
            val name: String,
            val size: Long,
            val sha256: String,
            val url: String,
        )
    }
}

internal fun updateApkUrl(releaseTag: String, apkName: String): String =
    "$UpdateBaseUrl/releases/$releaseTag/$apkName"

internal fun JSONObject.strictLong(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw IllegalArgumentException("更新清单中的 $key 必须是整数")
}

internal fun JSONObject.strictString(key: String): String =
    get(key) as? String ?: throw IllegalArgumentException("更新清单中的 $key 必须是文本")

enum class UpdatePhase { ENQUEUING, DOWNLOADING, VERIFYING, READY, FAILED }

data class ConfirmedUpdate(
    val manifest: UpdateManifest,
    val token: String,
    val downloadId: Long? = null,
    val phase: UpdatePhase = UpdatePhase.ENQUEUING,
    val error: String? = null,
)

data class UpdateUiState(
    val checking: Boolean = false,
    val preparingInstall: Boolean = false,
    val cancelling: Boolean = false,
    val available: UpdateManifest? = null,
    val promptVisible: Boolean = false,
    val taskVisible: Boolean = false,
    val task: ConfirmedUpdate? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val downloadMessage: String? = null,
    val message: String? = null,
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
)

interface UpdateStorage {
    var lastAttempt: Long
    var lastSuccess: Long
    var deferredVersion: Long
    var deferredUntil: Long
    fun load(): ConfirmedUpdate?
    fun save(task: ConfirmedUpdate?)
}

enum class DownloadStatus { RUNNING, COMPLETE, FAILED, MISSING }
data class DownloadSnapshot(val status: DownloadStatus, val downloaded: Long = 0, val total: Long = 0,
    val message: String? = null)

interface UpdatePlatform {
    val sdkInt: Int
    fun installedVersion(): Long
    fun enqueue(manifest: UpdateManifest, token: String): Long
    fun findDownload(token: String): Long?
    fun status(id: Long): DownloadSnapshot
    fun removeDownload(id: Long)
    fun cleanup(token: String)
    fun cleanupOrphans(activeToken: String?) {}
    suspend fun verify(task: ConfirmedUpdate)
}

fun updateAttemptDue(now: Long, lastAttempt: Long): Boolean =
    lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= UpdateIntervalMillis
