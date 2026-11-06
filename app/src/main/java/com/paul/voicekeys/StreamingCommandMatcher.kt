package com.paul.voicekeys

import kotlin.math.exp

data class RecognizedCommand(val word: String, val lookbackMs: Long)

enum class DecisionReason(val label: String) {
    ACCEPTED("Accepted"), LOW_SCORE("Below selectivity"), UNMAPPED("Not a command"),
    MISSING_SCORE("Score unavailable"), INVALID_SCORE("Invalid score"),
    REVISED_COMMITTED("Earlier output revised; segment restarted"),
}

data class RecognitionDecision(val word: String, val reason: DecisionReason, val score: Float?,
    val threshold: Float?, val audioStartMs: Long, val lookbackMs: Long)

/** Matches whole words, never fuzzy text.
 * Token log probabilities provide a rejection score, not a calibrated probability
 * that a command is correct. Beam hypotheses may revise uncommitted output.
 * A revision of committed output blocks the segment rather than replaying keys.
 */
class StreamingCommandMatcher(mappings: List<CommandMapping>, private val revisable: Boolean = false,
    private val onDecision: (RecognitionDecision) -> Unit = {}) {
    private val commands = mappings.associateBy { it.word }
    private var lastConsumedStart = -1
    private var previousTokens: List<String> = emptyList()
    private var stableSince: List<Long> = emptyList()
    private var committedTokenCount = 0
    private var blocked = false
    val needsReset: Boolean get() = blocked
    private val reported = mutableMapOf<Int, Pair<String, DecisionReason>>()

    fun accept(tokens: List<String>, logProbs: FloatArray, timestamps: FloatArray,
               clockMs: Long, endpoint: Boolean = false): List<RecognizedCommand> {
        val common = tokens.zip(previousTokens).takeWhile { (a, b) -> a == b }.size
        if (!revisable) {
            check(common == previousTokens.size) {
                "Recognition output changed unexpectedly; restart voice control."
            }
        } else if (!blocked && common < committedTokenCount) {
            blocked = true
            onDecision(RecognitionDecision("", DecisionReason.REVISED_COMMITTED, null, null, clockMs, 0))
        }
        stableSince = tokens.indices.map { if (it < common) stableSince[it] else clockMs }
        previousTokens = tokens.toList()
        if (tokens.isEmpty() || blocked) return emptyList()
        val starts = mutableListOf<Int>()
        tokens.forEachIndexed { index, token ->
            if (index == 0 || token.startsWith(' ') || token.startsWith('▁')) starts += index
        }
        val events = mutableListOf<RecognizedCommand>()
        starts.forEachIndexed { wordIndex, start ->
            val end = starts.getOrNull(wordIndex + 1) ?: tokens.size
            if (start <= lastConsumedStart) return@forEachIndexed
            val hasBoundary = wordIndex < starts.lastIndex
            // Beam words, including words followed by another word, must survive a
            // second decode. Stability belongs to the prefix, not the whole transcript.
            if (!endpoint && (revisable || !hasBoundary) && clockMs - stableSince[end - 1] < 120) return@forEachIndexed
            val word = normalizeWord(tokens.subList(start, end).joinToString("")
                .replace('▁', ' '))
            val mapping = commands[word]
            // Unknown final fragments may still grow into a command (PA -> PAUSE).
            if (mapping == null && !hasBoundary && !endpoint) return@forEachIndexed
            val scores = if (logProbs.size >= end) logProbs.sliceArray(start until end) else null
            val validScores = scores?.all { it.isFinite() && it <= 0f } == true
            val acousticScore = if (validScores) exp(scores!!.average()).toFloat() else null
            val reason = when {
                mapping == null -> DecisionReason.UNMAPPED
                scores == null -> DecisionReason.MISSING_SCORE
                !validScores -> DecisionReason.INVALID_SCORE
                acousticScore!! < mapping.threshold -> DecisionReason.LOW_SCORE
                else -> DecisionReason.ACCEPTED
            }
            val emittedAt = (timestamps.getOrNull(end - 1)?.times(1000))?.toLong() ?: clockMs
            val lookback = (clockMs - emittedAt).coerceAtLeast(0)
            val signature = word to reason
            if (reported[start] != signature) {
                onDecision(RecognitionDecision(word, reason, acousticScore, mapping?.threshold,
                    (timestamps.getOrNull(start)?.times(1000))?.toLong() ?: clockMs, lookback))
                reported[start] = signature
            }
            if (hasBoundary || endpoint || reason == DecisionReason.ACCEPTED) {
                lastConsumedStart = start
                committedTokenCount = end
            }
            if (reason == DecisionReason.ACCEPTED) events += RecognizedCommand(word, lookback)
        }
        return events
    }

    fun reset() {
        lastConsumedStart = -1; previousTokens = emptyList(); stableSince = emptyList()
        committedTokenCount = 0; blocked = false; reported.clear()
    }
}
