package com.paul.voicekeys

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Exercises the real Android runtime with held-out synthetic audio. Does not
 * open a microphone, modify the user's training, or send Bluetooth keys. */
@RunWith(AndroidJUnit4::class)
class NativeTrainedSoundsTest {
    private fun wav(name: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("sounds/$name.wav").use { it.readBytes() }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val length = data.getInt(offset + 4)
            if (bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII) == "data")
                return ShortArray(length / 2) { data.getShort(offset + 8 + it * 2) }
            offset += 8 + length + length % 2
        }
        error("No PCM data")
    }
    private fun harmonic(size: Int) = ShortArray(size) { i ->
        val t = i / 16000.0
        val phase = 2 * PI * (440 * t + .5 * sin(2 * PI * 5 * t))
        ((sin(phase) + .4 * sin(phase * 2) + .2 * sin(phase * 3)) * 7500).toInt().toShort()
    }
    @Test fun enrolledSoundMatchingRejectsNoiseAndPreservesRapidCommaOccurrences() {
        val model = TrainedSounds.defaults().let { bank -> bank.copy(profiles = bank.profiles.map { profile ->
            profile.copy(examples = List(5) { i ->
                val pcm = wav("${profile.label.lowercase()}-train-$i")
                val range = SoundEnrollment.suggestedRange(pcm)
                SoundEnrollment.example(pcm.copyOfRange((range.start * 16).toInt(), (range.endInclusive * 16).toInt().coerceAtMost(pcm.size)))
            })
        }, background = SoundEnrollment.background(harmonic(160000)), backgroundSeconds = 10f) }
        val spaced = model.profiles.fold(ShortArray(0)) { audio, profile ->
            audio + wav("${profile.label.lowercase()}-query") + ShortArray(16000)
        }
        val cases = model.profiles.map { profile ->
            Triple(profile.label, wav("${profile.label.lowercase()}-query"), listOf(profile.id))
        } + listOf(
            Triple("spaced", spaced, model.profiles.map { it.id }),
            Triple("repeated", wav("repeated"), List(5) { "advance" }),
            Triple("silence", ShortArray(48000), emptyList()),
            Triple("harmonic background", harmonic(48000), emptyList()),
        )
        var processingMs = 0L
        var audioMs = 0L
        cases.forEach { (name, audio, expected) ->
            val decisions = mutableListOf<SoundDecision>()
            val start = SystemClock.elapsedRealtime()
            val detector = TrainedSoundDetector(model, decisions::add)
            val input = audio + ShortArray(5000)
            val features = AcousticFeatures()
            for (offset in input.indices step 320) {
                features.accept(input.copyOfRange(offset, minOf(offset + 320, input.size))) { detector.accept(it) }
            }
            processingMs += SystemClock.elapsedRealtime() - start
            audioMs += input.size / 16
            assertEquals("$name; $decisions", expected, decisions.filter { it.accepted }.map { it.id })
            Log.i("VoiceKeysSoundTest", "$name passed")
        }
        Log.i("VoiceKeysSoundTest", "Processed ${audioMs}ms audio in ${processingMs}ms, including detector setup")
        assertTrue("Recognition must keep up with live input", processingMs < audioMs)
    }
}
