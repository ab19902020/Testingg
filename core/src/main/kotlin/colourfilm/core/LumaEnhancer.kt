package colourfilm.core

import kotlin.math.abs

/**
 * Picture restoration for the black-and-white image itself (the luma plane), done before
 * colour is added. All steps are conservative and measure the footage first, so clean modern
 * video is left almost untouched while damaged old film is corrected:
 *
 *  - [levels]: removes exposure flicker (brightness pumping between frames) and restores faded
 *    contrast, with limits on how far it will stretch.
 *  - [denoise]: grain reduction. Each pixel is averaged with the same point of the previous
 *    restored frame (found through the tracked motion), but only where the two agree to within
 *    the measured noise level, so moving things are not smeared or ghosted.
 *  - sharpening: a light unsharp mask applied after grain reduction.
 */
class LumaEnhancer(
    private val width: Int,
    private val height: Int,
    private val colour: VideoColour,
    private val settings: ColouriseSettings,
    private val parallel: Parallel,
) {
    private val black = if (colour.fullRange) 0 else 16
    private val white = if (colour.fullRange) 255 else 235

    // ---- flicker + contrast -------------------------------------------------------------
    private val hist = IntArray(256)
    private val lut = ByteArray(256)
    private var haveStats = false
    private var emaMid = 0f; private var emaSpread = 0f
    private var emaLo = 0f; private var emaHi = 0f

    /** Last measurements, for tests and diagnostics. */
    var lastGain = 1f; private set
    var lastNoiseSigma = 0f; private set

    /** In-place flicker and contrast correction of the luma plane. */
    fun levels(y: ByteArray, off: Int, stride: Int) {
        if (!settings.deflicker && !settings.autoContrast) return
        hist.fill(0)
        var count = 0
        var row = 0
        while (row < height) {
            val r = off + row * stride
            var x = 0
            while (x < width) { hist[y[r + x].toInt() and 0xFF]++; count++; x += 3 }
            row += 3
        }
        val p1 = percentile(count, 0.01f); val p5 = percentile(count, 0.05f)
        val p50 = percentile(count, 0.50f); val p95 = percentile(count, 0.95f); val p99 = percentile(count, 0.99f)
        val mid = p50.toFloat(); val spread = maxOf(4f, (p95 - p5).toFloat())

        // A large jump in the statistics is a new scene, not flicker: start the averages again.
        val sceneChange = !haveStats || abs(mid - emaMid) > 40f || spread > 1.8f * emaSpread || spread < 0.55f * emaSpread
        if (sceneChange) {
            emaMid = mid; emaSpread = spread; emaLo = p1.toFloat(); emaHi = p99.toFloat(); haveStats = true
        } else {
            emaMid += 0.12f * (mid - emaMid); emaSpread += 0.12f * (spread - emaSpread)
            emaLo += 0.04f * (p1 - emaLo); emaHi += 0.04f * (p99 - emaHi)
        }

        // Step 1 (flicker): map this frame's middle/spread onto the recent average.
        var g1 = 1f; var m0 = mid; var m1 = mid
        if (settings.deflicker && !sceneChange) {
            g1 = (emaSpread / spread).coerceIn(0.8f, 1.25f)
            m1 = mid + (emaMid - mid).coerceIn(-30f, 30f)
        }
        // Step 2 (contrast): stretch the scene's steady black/white points towards full range.
        var g2 = 1f; var lo = 0f; var newLo = 0f
        if (settings.autoContrast) {
            // Faded film has lifted, grey blacks. A scene that is simply dark (blacks already
            // black) or bright is left alone: only a lifted black point triggers a stretch.
            val lift = emaLo - (black + 3)
            if (lift >= MIN_BLACK_LIFT) {
                val range = maxOf(16f, emaHi - emaLo)
                val full = (white - black - 6).toFloat()
                val want = (full / range).coerceIn(1f, MAX_CONTRAST_GAIN)
                g2 = 1f + CONTRAST_STRENGTH * (want - 1f)
                lo = emaLo
                newLo = emaLo - CONTRAST_STRENGTH * lift
                // never push highlights past white
                val hiAfter = newLo + (emaHi - emaLo) * g2
                if (hiAfter > white - 3) g2 = maxOf(1f, (white - 3 - newLo) / maxOf(1f, emaHi - emaLo))
            }
        }
        lastGain = g1 * g2
        if (g1 == 1f && g2 == 1f && m0 == m1 && lo == newLo) return
        for (c in 0..255) {
            var v = (c - m0) * g1 + m1
            if (g2 != 1f || lo != newLo) v = (v - lo) * g2 + newLo
            lut[c] = (v + 0.5f).toInt().coerceIn(black, white).toByte()
        }
        parallel.rows(height) { r0, r1 ->
            for (ry in r0 until r1) {
                val r = off + ry * stride
                for (x in 0 until width) y[r + x] = lut[y[r + x].toInt() and 0xFF]
            }
        }
    }

    private fun percentile(count: Int, p: Float): Int {
        val target = (count * p).toInt()
        var acc = 0
        for (i in 0..255) { acc += hist[i]; if (acc > target) return i }
        return 255
    }

    // ---- grain reduction + sharpening -----------------------------------------------------
    private val prev = ByteArray(width * height)
    private val work = ByteArray(width * height)
    private var havePrev = false
    private var sigma = 0f
    private val diffHist = IntArray(64)

    /**
     * In-place grain reduction (and sharpening) of the luma plane.
     * @param flowX/flowY backward motion on the analysis grid ([grid] x [grid]); pixel units of that grid
     * @param historyValid false on the first frame of a shot (nothing to average with)
     */
    fun denoise(y: ByteArray, off: Int, stride: Int, flowX: FloatArray, flowY: FloatArray, grid: Int, historyValid: Boolean) {
        val strength = settings.grain
        if (strength == ColouriseSettings.Grain.OFF && !settings.sharpen) return
        val doTemporal = strength != ColouriseSettings.Grain.OFF && havePrev && historyValid
        if (doTemporal) {
            measureNoise(y, off, stride, flowX, flowY, grid)
            // No measurable grain: leave the picture alone.
            if (sigma >= MIN_SIGMA) temporalFilter(y, off, stride, flowX, flowY, grid, strength)
        }
        // keep the (unsharpened) result as history for the next frame
        for (ry in 0 until height) System.arraycopy(y, off + ry * stride, prev, ry * width, width)
        havePrev = true
        if (settings.sharpen) sharpen(y, off, stride)
    }

    private inline fun blockShift(flow: FloatArray, grid: Int, bx: Int, by: Int, scale: Float): Int {
        val gx = ((bx + BLOCK / 2f) * grid / width).toInt().coerceIn(0, grid - 1)
        val gy = ((by + BLOCK / 2f) * grid / height).toInt().coerceIn(0, grid - 1)
        return Math.round(flow[gy * grid + gx] * scale)
    }

    /** Robust noise level: median absolute frame difference along motion, on a sparse sample. */
    private fun measureNoise(y: ByteArray, off: Int, stride: Int, flowX: FloatArray, flowY: FloatArray, grid: Int) {
        diffHist.fill(0)
        var n = 0
        val sx = width.toFloat() / grid; val sy = height.toFloat() / grid
        var by = 0
        while (by < height - BLOCK) {
            var bx = 0
            while (bx < width - BLOCK) {
                val dx = blockShift(flowX, grid, bx, by, sx); val dy = blockShift(flowY, grid, bx, by, sy)
                val px = bx + 3 + dx; val py = by + 3 + dy
                if (px in 0 until width && py in 0 until height) {
                    val d = abs((y[off + (by + 3) * stride + bx + 3].toInt() and 0xFF) - (prev[py * width + px].toInt() and 0xFF))
                    diffHist[minOf(63, d)]++; n++
                }
                bx += BLOCK
            }
            by += BLOCK
        }
        if (n < 50) return
        var acc = 0; var med = 0
        for (i in 0..63) { acc += diffHist[i]; if (acc * 2 >= n) { med = i; break } }
        // median |difference of two noisy samples| = 0.954 * sigma for Gaussian noise; the history
        // frame is already cleaner, so treat the difference as mostly this frame's noise.
        val s = med / 0.75f
        sigma = if (sigma == 0f) s else sigma + 0.2f * (s - sigma)
        lastNoiseSigma = sigma
    }

    private fun temporalFilter(
        y: ByteArray, off: Int, stride: Int, flowX: FloatArray, flowY: FloatArray, grid: Int, strength: ColouriseSettings.Grain,
    ) {
        val sx = width.toFloat() / grid; val sy = height.toFloat() / grid
        val t1 = 1.6f * sigma + 2f      // differences up to here are treated as grain
        val t2 = 3.5f * sigma + 5f      // beyond this the picture really changed
        // Heavier grain gets a stronger blend (up to +0.2), since there is more to remove.
        val kMax = (strength.blend + 0.2f * ((sigma - 3f) / 5f).coerceIn(0f, 1f)).coerceAtMost(0.85f)
        // weight table by |local mean difference| * 4
        val wLut = FloatArray(256 * 4) { i ->
            val d = i / 4f
            when { d <= t1 -> kMax; d >= t2 -> 0f; else -> kMax * (t2 - d) / (t2 - t1) }
        }
        val blocksY = (height + BLOCK - 1) / BLOCK
        parallel.rows(blocksY) { b0, b1 ->
            val diff = IntArray((BLOCK + 2) * (BLOCK + 2))
            for (bRow in b0 until b1) {
                val by = bRow * BLOCK
                val bh = minOf(BLOCK, height - by)
                var bx = 0
                while (bx < width) {
                    val bw = minOf(BLOCK, width - bx)
                    var dx = blockShift(flowX, grid, bx, by, sx); var dy = blockShift(flowY, grid, bx, by, sy)
                    // keep the shifted block (plus a 1 px border) inside the frame, else no history
                    if (bx + dx < 1 || by + dy < 1 || bx + bw + dx > width - 1 || by + bh + dy > height - 1) { dx = 0; dy = 0 }
                    val x0 = maxOf(bx, 1); val x1 = minOf(bx + bw, width - 1)
                    val y0 = maxOf(by, 1); val y1 = minOf(by + bh, height - 1)
                    // differences on the block plus a 1 px border, for a 3x3 local mean
                    val dw = bw + 2
                    for (yy in y0 - 1..y1) for (xx in x0 - 1..x1) {
                        diff[(yy - by + 1) * dw + (xx - bx + 1)] =
                            (y[off + yy * stride + xx].toInt() and 0xFF) - (prev[(yy + dy) * width + xx + dx].toInt() and 0xFF)
                    }
                    for (yy in y0 until y1) {
                        val o = off + yy * stride
                        for (xx in x0 until x1) {
                            val c = (yy - by + 1) * dw + (xx - bx + 1)
                            val local = diff[c - dw - 1] + diff[c - dw] + diff[c - dw + 1] + diff[c - 1] + diff[c] + diff[c + 1] +
                                diff[c + dw - 1] + diff[c + dw] + diff[c + dw + 1]
                            val d = diff[c]
                            // local mean decides "did the picture change here"; the pixel's own
                            // difference must also be plausible grain (protects fine moving detail)
                            val k = wLut[minOf(wLut.size - 1, abs(local) * 4 / 9)] *
                                (if (abs(d) > 3f * t2) 0f else 1f)
                            if (k > 0f) work[yy * width + xx] = ((y[o + xx].toInt() and 0xFF) - k * d + 0.5f).toInt().coerceIn(0, 255).toByte()
                            else work[yy * width + xx] = y[o + xx]
                        }
                    }
                    bx += BLOCK
                }
            }
        }
        // copy back the interior (the 1 px frame border is left as it was)
        for (yy in 1 until height - 1) System.arraycopy(work, yy * width + 1, y, off + yy * stride + 1, width - 2)
    }

    /** Light unsharp mask with a small dead zone so remaining grain is not amplified. */
    private fun sharpen(y: ByteArray, off: Int, stride: Int) {
        for (ry in 0 until height) System.arraycopy(y, off + ry * stride, work, ry * width, width)
        val amount = SHARPEN_AMOUNT
        // On grainy footage only clear edges are sharpened, so grain is not brought back.
        val dead = maxOf(SHARPEN_DEADZONE, 1.1f * sigma * (1f - settings.grain.blend))
        parallel.rows(height) { r0, r1 ->
            for (yy in maxOf(1, r0) until minOf(height - 1, r1)) {
                val o = off + yy * stride
                val w0 = yy * width
                for (xx in 1 until width - 1) {
                    val c = work[w0 + xx].toInt() and 0xFF
                    val blur = ((work[w0 + xx - 1].toInt() and 0xFF) + (work[w0 + xx + 1].toInt() and 0xFF) +
                        (work[w0 + xx - width].toInt() and 0xFF) + (work[w0 + xx + width].toInt() and 0xFF)) * 2 +
                        (work[w0 + xx - width - 1].toInt() and 0xFF) + (work[w0 + xx - width + 1].toInt() and 0xFF) +
                        (work[w0 + xx + width - 1].toInt() and 0xFF) + (work[w0 + xx + width + 1].toInt() and 0xFF) + c * 4
                    val hp = c - blur / 16f
                    val a = abs(hp)
                    if (a > dead) {
                        val v = c + amount * (hp - (if (hp > 0) dead else -dead))
                        y[o + xx] = (v + 0.5f).toInt().coerceIn(black, white).toByte()
                    }
                }
            }
        }
    }

    fun reset() { havePrev = false; haveStats = false; sigma = 0f }

    companion object {
        const val BLOCK = 16
        const val MIN_SIGMA = 1.2f
        const val MAX_CONTRAST_GAIN = 2.2f
        /** Black point must sit at least this many codes above black before contrast is touched. */
        const val MIN_BLACK_LIFT = 10f
        const val CONTRAST_STRENGTH = 0.85f
        const val SHARPEN_AMOUNT = 0.4f
        const val SHARPEN_DEADZONE = 1.5f
    }
}
