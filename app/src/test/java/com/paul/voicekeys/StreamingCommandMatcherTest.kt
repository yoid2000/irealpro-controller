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

    @Test fun beamCanReviseAnUncommittedGuessThenAcceptStableCommand() {
        val matcher = StreamingCommandMatcher(defaultMappings(), revisable = true)
        assertTrue(matcher.accept(listOf(" FOR"), scores(1), FloatArray(1), 0).isEmpty())
        assertTrue(matcher.accept(listOf(" FOUR"), scores(1), FloatArray(1), 160).isEmpty())
        assertEquals(listOf("four"), matcher.accept(listOf(" FOUR"), scores(1), FloatArray(1), 320).map { it.word })
    }

    @Test fun beamPreservesAdjacentAndRepeatedOccurrencesAfterPrefixStabilizes() {
        val matcher = StreamingCommandMatcher(defaultMappings(), revisable = true)
        val tokens = listOf(" ONE", " TWO", " THREE", " FOUR", " FOUR")
        assertTrue(matcher.accept(tokens, scores(5), FloatArray(5), 0).isEmpty())
        assertEquals(listOf("one", "two", "three", "four", "four"),
            matcher.accept(tokens, scores(5), FloatArray(5), 160).map { it.word })
        assertTrue(matcher.accept(tokens, scores(5), FloatArray(5), 320).isEmpty())
    }

    @Test fun growingTranscriptDoesNotDelayAnAlreadyStablePrefix() {
        val matcher = StreamingCommandMatcher(defaultMappings(), revisable = true)
        matcher.accept(listOf(" ONE"), scores(1), FloatArray(1), 0)
        assertEquals(listOf("one"), matcher.accept(listOf(" ONE", " TWO"), scores(2), FloatArray(2), 160).map { it.word })
        assertEquals(listOf("two"), matcher.accept(listOf(" ONE", " TWO", " THREE"), scores(3), FloatArray(3), 320).map { it.word })
    }

    @Test fun revisionOfCommittedOutputNeverReplaysKeysAndNextSegmentRecovers() {
        val decisions = mutableListOf<RecognitionDecision>()
        val matcher = StreamingCommandMatcher(defaultMappings(), revisable = true, onDecision = decisions::add)
        assertEquals(1, matcher.accept(listOf(" ONE"), scores(1), FloatArray(1), 0, true).size)
        assertTrue(matcher.accept(listOf(" TWO", " THREE"), scores(2), FloatArray(2), 160, true).isEmpty())
        assertTrue(matcher.accept(listOf(" TWO", " THREE"), scores(2), FloatArray(2), 320, true).isEmpty())
        assertEquals(1, decisions.count { it.reason == DecisionReason.REVISED_COMMITTED })
        matcher.reset()
        assertEquals(1, matcher.accept(listOf(" TWO"), scores(1), FloatArray(1), 480, true).size)
    }

    @Test fun diagnosticsExplainRejectedHomophonesWithoutForcingCommands() {
        val decisions = mutableListOf<RecognitionDecision>()
        val matcher = StreamingCommandMatcher(defaultMappings(), onDecision = decisions::add)
        assertTrue(matcher.accept(listOf(" FOR", " PAWS"), scores(2), FloatArray(2), 0, true).isEmpty())
        assertEquals(listOf("for", "paws"), decisions.map { it.word })
        assertTrue(decisions.all { it.reason == DecisionReason.UNMAPPED })
    }

    @Test fun diagnosticsReportLowMissingAndInvalidScores() {
        val decisions = mutableListOf<RecognitionDecision>()
        val matcher = StreamingCommandMatcher(defaultMappings(), onDecision = decisions::add)
        matcher.accept(listOf(" FOUR"), scores(1, -3f), FloatArray(1), 0, true)
        assertEquals(DecisionReason.LOW_SCORE, decisions.single().reason)
        assertEquals(0.15f, decisions.single().threshold!!, 0.001f)
        matcher.reset(); decisions.clear()
        matcher.accept(listOf(" PAUSE"), floatArrayOf(), FloatArray(1), 0, true)
        assertEquals(DecisionReason.MISSING_SCORE, decisions.single().reason)
        matcher.reset(); decisions.clear()
        matcher.accept(listOf(" STOP"), floatArrayOf(Float.NaN), FloatArray(1), 0, true)
        assertEquals(DecisionReason.INVALID_SCORE, decisions.single().reason)
    }

    @Test fun lowScoreFinalWordCanRecoverWithoutDuplicateRejectionRows() {
        val decisions = mutableListOf<RecognitionDecision>()
        val matcher = StreamingCommandMatcher(defaultMappings(), revisable = true, onDecision = decisions::add)
        val tokens = listOf(" PAUSE")
        matcher.accept(tokens, scores(1, -3f), FloatArray(1), 0)
        matcher.accept(tokens, scores(1, -3f), FloatArray(1), 160)
        matcher.accept(tokens, scores(1, -3f), FloatArray(1), 320)
        assertEquals(1, decisions.size)
        assertEquals(1, matcher.accept(tokens, scores(1), FloatArray(1), 480).size)
        assertEquals(listOf(DecisionReason.LOW_SCORE, DecisionReason.ACCEPTED), decisions.map { it.reason })
    }
}
