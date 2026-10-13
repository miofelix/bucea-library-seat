package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.ocr.DdddOcr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineOcrInstrumentedTest {
    @Test
    fun recognizesRealLibraryCaptchaTwiceOnDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.context.assets.open("captcha.png").use { it.readBytes() }
        DdddOcr(instrumentation.targetContext).use { recognizer ->
            assertEquals("pme8", recognizer.recognize(image))
            // A second call exercises the reused native model/session, without network access.
            assertEquals("pme8", recognizer.recognize(image))
        }
    }
}
