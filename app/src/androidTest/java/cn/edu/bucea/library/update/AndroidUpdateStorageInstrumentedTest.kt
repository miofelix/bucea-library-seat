package cn.edu.bucea.library.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidUpdateStorageInstrumentedTest {
    private lateinit var environment: IsolatedUpdateEnvironment

    @Before fun createEnvironment() { environment = IsolatedUpdateEnvironment() }
    @After fun removeEnvironment() { environment.close() }

    @Test fun timestampsDeferralAndEveryTaskPhaseSurviveNewStorageInstances() {
        environment.storage.lastAttempt = 1_234_000
        environment.storage.lastSuccess = 1_234_567
        environment.storage.deferredVersion = 1008
        environment.storage.deferredUntil = 1_234_567 + UpdateIntervalMillis
        val restored = AndroidUpdateStorage(environment.context)
        assertEquals(1_234_000L, restored.lastAttempt)
        assertEquals(1_234_567L, restored.lastSuccess)
        assertEquals(1008L, restored.deferredVersion)
        assertEquals(1_234_567 + UpdateIntervalMillis, restored.deferredUntil)
        for (phase in UpdatePhase.entries) {
            val task = environment.task(phase = phase).copy(downloadId = 123, error = "Synthetic failure")
            environment.storage.save(task)
            assertEquals(task, AndroidUpdateStorage(environment.context).load())
        }
    }

    @Test fun unassignedDownloadIdRoundTripsAndClearingDeletesAtomicFiles() {
        val task = environment.task(phase = UpdatePhase.ENQUEUING)
        environment.storage.save(task)
        assertEquals(task, AndroidUpdateStorage(environment.context).load())
        environment.storage.save(null)
        assertNull(AndroidUpdateStorage(environment.context).load())
        assertFalse(record().exists())
        assertFalse(File(record().path + ".bak").exists())
        assertFalse(File(record().path + ".new").exists())
    }

    @Test fun atomicFileRecoversPreviousConfirmedTaskAfterInterruptedWrite() {
        val confirmed = environment.task(phase = UpdatePhase.DOWNLOADING).copy(downloadId = 123)
        environment.storage.save(confirmed)
        assertTrue(record().renameTo(File(record().path + ".bak")))
        record().writeText("{\"interrupted\":")
        assertEquals(confirmed, AndroidUpdateStorage(environment.context).load())
        assertFalse(File(record().path + ".bak").exists())
    }

    @Test fun corruptOrUntrustedRecordsAreDeletedInsteadOfRestored() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("token", "../untrusted") },
            { it.put("downloadId", 0) },
            { it.put("downloadId", "123") },
            { it.put("phase", "INSTALLING") },
            { it.getJSONObject("manifest").put("packageName", "another.package") },
            { it.getJSONObject("manifest").getJSONObject("apk").put("url", "https://example.test/update.apk") },
        )
        for (mutation in mutations) {
            environment.storage.save(environment.task())
            record().writeText(JSONObject(record().readText()).also(mutation).toString())
            assertNull(AndroidUpdateStorage(environment.context).load())
            assertFalse("Untrusted task must be removed", record().exists())
        }
        record().writeText("truncated-json")
        assertNull(environment.storage.load())
        assertFalse(record().exists())
    }

    @Test fun isolatedStorageCannotModifyTheRealUpdatePreferencesOrTask() {
        val realPreferences = environment.target.getSharedPreferences("app_updates", 0)
        val before = realPreferences.all.toMap()
        val realTask = File(environment.target.filesDir, "updates/task.json")
        val originalTask = realTask.takeIf(File::exists)?.readBytes()
        environment.storage.lastAttempt = 55
        environment.storage.deferredVersion = 1008
        environment.storage.save(environment.task())
        environment.storage.save(null)
        assertEquals(before, realPreferences.all)
        if (originalTask == null) assertFalse(realTask.exists()) else assertArrayEquals(originalTask, realTask.readBytes())
    }

    private fun record(): File = File(environment.files, "updates/task.json")
}
