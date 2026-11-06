package com.paul.voicekeys

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PracticeDiagnosticsTest {
    @Test fun recordingPreservesExactSignedPcmAndWritesPlayableWaveHeader() {
        val capture = PracticeRecording()
        capture.append(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE), 5)
        val clip = capture.finish()!!
        val wav = ByteBuffer.wrap(clip.wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(54, clip.wav.size)
        assertEquals("RIFF", clip.wav.take(4).toByteArray().toString(Charsets.US_ASCII))
        assertEquals(46, wav.getInt(4))
        assertEquals(16000, wav.getInt(24))
        assertEquals(10, wav.getInt(40))
        assertEquals(Short.MIN_VALUE, wav.getShort(44))
        assertEquals((-1).toShort(), wav.getShort(46))
        assertEquals(Short.MAX_VALUE, wav.getShort(52))
    }

    @Test fun recordingLimitAndStopCannotBeExtendedByLateWorkerWrites() {
        val capture = PracticeRecording(maxSamples = 3)
        capture.append(shortArrayOf(1, 2), 2)
        capture.append(shortArrayOf(3, 4, 5), 3)
        val clip = capture.finish()!!
        capture.append(shortArrayOf(6, 7), 2)
        assertEquals(3, clip.sampleCount)
        assertArrayEquals(clip.wav, capture.finish()!!.wav)
    }

    @Test fun emptyRecordingProducesNoExportableClip() {
        assertNull(PracticeRecording().finish())
    }

    @Test fun diagnosticsCsvPreservesUnknownTextQuotesScoresAndSessionIdentity() {
        val csv = practiceCsv(listOf(PracticeEntry("12:34", 2, "original", 1500,
            "transcript", "for, \"paws\"", "Partial; may change"),
            PracticeEntry("12:35", 3, "command-biased", 700, "word", "pause", "Below selectivity", .1f, .15f, 240)))
        assertTrue(csv.contains("\"for, \"\"paws\"\"\""))
        assertTrue(csv.contains("\"3\",\"command-biased\",\"700\""))
        assertTrue(csv.contains("\"0.1\",\"0.15\",\"240\""))
    }

    @Test fun commandBiasUsesActualModelCaseAndUpdatedVocabulary() {
        assertEquals("FOUR/PAUSE", RecognitionConfig.hotwords(listOf(
            CommandMapping(word = "four", key = OutputKey.COMMA),
            CommandMapping(word = "pause", key = OutputKey.PERIOD))))
        assertEquals("modified_beam_search", RecognitionConfig.create(true).decodingMethod)
        assertEquals("greedy_search", RecognitionConfig.create(false).decodingMethod)
    }
}
