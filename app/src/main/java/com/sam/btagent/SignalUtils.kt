package com.sam.btagent

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Shared DSP and statistics helpers used by the various test fragments.
 *
 * Previously each fragment carried its own copy of Goertzel / std-dev /
 * percentile, with subtly different contracts (some percentile callers had to
 * pre-sort, some did not). These are the single canonical implementations.
 */
object SignalUtils {

    /**
     * Goertzel single-bin magnitude estimate for [targetFreq].
     *
     * @param length number of leading samples to analyse (defaults to the whole array).
     * @param hannWindow apply a Hann window to reduce spectral leakage.
     */
    fun goertzelMagnitude(
        samples: ShortArray,
        targetFreq: Double,
        sampleRate: Int,
        length: Int = samples.size,
        hannWindow: Boolean = false
    ): Double {
        val n = length.coerceAtMost(samples.size)
        if (n <= 1) return 0.0
        val k = (0.5 + (n * targetFreq / sampleRate)).toInt()
        val omega = 2.0 * PI * k / n
        val coeff = 2.0 * cos(omega)
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until n) {
            val sample = if (hannWindow) {
                samples[i] * (0.5 * (1.0 - cos(2.0 * PI * i / (n - 1))))
            } else {
                samples[i].toDouble()
            }
            val q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        return sqrt(q1 * q1 + q2 * q2 - coeff * q1 * q2)
    }

    /** Population standard deviation; returns 0.0 for fewer than two samples. */
    fun stdDev(values: List<Long>): Double {
        if (values.size < 2) return 0.0
        val avg = values.average()
        return sqrt(values.map { (it - avg) * (it - avg) }.average())
    }

    /**
     * Nearest-rank percentile ([p] in 0.0..1.0). Sorts internally, so callers
     * may pass the values in any order.
     */
    fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = ceil(sorted.size * p).toInt().minus(1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
