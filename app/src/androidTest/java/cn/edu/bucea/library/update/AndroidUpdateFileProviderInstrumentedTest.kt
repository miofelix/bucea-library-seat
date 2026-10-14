package cn.edu.bucea.library.update

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidUpdateFileProviderInstrumentedTest {
    private lateinit var environment: IsolatedUpdateEnvironment

    @Before fun createEnvironment() { environment = IsolatedUpdateEnvironment() }
    @After fun removeEnvironment() { environment.close() }

    @Test fun configuredProviderExposesOnlyInternalInstallRootAndReadableApkBytes() {
        val file = environment.providerFile()
        val uri = FileProvider.getUriForFile(environment.target, authority(), file)
        assertEquals("content", uri.scheme)
        assertEquals(authority(), uri.authority)
        assertEquals("verified_updates", uri.pathSegments.first())
        assertEquals("application/vnd.android.package-archive", environment.target.contentResolver.getType(uri))
        val bytes = environment.target.contentResolver.openInputStream(uri).use { input -> requireNotNull(input).readBytes() }
        assertArrayEquals(file.readBytes(), bytes)
        val provider = requireNotNull(environment.target.packageManager.resolveContentProvider(authority(), 0))
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertTrue("Android installer must resolve a granted APK URI",
            environment.target.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 0).isNotEmpty())
    }

    @Test fun privateTaskAccountFilesCacheAndExternalDownloadsCannotBeShared() {
        for (file in listOf(
            File(environment.target.filesDir, "updates/task.json"),
            File(environment.target.filesDir, "private-account-record"),
            File(environment.target.cacheDir, "fixture.apk"),
            File(environment.target.getExternalFilesDir(null), "updates/fixture.apk"),
            File(environment.target.filesDir, "updates/install/../task.json"),
            File(environment.target.filesDir, "updates/install-elsewhere/fixture.apk"),
        )) {
            val failure = try { FileProvider.getUriForFile(environment.target, authority(), file); null }
                catch (error: IllegalArgumentException) { error }
            assertNotNull("FileProvider must reject $file", failure)
        }
    }

    @Test fun encodedPathTraversalCannotReadOutsideConfiguredInstallRoot() {
        val file = environment.providerFile()
        val uri = FileProvider.getUriForFile(environment.target, authority(), file)
        val traversal = uri.buildUpon().encodedPath("/verified_updates/%2E%2E%2Ftask.json").build()
        rejectsTraversal(traversal)
        rejectsTraversal(uri.buildUpon().encodedPath("/verified_updates/%2E%2E%2F%2E%2E%2Fprivate-account-record").build())
    }

    private fun rejectsTraversal(uri: Uri) {
        val failure = try { environment.target.contentResolver.openFileDescriptor(uri, "r")?.close(); null }
            catch (error: SecurityException) { error }
            catch (error: IllegalArgumentException) { error }
        assertNotNull("Escaping URI must be rejected before any file access", failure)
    }

    private fun authority(): String = "${environment.target.packageName}.updates"
}
