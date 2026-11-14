package colourfilm.core

import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Colour-space helpers shared by the Android app and the desktop benchmark.
 *
 * Conventions match OpenCV's float Lab (which DDColor was trained with):
 * L in [0, 100], a/b roughly in [-128, 127], sRGB primaries, D65 white.
 */
object ColourMath {
    private const val XN = 0.950456f
    private const val ZN = 1.088754f

    /** sRGB-encoded value (index/1023) to linear light. */
    private val toLinear1024 = FloatArray(1024) { i -> srgbToLinear(i / 1023f) }

    /** Linear light (index/4095) to sRGB-encoded value. */
    private val toSrgb4096 = FloatArray(4096) { i -> linearToSrgb(i / 4095f) }

    /** Lab L* of a neutral grey whose sRGB-encoded value is index/1023. */
    private val greyToL1024 = FloatArray(1024) { i -> 116f * labF(toLinear1024[i]) - 16f }

    fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun linearToSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else (1.055 * v.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    private fun labF(t: Float): Float =
        if (t > 0.008856f) cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f

    private fun labFInv(t: Float): Float =
        if (t > 0.206893f) t * t * t else (t - 16f / 116f) / 7.787f

    /** Lab L* for a neutral grey of sRGB-encoded brightness [g] in [0, 1]. */
    fun greyToL(g: Float): Float {
        val i = (g * 1023f + 0.5f).toInt().coerceIn(0, 1023)
        return greyToL1024[i]
    }

    fun fastLinearToSrgb(v: Float): Float {
        val i = (v * 4095f + 0.5f).toInt().coerceIn(0, 4095)
        return toSrgb4096[i]
    }

    /**
     * Converts Lab to *linear* RGB, writing into [out] (size >= 3).
     * Values may fall outside [0, 1] when the colour is out of gamut.
     */
    fun labToLinearRgb(l: Float, a: Float, b: Float, out: FloatArray) {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        val x = XN * labFInv(fx)
        val y = labFInv(fy)
        val z = ZN * labFInv(fz)
        out[0] = 3.240479f * x - 1.537150f * y - 0.498535f * z
        out[1] = -0.969256f * x + 1.875992f * y + 0.041556f * z
        out[2] = 0.055648f * x - 0.204043f * y + 1.057311f * z
    }

    /** Converts sRGB-encoded RGB (0..1) to Lab, for tests and metrics. */
    fun srgbToLab(r: Float, g: Float, b: Float, out: FloatArray) {
        val rl = srgbToLinear(r); val gl = srgbToLinear(g); val bl = srgbToLinear(b)
        val x = (0.412453f * rl + 0.357580f * gl + 0.180423f * bl) / XN
        val y = 0.212671f * rl + 0.715160f * gl + 0.072169f * bl
        val z = (0.019334f * rl + 0.119193f * gl + 0.950227f * bl) / ZN
        val fy = labF(y)
        out[0] = if (y > 0.008856f) 116f * fy - 16f else 903.3f * y
        out[1] = 500f * (labF(x) - fy)
        out[2] = 200f * (fy - labF(z))
    }
}

/** Y'CbCr matrix and quantisation range of a video stream. */
data class VideoColour(val matrix: Matrix, val fullRange: Boolean) {
    enum class Matrix(val kr: Float, val kb: Float) {
        BT601(0.299f, 0.114f),
        BT709(0.2126f, 0.0722f),
        BT2020(0.2627f, 0.0593f),
    }

    val kg: Float get() = 1f - matrix.kr - matrix.kb

    /** Lookup table from an 8-bit luma code to normalised 0..1 brightness. */
    val lumaToUnit: FloatArray = FloatArray(256) { code ->
        if (fullRange) code / 255f else ((code - 16) / 219f).coerceIn(0f, 1f)
    }

    fun chromaCode(c: Float): Int {
        // c is in [-0.5, 0.5]
        val v = if (fullRange) 128f + c * 255f else 128f + c * 224f
        return (v + 0.5f).toInt().coerceIn(if (fullRange) 0 else 16, if (fullRange) 255 else 240)
    }

    companion object {
        /** Sensible default when a container carries no colour metadata. */
        fun defaultFor(height: Int) = VideoColour(if (height >= 720) Matrix.BT709 else Matrix.BT601, false)
    }
}
