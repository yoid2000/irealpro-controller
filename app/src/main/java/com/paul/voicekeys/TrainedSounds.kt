package com.paul.voicekeys

import kotlin.math.*

enum class RecognitionMode { TRAINED_SOUNDS, SPEECH }
data class SoundExample(val frames: List<FloatArray>, val db: Float)
data class SoundProfile(val id: String, val label: String, val key: OutputKey,
    val examples: List<SoundExample> = emptyList(), val tolerance: Float = 1f) {
    fun limit(): Float {
        if (examples.size < 2) return .12f * tolerance
        val nearest = examples.mapIndexed { i, a ->
            examples.indices.filter { it != i }.minOf { j -> soundDistance(a.frames, examples[j].frames) }
        }.sorted()
        val typical = nearest[(nearest.size * .8).toInt().coerceAtMost(nearest.lastIndex)]
        return ((typical * 1.8f + .035f).coerceIn(.07f, .32f) * tolerance).coerceAtMost(.45f)
    }
}

data class TrainedSounds(val profiles: List<SoundProfile>, val background: List<SoundExample> = emptyList(),
    val backgroundSeconds: Float = 0f) {
    fun problem(forControl: Boolean): String? {
        val usable = profiles.filter { it.examples.size >= MIN_EXAMPLES }
        if (usable.isEmpty()) return "Train a sound under Commands first (at least $MIN_EXAMPLES examples)."
        if (forControl && usable.size != profiles.size) return "Train all four sounds with at least $MIN_EXAMPLES examples each before voice control. Practice can test the sounds already trained."
        if (backgroundSeconds < 10 || background.isEmpty()) return "Record 10 seconds of violin-only background under Commands before testing trained sounds."
        return null
    }
    companion object {
        const val MIN_EXAMPLES = 5
        const val MAX_EXAMPLES = 12
        fun defaults() = TrainedSounds(listOf(
            SoundProfile("advance", "BAP", OutputKey.COMMA),
            SoundProfile("start", "DEET", OutputKey.SPACE),
            SoundProfile("stop", "GOKE", OutputKey.SPACE),
            SoundProfile("pause", "SHOOM", OutputKey.PERIOD),
        ))
    }
}

object SoundEnrollment {
    fun example(pcm: ShortArray): SoundExample {
        require(pcm.size in 1600..19200) { "Select one complete sound lasting 0.10–1.20 seconds." }
        val frames = AcousticFeatures.extract(pcm)
        require(frames.count { it.db > -70 } >= frames.size * .7) { "The selection contains too much silence or is too quiet. Adjust its ends, or record again." }
        return SoundExample(frames.map { it.values }, frames.map { it.db }.average().toFloat())
    }

    fun background(pcm: ShortArray): List<SoundExample> {
        val frames = AcousticFeatures.extract(pcm)
        // Short BAPs must be compared with equally short violin transients,
        // rather than rejecting the negative comparison on duration alone.
        return listOf(10, 20, 40, 80).flatMap { length ->
            val starts = (0..(frames.size - length).coerceAtLeast(0) step 10).toList()
            val sampled = evenlySpaced(starts, 15)
            sampled.mapNotNull { start ->
                val part = frames.drop(start).take(length)
                if (part.size != length || part.count { it.db > -70 } < part.size * .8) null
                else SoundExample(part.map { it.values }, part.map { it.db }.average().toFloat())
            }
        }.also { require(it.size >= 5) { "Background was too quiet. Record while playing the violin." } }
    }

    fun mergeBackground(previous: List<SoundExample>, added: List<SoundExample>): List<SoundExample> =
        evenlySpaced(previous + added, 60)

    private fun <T> evenlySpaced(items: List<T>, maximum: Int): List<T> {
        if (items.size <= maximum) return items
        return List(maximum) { items[(it.toLong() * (items.size - 1) / (maximum - 1)).toInt()] }
    }

    fun suggestedRange(pcm: ShortArray): ClosedFloatingPointRange<Float> {
        val energies = pcm.asList().chunked(320).map { block ->
            sqrt(block.sumOf { it.toDouble() * it } / block.size)
        }
        val peak = energies.maxOrNull() ?: 0.0
        if (peak < 15) return 500f..1200f
        val active = energies.indices.filter { energies[it] > peak * .16 }
        val start = ((active.first() * 20 - 20).coerceAtLeast(0)).toFloat()
        val end = minOf((active.last() + 1) * 20 + 20, pcm.size / 16, start.toInt() + 1200).toFloat()
        return start..maxOf(end, start + 100)
    }
}

data class SoundDecision(val id: String, val label: String, val reason: String, val distance: Float,
    val limit: Float, val competingDistance: Float, val backgroundDistance: Float, val audioMs: Long,
    val lookbackMs: Long) { val accepted: Boolean get() = reason == "Accepted" }

/** Streaming subsequence DTW with onset rearming, whole-template matching and
 * rejection against both other sounds and recorded violin. There is no cooldown
 * after a key: a new acoustic onset can start the next occurrence immediately. */
