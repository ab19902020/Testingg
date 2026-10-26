package com.clrg.core.pose

import kotlin.math.ln
import kotlin.math.pow

/**
 * Low-light boost: brightens clearly dark model inputs before inference. Living rooms in the evening can be dim, and
 * the pose models were trained mostly on well-lit photos.
 *
 * Works on a crop buffer that is already sampled (interleaved RGB, values mapped linearly from 0..255 into
 * [lo]..[hi]). Three rules keep it from disturbing tracking:
 *  - crops with normal brightness are left exactly as they are. A dark crop is brought up to a fixed target (its
 *    brightest parts to [TARGET_HIGH], its middle to [TARGET_MID]) and no further, so the correction starts from
 *    nothing at the edge of "dark", there is no point where a small change in light makes the picture jump, and a
 *    brighter room never comes out darker than a dimmer one;
 *  - with a [State] (one per tracked person or hand) the correction changes smoothly from frame to frame, so the
 *    model never sees the brightness flicker;
 *  - it is applied through a lookup table, so it costs a fraction of a millisecond per crop.
 */
object AutoLevel {
    /** Smoothed correction for one tracked crop (a person or a hand). */
    class State {
        var gain = 1f
        var gamma = 1f
        var seen = false
    }

    private val hist = IntArray(64)
    private const val LUT_SIZE = 1024
    private val lut = FloatArray(LUT_SIZE + 1)
    private var lutGain = Float.NaN
    private var lutGamma = Float.NaN

    /** Brightness statistics of the last call (for diagnostics): median and 98th percentile, 0..1. */
    var lastMedian = 0f
        private set
    var lastHigh = 0f
        private set
    /** Correction applied by the last call (1 and 1 = untouched). */
    var lastGain = 1f
        private set
    var lastGamma = 1f
        private set

    /**
     * @param n number of pixels (the buffer holds 3n values).
     * @param state smoothing for a tracked crop; null applies this crop's own correction directly.
     * @param minChange a correction smaller than this (in gain above 1 or gamma below 1) is not worth applying.
     * @param search a one-off look for people in a dark picture (not frame-to-frame tracking, so flicker cannot
     *   matter): its highlights are stretched up to near white ([SEARCH_HIGH]) instead of [TARGET_HIGH].
     * @return true when the crop was adjusted.
     */
    fun apply(buf: FloatArray, n: Int, lo: Float, hi: Float, state: State? = null, minChange: Float = 0.02f, search: Boolean = false): Boolean {
        val span = hi - lo
        hist.fill(0)
        var count = 0
        var i = 0
        // luminance histogram over a subsample of the pixels
        while (i < n) {
            val o = i * 3
            // padding outside the picture (exactly black in all channels) is not part of the scene
            if (buf[o] == lo && buf[o + 1] == lo && buf[o + 2] == lo) { i += 5; continue }
            val y = (0.299f * (buf[o] - lo) + 0.587f * (buf[o + 1] - lo) + 0.114f * (buf[o + 2] - lo)) / span
            hist[(y * 63.99f).toInt().coerceIn(0, 63)]++
            count++
            i += 5
        }
        var tGain = 1f
        var tGamma = 1f
        if (count >= 50) {
            val median = percentile(count, 0.5f)
            val high = percentile(count, 0.98f)
            lastMedian = median; lastHigh = high
            // stretch dim highlights up to the target (bounded: noise grows with the gain)
            tGain = ((if (search) SEARCH_HIGH else TARGET_HIGH) / high.coerceAtLeast(1e-3f)).coerceIn(1f, maxGain)
            // then lift the mid-tones up to their target if the middle is still very dark (a figure against a window)
            val m = (median * tGain).coerceAtLeast(1e-3f)
            tGamma = if (m < TARGET_MID) (ln(TARGET_MID) / ln(m)).coerceIn(minGamma, 1f) else 1f
        }
        var gain = tGain
        var gamma = tGamma
        if (state != null) {
            if (!state.seen) { state.gain = tGain; state.gamma = tGamma; state.seen = true }
            else { state.gain += SMOOTH * (tGain - state.gain); state.gamma += SMOOTH * (tGamma - state.gamma) }
            gain = state.gain; gamma = state.gamma
        }
        lastGain = gain; lastGamma = gamma
        if (gain - 1f < minChange && 1f - gamma < minChange) return false
        if (gain != lutGain || gamma != lutGamma) {
            for (k in 0..LUT_SIZE) {
                val v = (k.toFloat() / LUT_SIZE * gain).coerceIn(0f, 1f)
                lut[k] = if (gamma < 1f) v.pow(gamma) else v
            }
            lutGain = gain; lutGamma = gamma
        }
        val toIdx = LUT_SIZE / span
        for (k in 0 until n * 3) {
            val x = ((buf[k] - lo) * toIdx).coerceIn(0f, LUT_SIZE - 0.001f)
            val j = x.toInt()
            val f = x - j
            buf[k] = lo + span * (lut[j] + (lut[j + 1] - lut[j]) * f)
        }
        return true
    }

    /** Brightness below which a fraction [p] of the sampled pixels lie, interpolated within the bin (no steps). */
    private fun percentile(count: Int, p: Float): Float {
        val target = count * p
        var acc = 0
        for (b in 0 until 64) {
            val h = hist[b]
            if (acc + h > target) return (b + (target - acc) / h) / 64f
            acc += h
        }
        return 1f
    }

    /** Dark crops have their brightest 2% brought up to this (crops already brighter are untouched). */
    const val TARGET_HIGH = 0.5f
    /** Highlights target for the person detector's search of a dark picture. */
    const val SEARCH_HIGH = 0.92f
    /** ... and their middle brightness up to this, if still darker after the stretch. */
    const val TARGET_MID = 0.15f
    /** How far the smoothed correction moves towards a new frame's value (per frame). */
    const val SMOOTH = 0.25f
    /** Largest brightening (noise grows with it) and the strongest mid-tone lift (gamma; 1 = none). */
    @Volatile var maxGain = 4f
    @Volatile var minGamma = 0.5f
}
