package com.clrg.core.pose

import kotlin.math.floor

/**
 * A camera frame in *buffer* coordinates (as delivered by the sensor). Implementations return RGB in 0..255.
 * Coordinates are continuous: pixel (i, j) covers [i, i+1) x [j, j+1) and its centre is (i + 0.5, j + 0.5).
 */
interface PixelSource {
    val width: Int
    val height: Int

    /** Reads the integer pixel (clamped by caller) into out[o..o+2] as R, G, B (0..255). */
    fun readPixel(ix: Int, iy: Int, out: FloatArray, o: Int)

    /**
     * Bilinear sample at continuous coordinate (x, y). Outside the image: replicate edge pixels, or black when
     * [zeroBorder] (per-tap, matching OpenCV BORDER_CONSTANT).
     */
    fun sampleBilinear(x: Float, y: Float, zeroBorder: Boolean, out: FloatArray, o: Int, tmp: FloatArray) {
        val fx = x - 0.5f
        val fy = y - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val wx = fx - x0
        val wy = fy - y0
        var r = 0f; var g = 0f; var b = 0f
        for (k in 0 until 4) {
            val ix = x0 + (k and 1)
            val iy = y0 + (k shr 1)
            val w = (if (k and 1 == 1) wx else 1f - wx) * (if (k shr 1 == 1) wy else 1f - wy)
            if (w == 0f) continue
            if (ix < 0 || iy < 0 || ix >= width || iy >= height) {
                if (zeroBorder) continue
                readPixel(ix.coerceIn(0, width - 1), iy.coerceIn(0, height - 1), tmp, 0)
            } else {
                readPixel(ix, iy, tmp, 0)
            }
            r += w * tmp[0]; g += w * tmp[1]; b += w * tmp[2]
        }
        out[o] = r; out[o + 1] = g; out[o + 2] = b
    }
}

/** Packed ARGB/RGB int image (tests, simulated input, screenshots). */
class RgbImage(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) : PixelSource {
    override fun readPixel(ix: Int, iy: Int, out: FloatArray, o: Int) {
        val p = pixels[iy * width + ix]
        out[o] = ((p shr 16) and 0xFF).toFloat()
        out[o + 1] = ((p shr 8) and 0xFF).toFloat()
        out[o + 2] = (p and 0xFF).toFloat()
    }
}

/**
 * YUV_420_888 frame copied out of android.media.Image planes (luma + 2x2 subsampled chroma, arbitrary strides).
 * Conversion uses full-range BT.601 (JFIF), which is what Android camera YUV output uses.
 */
class YuvImage(
    override val width: Int,
    override val height: Int,
    val y: ByteArray, val yRowStride: Int,
    val u: ByteArray, val v: ByteArray, val uvRowStride: Int, val uvPixelStride: Int,
) : PixelSource {
    override fun readPixel(ix: Int, iy: Int, out: FloatArray, o: Int) {
        val yy = (y[iy * yRowStride + ix].toInt() and 0xFF).toFloat()
        val ci = (iy shr 1) * uvRowStride + (ix shr 1) * uvPixelStride
        val uu = (u[ci].toInt() and 0xFF) - 128f
        val vv = (v[ci].toInt() and 0xFF) - 128f
        out[o] = clamp255(yy + 1.402f * vv)
        out[o + 1] = clamp255(yy - 0.344136f * uu - 0.714136f * vv)
        out[o + 2] = clamp255(yy + 1.772f * uu)
    }

    private fun clamp255(v: Float) = if (v < 0f) 0f else if (v > 255f) 255f else v

    companion object {
        /** Builds a YUV image from RGB (tests). */
        fun fromRgb(img: RgbImage): YuvImage {
            val w = img.width
            val h = img.height
            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            val yb = ByteArray(w * h)
            val ub = ByteArray(cw * ch)
            val vb = ByteArray(cw * ch)
            val tmp = FloatArray(3)
            for (j in 0 until h) for (i in 0 until w) {
                img.readPixel(i, j, tmp, 0)
                yb[j * w + i] = (0.299f * tmp[0] + 0.587f * tmp[1] + 0.114f * tmp[2]).toInt().coerceIn(0, 255).toByte()
            }
            for (j in 0 until ch) for (i in 0 until cw) {
                img.readPixel(minOf(i * 2, w - 1), minOf(j * 2, h - 1), tmp, 0)
                val uu = -0.168736f * tmp[0] - 0.331264f * tmp[1] + 0.5f * tmp[2] + 128f
                val vv = 0.5f * tmp[0] - 0.418688f * tmp[1] - 0.081312f * tmp[2] + 128f
                ub[j * cw + i] = uu.toInt().coerceIn(0, 255).toByte()
                vb[j * cw + i] = vv.toInt().coerceIn(0, 255).toByte()
            }
            return YuvImage(w, h, yb, w, ub, vb, cw, 1)
        }
    }
}

/**
 * Orientation of the camera buffer relative to the landscape display.
 *
 * [rotationCw] is the clockwise rotation that makes the buffer upright for the current display rotation
 * (front camera: (sensor + display) % 360, back camera: (sensor - display + 360) % 360). The upright image is the
 * true (non-mirrored) camera view, which is what the pose models expect. Mirroring for the "magic mirror" display
 * is applied later, when landmarks are converted to frame units (see [FrameGeometry]).
 */
data class FrameOrientation(val bufferWidth: Int, val bufferHeight: Int, val rotationCw: Int) {
    init {
        require(rotationCw in intArrayOf(0, 90, 180, 270)) { "rotation must be a multiple of 90: $rotationCw" }
    }

