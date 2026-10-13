package cn.edu.bucea.library

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Inspect the installed production APK, rather than source text or the instrumentation package. */
@RunWith(AndroidJUnit4::class)
class NativeArchitectureInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test fun applicationHasOnlyTheNativeMainActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
        assertEquals(listOf(MainActivity::class.java.name), packageInfo.activities.orEmpty().map { it.name }.sorted())
        assertTrue(packageInfo.activities.orEmpty().single().exported)
    }

    @Test fun applicationAssetsContainModelsWithoutEmbeddedWebPagesOrScripts() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val files = mutableListOf<String>()
        fun inspect(path: String) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                if (path.isNotBlank()) files += path
                return
            }
            children.forEach { name -> inspect(if (path.isBlank()) name else "$path/$name") }
        }
        inspect("")
        assertTrue("The packaged OCR model must remain available", files.any { it.endsWith(".onnx", ignoreCase = true) })
        val webFiles = files.filter { path -> path.substringAfterLast('.', "").lowercase() in
            setOf("html", "htm", "js", "mjs", "css") }
        assertEquals("Native APK must not package webpage UI or JavaScript adapters", emptyList<String>(), webFiles)
        assertFalse(files.any { it.startsWith("mobile/") })
    }
}
