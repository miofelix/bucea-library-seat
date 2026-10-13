package cn.edu.bucea.library.ocr

import java.nio.FloatBuffer

internal object CtcDecoder {
    fun decode(logits: FloatBuffer, charset: List<String>): String {
        require(charset.isNotEmpty() && logits.remaining() % charset.size == 0)
        var previous = -1
        return buildString {
            while (logits.hasRemaining()) {
                var predicted = 0
                var maximum = Float.NEGATIVE_INFINITY
                for (index in charset.indices) {
                    val value = logits.get()
                    if (value > maximum) {
                        maximum = value
                        predicted = index
                    }
                }
                if (predicted != previous && predicted != 0) append(charset[predicted])
                previous = predicted
            }
        }
    }
}
