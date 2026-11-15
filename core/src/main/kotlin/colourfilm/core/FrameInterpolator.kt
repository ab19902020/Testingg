package colourfilm.core

import kotlin.math.abs

/**
 * Creates in-between frames from two finished (restored, colourised) frames A and B, to raise
 * the frame rate of old film shot at 12–18 frames per second.
 *
 * Method (motion-compensated interpolation): motion is measured in both directions between A
 * and B; each new frame takes every point from where it was in A and where it will be in B and
 * mixes the two. Where those two disagree (something appeared or was uncovered, or the motion
 * was too complex to follow) it falls back smoothly to a plain cross-fade for that area, and if
 * most of the picture disagrees the pair is skipped altogether. Colour planes are moved with the
 * same motion, so in-between frames keep exactly the colours of their neighbours.
 *
 * Usage: [prepare] once per pair, then [render] for each in-between time 0 < t < 1.
 */
class FrameInterpolator(private val width: Int, private val height: Int, private val parallel: Parallel) {
    private val g = GRID
    private val est = MotionEstimator(g)
    private val cw = width / 2; private val ch = height / 2
    private val gridA = FloatArray(g * g); private val gridB = FloatArray(g * g)
    private val normA = FloatArray(g * g); private val normB = FloatArray(g * g)
    private val tmp = FloatArray(height * g)
    private val fx = FloatArray(g * g); private val fy = FloatArray(g * g)
    private val unit = FloatArray(256) { it / 255f }
    // motion at chroma resolution, in full-resolution luma pixels
    private val abX = FloatArray(cw * ch); private val abY = FloatArray(cw * ch) // for a point, where A-content moves to in B
    private val baX = FloatArray(cw * ch); private val baY = FloatArray(cw * ch)
    private val wa = ByteArray(width * height); private val wb = ByteArray(width * height)
    private val dis = FloatArray(cw * ch); private val disTmp = FloatArray(cw * ch)

    val midY = ByteArray(width * height)
    val midU = ByteArray(cw * ch)
    val midV = ByteArray(cw * ch)

    /** Share of the last rendered frame that had to fall back to a cross-fade (0..1). */
    var lastFallback = 0f; private set

    /** Measures motion between the two frames' luma planes (tightly packed width x height). */
    fun prepare(aY: ByteArray, bY: ByteArray) {
        Resample.lumaToGrid(aY, 0, width, width, height, unit, g, g, gridA, tmp)
        Resample.lumaToGrid(bY, 0, width, width, height, unit, g, g, gridB, tmp)
        TemporalColouriser.normalise(gridA, normA); TemporalColouriser.normalise(gridB, normB)
        // estimate(prev, cur) tells, for each point of cur, where it is in prev.
        est.estimate(normB, normA, fx, fy); upscale(fx, fy, abX, abY)   // A's points -> position in B
        est.estimate(normA, normB, fx, fy); upscale(fx, fy, baX, baY)   // B's points -> position in A
    }

    private fun upscale(gx: FloatArray, gy: FloatArray, outX: FloatArray, outY: FloatArray) {
        parallel.rows(ch) { r0, r1 ->
            Resample.upscaleRows(gx, 0, g, g, outX, cw, ch, r0, r1)
            Resample.upscaleRows(gy, 0, g, g, outY, cw, ch, r0, r1)
            val sx = width.toFloat() / g; val sy = height.toFloat() / g
            for (i in r0 * cw until r1 * cw) { outX[i] *= sx; outY[i] *= sy }
        }
    }

