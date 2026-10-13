package cn.edu.bucea.library.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import cn.edu.bucea.library.data.models.VerificationChallenge
import cn.edu.bucea.library.data.models.VerificationPoint
import java.io.ByteArrayOutputStream
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** Ordered click solving uses only the bundled ddddocr detection and recognition models. */
class DdddClickSolver(context: Context) : Closeable {
    private val recognizer = DdddOcr(context)
    private val detector = DdddDetector(context)

    suspend fun solve(challenge: VerificationChallenge): List<VerificationPoint> = withContext(Dispatchers.Default) {
        val targets = recognizer.recognize(challenge.wordImage).filterNot { it.isWhitespace() }.map { it.toString() }
        require(targets.size == challenge.requiredPoints) { "目标文字未识别完整，请刷新验证码" }
        val boxes = detector.detect(challenge.image)
        require(boxes.size in challenge.requiredPoints..16) { "验证码文字位置未识别完整，请刷新验证码" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(challenge.image, 0, challenge.image.size))
        try {
            val results = boxes.map { box ->
                coroutineContext.ensureActive()
                val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.right - box.left, box.bottom - box.top)
                try {
                    val isolated = isolateGlyph(crop)
                    val masked = try { recognizer.recognize(png(isolated)) } finally { isolated.recycle() }
                    val raw = if (targets.any { it in masked }) "" else recognizer.recognize(png(crop))
                    box to (masked + raw)
                } finally { crop.recycle() }
            }
            val used = mutableSetOf<Int>()
            targets.map { target ->
                val matches = results.indices.filter { it !in used && target in results[it].second }
                require(matches.size == 1) { "验证码文字识别不明确，请刷新验证码" }
                val index = matches.single(); used += index
                val box = results[index].first
                VerificationPoint((box.left + box.right) / 2.0 / bitmap.width * challenge.width,
                    (box.top + box.bottom) / 2.0 / bitmap.height * challenge.height)
            }
        } finally { bitmap.recycle() }
    }

    private fun isolateGlyph(source: Bitmap): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val histogram = mutableMapOf<Int, Int>()
        for (pixel in pixels) {
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            val high = max(r, max(g, b)); val low = min(r, min(g, b))
            if (high - low > 70 && low < high * 0.25) {
                val bucket = (r / 16 shl 8) or (g / 16 shl 4) or (b / 16)
                histogram[bucket] = (histogram[bucket] ?: 0) + 1
            }
        }
        val selected = histogram.maxByOrNull { it.value }?.key ?: return pad(source)
        val red = (selected ushr 8) * 16 + 7
        val green = ((selected ushr 4) and 15) * 16 + 7
        val blue = (selected and 15) * 16 + 7
        val result = Bitmap.createBitmap(source.width + 12, source.height + 12, Bitmap.Config.ARGB_8888)
        result.eraseColor(Color.WHITE)
        val output = IntArray(pixels.size) { index ->
            val p = pixels[index]
            val dr = Color.red(p) - red; val dg = Color.green(p) - green; val db = Color.blue(p) - blue
            if (dr * dr + dg * dg + db * db < 85 * 85) Color.BLACK else Color.WHITE
        }
        result.setPixels(output, 0, source.width, 6, 6, source.width, source.height)
        return result
    }

    private fun pad(source: Bitmap): Bitmap = Bitmap.createBitmap(source.width + 12, source.height + 12, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        setPixels(pixels, 0, source.width, 6, 6, source.width, source.height)
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
    }

    override fun close() { detector.close(); recognizer.close() }
}
