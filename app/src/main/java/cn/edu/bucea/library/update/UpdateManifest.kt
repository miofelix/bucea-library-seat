package cn.edu.bucea.library.update

import org.json.JSONObject

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
        fun parse(json: JSONObject, expectedPackage: String): UpdateManifest {
            require(json.strictLong("schemaVersion") == 1L) { "更新清单格式不受支持" }
            val apk = json.getJSONObject("apk")
            val manifest = UpdateManifest(
                json.strictString("packageName"), json.strictLong("versionCode"), json.strictString("versionName"),
                json.strictLong("minSdk").also { require(it in 1..1000) }.toInt(), json.strictString("notes"),
                apk.strictLong("assetId"), apk.strictString("name"), apk.strictLong("size"),
                apk.strictString("sha256"), apk.strictString("url"),
                (json.get("prerelease") as? Boolean) ?: error("更新清单缺少版本类型"), json.strictString("releaseTag"),
            )
            require(manifest.packageName == expectedPackage) { "更新包名不匹配" }
            require(manifest.versionCode in 1..2_100_000_000L && manifest.assetId > 0) { "更新版本无效" }
            require(manifest.versionName.length in 1..100 &&
                manifest.releaseTag.matches(Regex("v[0-9][A-Za-z0-9._-]{0,198}")) &&
                manifest.notes.length <= 16000) { "更新说明无效" }
            require(manifest.size in 1..MaxUpdateApkBytes && manifest.sha256.matches(Regex("[0-9a-f]{64}"))) {
                "更新文件校验信息无效"
            }
            require(manifest.apkName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.apk"))) { "更新文件名无效" }
            // Exact comparison also rejects user-info, alternate ports, queries, fragments,
            // encoded paths and lookalike/subdomains. Only the project HTTPS origin is allowed.
            require(manifest.url == updateApkUrl(manifest.releaseTag, manifest.apkName)) { "更新下载地址无效" }
            return manifest
        }
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
