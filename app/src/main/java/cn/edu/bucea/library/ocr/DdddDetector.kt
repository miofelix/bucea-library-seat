package cn.edu.bucea.library.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.BitmapFactory
import java.io.Closeable
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Port of ddddocr common_det.onnx YOLOX preprocessing, score filtering and NMS. */
class DdddDetector(context: Context) : Closeable {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class Detection(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float)
    private val assets = context.applicationContext.assets
    private val lock = Any()
    private var session: OrtSession? = null
    private var closed = false

    suspend fun detect(image: ByteArray): List<Box> = withContext(Dispatchers.Default) {
        synchronized(lock) {
            check(!closed)
            require(image.isNotEmpty() && image.size <= 4 * 1024 * 1024)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image, 0, image.size)) { "无法读取验证码图片" }
            try {
                require(bitmap.width.toLong() * bitmap.height <= 4_000_000)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val ratio = min(416.0 / bitmap.width, 416.0 / bitmap.height)
                val width = (bitmap.width * ratio).toInt()
                val height = (bitmap.height * ratio).toInt()
                val input = FloatArray(3 * 416 * 416) { 114f }
                // OpenCV's half-pixel linear interpolation; preserve BGR channel order.
                for (y in 0 until height) {
                    val sy = (y + 0.5) * bitmap.height / height - 0.5
                    val y0 = floor(sy).toInt().coerceIn(0, bitmap.height - 1)
                    val y1 = (floor(sy).toInt() + 1).coerceIn(0, bitmap.height - 1)
                    val dy = (sy - floor(sy)).coerceIn(0.0, 1.0)
                    for (x in 0 until width) {
                        val sx = (x + 0.5) * bitmap.width / width - 0.5
                        val x0 = floor(sx).toInt().coerceIn(0, bitmap.width - 1)
                        val x1 = (floor(sx).toInt() + 1).coerceIn(0, bitmap.width - 1)
                        val dx = (sx - floor(sx)).coerceIn(0.0, 1.0)
                        for (channel in 0..2) {
                            fun value(xx: Int, yy: Int) = (pixels[yy * bitmap.width + xx] ushr (channel * 8)) and 255
                            val upper = value(x0, y0) * (1 - dx) + value(x1, y0) * dx
                            val lower = value(x0, y1) * (1 - dx) + value(x1, y1) * dx
                            input[channel * 416 * 416 + y * 416 + x] = (upper * (1 - dy) + lower * dy + 0.5).toInt().toFloat()
                        }
                    }
                }
                val active = session ?: OrtSession.SessionOptions().use { options ->
                    options.setInterOpNumThreads(1); options.setIntraOpNumThreads(2)
                    OrtEnvironment.getEnvironment().createSession(assets.open("ocr/common_det.onnx").use { it.readBytes() }, options)
                }.also { session = it }
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input), longArrayOf(1, 3, 416, 416)).use { tensor ->
                    active.run(mapOf(active.inputNames.first() to tensor)).use { output ->
                        val values = (output[0] as OnnxTensor).floatBuffer
                        val candidates = mutableListOf<Detection>()
                        for (stride in listOf(8, 16, 32)) {
                            for (y in 0 until 416 / stride) for (x in 0 until 416 / stride) {
                                val cx = (values.get() + x) * stride
                                val cy = (values.get() + y) * stride
                                val bw = exp(values.get()) * stride
                                val bh = exp(values.get()) * stride
                                val score = values.get() * values.get()
                                if (score > 0.1f) candidates += Detection(((cx - bw / 2) / ratio).toFloat(), ((cy - bh / 2) / ratio).toFloat(),
                                    ((cx + bw / 2) / ratio).toFloat(), ((cy + bh / 2) / ratio).toFloat(), score)
                            }
                        }
                        val kept = mutableListOf<Detection>()
                        for (candidate in candidates.sortedByDescending { it.score }) {
                            if (kept.none { overlap(candidate, it) > 0.45f }) kept += candidate
                        }
                        kept.map { Box(it.left.toInt().coerceIn(0, bitmap.width), it.top.toInt().coerceIn(0, bitmap.height),
                            it.right.toInt().coerceIn(0, bitmap.width), it.bottom.toInt().coerceIn(0, bitmap.height)) }
                            .filter { it.right > it.left && it.bottom > it.top }
                    }
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun overlap(a: Detection, b: Detection): Float {
        val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left) + 1) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top) + 1)
        val areaA = (a.right - a.left + 1) * (a.bottom - a.top + 1)
        val areaB = (b.right - b.left + 1) * (b.bottom - b.top + 1)
        return intersection / (areaA + areaB - intersection)
    }

    override fun close() { synchronized(lock) { closed = true; session?.close(); session = null } }
}
