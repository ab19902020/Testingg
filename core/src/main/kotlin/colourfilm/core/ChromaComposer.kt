package colourfilm.core

/**
 * Builds full-resolution 4:2:0 chroma planes from the analysis-grid Lab a/b estimate.
 *
 * The original luma plane is never modified: brightness, grain and fine detail of the source
 * are preserved exactly; only Cb/Cr are generated. Optional guided filtering (He et al.,
 * "Guided Image Filtering") uses the full-resolution luma as the guide so colour edges follow
 * real picture edges instead of bleeding across them.
 */
class ChromaComposer(
    val width: Int,
    val height: Int,
    private val colour: VideoColour,
    private val settings: ColouriseSettings,
    private val parallel: Parallel,
) {
    val cw = width / 2
    val ch = height / 2
    private val cn = cw * ch
    private val guide = FloatArray(cn)
    private val a = FloatArray(cn)
    private val b = FloatArray(cn)
    // guided filter buffers
    private val meanI = FloatArray(cn)
    private val varI = FloatArray(cn)
    private val tmp1 = FloatArray(cn)
    private val tmp2 = FloatArray(cn)
    private val tmp3 = FloatArray(cn)
    private val coefA = FloatArray(cn)
    private val coefB = FloatArray(cn)
    private val radius = maxOf(2, Math.round(maxOf(cw, ch) / 128f))

    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) { "even frame size required" }
    }

    /**
     * @param y luma plane (8-bit), [yStride] bytes per row, offset [yOffset]
     * @param ab analysis-grid a/b (planar), grid side [s]
     * @param outU/outV chroma planes [cw] x [ch], tightly packed
     */
    fun compose(y: ByteArray, yOffset: Int, yStride: Int, ab: FloatArray, s: Int, outU: ByteArray, outV: ByteArray) {
        val lut = colour.lumaToUnit
        parallel.rows(ch) { r0, r1 ->
            for (cy in r0 until r1) {
                val row0 = yOffset + 2 * cy * yStride
                val row1 = row0 + yStride
                for (cx in 0 until cw) {
                    val x = 2 * cx
                    guide[cy * cw + cx] = 0.25f * (
                        lut[y[row0 + x].toInt() and 0xFF] + lut[y[row0 + x + 1].toInt() and 0xFF] +
                            lut[y[row1 + x].toInt() and 0xFF] + lut[y[row1 + x + 1].toInt() and 0xFF])
                }
            }
            Resample.upscaleRows(ab, 0, s, s, a, cw, ch, r0, r1)
            Resample.upscaleRows(ab, s * s, s, s, b, cw, ch, r0, r1)
        }
        if (settings.edgeRefine) {
            prepareGuide()
            guidedFilter(a)
            guidedFilter(b)
        }
        val gain = settings.chromaGain
        val kr = colour.matrix.kr; val kb = colour.matrix.kb; val kg = colour.kg
        val cbDen = 2f * (1f - kb); val crDen = 2f * (1f - kr)
        parallel.rows(ch) { r0, r1 ->
            val rgb = FloatArray(3)
            for (i in r0 * cw until r1 * cw) {
                val l = ColourMath.greyToL(guide[i])
                var aa = a[i] * gain
                var bb = b[i] * gain
                ColourMath.labToLinearRgb(l, aa, bb, rgb)
                if (outOfGamut(rgb)) {
                    // reduce chroma (keeping hue and lightness) until the colour is displayable
                    var lo = 0f; var hi = 1f
                    repeat(6) {
                        val mid = 0.5f * (lo + hi)
                        ColourMath.labToLinearRgb(l, aa * mid, bb * mid, rgb)
                        if (outOfGamut(rgb)) hi = mid else lo = mid
                    }
                    aa *= lo; bb *= lo
                    ColourMath.labToLinearRgb(l, aa, bb, rgb)
                }
                val r = ColourMath.fastLinearToSrgb(rgb[0])
                val g = ColourMath.fastLinearToSrgb(rgb[1])
                val bl = ColourMath.fastLinearToSrgb(rgb[2])
                val yy = kr * r + kg * g + kb * bl
                outU[i] = colour.chromaCode((bl - yy) / cbDen).toByte()
                outV[i] = colour.chromaCode((r - yy) / crDen).toByte()
            }
        }
    }

    private fun outOfGamut(c: FloatArray) =
        c[0] < -0.002f || c[1] < -0.002f || c[2] < -0.002f || c[0] > 1.002f || c[1] > 1.002f || c[2] > 1.002f

    private fun prepareGuide() {
        boxFilter(guide, meanI)
        for (i in 0 until cn) tmp1[i] = guide[i] * guide[i]
        boxFilter(tmp1, varI)
        for (i in 0 until cn) varI[i] -= meanI[i] * meanI[i]
    }

    private fun guidedFilter(p: FloatArray) {
        val eps = 0.002f
        boxFilter(p, tmp1) // mean_p
        for (i in 0 until cn) tmp2[i] = guide[i] * p[i]
        boxFilter(tmp2, tmp3) // mean_Ip
        for (i in 0 until cn) {
            val cov = tmp3[i] - meanI[i] * tmp1[i]
            val ai = cov / (varI[i] + eps)
            coefA[i] = ai
            coefB[i] = tmp1[i] - ai * meanI[i]
        }
        boxFilter(coefA, tmp1)
        boxFilter(coefB, tmp2)
        for (i in 0 until cn) p[i] = tmp1[i] * guide[i] + tmp2[i]
    }

    private val boxTmp = FloatArray(cn)

    /** Separable box filter of radius [radius] with edge normalisation. */
    private fun boxFilter(src: FloatArray, dst: FloatArray) {
        val r = radius
        parallel.rows(ch) { r0, r1 ->
            for (y in r0 until r1) {
                val row = y * cw
                var s = 0f
                var cnt = 0
                for (x in 0..minOf(r, cw - 1)) { s += src[row + x]; cnt++ }
                for (x in 0 until cw) {
                    boxTmp[row + x] = s / cnt
                    val add = x + r + 1; val rem = x - r
                    if (add < cw) { s += src[row + add]; cnt++ }
                    if (rem >= 0) { s -= src[row + rem]; cnt-- }
                }
            }
        }
        parallel.rows(cw) { c0, c1 ->
            for (x in c0 until c1) {
                var s = 0f
                var cnt = 0
                for (y in 0..minOf(r, ch - 1)) { s += boxTmp[y * cw + x]; cnt++ }
                for (y in 0 until ch) {
                    dst[y * cw + x] = s / cnt
                    val add = y + r + 1; val rem = y - r
                    if (add < ch) { s += boxTmp[add * cw + x]; cnt++ }
                    if (rem >= 0) { s -= boxTmp[rem * cw + x]; cnt-- }
                }
            }
        }
    }
}
