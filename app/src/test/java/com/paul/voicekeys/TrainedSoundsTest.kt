package com.paul.voicekeys

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TrainedSoundsTest {
    private fun vector(index: Int) = FloatArray(13) { if (it == index) 1f else 0f }
    private fun pattern(id: Int) = List(16) { vector(id * 3 + minOf(it / 4, 2)) }
    private fun example(id: Int) = SoundExample(pattern(id), -25f)
    private fun model() = TrainedSounds(TrainedSounds.defaults().profiles.mapIndexed { i, p ->
        p.copy(examples = List(5) { example(i) })
    }, List(5) { SoundExample(List(40) { vector(12) }, -25f) }, 10f)
    private fun feed(detector: TrainedSoundDetector, frames: List<FloatArray>, db: Float = -25f) {
        frames.forEach { detector.accept(AcousticFrame(it, db)) }
    }
    private fun silence(detector: TrainedSoundDetector, frames: Int = 20) =
        feed(detector, List(frames) { FloatArray(13) }, -110f)

    @Test fun adjacentDistinctSoundsNeedNoSilenceAndMapToSeparateActions() {
        val decisions = mutableListOf<SoundDecision>()
        val detector = TrainedSoundDetector(model(), decisions::add)
        for (id in 0..3) feed(detector, pattern(id))
        silence(detector)
        assertEquals(listOf("advance", "start", "stop", "pause"), decisions.filter { it.accepted }.map { it.id })
    }

    @Test fun fivePerSecondRepetitionsProduceFiveEventsWithoutCooldownLoss() {
        val decisions = mutableListOf<SoundDecision>()
        val detector = TrainedSoundDetector(model(), decisions::add)
        repeat(5) { feed(detector, pattern(0)); silence(detector, 4) }
        silence(detector)
        assertEquals(5, decisions.count { it.accepted })
        assertTrue(decisions.filter { it.accepted }.all { it.id == "advance" })
    }

    @Test fun oneHeldSoundDoesNotRetriggerOnElapsedTimeAlone() {
        val decisions = mutableListOf<SoundDecision>()
        val detector = TrainedSoundDetector(model(), decisions::add)
        feed(detector, pattern(0)); feed(detector, List(300) { vector(2) }); silence(detector)
        assertEquals(1, decisions.count { it.accepted })
    }

    @Test fun violinExampleIsRejectedEvenWhenItResemblesATaughtSound() {
        val decisions = mutableListOf<SoundDecision>()
        val detector = TrainedSoundDetector(model().copy(background = List(5) { example(0) }), decisions::add)
        feed(detector, pattern(0)); silence(detector)
        assertFalse(decisions.any { it.accepted })
        assertTrue(decisions.any { it.reason == "Closer to violin background" })
    }

    @Test fun indistinguishableCommandsAreRejectedInsteadOfChoosingFirst() {
        val decisions = mutableListOf<SoundDecision>()
        val original = model()
        val ambiguous = original.copy(profiles = original.profiles.mapIndexed { i, p ->
            if (i == 1) p.copy(examples = original.profiles.first().examples) else p
        })
        val detector = TrainedSoundDetector(ambiguous, decisions::add)
        feed(detector, pattern(0)); silence(detector)
        assertFalse(decisions.any { it.accepted })
        assertTrue(decisions.any { it.reason == "Ambiguous between sounds" })
    }

    @Test fun silenceAndUnrelatedSustainedToneAreNotForcedIntoCommands() {
        val decisions = mutableListOf<SoundDecision>()
        val detector = TrainedSoundDetector(model(), decisions::add)
        silence(detector, 500); feed(detector, List(500) { vector(12) }); silence(detector, 500)
        assertFalse(decisions.any { it.accepted })
    }

    @Test fun durationWarpingAllowsFasterAndSlowerExamples() {
        val decisions = mutableListOf<SoundDecision>()
        val detector = TrainedSoundDetector(model(), decisions::add)
        feed(detector, pattern(0).filterIndexed { i, _ -> i % 2 == 0 }); silence(detector)
        feed(detector, pattern(1).flatMap { listOf(it, it) }); silence(detector)
        assertEquals(listOf("advance", "start"), decisions.filter { it.accepted }.map { it.id })
    }

    @Test fun featureExtractionIsIndependentOfAudioRecordChunkBoundaries() {
        val pcm = ShortArray(16000) { (sin(2 * PI * 730 * it / 16000) * 10000).toInt().toShort() }
        val whole = AcousticFeatures.extract(pcm)
        val chunks = mutableListOf<AcousticFrame>()
        val extractor = AcousticFeatures()
        for (offset in pcm.indices step 317) extractor.accept(pcm.copyOfRange(offset, minOf(offset + 317, pcm.size)), consume = chunks::add)
        assertEquals(98, whole.size)
        assertEquals(whole.size, chunks.size)
        whole.indices.forEach { assertArrayEquals(whole[it].values, chunks[it].values, 1e-6f) }
    }

    @Test fun enrollmentRejectsSilenceAndRequiresACompleteShortExample() {
        assertThrows(IllegalArgumentException::class.java) { SoundEnrollment.example(ShortArray(16000)) }
        assertThrows(IllegalArgumentException::class.java) { SoundEnrollment.example(ShortArray(1000)) }
        assertThrows(IllegalArgumentException::class.java) { SoundEnrollment.background(ShortArray(160000)) }
    }

    @Test fun controlRequiresFourTrainedSlotsAndViolinButPracticeCanUseOneSlot() {
        val bank = model()
        assertNull(bank.problem(true))
        val one = bank.copy(profiles = bank.profiles.mapIndexed { i, p -> if (i == 0) p else p.copy(examples = emptyList()) })
        assertNull(one.problem(false)); assertNotNull(one.problem(true))
        assertNotNull(bank.copy(background = emptyList()).problem(false))
        assertNotNull(TrainedSounds.defaults().problem(false))
    }

    @Test fun backgroundHasShortAndLongWindowsForBothBapsAndSlowerSounds() {
        val pcm = ShortArray(160000) { (sin(2 * PI * 440 * it / 16000) * 10000).toInt().toShort() }
        val background = SoundEnrollment.background(pcm)
        assertEquals(setOf(10, 20, 40, 80), background.map { it.frames.size }.toSet())
        assertEquals(60, background.size)
    }

    @Test fun addingViolinPassagesRetainsExamplesOfBothWithinABoundedBank() {
        val first = List(60) { SoundExample(List(10) { vector(1) }, -20f) }
        val second = List(60) { SoundExample(List(10) { vector(2) }, -20f) }
        val combined = SoundEnrollment.mergeBackground(first, second)
        assertEquals(60, combined.size)
        assertTrue(combined.any { it.frames.first()[1] == 1f })
        assertTrue(combined.any { it.frames.first()[2] == 1f })
    }
}
