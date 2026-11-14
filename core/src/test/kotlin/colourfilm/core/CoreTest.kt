package colourfilm.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun labRoundTrip() {
        val lab = FloatArray(3); val rgb = FloatArray(3)
        for (c in listOf(floatArrayOf(0.8f, 0.3f, 0.2f), floatArrayOf(0.1f, 0.6f, 0.9f), floatArrayOf(0.5f, 0.5f, 0.5f))) {
            ColourMath.srgbToLab(c[0], c[1], c[2], lab)
            ColourMath.labToLinearRgb(lab[0], lab[1], lab[2], rgb)
            for (k in 0..2) assertEquals(c[k], ColourMath.linearToSrgb(rgb[k]), 0.01f)
        }
        // neutral grey L matches a grey with zero chroma
        ColourMath.srgbToLab(0.4f, 0.4f, 0.4f, lab)
        assertEquals(lab[0], ColourMath.greyToL(0.4f), 0.2f)
    }

    @Test
    fun zeroChromaGivesNeutralPlanes() {
        val w = 64; val h = 48
        val y = ByteArray(w * h) { (16 + (it % 200)).toByte() }
        val c = ChromaComposer(w, h, VideoColour(VideoColour.Matrix.BT709, false), ColouriseSettings(), Parallel(1))
        val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
        c.compose(y, 0, w, FloatArray(2 * 16 * 16), 16, u, v)
        assertTrue(u.all { abs((it.toInt() and 0xFF) - 128) <= 1 })
        assertTrue(v.all { abs((it.toInt() and 0xFF) - 128) <= 1 })
    }

    @Test
    fun redChromaProducesRedCr() {
        val w = 32; val h = 32
        val y = ByteArray(w * h) { 120.toByte() }
        val c = ChromaComposer(w, h, VideoColour(VideoColour.Matrix.BT601, false), ColouriseSettings(edgeRefine = false), Parallel(1))
        val ab = FloatArray(2 * 8 * 8); for (i in 0 until 64) ab[i] = 50f // +a = red
        val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
        c.compose(y, 0, w, ab, 8, u, v)
        assertTrue((v[0].toInt() and 0xFF) > 150, "Cr should be strongly positive")
    }

    private fun texture(size: Int, dx: Float, dy: Float) = FloatArray(size * size) { i ->
        val x = i % size - dx; val y = i / size - dy
        0.5f + 0.25f * sin(x * 0.21f) * sin(y * 0.17f) + 0.2f * sin((x + 2 * y) * 0.07f)
    }

    @Test
    fun motionEstimatorRecoversShift() {
        val s = 128
        val prev = texture(s, 0f, 0f)
        val cur = texture(s, 3f, -2f) // content moved +3,-2 => previous position is x-3, y+2
        val fx = FloatArray(s * s); val fy = FloatArray(s * s)
        val pn = FloatArray(s * s); val cn = FloatArray(s * s)
        TemporalColouriser.normalise(prev, pn); TemporalColouriser.normalise(cur, cn)
        MotionEstimator(s).estimate(pn, cn, fx, fy)
        val c = (s / 2) * s + s / 2
        assertEquals(-3f, fx[c], 0.6f); assertEquals(2f, fy[c], 0.6f)
    }

    private class ConstModel(val value: Float) : ChromaModel {
        override val inputSize = 64; override val id = "const"
        var calls = 0
        override fun predict(grey: FloatArray, outAb: FloatArray) { calls++; outAb.fill(value) }
    }

    @Test
    fun temporalStrideAndCutReset() {
        val m = ConstModel(10f)
        val t = TemporalColouriser(m, ColouriseSettings(speed = ColouriseSettings.Speed.FAST))
        val ab = FloatArray(2 * 64 * 64)
        val a = texture(64, 0f, 0f)
        repeat(8) { t.next(a, ab) }
        assertEquals(2, m.calls, "static scene: model every 4th frame")
        // completely different picture => cut => model re-run immediately and no carry-over
        val b = FloatArray(64 * 64) { if ((it / 64 + it % 64) % 7 < 3) 0.05f else 0.95f }
        t.next(b, ab)
        assertTrue(t.lastCut)
        assertEquals(3, m.calls)
        assertEquals(10f, ab[100], 1e-4f)
    }

    @Test
    fun planSegments() {
        val p = JobPlan(0, 61_000_000, 15_000_000)
        assertEquals(5, p.segmentCount)
        assertEquals(60_000_000, p.segmentStart(4)); assertEquals(61_000_000, p.segmentEnd(4))
        assertEquals(4, p.segmentOf(60_500_000))
        val f1 = JobPlan.fingerprint("src", "m", ColouriseSettings(), p, 1920, 1080)
        val f2 = JobPlan.fingerprint("src", "m", ColouriseSettings(intensity = 1.2f), p, 1920, 1080)
        assertTrue(f1 != f2)
    }

    @Test
    fun stateRoundTrip() {
        val m = ConstModel(5f)
        val t = TemporalColouriser(m, ColouriseSettings())
        val ab = FloatArray(2 * 64 * 64)
        t.next(texture(64, 0f, 0f), ab)
        val st = t.exportState()
        val t2 = TemporalColouriser(m, ColouriseSettings()); t2.importState(st)
        t2.next(texture(64, 0.5f, 0f), ab)
        assertTrue(!t2.lastCut)
    }
}
