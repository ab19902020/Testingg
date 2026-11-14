package colourfilm.core

/** Area (box) and bilinear resampling helpers for single-channel float images. */
object Resample {

    /**
     * Area-averages an 8-bit luma plane into an [outW]x[outH] float image in 0..1.
     * [stride] is the row stride in bytes of [src].
     */
    fun lumaToGrid(
        src: ByteArray, srcOffset: Int, stride: Int, w: Int, h: Int,
        lut: FloatArray, outW: Int, outH: Int, out: FloatArray, tmp: FloatArray,
    ) {
        // horizontal pass: h x outW
        val x0 = IntArray(outW) { (it.toLong() * w / outW).toInt() }
        val x1 = IntArray(outW) { maxOf(x0[it] + 1, ((it + 1).toLong() * w / outW).toInt()) }
        for (y in 0 until h) {
            val row = srcOffset + y * stride
            val o = y * outW
            for (i in 0 until outW) {
                var s = 0f
                for (x in x0[i] until x1[i]) s += lut[src[row + x].toInt() and 0xFF]
                tmp[o + i] = s / (x1[i] - x0[i])
            }
        }
        // vertical pass
        for (j in 0 until outH) {
            val y0 = (j.toLong() * h / outH).toInt()
            val y1 = maxOf(y0 + 1, ((j + 1).toLong() * h / outH).toInt())
            val inv = 1f / (y1 - y0)
            for (i in 0 until outW) {
                var s = 0f
                for (y in y0 until y1) s += tmp[y * outW + i]
                out[j * outW + i] = s * inv
            }
        }
    }

    /** 2x2 box downsample. */
    fun half(src: FloatArray, w: Int, h: Int, out: FloatArray) {
        val ow = w / 2; val oh = h / 2
        for (y in 0 until oh) for (x in 0 until ow) {
            val i = 2 * y * w + 2 * x
            out[y * ow + x] = 0.25f * (src[i] + src[i + 1] + src[i + w] + src[i + w + 1])
        }
    }

    /** Bilinear sample with edge clamping. */
    fun sample(img: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        if (w < 2 || h < 2) {
            val xi = x.toInt().coerceIn(0, w - 1); val yi = y.toInt().coerceIn(0, h - 1)
            return img[yi * w + xi]
        }
        val xc = x.coerceIn(0f, (w - 1).toFloat())
        val yc = y.coerceIn(0f, (h - 1).toFloat())
        val ix = xc.toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val iy = yc.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val fx = xc - ix; val fy = yc - iy
        val i = iy * w + ix
        val top = img[i] + (img[i + 1] - img[i]) * fx
        val bot = img[i + w] + (img[i + w + 1] - img[i + w]) * fx
        return top + (bot - top) * fy
    }

    /**
     * Bilinear upscale of a [sw]x[sh] plane (starting at [srcOffset]) to [dw]x[dh] rows [r0, r1),
     * using pixel-centre alignment.
     */
    fun upscaleRows(
        src: FloatArray, srcOffset: Int, sw: Int, sh: Int,
        dst: FloatArray, dw: Int, dh: Int, r0: Int, r1: Int,
    ) {
        val sx = sw.toFloat() / dw; val sy = sh.toFloat() / dh
        for (y in r0 until r1) {
            val fyRaw = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val iy = minOf(fyRaw.toInt(), sh - 2).coerceAtLeast(0)
            val fy = fyRaw - iy
            val row0 = srcOffset + iy * sw
            val row1 = if (sh > 1) row0 + sw else row0
            for (x in 0 until dw) {
                val fxRaw = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val ix = minOf(fxRaw.toInt(), sw - 2).coerceAtLeast(0)
                val fx = fxRaw - ix
                val ix1 = if (sw > 1) ix + 1 else ix
                val t = src[row0 + ix] + (src[row0 + ix1] - src[row0 + ix]) * fx
                val b = src[row1 + ix] + (src[row1 + ix1] - src[row1 + ix]) * fx
                dst[y * dw + x] = t + (b - t) * fy
            }
        }
    }
}
