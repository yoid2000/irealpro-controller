package com.paul.voicekeys

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RecordedInput(val wav: ByteArray, val sampleCount: Int) {
    val seconds: Float get() = sampleCount / 16000f
}

/** The exact verified PCM supplied to the recognizer. Bounded, opt-in, memory only. */
class PracticeRecording(private val maxSamples: Int = 16000 * 30) {
    private val pcm = ByteArrayOutputStream()
    private var finished = false
    val sampleCount: Int @Synchronized get() = pcm.size() / 2

    @Synchronized fun append(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        if (finished) return
        repeat(minOf(count, maxSamples - pcm.size() / 2)) { i ->
            val sample = samples[i].toInt()
            pcm.write(sample and 0xff)
            pcm.write((sample shr 8) and 0xff)
        }
    }

    @Synchronized fun finish(): RecordedInput? {
        finished = true
        if (pcm.size() == 0) return null
        val data = pcm.toByteArray()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size).array()
        return RecordedInput(header + data, data.size / 2)
    }
}
