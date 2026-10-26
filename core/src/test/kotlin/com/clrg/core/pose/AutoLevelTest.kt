package com.clrg.core.pose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Brightening of dark model inputs: well-lit crops must be left exactly as they are. */
class AutoLevelTest {
    private fun crop(n: Int, f: (Int) -> Float): FloatArray = FloatArray(n * 3) { f(it / 3) }

    @Test
    fun wellLitCropsAreUntouched() {
        val n = 64 * 64
        val buf = crop(n) { i -> 0.15f + 0.8f * (i % 97) / 96f }
        val copy = buf.copyOf()
        assertFalse(AutoLevel.apply(buf, n, 0f, 1f))
        assertTrue(buf.contentEquals(copy))
    }

    @Test
    fun darkCropsAreBrightened() {
        val n = 64 * 64
        val buf = crop(n) { i -> 0.02f + 0.12f * (i % 97) / 96f }
        assertTrue(AutoLevel.apply(buf, n, 0f, 1f))
        val max = buf.max()
        assertTrue(max > 0.6f, "brightest part lifted: $max")
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
}
