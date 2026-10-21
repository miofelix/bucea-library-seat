package cn.edu.bucea.library.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest

class AndroidUpdatePlatform(private val context: Context) : UpdatePlatform {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val internal = File(context.filesDir, "updates/install")
    private val externalOrNull: File? get() = context.getExternalFilesDir(null)?.let { File(it, "updates") }
    private val external: File get() = externalOrNull
        ?: throw IllegalStateException("无法访问下载目录，请检查存储后重试")
    override val sdkInt get() = Build.VERSION.SDK_INT
    private val signingFlags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlags)

    override fun installedVersion(): Long = versionCode(installed())

    @Suppress("DEPRECATION")
    override fun enqueue(manifest: UpdateManifest, token: String): Long {
        // Reuse full manifest validation before passing a URL to DownloadManager.
        UpdateManifest.parse(manifest.toJson(), context.packageName)
        external.mkdirs()
        internal.mkdirs()
        // Reserve room for both the DownloadManager file and its verified internal copy.
        require(StatFs(context.filesDir.path).availableBytes >= manifest.size * 2 + 64 * 1024 * 1024 &&
            StatFs(external.path).availableBytes >= manifest.size + 32 * 1024 * 1024) { "存储空间不足，请清理后重试" }
        return manager.enqueue(DownloadManager.Request(Uri.parse(manifest.url))
            .setTitle("北建大图书馆选座 ${manifest.displayVersion}")
            .setDescription("应用更新")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(false)
            // The completed APK must be offered through our verification/install flow.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setVisibleInDownloadsUi(false)
            .setDestinationInExternalFilesDir(context, "updates", downloadName(token)))
    }

    override fun findDownload(token: String): Long? {
        val directory = externalOrNull ?: return null
        return manager.query(DownloadManager.Query())?.use { cursor ->
            val uri = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (cursor.moveToNext()) {
                if (updateDownloadToken(cursor.getString(uri), directory) == token) return@use cursor.getLong(id)
            }
            null
        }
    }

    override fun status(id: Long): DownloadSnapshot = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) return@use DownloadSnapshot(DownloadStatus.MISSING)
        val systemStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        val status = when (systemStatus) {
            DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETE
            DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
            else -> DownloadStatus.RUNNING
        }
        val message = when (systemStatus) {
            DownloadManager.STATUS_PENDING -> "正在等待开始下载"
            DownloadManager.STATUS_PAUSED -> when (reason) {
                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "正在等待网络连接，恢复后自动继续"
                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "正在等待 Wi-Fi 连接"
                else -> "下载暂时中断，系统将自动重试"
            }
            DownloadManager.STATUS_FAILED -> when (reason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请清理后重试"
                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载目录不可用，请检查存储后重试"
                else -> "下载失败，请检查网络后重试"
            }
            else -> null
        }
        DownloadSnapshot(status, cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), message)
    } ?: DownloadSnapshot(DownloadStatus.MISSING)

    override fun removeDownload(id: Long) { manager.remove(id) }

    fun readyFile(task: ConfirmedUpdate): File = File(internal, "${task.token}.apk")

    override fun cleanup(token: String) {
        externalOrNull?.let { File(it, downloadName(token)).delete() }
        File(internal, "$token.apk").delete()
        File(internal, "$token.part").delete()
    }

    override fun cleanupOrphans(activeToken: String?) {
        val ids = mutableListOf<Long>()
        val directory = externalOrNull
        if (directory != null) {
            manager.query(DownloadManager.Query())?.use { cursor ->
                while (cursor.moveToNext()) {
                    val token = updateDownloadToken(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                        directory) ?: continue
                    if (token != activeToken) ids += cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        ids.forEach(::removeDownload)
        directory?.listFiles()?.filter { it.name != activeToken?.let(::downloadName) }?.forEach { it.delete() }
        internal.listFiles()?.filter { it.name != "$activeToken.apk" && it.name != "$activeToken.part" }?.forEach { it.delete() }
    }

    override suspend fun verify(task: ConfirmedUpdate): Unit = withContext(Dispatchers.IO) {
        val manifest = task.manifest
        require(manifest.versionCode > installedVersion()) { "此更新版本已安装" }
        require(manifest.minSdk <= sdkInt) { "当前 Android 版本不支持此更新" }
        val target = readyFile(task)
        if (!target.exists()) {
            internal.mkdirs()
            require(StatFs(internal.path).availableBytes >= manifest.size + 32 * 1024 * 1024) { "存储空间不足，请清理后重试" }
            val id = requireNotNull(task.downloadId) { "下载文件已丢失，请重新下载" }
            require(status(id).status == DownloadStatus.COMPLETE) { "下载尚未完成" }
            val scratch = File(internal, "${task.token}.part")
            scratch.delete()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { input ->
                    FileOutputStream(scratch).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= manifest.size) { "更新文件大小不匹配" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                verifyArchive(scratch, manifest)
                check(scratch.renameTo(target)) { "无法保存安装文件" }
            } finally {
                scratch.delete()
            }
        } else verifyArchive(target, manifest)
    }

    @Suppress("DEPRECATION")
    private suspend fun verifyArchive(file: File, manifest: UpdateManifest) {
        require(file.length() == manifest.size) { "更新文件大小不匹配" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().hex() == manifest.sha256) { "更新文件摘要校验失败，请重新下载" }
        currentCoroutineContext().ensureActive()
        val archive = context.packageManager.getPackageArchiveInfo(file.path, signingFlags)
            ?: throw IllegalArgumentException("无法读取 APK 或其签名")
        require(archive.packageName == context.packageName && archive.packageName == manifest.packageName) { "APK 包名不匹配" }
        require(versionCode(archive) == manifest.versionCode && archive.versionName == manifest.versionName) { "APK 版本不匹配" }
        val application = requireNotNull(archive.applicationInfo) { "APK 缺少应用信息" }
        require(application.minSdkVersion == manifest.minSdk && application.minSdkVersion <= sdkInt) { "APK 最低 Android 版本不匹配" }
        require(application.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "更新 APK 未关闭调试" }
        require(archive.splitNames.isNullOrEmpty()) { "更新必须使用通用 APK" }
        require(sameSigningCertificates(certificates(installed()), certificates(archive))) { "APK 签名与当前应用不一致" }
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): List<ByteArray> = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
    } else info.signatures.orEmpty().map { it.toByteArray() }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun downloadName(token: String) = "download-$token.apk"
}

/** A matching basename elsewhere in this app's downloads does not belong to the updater. */
internal fun updateDownloadToken(localUri: String?, directory: File): String? = runCatching {
    if (localUri == null) return@runCatching null
    val file = File(URI(localUri)).canonicalFile
    if (file.parentFile != directory.canonicalFile) return@runCatching null
    Regex("download-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.apk")
        .matchEntire(file.name)?.groupValues?.get(1)
}.getOrNull()

internal fun sameSigningCertificates(installed: List<ByteArray>, candidate: List<ByteArray>): Boolean {
    if (installed.isEmpty() || candidate.isEmpty()) return false
    fun digests(values: List<ByteArray>) = values.map { MessageDigest.getInstance("SHA-256").digest(it).hex() }.toSet()
    return digests(installed) == digests(candidate)
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
