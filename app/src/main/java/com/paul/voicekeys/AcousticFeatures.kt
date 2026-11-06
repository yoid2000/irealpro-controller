package com.paul.voicekeys

import kotlin.math.*

data class AcousticFrame(val values: FloatArray, val db: Float)

/** 25 ms windows, 10 ms steps, 13 normalized MFCCs. No text or language model. */
class AcousticFeatures {
    private val samples = ShortArray(WINDOW)
    private var filled = 0
    private val real = DoubleArray(FFT)
    private val imaginary = DoubleArray(FFT)
    private val powers = DoubleArray(FFT / 2 + 1)
    private val logs = DoubleArray(BANDS)

    fun accept(pcm: ShortArray, count: Int = pcm.size, consume: (AcousticFrame) -> Unit) {
        require(count in 0..pcm.size)
        var offset = 0
        while (offset < count) {
            val n = minOf(WINDOW - filled, count - offset)
            pcm.copyInto(samples, filled, offset, offset + n)
            offset += n; filled += n
            if (filled == WINDOW) {
                consume(frame())
                samples.copyInto(samples, 0, HOP, WINDOW)
                filled -= HOP
            }
        }
    }

    private fun frame(): AcousticFrame {
        real.fill(0.0); imaginary.fill(0.0)
        var energy = 0.0
        for (i in samples.indices) {
            val x = samples[i] / 32768.0
            val preceding = if (i == 0) 0.0 else samples[i - 1] / 32768.0
            energy += x * x
            real[i] = (x - .97 * preceding) * window[i]
        }
        // In-place radix-2 FFT, with precomputed bit reversal and twiddles.
        for (i in 0 until FFT) {
            val j = reversed[i]
            if (j > i) { val r = real[i]; real[i] = real[j]; real[j] = r }
        }
        var length = 2
        while (length <= FFT) {
            val half = length / 2
            for (start in 0 until FFT step length) for (j in 0 until half) {
                val twiddle = j * FFT / length
                val a = start + j; val b = a + half
                val r = real[b] * cosines[twiddle] - imaginary[b] * sines[twiddle]
                val im = real[b] * sines[twiddle] + imaginary[b] * cosines[twiddle]
                real[b] = real[a] - r; imaginary[b] = imaginary[a] - im
                real[a] += r; imaginary[a] += im
            }
            length *= 2
        }
        for (i in powers.indices) powers[i] = real[i] * real[i] + imaginary[i] * imaginary[i]
        for (band in 0 until BANDS) {
            var sum = 0.0
            for (bin in powers.indices) sum += powers[bin] * filters[band][bin]
            logs[band] = ln(sum.coerceAtLeast(1e-12))
        }
        val vector = FloatArray(DIMENSIONS) { c ->
            var sum = 0.0
            for (band in 0 until BANDS) sum += logs[band] * dct[c][band]
            (sum / BANDS).toFloat()
        }
        val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
        val db = (10 * log10((energy / WINDOW).coerceAtLeast(1e-12))).toFloat()
        if (norm > 1e-6f && db > -80f) vector.indices.forEach { vector[it] /= norm } else vector.fill(0f)
        return AcousticFrame(vector, db)
    }

    companion object {
        const val RATE = 16000
        const val HOP = 160
        const val WINDOW = 400
        const val DIMENSIONS = 13
        private const val FFT = 512
        private const val BANDS = 32
        private val window = DoubleArray(WINDOW) { .54 - .46 * cos(2 * PI * it / (WINDOW - 1)) }
        private val reversed = IntArray(FFT) { Integer.reverse(it) ushr (32 - 9) }
        private val cosines = DoubleArray(FFT / 2) { cos(-2 * PI * it / FFT) }
        private val sines = DoubleArray(FFT / 2) { sin(-2 * PI * it / FFT) }
        private val dct = Array(DIMENSIONS) { c -> DoubleArray(BANDS) { cos(PI * (c + 1) * (it + .5) / BANDS) } }
        private val filters = run {
            fun mel(hz: Double) = 2595 * log10(1 + hz / 700)
            fun hz(mel: Double) = 700 * (10.0.pow(mel / 2595) - 1)
            val edges = DoubleArray(BANDS + 2) { hz(mel(80.0) + it * (mel(7600.0) - mel(80.0)) / (BANDS + 1)) }
            Array(BANDS) { band -> DoubleArray(FFT / 2 + 1) { bin ->
                val frequency = bin * RATE.toDouble() / FFT
                minOf((frequency - edges[band]) / (edges[band + 1] - edges[band]),
                    (edges[band + 2] - frequency) / (edges[band + 2] - edges[band + 1])).coerceIn(0.0, 1.0)
            } }
        }

        fun extract(pcm: ShortArray): List<AcousticFrame> = mutableListOf<AcousticFrame>().also { out ->
            AcousticFeatures().accept(pcm, consume = out::add)
        }

        fun distance(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in 0 until DIMENSIONS) dot += a[i] * b[i]
            return (1f - dot).coerceIn(0f, 2f)
        }
    }
}

/** Time alignment of two complete examples; normalized by the longer duration. */
fun soundDistance(a: List<FloatArray>, b: List<FloatArray>): Float {
    if (a.isEmpty() || b.isEmpty() || a.size > b.size * 2.5 || b.size > a.size * 2.5) return Float.POSITIVE_INFINITY
    var previous = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }.also { it[0] = 0f }
    var current = FloatArray(b.size + 1)
    for (frame in a) {
        current.fill(Float.POSITIVE_INFINITY)
        for (j in b.indices) current[j + 1] = AcousticFeatures.distance(frame, b[j]) +
            minOf(previous[j], previous[j + 1] + .015f, current[j] + .015f)
        val swap = previous; previous = current; current = swap
    }
    return previous[b.size] / maxOf(a.size, b.size)
}
