package cn.edu.bucea.library.ocr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pillow's separable Lanczos3 resampling, followed by convert("L") and /255. */
internal object LanczosPreprocessor {
    data class Input(val pixels: FloatArray, val width: Int)
    private data class Filter(val first: Int, val coefficients: IntArray)

    fun process(argb: IntArray, sourceWidth: Int, sourceHeight: Int): Input {
        require(sourceWidth > 0 && sourceHeight > 0 && argb.size == sourceWidth * sourceHeight)
        val width = (sourceWidth * (64.0 / sourceHeight)).toInt()
        require(width in 1..4096) { "验证码图片比例无效" }
        // Pillow returns a copy for an unchanged size, so premultiply only when resizing.
        val transparent = sourceHeight != 64 && argb.any { (it ushr 24) != 255 }
        val channels = if (transparent) 4 else 3
        var pixels = IntArray(argb.size * channels)
        argb.forEachIndexed { index, color ->
            val alpha = color ushr 24
            for (channel in 0..2) {
                val value = (color ushr (16 - channel * 8)) and 255
                pixels[index * channels + channel] = if (transparent) {
                    (value * alpha + 127) / 255
                } else value
            }
            if (transparent) pixels[index * channels + 3] = alpha
        }
        if (width != sourceWidth) {
            val filters = filters(sourceWidth, width)
            val resized = IntArray(width * sourceHeight * channels)
            for (y in 0 until sourceHeight) {
                for (x in 0 until width) {
                    val filter = filters[x]
                    for (channel in 0 until channels) {
                        var value = ROUNDING
                        filter.coefficients.forEachIndexed { offset, coefficient ->
                            value += pixels[(y * sourceWidth + filter.first + offset) * channels + channel] *
                                coefficient
                        }
                        resized[(y * width + x) * channels + channel] = (value shr PRECISION).coerceIn(0, 255)
                    }
                }
            }
            pixels = resized
        }
        if (sourceHeight != 64) {
            val filters = filters(sourceHeight, 64)
            val resized = IntArray(width * 64 * channels)
            for (y in 0 until 64) {
                val filter = filters[y]
                for (x in 0 until width) {
                    for (channel in 0 until channels) {
                        var value = ROUNDING
                        filter.coefficients.forEachIndexed { offset, coefficient ->
                            value += pixels[((filter.first + offset) * width + x) * channels + channel] *
                                coefficient
                        }
                        resized[(y * width + x) * channels + channel] = (value shr PRECISION).coerceIn(0, 255)
                    }
                }
            }
            pixels = resized
        }
        return Input(FloatArray(width * 64) { index ->
            val base = index * channels
            fun channel(offset: Int): Int {
                val value = pixels[base + offset]
                if (!transparent) return value
                val alpha = pixels[base + 3]
                return if (alpha == 0 || alpha == 255) value else min(255, value * 255 / alpha)
            }
            // These are Pillow's integer ITU-R 601 conversion coefficients.
            val gray = (channel(0) * 19595 + channel(1) * 38470 + channel(2) * 7471 + 32768) shr 16
            gray / 255f
        }, width)
    }

    private fun filters(inputSize: Int, outputSize: Int): List<Filter> {
        val scale = inputSize.toDouble() / outputSize
        val filterScale = max(1.0, scale)
        val support = 3.0 * filterScale
        return List(outputSize) { position ->
            val center = (position + 0.5) * scale
            val first = max(0, (center - support + 0.5).toInt())
            val last = min(inputSize, (center + support + 0.5).toInt())
            val weights = DoubleArray(last - first) { offset ->
                lanczos((first + offset + 0.5 - center) / filterScale)
            }
            val total = weights.sum()
            Filter(first, IntArray(weights.size) { offset ->
                val value = weights[offset] / total * (1 shl PRECISION)
                (value + if (value < 0) -0.5 else 0.5).toInt()
            })
        }
    }

    private fun lanczos(distance: Double): Double {
        if (abs(distance) >= 3.0) return 0.0
        if (distance == 0.0) return 1.0
        val angle = distance * PI
        return (sin(angle) / angle) * (sin(angle / 3.0) / (angle / 3.0))
    }

    private const val PRECISION = 22
    private const val ROUNDING = 1 shl (PRECISION - 1)
}
