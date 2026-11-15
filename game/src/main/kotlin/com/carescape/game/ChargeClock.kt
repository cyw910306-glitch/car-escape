package com.carescape.game

import kotlin.math.ceil

/**
 * Estimates charge speed from SoC samples (least squares over a sliding window, robust to
 * integer steps and +-1 noise) and turns it into minutes left to the target.
 */
class ChargeClock(
    private val windowMs: Long = 10 * 60_000L,
    private val minSpanMs: Long = 90_000L,
    private val sampleEveryMs: Long = 5_000L,
    private val stallMs: Long = 5 * 60_000L,
    private val minRatePerMin: Double = 0.02, // below this the charger counts as stalled
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun add(nowMs: Long, soc: Float) {
        val last = samples.lastOrNull()
        if (last != null && nowMs - last.first < sampleEveryMs) return
        samples.addLast(nowMs to soc)
        while (nowMs - samples.first().first > windowMs) samples.removeFirst()
    }

    fun reset() = samples.clear()

    /** Percent per minute, null when data is thin or the charger stalled. */
    fun ratePerMin(): Double? {
        if (samples.size < 3) return null
        val t0 = samples.first().first
        val end = samples.last().first
        if (end - t0 < minSpanMs) return null
        // Stalled: no movement over the last stallMs although we have that much history.
        if (end - t0 >= stallMs) {
            val recent = samples.filter { end - it.first <= stallMs }.map { it.second }
            if (recent.max() - recent.min() < 0.5f) return null
        }
        val n = samples.size
        val xs = samples.map { (it.first - t0) / 60_000.0 }
        val ys = samples.map { it.second.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        val slope = (0 until n).sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        return slope.takeIf { it >= minRatePerMin }
    }

    fun etaMinutes(soc: Float, target: Int, charging: Boolean): Int? {
        if (!charging) return null
        if (soc >= target) return 0
        val rate = ratePerMin() ?: return null
        return ceil((target - soc) / rate).toInt().coerceAtMost(999)
    }
}
