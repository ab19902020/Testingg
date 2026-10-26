package com.clrg.core.pose

import kotlin.math.ln
import kotlin.math.pow

/**
 * Brightens dark or backlit model inputs before inference. Living rooms in the evening are often dim, and a player
 * standing in front of a bright window is a dark shape; the pose models were trained mostly on well-lit photos.
 *
 * Works on a crop buffer that is already sampled (interleaved RGB, values mapped linearly from 0..255 into
 * [lo]..[hi]). Well-exposed crops are left exactly as they are, so normal lighting gives the same results as before.
 */
object AutoLevel {
    private val hist = IntArray(64)

    /** Brightness statistics of the last call (for diagnostics): median and 98th percentile, 0..1. */
    var lastMedian = 0f
        private set
    var lastHigh = 0f
        private set
    /** Gain applied by the last call (1 = untouched). */
    var lastGain = 1f
        private set

    /**
     * @param n number of pixels (the buffer holds 3n values).
     * @return true when the crop was adjusted.
     */
    fun apply(buf: FloatArray, n: Int, lo: Float, hi: Float): Boolean {
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
        if (count < 50) return false
        val median = percentile(count, 0.5f)
        val high = percentile(count, 0.98f)
        lastMedian = median; lastHigh = high; lastGain = 1f
        if (high >= DARK_HIGH && median >= DARK_MEDIAN) return false
        // stretch so the brightest few percent reach near white (bounded: noise grows with the gain)
        val gain = (0.92f / high.coerceAtLeast(0.04f)).coerceIn(1f, MAX_GAIN)
        // then lift the mid-tones if the middle of the picture is still dark
        val m = (median * gain).coerceAtLeast(0.02f)
        val gamma = if (m < 0.38f) (ln(0.42f) / ln(m)).coerceIn(0.5f, 1f) else 1f
        if (gain < 1.05f && gamma > 0.97f) return false
        lastGain = gain
        for (k in 0 until n * 3) {
            val v = ((buf[k] - lo) / span * gain).coerceIn(0f, 1f)
            buf[k] = lo + span * (if (gamma < 1f) v.pow(gamma) else v)
        }
        return true
    }

    private fun percentile(count: Int, p: Float): Float {
        val target = (count * p).toInt()
        var acc = 0
        for (b in 0 until 64) {
            acc += hist[b]
            if (acc > target) return (b + 0.5f) / 64f
        }
        return 1f
    }

    /** Crops whose 98th-percentile brightness is below this are treated as dark. */
    const val DARK_HIGH = 0.55f
    /** ... and so are crops whose median brightness is below this (a dark subject against a bright window). */
    const val DARK_MEDIAN = 0.2f
    const val MAX_GAIN = 4f
}
