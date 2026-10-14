package cn.edu.bucea.library.update

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidUpdateArchiveInstrumentedTest {
    private lateinit var environment: IsolatedUpdateEnvironment

    @Before fun createEnvironment() { environment = IsolatedUpdateEnvironment() }
    @After fun removeEnvironment() { environment.close() }

    @Test fun packageManagerAcceptsHigherNonDebuggableSameSignatureUniversalApk() = runBlocking {
        val fixture = environment.fixture()
        val info = environment.archive(fixture)
        val manifest = environment.manifest(fixture)
        assertEquals(environment.target.packageName, info.packageName)
        assertTrue("Fixture must be newer than the actual installed app", manifest.versionCode > environment.platform.installedVersion())
        assertTrue(manifest.minSdk <= Build.VERSION.SDK_INT)
        assertEquals(0, requireNotNull(info.applicationInfo).flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue(info.splitNames.isNullOrEmpty())
        val task = environment.task(manifest)
        val ready = environment.ready(task)
        environment.platform.verify(task)
        assertEquals(manifest.sha256, digest(ready))
    }

    @Test fun truncatedApkFailsBeforePackageParsing() = runBlocking {
        val task = environment.task()
        val ready = environment.ready(task)
        java.io.RandomAccessFile(ready, "rw").use { it.setLength(ready.length() - 1) }
        rejects(task, "更新文件大小不匹配")
    }

    @Test fun digestMismatchRejectsSameLengthModifiedApk() = runBlocking {
        val task = environment.task()
        val ready = environment.ready(task)
        java.io.RandomAccessFile(ready, "rw").use { file ->
            val value = file.readByte()
            file.seek(0)
            file.writeByte(value.toInt() xor 1)
        }
        assertEquals(task.manifest.size, ready.length())
        rejects(task, "更新文件摘要校验失败")
    }

    @Test fun nonApkBytesWithMatchingSizeAndDigestAreRejectedByPackageManager() = runBlocking {
        val source = File(environment.root, "not-an-apk").apply { writeText("Synthetic bytes with a valid digest") }
        val manifest = environment.manifest().copy(size = source.length(), sha256 = digest(source))
        val task = environment.task(manifest)
        environment.ready(task, source)
        rejects(task, "无法读取 APK 或其签名")
    }

    @Test fun wrongPackageRealApkIsRejectedEvenWithMatchingDigest() = runBlocking {
        val source = environment.fixture("wrong-package")
        assertNotEquals(environment.target.packageName, environment.archive(source).packageName)
        val task = environment.task(environment.manifest(source))
        environment.ready(task, source)
        rejects(task, "APK 包名不匹配")
    }

    @Test fun wrongRealApkVersionCodeAndVersionNameAreRejected() = runBlocking {
        val original = environment.manifest()
        for (manifest in listOf(original.copy(versionCode = original.versionCode + 1),
            original.copy(versionName = original.versionName + "-unexpected"))) {
            val task = environment.task(manifest)
            environment.ready(task)
            rejects(task, "APK 版本不匹配")
        }
    }

    @Test fun wrongMinimumSdkInManifestIsRejectedAgainstRealApkMetadata() = runBlocking {
        val original = environment.manifest()
        val task = environment.task(original.copy(minSdk = original.minSdk - 1))
        environment.ready(task)
        rejects(task, "APK 最低 Android 版本不匹配")
    }

    @Test fun unsupportedAndroidVersionIsRejectedBeforeOpeningDownload() = runBlocking {
        val task = environment.task(environment.manifest().copy(minSdk = Build.VERSION.SDK_INT + 1))
        rejects(task, "当前 Android 版本不支持此更新")
    }

    @Test fun alreadyInstalledVersionIsRejectedBeforeOpeningDownload() = runBlocking {
        val task = environment.task(environment.manifest().copy(versionCode = environment.platform.installedVersion()))
        rejects(task, "此更新版本已安装")
    }

    @Test fun debuggableRealApkIsRejectedEvenWhenSignedWithInstalledCertificate() = runBlocking {
        val source = environment.fixture("debuggable")
        assertTrue(requireNotNull(environment.archive(source).applicationInfo).flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val task = environment.task(environment.manifest(source))
        environment.ready(task, source)
        rejects(task, "更新 APK 未关闭调试")
    }

    @Test fun wrongSignatureRealApkIsRejectedAfterPackageMetadataValidation() = runBlocking {
        val source = environment.fixture("wrong-signature")
        val task = environment.task(environment.manifest(source))
        environment.ready(task, source)
        rejects(task, "APK 签名与当前应用不一致")
    }

    @Test fun missingDownloadCannotProduceAnInstallableInternalFile() = runBlocking {
        val task = environment.task().copy(downloadId = Long.MAX_VALUE)
        rejects(task, "下载尚未完成")
        assertFalse(environment.platform.readyFile(task).exists())
        assertFalse(File(environment.files, "updates/install/${task.token}.part").exists())
    }

    private suspend fun rejects(task: ConfirmedUpdate, expectedMessage: String) {
        val error = try { environment.platform.verify(task); null } catch (failure: IllegalArgumentException) { failure }
        assertNotNull("Invalid APK must be rejected: $expectedMessage", error)
        assertTrue("Expected '$expectedMessage', got '${error?.message}'", error?.message.orEmpty().contains(expectedMessage))
    }
}
