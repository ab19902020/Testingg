package com.clrg.core.game

import kotlin.math.abs

/**
 * The timing test: a snowball reaches a ring at a steady beat and the player swings as it gets there. The game
 * cannot see the TV, so it measures the whole chain at once - how long after the game draws "now!" the swing is
 * stamped (phone display + TV + the player's own habit + any camera clock offset). Timing games then allow for
 * exactly that.
 *
 * All times are on the camera/game clock (seconds). [start] is when the test began.
 */
class TimingCalibrator(
    val start: Double,
    val cues: Int = 10,
    val period: Double = 1.6,
    /** Time before the first cue (to get ready). */
    val lead: Double = 3.0,
) {
    private val matched = BooleanArray(cues)
    /** Swing time minus cue time for each matched swing (s). */
    val offsets = ArrayList<Double>()
    var lastOffset = Double.NaN
        private set

    fun cueTime(i: Int): Double = start + lead + i * period

    /** Index of the cue the snowball is travelling towards at time [t] (cues..: finished). */
    fun cueIndexAt(t: Double): Int = ((t - start - lead) / period + 0.5).toInt().coerceIn(0, cues)

    /**
     * A swing stamped at time [t]. Matched to the nearest cue not yet used, within [MATCH_SEC]. Returns the offset
     * (positive = after the cue), or NaN when it does not belong to any cue.
     */
    fun swing(t: Double): Double {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until cues) {
            if (matched[i]) continue
            val d = t - cueTime(i)
            if (abs(d) <= MATCH_SEC && abs(d) < abs(bestD)) { best = i; bestD = d }
        }
        if (best < 0) return Double.NaN
        matched[best] = true
        offsets.add(bestD)
        lastOffset = bestD
        return bestD
    }

    fun finished(now: Double): Boolean = now > cueTime(cues - 1) + MATCH_SEC

    /** Middle value of the offsets (robust against a stray swing). */
    fun median(): Double {
        if (offsets.isEmpty()) return Double.NaN
        val s = offsets.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /** Typical distance of a swing from the median (s). */
    fun spread(): Double {
        if (offsets.isEmpty()) return Double.NaN
        val m = median()
        val d = offsets.map { abs(it - m) }.sorted()
        return d[d.size / 2]
    }

    sealed class Result {
        /** The measured delay, in milliseconds. */
        class Measured(val delayMs: Int, val swings: Int, val spreadMs: Int) : Result()
        object TooFewSwings : Result()
        object TooIrregular : Result()
    }

    fun result(): Result {
        if (offsets.size < MIN_SWINGS) return Result.TooFewSwings
        if (spread() > MAX_SPREAD_SEC) return Result.TooIrregular
        val ms = (median() * 1000.0).toInt().coerceIn(com.clrg.core.party.Settings.MIN_DELAY_MS, com.clrg.core.party.Settings.MAX_DELAY_MS)
        return Result.Measured(ms, offsets.size, (spread() * 1000.0).toInt())
    }

    companion object {
        /** A swing this close to a cue (either side) counts for it. */
        const val MATCH_SEC = 0.6
        const val MIN_SWINGS = 6
        /** Swings scattered more than this around their middle are too irregular to measure a delay from. */
        const val MAX_SPREAD_SEC = 0.12
    }
}
