package com.clrg.core.pose

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Low-light boost of dark model inputs: normal crops untouched, no flicker, cheap. */
class AutoLevelTest {
    private fun crop(n: Int, f: (Int) -> Float): FloatArray = FloatArray(n * 3) { f(it / 3) }

    @Test
    fun wellLitCropsAreUntouched() {
        val n = 64 * 64
        val buf = crop(n) { i -> 0.15f + 0.8f * (i % 97) / 96f }
        val copy = buf.copyOf()
        assertFalse(AutoLevel.apply(buf, n, 0f, 1f))
        assertTrue(buf.contentEquals(copy))
        // a fairly dim but normal crop (dark clothes, evening lamp light) is left alone too
        val dim = crop(n) { i -> 0.05f + 0.5f * (i % 97) / 96f }
        val dimCopy = dim.copyOf()
        assertFalse(AutoLevel.apply(dim, n, 0f, 1f))
        assertTrue(dim.contentEquals(dimCopy))
    }

    @Test
    fun darkCropsAreBrightened() {
        val n = 64 * 64
        val buf = crop(n) { i -> 0.02f + 0.12f * (i % 97) / 96f }
        assertTrue(AutoLevel.apply(buf, n, 0f, 1f))
        val max = buf.max()
        assertTrue(max > 0.5f, "brightest part lifted: $max")
        assertTrue(buf.all { it in 0f..1f })
        // order is kept: brighter stays brighter
        assertTrue(buf[3 * 96] > buf[3 * 10])
    }

    @Test
    fun aDarkFigureAgainstABrightWindowIsLifted() {
        val n = 64 * 64
        // 70% dark subject, 30% blown-out window
        val buf = crop(n) { i -> if (i % 10 < 7) 0.06f + 0.05f * (i % 7) / 6f else 0.97f }
        assertTrue(AutoLevel.apply(buf, n, 0f, 1f), "median is dark even though highlights are bright")
        assertTrue(buf[0] > 0.06f, "the subject got brighter")
    }

    @Test
    fun detectorRangeAndBlackPaddingAreHandled() {
        val n = 64 * 64
        // detector inputs are in -1..1; half the crop is padding (exactly -1) around a well-lit picture
        val buf = crop(n) { i -> if (i < n / 2) -1f else -1f + 2f * (0.2f + 0.7f * (i % 89) / 88f) }
        val copy = buf.copyOf()
        assertFalse(AutoLevel.apply(buf, n, -1f, 1f), "padding does not make a well-lit picture look dark")
        assertTrue(buf.contentEquals(copy))
        val dark = crop(n) { i -> if (i < n / 2) -1f else -1f + 2f * (0.03f + 0.1f * (i % 89) / 88f) }
        assertTrue(AutoLevel.apply(dark, n, -1f, 1f))
        assertEquals(-1f, dark[0], "padding stays black")
    }

    /**
     * 1.8.0 switched on abruptly: a crop just below the "dark" line was made ~1.7x brighter than one just above it,
     * so a room near the line flickered between the two and the body points jumped. Now a small change in light
     * gives a small change in what the model sees, all the way from bright to very dark.
     */
    @Test
    fun aSmallChangeInLightNeverMakesThePictureJump() {
        val n = 64 * 64
        fun outMean(scale: Float): Float {
            val buf = crop(n) { i -> scale * (0.1f + 0.9f * (i % 97) / 96f) }
            AutoLevel.apply(buf, n, 0f, 1f)
            return buf.average().toFloat()
        }
        var worst = 0f
        var s = 0.08f
        var prev = outMean(s)
        while (s < 0.9f) {
            s += 0.005f
            val m = outMean(s)
            worst = maxOf(worst, abs(m - prev))
            prev = m
        }
        println("[low-light] largest change in mean brightness for a 0.5%% change in light: %.4f".format(worst))
        assertTrue(worst < 0.03f, "no jump in brightness: $worst")
    }

    @Test
    fun aTrackedCropEasesIntoTheBoost() {
        val n = 64 * 64
        val state = AutoLevel.State()
        val bright = { crop(n) { i -> 0.15f + 0.8f * (i % 97) / 96f } }
        val dark = { crop(n) { i -> 0.02f + 0.12f * (i % 97) / 96f } }
        repeat(5) { AutoLevel.apply(bright(), n, 0f, 1f, state) }
        assertEquals(1f, state.gain)
        // the lights go down: the boost builds up over several frames, not in one step
        AutoLevel.apply(dark(), n, 0f, 1f, state)
        val first = state.gain
        repeat(20) { AutoLevel.apply(dark(), n, 0f, 1f, state) }
        val settled = state.gain
        assertTrue(first > 1f && first < 1f + 0.4f * (settled - 1f), "first frame $first, settled $settled")
        assertTrue(settled > 3f, "settled boost $settled")
    }

    @Test
    fun theLookupTableMatchesTheExactCurve() {
        val n = 64 * 64
        val src = crop(n) { i -> if (i % 10 < 7) 0.01f + 0.03f * (i % 7) / 6f else 0.2f + 0.05f * (i % 3) }
        val buf = src.copyOf()
        assertTrue(AutoLevel.apply(buf, n, 0f, 1f))
        val gain = AutoLevel.lastGain
        val gamma = AutoLevel.lastGamma
        var worst = 0f
        for (k in buf.indices) {
            val exact = (src[k] * gain).coerceIn(0f, 1f).pow(gamma)
            worst = maxOf(worst, abs(buf[k] - exact))
        }
        assertTrue(worst < 0.004f, "lookup error $worst (gain $gain, gamma $gamma)")
        assertTrue(gamma < 0.95f && ln(gamma.toDouble()) < 0, "this crop gets a mid-tone lift (gamma $gamma)")
    }

    @Test
    fun brighteningABodyCropIsCheap() {
        val n = 256 * 256
        val src = crop(n) { i -> val r = (i * 7919) % 1000; if (r < 900) 0.02f + 0.04f * r / 900f else 0.3f }
        val buf = src.copyOf()
        repeat(40) { System.arraycopy(src, 0, buf, 0, buf.size); AutoLevel.apply(buf, n, 0f, 1f) }
        val runs = 100
        val t0 = System.nanoTime()
        repeat(runs) { System.arraycopy(src, 0, buf, 0, buf.size); AutoLevel.apply(buf, n, 0f, 1f) }
        val ms = (System.nanoTime() - t0) / 1e6f / runs
        println("[low-light] a dark 256x256 body crop takes %.2f ms to brighten here (1.8.0: about 3.7 ms on the same machine)".format(ms))
        assertTrue(AutoLevel.lastGamma < 1f, "the slow path (mid-tone lift) was exercised")
        assertTrue(ms < 2.5f, "brightening a crop took $ms ms")
    }
}
