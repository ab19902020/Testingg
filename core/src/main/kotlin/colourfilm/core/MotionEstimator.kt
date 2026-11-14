package colourfilm.core

import kotlin.math.abs

/**
 * Dense backward motion between two analysis frames, using coarse-to-fine block matching
 * with sub-pixel refinement and median outlier rejection.
 *
 * For each pixel of the current frame it returns where that pixel was in the previous
 * frame (flow in analysis-grid pixels). This is cheap enough to run on every frame on a
 * phone CPU because it works on a small luminance grid, not on full-resolution video.
 */
class MotionEstimator(private val size: Int) {
    private val levels = if (size >= 256) 3 else 2
    private val block = 8
    private val step = 4
    private val dims = IntArray(levels) { (size / 2) shr it } // 128, 64, 32 for size 256
    private val prevPyr = Array(levels) { FloatArray(dims[it] * dims[it]) }
    private val curPyr = Array(levels) { FloatArray(dims[it] * dims[it]) }
    private val nb = IntArray(levels) { (dims[it] - block) / step + 1 }
    private val vx = Array(levels) { FloatArray(nb[it] * nb[it]) }
    private val vy = Array(levels) { FloatArray(nb[it] * nb[it]) }
    private val tmpX = FloatArray(nb[0] * nb[0])
    private val tmpY = FloatArray(nb[0] * nb[0])

    init {
        require(size >= 64 && size % 8 == 0) { "analysis size must be a multiple of 8 and >= 64" }
    }

    fun estimate(prev: FloatArray, cur: FloatArray, flowX: FloatArray, flowY: FloatArray) {
        Resample.half(prev, size, size, prevPyr[0])
        Resample.half(cur, size, size, curPyr[0])
        for (l in 1 until levels) {
            Resample.half(prevPyr[l - 1], dims[l - 1], dims[l - 1], prevPyr[l])
            Resample.half(curPyr[l - 1], dims[l - 1], dims[l - 1], curPyr[l])
        }
        for (l in levels - 1 downTo 0) matchLevel(l)
        median3(vx[0], tmpX, nb[0]); median3(vy[0], tmpY, nb[0])
        densify(flowX, flowY)
    }

    private fun sad(p: FloatArray, c: FloatArray, d: Int, bx: Int, by: Int, dx: Int, dy: Int, limit: Float): Float {
        var s = 0f
        for (y in 0 until block) {
            val cy = by + y
            val py = (cy + dy).coerceIn(0, d - 1)
            val cRow = cy * d
            val pRow = py * d
            for (x in 0 until block) {
                val cx = bx + x
                val px = (cx + dx).coerceIn(0, d - 1)
                s += abs(c[cRow + cx] - p[pRow + px])
            }
            if (s > limit) return s
        }
        return s
    }

    private fun matchLevel(l: Int) {
        val d = dims[l]; val n = nb[l]
        val p = prevPyr[l]; val c = curPyr[l]
        val coarsest = l == levels - 1
        val radius = if (coarsest) 4 else 2
        val lambda = 0.02f * block * block
        for (j in 0 until n) for (i in 0 until n) {
            val bx = i * step; val by = j * step
            var px = 0f; var py = 0f
            if (!coarsest) {
                // predictor from the coarser level, sampled at this block's centre
                val nc = nb[l + 1]
                val u = ((bx + block / 2f) / 2f - block / 2f) / step
                val v = ((by + block / 2f) / 2f - block / 2f) / step
                px = 2f * Resample.sample(vx[l + 1], nc, nc, u, v)
                py = 2f * Resample.sample(vy[l + 1], nc, nc, u, v)
            }
            val cx0 = Math.round(px); val cy0 = Math.round(py)
            var best = sad(p, c, d, bx, by, 0, 0, Float.MAX_VALUE) + lambda * 0.02f * (abs(cx0) + abs(cy0))
            var bdx = 0; var bdy = 0
            for (dy in cy0 - radius..cy0 + radius) for (dx in cx0 - radius..cx0 + radius) {
                val pen = lambda * 0.02f * (abs(dx - cx0) + abs(dy - cy0))
                val s = sad(p, c, d, bx, by, dx, dy, best - pen) + pen
                if (s < best) { best = s; bdx = dx; bdy = dy }
            }
            var fx = bdx.toFloat(); var fy = bdy.toFloat()
            if (l == 0) {
                // parabolic sub-pixel refinement
                val s0 = sad(p, c, d, bx, by, bdx, bdy, Float.MAX_VALUE)
                val sl = sad(p, c, d, bx, by, bdx - 1, bdy, Float.MAX_VALUE)
                val sr = sad(p, c, d, bx, by, bdx + 1, bdy, Float.MAX_VALUE)
                val su = sad(p, c, d, bx, by, bdx, bdy - 1, Float.MAX_VALUE)
                val sd = sad(p, c, d, bx, by, bdx, bdy + 1, Float.MAX_VALUE)
                val denX = sl - 2 * s0 + sr
                val denY = su - 2 * s0 + sd
                if (denX > 1e-6f) fx += (0.5f * (sl - sr) / denX).coerceIn(-0.5f, 0.5f)
                if (denY > 1e-6f) fy += (0.5f * (su - sd) / denY).coerceIn(-0.5f, 0.5f)
            }
            vx[l][j * n + i] = fx; vy[l][j * n + i] = fy
        }
    }

    private fun median3(v: FloatArray, tmp: FloatArray, n: Int) {
        val w = FloatArray(9)
        for (j in 0 until n) for (i in 0 until n) {
            var k = 0
            for (dj in -1..1) for (di in -1..1) {
                val jj = (j + dj).coerceIn(0, n - 1); val ii = (i + di).coerceIn(0, n - 1)
                w[k++] = v[jj * n + ii]
            }
            w.sort()
            tmp[j * n + i] = w[4]
        }
        System.arraycopy(tmp, 0, v, 0, n * n)
    }

    private fun densify(flowX: FloatArray, flowY: FloatArray) {
        val n = nb[0]
        for (y in 0 until size) {
            val p = (y + 0.5f) / 2f - 0.5f
            val v = (p - (block / 2f - 0.5f)) / step
            for (x in 0 until size) {
                val q = (x + 0.5f) / 2f - 0.5f
                val u = (q - (block / 2f - 0.5f)) / step
                flowX[y * size + x] = 2f * Resample.sample(vx[0], n, n, u, v)
                flowY[y * size + x] = 2f * Resample.sample(vy[0], n, n, u, v)
            }
        }
    }
}