    /**
     * Renders the frame at time [t] (0 = A, 1 = B) into [midY], [midU], [midV].
     * Returns false when the motion could not be followed well enough; the caller should then
     * emit no in-between frame (the player simply holds A until B).
     */
    fun render(
        aY: ByteArray, aU: ByteArray, aV: ByteArray, bY: ByteArray, bU: ByteArray, bV: ByteArray, t: Float,
    ): Boolean {
        val u = 1f - t
        // 1. move A forward by t and B backward by (1 - t)
        parallel.rows(height) { r0, r1 ->
            for (y in r0 until r1) {
                val cRow = minOf(y / 2, ch - 1) * cw
                val o = y * width
                for (x in 0 until width) {
                    val c = cRow + minOf(x / 2, cw - 1)
                    wa[o + x] = sample(aY, width, height, x - t * abX[c], y - t * abY[c]).toByte()
                    wb[o + x] = sample(bY, width, height, x - u * baX[c], y - u * baY[c]).toByte()
                }
            }
        }
        // 2. where the two disagree, the motion is not trustworthy there
        parallel.rows(ch) { r0, r1 ->
            for (cy in r0 until r1) for (cx in 0 until cw) {
                val i = 2 * cy * width + 2 * cx
                dis[cy * cw + cx] = 0.25f * (
                    abs((wa[i].toInt() and 0xFF) - (wb[i].toInt() and 0xFF)) + abs((wa[i + 1].toInt() and 0xFF) - (wb[i + 1].toInt() and 0xFF)) +
                        abs((wa[i + width].toInt() and 0xFF) - (wb[i + width].toInt() and 0xFF)) +
                        abs((wa[i + width + 1].toInt() and 0xFF) - (wb[i + width + 1].toInt() and 0xFF)))
            }
        }
        box(dis, disTmp, 2)
        var fall = 0.0
        for (i in 0 until cw * ch) {
            val k = ((dis[i] - DISAGREE_LO) / (DISAGREE_HI - DISAGREE_LO)).coerceIn(0f, 1f)
            dis[i] = k; fall += k
        }
        lastFallback = (fall / (cw * ch)).toFloat()
        if (lastFallback > MAX_FALLBACK) return false
        // 3. mix
        parallel.rows(height) { r0, r1 ->
            for (y in r0 until r1) {
                val cRow = minOf(y / 2, ch - 1) * cw
                val o = y * width
                for (x in 0 until width) {
                    val k = dis[cRow + minOf(x / 2, cw - 1)]
                    val mc = u * (wa[o + x].toInt() and 0xFF) + t * (wb[o + x].toInt() and 0xFF)
                    val v = if (k <= 0f) mc else {
                        val fade = u * (aY[o + x].toInt() and 0xFF) + t * (bY[o + x].toInt() and 0xFF)
                        mc + k * (fade - mc)
                    }
                    midY[o + x] = (v + 0.5f).toInt().toByte()
                }
            }
        }
        parallel.rows(ch) { r0, r1 ->
            for (cy in r0 until r1) for (cx in 0 until cw) {
                val c = cy * cw + cx
                val k = dis[c]
                val ax = cx - t * abX[c] * 0.5f; val ay = cy - t * abY[c] * 0.5f
                val bx = cx - u * baX[c] * 0.5f; val by = cy - u * baY[c] * 0.5f
                var mu = u * sample(aU, cw, ch, ax, ay) + t * sample(bU, cw, ch, bx, by)
                var mv = u * sample(aV, cw, ch, ax, ay) + t * sample(bV, cw, ch, bx, by)
                if (k > 0f) {
                    mu += k * (u * (aU[c].toInt() and 0xFF) + t * (bU[c].toInt() and 0xFF) - mu)
                    mv += k * (u * (aV[c].toInt() and 0xFF) + t * (bV[c].toInt() and 0xFF) - mv)
                }
                midU[c] = (mu + 0.5f).toInt().toByte(); midV[c] = (mv + 0.5f).toInt().toByte()
            }
        }
        return true
    }

    /** Bilinear sample of an 8-bit plane with edge clamping; returns 0..255. */
    private fun sample(p: ByteArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xc = if (x < 0f) 0f else if (x > w - 1.001f) w - 1.001f else x
        val yc = if (y < 0f) 0f else if (y > h - 1.001f) h - 1.001f else y
        val ix = xc.toInt(); val iy = yc.toInt()
        val fx = xc - ix; val fy = yc - iy
        val i = iy * w + ix
        val top = (p[i].toInt() and 0xFF) + ((p[i + 1].toInt() and 0xFF) - (p[i].toInt() and 0xFF)) * fx
        val bot = (p[i + w].toInt() and 0xFF) + ((p[i + w + 1].toInt() and 0xFF) - (p[i + w].toInt() and 0xFF)) * fx
        return (top + (bot - top) * fy + 0.5f).toInt()
    }

    private fun box(a: FloatArray, t: FloatArray, r: Int) {
        for (y in 0 until ch) {
            val row = y * cw
            var s = 0f; var c = 0
            for (x in 0..minOf(r, cw - 1)) { s += a[row + x]; c++ }
            for (x in 0 until cw) {
                t[row + x] = s / c
                if (x + r + 1 < cw) { s += a[row + x + r + 1]; c++ }
                if (x - r >= 0) { s -= a[row + x - r]; c-- }
            }
        }
        for (x in 0 until cw) {
            var s = 0f; var c = 0
            for (y in 0..minOf(r, ch - 1)) { s += t[y * cw + x]; c++ }
            for (y in 0 until ch) {
                a[y * cw + x] = s / c
                if (y + r + 1 < ch) { s += t[(y + r + 1) * cw + x]; c++ }
                if (y - r >= 0) { s -= t[(y - r) * cw + x]; c-- }
            }
        }
    }

    companion object {
        /** Side of the square grid on which motion is measured. */
        const val GRID = 384
        /** Disagreement (8-bit codes) between the two motion-moved frames: trusted below LO, cross-fade above HI. */
        const val DISAGREE_LO = 8f
        const val DISAGREE_HI = 32f
        /** Skip the in-between frame when more than this share of the picture needed the cross-fade. */
        const val MAX_FALLBACK = 0.30f
    }
}
