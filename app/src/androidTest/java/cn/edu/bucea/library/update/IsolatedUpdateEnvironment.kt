package cn.edu.bucea.library.update

import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real Android services with unique test files and preferences, never the user's update state. */
internal class IsolatedUpdateEnvironment {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val target: Context = instrumentation.targetContext
    val name = "instrumentation_updates_${UUID.randomUUID()}"
    val root = File(target.cacheDir, name).apply { check(mkdirs()) }
    val files = File(root, "files").apply { check(mkdirs()) }
    val external = File(requireNotNull(target.getExternalFilesDir(null)), name).apply { check(mkdirs()) }
    val context = object : ContextWrapper(target) {
        override fun getFilesDir(): File = files
        override fun getExternalFilesDir(type: String?): File =
            if (type == null) external else File(external, type).apply { mkdirs() }
        override fun getSharedPreferences(prefName: String, mode: Int): SharedPreferences {
            require(prefName == "app_updates")
            return target.getSharedPreferences(name, mode)
        }
    }
    val platform = AndroidUpdatePlatform(context)
    val manager: DownloadManager = target.getSystemService(DownloadManager::class.java)
    val storage = AndroidUpdateStorage(context)
    val registeredDownloads = mutableSetOf<Long>()
    private val sharedProviderFiles = mutableListOf<File>()

    @Suppress("DEPRECATION")
    fun archive(file: File): PackageInfo = requireNotNull(target.packageManager.getPackageArchiveInfo(
        file.path,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
    )) { "Android PackageManager cannot parse ${file.name}" }

    fun fixture(name: String = "valid"): File = File(root, "$name.apk").also { output ->
        if (!output.exists()) instrumentation.context.assets.open("update-fixtures/$name.apk").use { input ->
            output.outputStream().use { stream -> input.copyTo(stream) }
        }
    }

    @Suppress("DEPRECATION")
    fun manifest(file: File = fixture()): UpdateManifest {
        val info = archive(file)
        return UpdateManifest(
            packageName = target.packageName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            versionName = requireNotNull(info.versionName),
            minSdk = requireNotNull(info.applicationInfo).minSdkVersion,
            notes = "Synthetic Android update fixture",
            assetId = 2_147_483_647L,
            apkName = "instrumentation-fixture.apk",
            size = file.length(),
            sha256 = digest(file),
            url = updateApkUrl("v0-instrumentation-only", "instrumentation-fixture.apk"),
            prerelease = true,
            releaseTag = "v0-instrumentation-only",
        )
    }

    fun task(manifest: UpdateManifest = manifest(), phase: UpdatePhase = UpdatePhase.READY): ConfirmedUpdate =
        ConfirmedUpdate(manifest, UUID.randomUUID().toString(), phase = phase)

    fun ready(task: ConfirmedUpdate, source: File = fixture()): File = platform.readyFile(task).also { output ->
        output.parentFile?.mkdirs()
        source.copyTo(output, overwrite = true)
    }

    /**
     * DownloadProvider registers a real completed row, including a readable file descriptor.
     * Its deprecated API maps showNotification=false to hidden visibility, which needs
     * DOWNLOAD_WITHOUT_NOTIFICATION on current Android. Visible fixture notifications are
     * allowed under the production permission set and removed with their rows in close().
     */
    @Suppress("DEPRECATION")
    fun completedDownload(task: ConfirmedUpdate, source: File = fixture(), directory: File = File(external, "updates")): Long {
        check(directory.mkdirs() || directory.isDirectory)
        val download = File(directory, "download-${task.token}.apk")
        source.copyTo(download, overwrite = true)
        return manager.addCompletedDownload("Synthetic update fixture", "Instrumentation only", false,
            "application/vnd.android.package-archive", download.absolutePath, download.length(), true)
            .also { registeredDownloads += it }
    }

    fun trackDownload(id: Long): Long = id.also { registeredDownloads += it }

    /** The actual FileProvider uses the actual target application's configured directory. */
    fun providerFile(): File = File(target.filesDir, "updates/install/$name.apk").also { file ->
        file.parentFile?.mkdirs()
        file.writeText("Synthetic FileProvider update bytes")
        sharedProviderFiles += file
    }

    fun close() {
        registeredDownloads.forEach { manager.remove(it) }
        sharedProviderFiles.forEach { it.delete() }
        target.deleteSharedPreferences(name)
        assertTrue("Only the unique instrumentation cache directory is removed", root.deleteRecursively())
        assertTrue("Only the unique instrumentation external directory is removed", external.deleteRecursively())
    }
}

internal fun digest(file: File): String {
    val sha256 = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            sha256.update(buffer, 0, count)
        }
    }
    return sha256.digest().joinToString("") { "%02x".format(it) }
}
