package colourfilm.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestorationTest {
    private val w = 128; private val h = 96

    private fun scene(shift: Int = 0, gain: Float = 1f, noise: Int = 0, seed: Int = 1): ByteArray {
        val r = java.util.Random(seed.toLong())
        return ByteArray(w * h) { i ->
            val x = i % w + shift; val y = i / w
            val base = 60 + ((x / 8 + y / 8) % 2) * 80 + (x % 16)
            ((base * gain) + (if (noise > 0) r.nextGaussian() * noise else 0.0)).toInt().coerceIn(16, 235).toByte()
        }
    }

    @Test
    fun dustSpeckInStillSceneIsRemovedAndOutputIsOneFrameLate() {
        val d = DustRemover(w, h, true)
        val clean = scene()
        val dirty = clean.copyOf()
        for (y in 40..43) for (x in 60..63) dirty[y * w + x] = 235.toByte() // 4x4 white speck, one frame only
        assertTrue(!d.push(clean, 0, w))
        assertTrue(d.push(dirty, 0, w)); assertTrue(d.out.contentEquals(clean), "first frame passes through")
        assertTrue(d.push(clean, 0, w))
        assertEquals(1, d.spotsRemoved)
        assertTrue(abs((d.out[41 * w + 61].toInt() and 0xFF) - (clean[41 * w + 61].toInt() and 0xFF)) <= 1, "speck repaired")
        assertTrue(d.flush()); assertTrue(d.out.contentEquals(clean))
        assertTrue(!d.flush())
    }

    @Test
    fun smallMovingObjectIsNotTreatedAsDust() {
        val d = DustRemover(w, h, true)
        // a dark 4x4 "foot" attached to a moving 4x30 "leg": appears at each place for one frame
        fun frame(pos: Int) = scene().also { f ->
            for (y in 30..63) for (x in pos..pos + 3) f[y * w + x] = 20
        }
        d.push(frame(20), 0, w); d.push(frame(40), 0, w); d.push(frame(60), 0, w)
        assertEquals(0, d.spotsRemoved)
        assertTrue(d.out.contentEquals(frame(40)))
    }

    @Test
    fun flickerIsSteadiedAndCleanFootageIsLeftAlone() {
        val settings = ColouriseSettings(grain = ColouriseSettings.Grain.OFF, sharpen = false, dust = false)
        val e = LumaEnhancer(w, h, VideoColour(VideoColour.Matrix.BT601, false), settings, Parallel(1))
        fun mean(b: ByteArray) = b.sumOf { it.toInt() and 0xFF } / b.size.toDouble()
        val means = ArrayList<Double>()
        for (i in 0 until 30) {
            val f = scene(gain = if (i % 2 == 0) 1.12f else 0.9f)
            e.levels(f, 0, w); means += mean(f)
        }
        val swing = means.drop(10).zipWithNext { a, b -> abs(a - b) }.average()
        assertTrue(swing < 4.0, "flicker of about 25 codes reduced to $swing")

        val e2 = LumaEnhancer(w, h, VideoColour(VideoColour.Matrix.BT601, false), settings, Parallel(1))
        // full-contrast picture: blacks at black, whites at white
        val steady = ByteArray(w * h) { i -> (16 + ((i % w) * 219 / (w - 1))).toByte() }
        repeat(5) { val f = steady.copyOf(); e2.levels(f, 0, w); assertTrue(f.contentEquals(steady), "steady full-contrast footage unchanged") }
    }

    @Test
    fun grainIsReducedOnStillScene() {
        val settings = ColouriseSettings(deflicker = false, autoContrast = false, sharpen = false, dust = false, grain = ColouriseSettings.Grain.STRONG)
        val e = LumaEnhancer(w, h, VideoColour(VideoColour.Matrix.BT601, false), settings, Parallel(1))
        val clean = scene()
        val zero = FloatArray(64 * 64)
        fun rms(b: ByteArray): Double { var s = 0.0; for (i in b.indices) { val d = (b[i].toInt() and 0xFF) - (clean[i].toInt() and 0xFF); s += d * d }; return Math.sqrt(s / b.size) }
        var last = 0.0; var first = 0.0
        for (i in 0 until 12) {
            val f = scene(noise = 8, seed = i + 10)
            if (i == 0) first = rms(f)
            e.denoise(f, 0, w, zero, zero, 64, historyValid = i > 0)
            last = rms(f)
        }
        assertTrue(last < 0.6 * first, "grain rms $first reduced to $last")
    }

    private class Flat : ChromaModel {
        override val inputSize = 64; override val id = "flat"
        override fun predict(grey: FloatArray, outAb: FloatArray) { outAb.fill(5f) }
    }

    @Test
    fun everyFrameComesOutOnceWithDustRemovalOnOrOff() {
        for (dust in listOf(true, false)) {
            val p = FrameProcessor(w, h, VideoColour(VideoColour.Matrix.BT601, false), Flat(), ColouriseSettings(dust = dust), Parallel(1))
            var outFrames = 0
            repeat(7) { if (p.push(scene(shift = it), 0, w)) outFrames++ }
            while (p.flush()) outFrames++
            assertEquals(7, outFrames, "dust=$dust")
        }
    }
}

class InterpolationTest {
    private val w = 192; private val h = 128
    private fun frame(shift: Int) = ByteArray(w * h) { i ->
        val x = i % w - shift; val y = i / w
        (128 + 60 * Math.sin(x * 0.19) * Math.cos(y * 0.23) + 40 * Math.sin((x + y) * 0.07)).toInt().coerceIn(16, 235).toByte()
    }

    @Test
    fun inBetweenFrameIsHalfwayAlongTheMotion() {
        val fi = FrameInterpolator(w, h, Parallel(1))
        val a = frame(0); val b = frame(8); val truth = frame(4)
        val grey = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        fi.prepare(a, b)
        assertTrue(fi.render(a, grey, grey, b, grey, grey, 0.5f))
        fun err(x: ByteArray): Double { var s = 0.0; var n = 0
            for (yy in 16 until h - 16) for (xx in 16 until w - 16) { val i = yy * w + xx; s += abs((x[i].toInt() and 0xFF) - (truth[i].toInt() and 0xFF)); n++ }
            return s / n }
        val blend = ByteArray(w * h) { (((a[it].toInt() and 0xFF) + (b[it].toInt() and 0xFF)) / 2).toByte() }
        val e = err(fi.midY); val eb = err(blend)
        assertTrue(e < 0.4 * eb, "motion-compensated error $e should be far below plain blend $eb")
    }

    @Test
    fun unrelatedFramesAreNotInterpolated() {
        val fi = FrameInterpolator(w, h, Parallel(1))
        val a = frame(0)
        val b = ByteArray(w * h) { i -> if (((i % w) / 6 + (i / w) / 6) % 2 == 0) 30 else 220.toByte() }
        val grey = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        fi.prepare(a, b)
        assertTrue(!fi.render(a, grey, grey, b, grey, grey, 0.5f), "fallback share ${fi.lastFallback}")
    }

    @Test
    fun autoModeOnlyActsOnLowFrameRates() {
        val s = ColouriseSettings()
        assertEquals(0, s.inBetweenFrames(30f)); assertEquals(0, s.inBetweenFrames(24f))
        assertEquals(1, s.inBetweenFrames(16f)); assertEquals(2, s.inBetweenFrames(10f))
        assertEquals(1, s.copy(smooth = ColouriseSettings.Smooth.DOUBLE).inBetweenFrames(30f))
        assertEquals(0, s.copy(smooth = ColouriseSettings.Smooth.OFF).inBetweenFrames(12f))
    }
}
