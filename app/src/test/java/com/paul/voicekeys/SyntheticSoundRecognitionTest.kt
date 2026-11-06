package com.paul.voicekeys

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Held-out synthetic utterances, not evidence of headset/violin accuracy. */
class SyntheticSoundRecognitionTest {
    private fun wav(name: String): ShortArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sounds/$name.wav")!!.use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 < bytes.size) {
            val length = buffer.getInt(offset + 4)
            if (bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII) == "data")
                return ShortArray(length / 2) { buffer.getShort(offset + 8 + it * 2) }
            offset += length + 8 + length % 2
        }
        error("Missing PCM")
    }
    private fun bank(): TrainedSounds {
        val noise = violinLike(16000 * 10)
        return TrainedSounds.defaults().let { model -> model.copy(profiles = model.profiles.map { p ->
            p.copy(examples = List(5) { i ->
                val pcm = wav("${p.label.lowercase()}-train-$i")
                val range = SoundEnrollment.suggestedRange(pcm)
                SoundEnrollment.example(pcm.copyOfRange((range.start * 16).toInt(), (range.endInclusive * 16).toInt().coerceAtMost(pcm.size)))
            })
        }, background = SoundEnrollment.background(noise), backgroundSeconds = 10f) }
    }
    private fun violinLike(size: Int) = ShortArray(size) { i ->
        val t = i / 16000.0
        val phase = 2 * PI * (440 * t + .5 * sin(2 * PI * 5 * t))
        ((sin(phase) + .4 * sin(2 * phase) + .2 * sin(3 * phase)) * 7500).toInt().toShort()
    }
    private fun recognize(model: TrainedSounds, pcm: ShortArray): List<SoundDecision> {
        val decisions = mutableListOf<SoundDecision>()
        val detector = TrainedSoundDetector(model, decisions::add)
        AcousticFeatures().accept(pcm + ShortArray(5000)) { detector.accept(it) }
        return decisions
    }
    @Test fun heldOutIndividualSoundsMatchTheirAudioLabelsWithoutTranscription() {
        val model = bank()
        model.profiles.forEach { profile ->
            val decisions = recognize(model, wav("${profile.label.lowercase()}-query"))
            assertEquals("${profile.label}; limits=${model.profiles.map { it.label to it.limit() }}; decisions=$decisions",
                listOf(profile.id), decisions.filter { it.accepted }.map { it.id })
        }
    }
    @Test fun continuousSyntheticSequencePreservesFourDifferentOccurrences() {
        val model = bank()
        val decisions = recognize(model, wav("sequence"))
        val detail = model.profiles.map { p -> "${p.label}: ${p.limit()}, lengths=${p.examples.map { it.frames.size }}" }
        val expected = model.profiles.map { it.id }
        val actual = decisions.filter { it.accepted }.map { it.id }
        val missed = if (expected == actual) emptyList() else {
            val features = AcousticFeatures.extract(wav("sequence")).map { it.values }
            model.profiles.filter { it.id !in actual }.map { p ->
                var best = Triple(Float.POSITIVE_INFINITY, 0, 0)
                for (start in features.indices) for (length in 8..minOf(60, features.size - start)) {
                    val score = p.examples.minOf { soundDistance(features.subList(start, start + length), it.frames) }
                    if (score < best.first) best = Triple(score, start, start + length)
                }
                p.label to best
            }
        }
        assertEquals("$decisions; $detail; missed=$missed", expected, actual)
    }
    @Test fun rapidSyntheticRepetitionsAreNotCollapsed() {
        val decisions = recognize(bank(), wav("repeated"))
        assertEquals("$decisions", List(5) { "advance" }, decisions.filter { it.accepted }.map { it.id })
    }
    @Test fun commandsWithOneSecondBetweenThemPreserveAllFourActions() {
        val model = bank()
        val audio = model.profiles.fold(ShortArray(0)) { pcm, profile ->
            pcm + wav("${profile.label.lowercase()}-query") + ShortArray(16000)
        }
        assertEquals(model.profiles.map { it.id }, recognize(model, audio).filter { it.accepted }.map { it.id })
    }
    @Test fun silenceAndHarmonicBackgroundDoNotGenerateCommands() {
        val model = bank()
        assertTrue(recognize(model, ShortArray(16000 * 3)).none { it.accepted })
        assertTrue(recognize(model, violinLike(16000 * 3)).none { it.accepted })
    }
}