class TrainedSoundDetector(model: TrainedSounds, private val onDecision: (SoundDecision) -> Unit) {
    private val profiles = model.profiles.filter { it.examples.size >= TrainedSounds.MIN_EXAMPLES }
    private val background = model.background
    private val limits = profiles.associate { it.id to it.limit() }
    private val paths = profiles.flatMap { profile -> profile.examples.map { Path(profile, it.frames) } }
    private data class Observed(val index: Int, val frame: AcousticFrame, val onset: Int)
    private data class Candidate(val profile: SoundProfile, val distance: Float, val start: Int, val end: Int)
    private val history = ArrayDeque<Observed>()
    private var index = -1
    private var lastOnset = -1000
    private var consumed = -1
    private var pending: Candidate? = null
    private var lastRejection = -1000

    fun accept(frame: AcousticFrame) {
        index++
        val reference = history.elementAtOrNull((history.size - 4).coerceAtLeast(0))?.frame
        val novelty = reference == null || frame.db - reference.db > 5f ||
            AcousticFeatures.distance(frame.values, reference.values) > .12f
        if (frame.db > -70 && novelty && index - lastOnset >= 4) lastOnset = index
        val observed = Observed(index, frame, lastOnset)
        history.addLast(observed)
        while (history.size > 180) history.removeFirst()
        step(observed)
        val candidate = pending
        if (candidate != null && index - candidate.end >= 3) finish(candidate)
    }

    private fun step(observed: Observed) {
        val startAllowed = observed.onset > consumed && observed.index - observed.onset in 0..3 && observed.frame.db > -70
        var best: Candidate? = null
        for (path in paths) {
            val result = path.accept(observed.frame.values, observed.index, startAllowed) ?: continue
            if (result.start <= consumed || result.distance > limits.getValue(result.profile.id)) continue
            if (best == null || result.distance < best.distance) best = result
        }
        val match = best ?: return
        val old = pending
        if (old == null || (match.start <= old.end && match.distance < old.distance)) pending = match
    }

    private fun finish(candidate: Candidate) {
        val query = history.filter { it.index in candidate.start..candidate.end }.map { it.frame.values }
        if (query.size != candidate.end - candidate.start + 1) { resetPaths(candidate.end); return }
        val ranked = profiles.map { p -> p to p.examples.minOf { soundDistance(query, it.frames) } }.sortedBy { it.second }
        val winner = ranked.first()
        val best = winner.second
        val second = ranked.getOrNull(1)?.second ?: Float.POSITIVE_INFINITY
        val noise = background.minOfOrNull { soundDistance(query, it.frames) } ?: Float.POSITIVE_INFINITY
        val limit = limits.getValue(winner.first.id)
        val reason = when {
            best > limit -> "Outside trained sound range"
            second - best < .025f -> "Ambiguous between sounds"
            noise <= best + .02f -> "Closer to violin background"
            else -> "Accepted"
        }
        if (reason == "Accepted" || index - lastRejection >= 15) {
            onDecision(SoundDecision(winner.first.id, winner.first.label, reason, best, limit, second, noise,
                candidate.start * 10L, (index - candidate.end) * 10L + 25))
            if (reason != "Accepted") lastRejection = index
        }
        resetPaths(candidate.end)
    }

    private fun resetPaths(end: Int) {
        consumed = end; pending = null
        paths.forEach { it.reset() }
        // Retain audio after the matched endpoint, including the next onset.
        history.filter { it.index > end }.forEach { step(it) }
    }

    private class Path(private val profile: SoundProfile, private val template: List<FloatArray>) {
        private var previous = FloatArray(template.size + 1) { Float.POSITIVE_INFINITY }
        private var current = FloatArray(template.size + 1)
        private var previousStarts = IntArray(template.size + 1) { -1 }
        private var currentStarts = IntArray(template.size + 1)
        fun reset() { previous.fill(Float.POSITIVE_INFINITY); previousStarts.fill(-1) }
        fun accept(frame: FloatArray, time: Int, allowStart: Boolean): Candidate? {
            current.fill(Float.POSITIVE_INFINITY); currentStarts.fill(-1)
            for (i in 1..template.size) {
                var cost = previous[i - 1]; var start = previousStarts[i - 1]
                if (previous[i] + .015f < cost) { cost = previous[i] + .015f; start = previousStarts[i] }
                if (current[i - 1] + .015f < cost) { cost = current[i - 1] + .015f; start = currentStarts[i - 1] }
                if (i == 1 && allowStart) { cost = 0f; start = time }
                if (start < 0 || time - start > 150) continue
                current[i] = cost + AcousticFeatures.distance(frame, template[i - 1])
                currentStarts[i] = start
            }
            val swap = previous; previous = current; current = swap
            val startsSwap = previousStarts; previousStarts = currentStarts; currentStarts = startsSwap
            val start = previousStarts.last()
            val duration = time - start + 1
            if (start < 0 || duration < maxOf(8, (template.size * .4).toInt()) || duration > template.size * 2.2) return null
            return Candidate(profile, previous.last() / maxOf(template.size, duration), start, time)
        }
    }
}