    val uprightWidth: Int get() = if (rotationCw % 180 == 0) bufferWidth else bufferHeight
    val uprightHeight: Int get() = if (rotationCw % 180 == 0) bufferHeight else bufferWidth

    /** Upright continuous coordinates -> buffer continuous coordinates. */
    fun uprightToBuffer(): Affine {
        val wb = bufferWidth.toFloat()
        val hb = bufferHeight.toFloat()
        return when (rotationCw) {
            0 -> Affine.IDENTITY
            90 -> Affine(0f, 1f, 0f, -1f, 0f, hb)       // xb = yu, yb = Hb - xu
            180 -> Affine(-1f, 0f, wb, 0f, -1f, hb)     // xb = Wb - xu, yb = Hb - yu
            else -> Affine(0f, -1f, wb, 1f, 0f, 0f)     // 270: xb = Wb - yu, yb = xu
        }
    }

    companion object {
        fun rotationFor(sensorOrientation: Int, displayRotationDegrees: Int, frontFacing: Boolean): Int =
            if (frontFacing) (sensorOrientation + displayRotationDegrees) % 360
            else (sensorOrientation - displayRotationDegrees + 360) % 360
    }
}

/** Samples model input tensors from a camera frame. */
object CropSampler {
    /**
     * Fills [out] (n*n*3 floats, HWC) by sampling [src] through [tensorToBuffer] (tensor pixel index -> buffer
     * coordinate). Each channel value is pixel * [scale] + [offset].
     */
    fun sample(
        src: PixelSource, tensorToBuffer: Affine, n: Int, out: FloatArray,
        scale: Float, offset: Float, zeroBorder: Boolean,
    ) {
        val tmp = FloatArray(3)
        val px = FloatArray(3)
        val wMax = src.width - 1.5f
        val hMax = src.height - 1.5f
        var o = 0
        for (v in 0 until n) {
            val vf = v.toFloat()
            var x = tensorToBuffer.b * vf + tensorToBuffer.c
            var y = tensorToBuffer.e * vf + tensorToBuffer.f
            for (u in 0 until n) {
                // fast path: all four taps inside the image
                if (x >= 0.5f && y >= 0.5f && x < wMax && y < hMax) {
                    val fx = x - 0.5f
                    val fy = y - 0.5f
                    val x0 = fx.toInt()
                    val y0 = fy.toInt()
                    val wx = fx - x0
                    val wy = fy - y0
                    src.readPixel(x0, y0, tmp, 0)
                    var r = tmp[0] * (1 - wx) * (1 - wy); var g = tmp[1] * (1 - wx) * (1 - wy); var b = tmp[2] * (1 - wx) * (1 - wy)
                    src.readPixel(x0 + 1, y0, tmp, 0)
                    r += tmp[0] * wx * (1 - wy); g += tmp[1] * wx * (1 - wy); b += tmp[2] * wx * (1 - wy)
                    src.readPixel(x0, y0 + 1, tmp, 0)
                    r += tmp[0] * (1 - wx) * wy; g += tmp[1] * (1 - wx) * wy; b += tmp[2] * (1 - wx) * wy
                    src.readPixel(x0 + 1, y0 + 1, tmp, 0)
                    r += tmp[0] * wx * wy; g += tmp[1] * wx * wy; b += tmp[2] * wx * wy
                    px[0] = r; px[1] = g; px[2] = b
                } else {
                    src.sampleBilinear(x, y, zeroBorder, px, 0, tmp)
                }
                out[o] = px[0] * scale + offset
                out[o + 1] = px[1] * scale + offset
                out[o + 2] = px[2] * scale + offset
                o += 3
                x += tensorToBuffer.a
                y += tensorToBuffer.d
            }
        }
    }

    /** Average colour over a set of points (used for appearance signatures). Returns false if all were outside. */
    fun meanColor(src: PixelSource, toBuffer: Affine, xs: FloatArray, ys: FloatArray, count: Int, out: FloatArray): Boolean {
        val tmp = FloatArray(3)
        val px = FloatArray(3)
        var n = 0
        var r = 0f; var g = 0f; var b = 0f
        for (i in 0 until count) {
            val bx = toBuffer.mapX(xs[i], ys[i])
            val by = toBuffer.mapY(xs[i], ys[i])
            if (bx < 0f || by < 0f || bx >= src.width || by >= src.height) continue
            src.sampleBilinear(bx, by, false, px, 0, tmp)
            r += px[0]; g += px[1]; b += px[2]; n++
        }
        if (n == 0) return false
        out[0] = r / n; out[1] = g / n; out[2] = b / n
        return true
    }
}

/**
 * Converts upright camera pixels into "frame units" used by tracking and games: y in [0, 1] (top to bottom),
 * x in [0, aspect], uniformly scaled, and mirrored horizontally when [mirror] is set so the screen behaves like a
 * mirror (a player standing on their left appears on the left of the screen).
 */
data class FrameGeometry(val uprightWidth: Int, val uprightHeight: Int, val mirror: Boolean) {
    val aspect: Float get() = uprightWidth.toFloat() / uprightHeight
    fun toFrameX(xu: Float): Float = (if (mirror) uprightWidth - xu else xu) / uprightHeight
    fun toFrameY(yu: Float): Float = yu / uprightHeight
    fun toUprightX(xf: Float): Float = if (mirror) uprightWidth - xf * uprightHeight else xf * uprightHeight
    fun toUprightY(yf: Float): Float = yf * uprightHeight
}
