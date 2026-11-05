package com.paul.voicekeys

import kotlin.math.exp

data class RecognizedCommand(val word: String, val lookbackMs: Long)

/** Matches whole words from monotonic greedy transducer output, never fuzzy text.
 * Token log probabilities provide a rejection score, not a calibrated probability
 * that a command is correct. Each token position can produce at most one event.
 */
class StreamingCommandMatcher(mappings: List<CommandMapping>) {
    private val commands = mappings.associateBy { it.word }
    private var lastConsumedStart = -1
    private var previousTokens: List<String> = emptyList()
    private var changedAt = 0L

    fun accept(tokens: List<String>, logProbs: FloatArray, timestamps: FloatArray,
               clockMs: Long, endpoint: Boolean = false): List<RecognizedCommand> {
        // Decoder output is append-only in greedy mode. Fail closed on unexpected revisions.
        check(tokens.size >= previousTokens.size && tokens.take(previousTokens.size) == previousTokens) {
            "Recognition output changed unexpectedly; restart voice control."
        }
        if (tokens != previousTokens) { changedAt = clockMs; previousTokens = tokens.toList() }
        if (tokens.isEmpty()) return emptyList()
        val starts = mutableListOf<Int>()
        tokens.forEachIndexed { index, token ->
            if (index == 0 || token.startsWith(' ') || token.startsWith('▁')) starts += index
        }
        val events = mutableListOf<RecognizedCommand>()
        starts.forEachIndexed { wordIndex, start ->
            val end = starts.getOrNull(wordIndex + 1) ?: tokens.size
            if (start <= lastConsumedStart) return@forEachIndexed
            val hasBoundary = wordIndex < starts.lastIndex
            // Evaluate the final word only after another decode confirms it is stable.
            if (!hasBoundary && !endpoint && clockMs - changedAt < 120) return@forEachIndexed
            val word = normalizeWord(tokens.subList(start, end).joinToString("")
                .replace('▁', ' '))
            if (hasBoundary || endpoint) lastConsumedStart = start
            val mapping = commands[word] ?: return@forEachIndexed
            if (logProbs.size < end) return@forEachIndexed
            val scores = logProbs.sliceArray(start until end)
            if (scores.any { !it.isFinite() || it > 0f }) return@forEachIndexed
            val acousticScore = exp(scores.average()).toFloat()
            if (acousticScore < mapping.threshold) return@forEachIndexed
            lastConsumedStart = start
            val emittedAt = (timestamps.getOrNull(end - 1)?.times(1000))?.toLong() ?: clockMs
            events += RecognizedCommand(word, (clockMs - emittedAt).coerceAtLeast(0))
        }
        return events
    }

    fun reset() { lastConsumedStart = -1; previousTokens = emptyList(); changedAt = 0L }
}
