package com.paul.voicekeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs the shipped native libraries, model, bias tokenizer and Kotlin matcher
 * together on Android. No microphone, Bluetooth operation or key transmission. */
@RunWith(AndroidJUnit4::class)
class NativeRecognitionTest {
    @Test fun nativeBiasedDecoderPreservesCommandsCountingRepetitionsAndSilence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mappings = defaultMappings()
        val recognizer = OnlineRecognizer(instrumentation.targetContext.assets, RecognitionConfig.create(true))
        try {
            val samples = listOf(
                "counting" to listOf("one", "two", "three", "four"),
                "counting-slower" to listOf("one", "two", "three", "four"),
                "commands" to listOf("one", "two", "three", "four", "start", "stop", "pause"),
                // Repeated words remain low-scoring; evaluate this stress clip
                // at 0.15 without weakening the saved/default 0.20 setting.
                "repeated-low" to List(4) { "one" },
                "silence" to emptyList(),
            )
            samples.forEach { (name, expected) ->
                val sessionMappings = if (name == "repeated-low") mappings.map { it.copy(threshold = 0.15f) } else mappings
                val audio = if (name == "silence") FloatArray(16000 * 5) else {
                    val wav = instrumentation.context.assets.open("${name.removeSuffix("-low")}.wav").use { it.readBytes() }
                    pcm(wav)
                }
                val stream = recognizer.createStream(RecognitionConfig.hotwords(sessionMappings))
                val decisions = mutableListOf<RecognitionDecision>()
                val matcher = StreamingCommandMatcher(sessionMappings, revisable = true, onDecision = decisions::add)
                val detections = mutableListOf<String>()
                var segmentStart = 0
                try {
                    val padded = audio + FloatArray(32000)
                    for (offset in padded.indices step 320) {
                        val end = minOf(offset + 320, padded.size)
                        stream.acceptWaveform(padded.copyOfRange(offset, end), 16000)
                        while (recognizer.isReady(stream)) {
                            recognizer.decode(stream)
                            val result = recognizer.getResult(stream)
                            val endpoint = recognizer.isEndpoint(stream)
                            detections += matcher.accept(result.tokens.toList(), result.ysProbs,
                                result.timestamps, (end - segmentStart) / 16L, endpoint).map { it.word }
                            if (endpoint || matcher.needsReset) { recognizer.reset(stream); matcher.reset(); segmentStart = end }
                        }
                    }
                    assertEquals("$name; decisions=$decisions", expected, detections)
                    android.util.Log.i("VoiceKeysNativeTest", "$name passed: $detections")
                    assertFalse("$name revised committed output", decisions.any { it.reason == DecisionReason.REVISED_COMMITTED })
                } finally { stream.release() }
            }
        } finally { recognizer.release() }
    }

    private fun pcm(wav: ByteArray): FloatArray {
        val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var position = 12
        while (position + 8 <= wav.size) {
            val length = data.getInt(position + 4)
            val name = wav.copyOfRange(position, position + 4).toString(Charsets.US_ASCII)
            if (name == "data") return FloatArray(length / 2) { data.getShort(position + 8 + it * 2) / 32768f }
            position += 8 + length + length % 2
        }
        error("No WAV PCM data")
    }
}
