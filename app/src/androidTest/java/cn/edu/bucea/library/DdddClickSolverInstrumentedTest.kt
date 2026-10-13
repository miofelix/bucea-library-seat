package cn.edu.bucea.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bucea.library.data.models.VerificationChallenge
import cn.edu.bucea.library.ocr.DdddClickSolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Captcha pixels only: fixtures contain no credentials, cookies or proof tokens. */
@RunWith(AndroidJUnit4::class)
class DdddClickSolverInstrumentedTest {
    @Test
    fun offlineDetectorAndOcrLocateTargetsOnRealSchoolClickCaptchas() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtures = listOf(Triple("fa", 21.5, 86.0), Triple("gai", 95.5, 82.0),
            Triple("ye", 243.0, 42.5), Triple("di", 83.0, 41.0))
        DdddClickSolver(instrumentation.targetContext).use { solver ->
            for ((name, x, y) in fixtures) {
                fun image(type: String) = instrumentation.context.assets.open("click/$name-$type.jpg").use { it.readBytes() }
                val challenge = VerificationChallenge("fake", "synthetic-token", 1, image("image"), image("word"), System.currentTimeMillis())
                val points = solver.solve(challenge)
                assertEquals(1, points.size)
                assertEquals("$name x", x, points.single().x, 3.0)
                assertEquals("$name y", y, points.single().y, 3.0)
            }
        }
    }
}
