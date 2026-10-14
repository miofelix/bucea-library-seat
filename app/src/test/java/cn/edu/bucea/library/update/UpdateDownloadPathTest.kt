package cn.edu.bucea.library.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateDownloadPathTest {
    @get:Rule val temporary = TemporaryFolder()
    private val token = "11111111-2222-3333-4444-555555555555"

    @Test fun onlyFilesInTheManagedDownloadDirectoryCanBeAdoptedOrRemoved() {
        val managed = temporary.newFolder("updates")
        val other = temporary.newFolder("other")
        val name = "download-$token.apk"
        // A pending DownloadManager destination can be identified before the file exists.
        assertEquals(token, updateDownloadToken(File(managed, name).toURI().toString(), managed))
        assertNull(updateDownloadToken(File(other, name).toURI().toString(), managed))
        assertNull(updateDownloadToken(File(File(managed, "nested"), name).toURI().toString(), managed))
        assertNull(updateDownloadToken(File(managed, "../other/$name").toURI().toString(), managed))
    }

    @Test fun nonFileUrisAndUnmanagedNamesCannotClaimUpdaterOwnership() {
        val managed = temporary.newFolder("updates with spaces")
        val uri = File(managed, "download-$token.apk").toURI().toString()
        assertEquals(token, updateDownloadToken(uri, managed))
        for (unmanaged in listOf(null, "", "content://downloads/$token", "https://example.test/download-$token.apk",
            "$uri?query=1", "$uri#fragment", File(managed, "download-${"-".repeat(36)}.apk").toURI().toString(),
            File(managed, "another.apk").toURI().toString())) {
            assertNull(updateDownloadToken(unmanaged, managed))
        }
    }
}
