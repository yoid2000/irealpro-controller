package com.paul.voicekeys

import org.junit.Assert.*
import org.junit.Test

class StreamingCommandMatcherTest {
    private fun scores(size: Int, score: Float = -0.1f) = FloatArray(size) { score }

    @Test fun continuousCountingProducesFourOccurrencesWithoutWordSilence() {
        val matcher = StreamingCommandMatcher(defaultMappings())
        val tokens = listOf(" ONE", " TWO", " THREE", " FOUR")
        val timestamps = floatArrayOf(0f, 0.25f, 0.5f, 0.75f)
        val first = matcher.accept(tokens, scores(4), timestamps, 1000)
        assertEquals(listOf("one", "two", "three"), first.map { it.word })
        val last = matcher.accept(tokens, scores(4), timestamps, 1320)
        assertEquals(listOf("four"), last.map { it.word })
        assertTrue(matcher.accept(tokens, scores(4), timestamps, 1640).isEmpty())
    }

    @Test fun repeatedIdenticalWordsHaveDistinctTokenPositions() {
        val matcher = StreamingCommandMatcher(defaultMappings())
        val tokens = listOf("▁ONE", "▁ONE", "▁ONE", "▁ONE")
        val events = matcher.accept(tokens, scores(4), FloatArray(4), 1000, endpoint = true)
        assertEquals(List(4) { "one" }, events.map { it.word })
    }

    @Test fun subtokensAreJoinedBeforeMatching() {
        val matcher = StreamingCommandMatcher(defaultMappings())
        assertTrue(matcher.accept(listOf(" ST"), scores(1), floatArrayOf(0f), 0).isEmpty())
        assertTrue(matcher.accept(listOf(" ST", "O"), scores(2), FloatArray(2), 320).isEmpty())
        assertTrue(matcher.accept(listOf(" ST", "O", "P"), scores(3), FloatArray(3), 640).isEmpty())
        assertEquals(listOf("stop"), matcher.accept(listOf(" ST", "O", "P"), scores(3),
            FloatArray(3), 960).map { it.word })
    }

    @Test fun uncertainMappedWordsAndUnmappedAudioProduceNothing() {
        val matcher = StreamingCommandMatcher(defaultMappings())
        val tokens = listOf(" ONE", " MUSIC", " THREE")
        assertTrue(matcher.accept(tokens, scores(3, -2f), FloatArray(3), 1000, true).isEmpty())
    }

    @Test fun missingAcousticScoresFailClosed() {
        val matcher = StreamingCommandMatcher(defaultMappings())
        assertTrue(matcher.accept(listOf(" ONE"), floatArrayOf(), floatArrayOf(), 1000, true).isEmpty())
    }

    @Test fun incompleteCountingIsNeverCompletedAutomatically() {
        val matcher = StreamingCommandMatcher(defaultMappings())
        assertEquals(listOf("one", "two"), matcher.accept(listOf(" ONE", " TWO"), scores(2),
            FloatArray(2), 1000, true).map { it.word })
    }

    @Test fun unexpectedHypothesisRevisionFailsClosed() {
        val matcher = StreamingCommandMatcher(defaultMappings())
        matcher.accept(listOf(" ONE"), scores(1), FloatArray(1), 0)
        assertThrows(IllegalStateException::class.java) {
            matcher.accept(listOf(" TWO"), scores(1), FloatArray(1), 320)
        }
    }

    @Test fun newSegmentCanRepeatPreviousWord() {
        val matcher = StreamingCommandMatcher(defaultMappings())
        repeat(2) {
            assertEquals(1, matcher.accept(listOf(" ONE"), scores(1), FloatArray(1), 1000, true).size)
            matcher.reset()
        }
    }

    @Test fun extendingAnAlreadyCommittedWordCannotGenerateASecondKey() {
        val matcher = StreamingCommandMatcher(listOf(CommandMapping(word = "go", key = OutputKey.SPACE),
            CommandMapping(word = "going", key = OutputKey.COMMA)))
        assertEquals(1, matcher.accept(listOf(" GO"), scores(1), FloatArray(1), 0, true).size)
        assertTrue(matcher.accept(listOf(" GO", "ING"), scores(2), FloatArray(2), 320, true).isEmpty())
    }
}
