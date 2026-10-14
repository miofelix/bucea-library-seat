package cn.edu.bucea.library

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.update.UpdateHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read only public HTTPS endpoint check through the production update client's TLS/parser. */
@RunWith(AndroidJUnit4::class)
class LiveUpdateEndpointTest {
    @Test fun actualUpdateClientFetchesPublicManifest() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Public update endpoint check needs live_update_endpoint=true",
            arguments.getString("live_update_endpoint") == "true")
        val expectedVersion = arguments.getString("expected_version_code")?.toLong() ?: 9L
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            val manifest = UpdateHttpClient(context.packageName).fetch()
            assertEquals("Public update manifest must identify the installed package", context.packageName, manifest.packageName)
            assertEquals("Public update manifest must identify the expected release", expectedVersion, manifest.versionCode)
            if (expectedVersion == 9L) assertEquals("0.3.4", manifest.versionName)
            Log.i(LogTag, "Production client parsed public manifest code=${manifest.versionCode} version=${manifest.versionName} bytes=${manifest.size}")
        } catch (failure: Throwable) {
            // The isolated update client has no account/campus request or credentials.
            // Log only its public-endpoint failure type/message, including nested TLS causes.
            var cause: Throwable? = failure
            var depth = 0
            while (cause != null && depth < 8) {
                Log.e(LogTag, "Public update failure[$depth] ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            throw failure
        }
    }

    companion object {
        private const val LogTag = "UpdateEndpointTest"
    }
}
