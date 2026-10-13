package cn.edu.bucea.library.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.BitmapFactory
import java.io.Closeable
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Offline port of ddddocr's default common_old.onnx OCR pipeline. */
class DdddOcr(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var session: OrtSession? = null
    private var charset: List<String>? = null
    private var closed = false

    /** Model loading, image processing and inference all run away from the UI thread. */
    suspend fun recognize(image: ByteArray): String = withContext(Dispatchers.Default) {
        synchronized(lock) {
            check(!closed) { "验证码识别器已关闭" }
            require(image.isNotEmpty() && image.size <= MAX_IMAGE_BYTES) { "验证码图片无效" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取验证码图片" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) {
                "验证码图片尺寸过大"
            }
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image, 0, image.size)) {
                "无法读取验证码图片"
            }
            val input = try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                LanczosPreprocessor.process(pixels, bitmap.width, bitmap.height)
            } finally {
                bitmap.recycle()
            }
            val activeSession = session ?: createSession().also { session = it }
            val characters = charset ?: loadCharset().also { charset = it }
            OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(input.pixels),
                longArrayOf(1, 1, 64, input.width.toLong()),
            ).use { tensor ->
                activeSession.run(mapOf(activeSession.inputNames.first() to tensor)).use { output ->
                    val logits = output[0] as? OnnxTensor
                        ?: error("验证码模型输出格式无效")
                    // The upstream model has stale output metadata. Inspect the runtime tensor.
                    val shape = (logits.info as TensorInfo).shape
                    require(shape.last() == characters.size.toLong()) { "验证码模型字符集不匹配" }
                    require(shape.size == 2 ||
                        (shape.size == 3 && (shape[0] == 1L || shape[1] == 1L))) {
                        "验证码模型输出维度无效"
                    }
                    CtcDecoder.decode(logits.floatBuffer, characters)
                }
            }
        }
    }

    private fun createSession(): OrtSession {
        val model = appContext.assets.open("ocr/common_old.onnx").use { it.readBytes() }
        return OrtSession.SessionOptions().use { options ->
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(2)
            OrtEnvironment.getEnvironment().createSession(model, options)
        }
    }

    private fun loadCharset(): List<String> {
        val content = appContext.assets.open("ocr/charset.json").bufferedReader().use { it.readText() }
        val array = JSONArray(content)
        return List(array.length()) { array.getString(it) }.also {
            require(it.isNotEmpty() && it.first().isEmpty()) { "验证码模型字符集无效" }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            session?.close()
            session = null
            charset = null
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_IMAGE_PIXELS = 4_000_000L
    }
}
