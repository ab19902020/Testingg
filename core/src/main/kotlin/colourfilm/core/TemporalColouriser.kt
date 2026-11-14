package colourfilm.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Video-aware colour estimation on the analysis grid.
 *
 * Per frame:
 *  1. Normalise brightness (old film often flickers in exposure) and estimate backward motion.
 *  2. Warp the previous frame's colour along that motion and score each pixel's reliability by
 *     how well the warped previous picture matches the current one (occlusions and newly
 *     revealed areas score low, so they are not painted with stale colour - no ghosting).
 *  3. Detect shot changes; on a cut, all temporal state is dropped so colour never carries
 *     over into an unrelated shot.
 *  4. Run the AI model when required (every frame in BEST, otherwise whenever tracking becomes
 *     unreliable or the maximum stride is reached) and blend it with the tracked colour only
 *     where tracking is reliable. That suppresses flicker without smearing moving subjects.
 */
class TemporalColouriser(private val model: ChromaModel, private val settings: ColouriseSettings) {
    val size = model.inputSize
    private val n = size * size
    private val motion = MotionEstimator(size)

    private val curNorm = FloatArray(n)
    private var prevNorm = FloatArray(n)
    private var prevAb = FloatArray(2 * n)
    private var hasPrev = false
    private var sinceModel = 0
    private var confAvg = 0f

    private val flowX = FloatArray(n)
    private val flowY = FloatArray(n)
    private val conf = FloatArray(n)
    private val confTmp = FloatArray(n)
    private val modelAb = FloatArray(2 * n)
    private val warped = FloatArray(2 * n)
    private val wide = FloatArray(2 * n)
    private val boxTmp = FloatArray(n)
    private val prevHist = FloatArray(HIST_BINS)
    private val curHist = FloatArray(HIST_BINS)

    /** Result of the most recent [next] call (for progress statistics and tests). */
    var lastModelRun = false; private set
    var lastCut = false; private set
    var lastMeanConfidence = 0f; private set
    var lastHistDistance = 0f; private set
    var modelRuns = 0L; private set
    var cuts = 0L; private set

    /**
     * Estimates colour for one frame.
     * @param grey analysis-grid luminance in 0..1 (size x size)
     * @param outAb receives planar Lab a/b (2 x size x size)
     */
    fun next(grey: FloatArray, outAb: FloatArray) {
        normalise(grey, curNorm)
        histogram(curNorm, curHist)

        var cut = !hasPrev
        var lowFraction = 1f
        if (hasPrev) {
            motion.estimate(prevNorm, curNorm, flowX, flowY)
            var sum = 0f; var low = 0
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                val px = x + flowX[i]; val py = y + flowY[i]
                val d = curNorm[i] - Resample.sample(prevNorm, size, size, px, py)
                // pixels whose motion points outside the frame have no history
                val outside = px < -0.5f || py < -0.5f || px > size - 0.5f || py > size - 0.5f
                conf[i] = if (outside) 0f else exp(-(d * d) / (2f * SIGMA * SIGMA))
            }
            box3(conf, confTmp)
            for (i in 0 until n) { sum += conf[i]; if (conf[i] < 0.5f) low++ }
            lastMeanConfidence = sum / n
            lowFraction = low.toFloat() / n
            val histDist = histDistance(prevHist, curHist)
            lastHistDistance = histDist
            val c = lastMeanConfidence
            // A cut is a sharp drop in how well the previous frame explains this one, relative
            // to the recent norm for this shot (grainy or fast footage has lower typical values).
            val relDrop = confAvg > 0f && c < 0.7f * confAvg && c < 0.65f
            cut = histDist > 0.5f || c < 0.3f || relDrop || (histDist > 0.3f && c < 0.5f)
            confAvg = if (cut) 0f else if (confAvg == 0f) c else 0.8f * confAvg + 0.2f * c
        } else {
            lastMeanConfidence = 0f
        }

        val runModel = cut || sinceModel + 1 >= settings.speed.maxStride || lowFraction > LOW_CONF_LIMIT
        if (runModel) {
            model.predict(grey, modelAb)
            modelRuns++
            sinceModel = 0
        } else {
            sinceModel++
        }

        if (cut) {
            cuts += if (hasPrev) 1 else 0
            System.arraycopy(modelAb, 0, outAb, 0, 2 * n)
        } else {
            warpAb()
            if (runModel) {
                val alpha = settings.stability.alpha
                for (i in 0 until n) {
                    val w = alpha * conf[i]
                    outAb[i] = modelAb[i] + w * (warped[i] - modelAb[i])
                    outAb[n + i] = modelAb[n + i] + w * (warped[n + i] - modelAb[n + i])
                }
            } else {
                // Tracking is reliable almost everywhere; keep tracked colour. The few
                // unreliable pixels (newly revealed background next to a moving subject) take
                // the broad colour of their surroundings rather than the subject's colour,
                // which would otherwise be dragged along and leave a coloured trail.
                localMean(warped, 0, wide); localMean(warped, n, wide)
                for (i in 0 until n) {
                    val c = conf[i]
                    outAb[i] = wide[i] + c * (warped[i] - wide[i])
                    outAb[n + i] = wide[n + i] + c * (warped[n + i] - wide[n + i])
                }
            }
        }
        lastModelRun = runModel
        lastCut = cut && hasPrev

        System.arraycopy(curNorm, 0, prevNorm, 0, n)
        System.arraycopy(curHist, 0, prevHist, 0, HIST_BINS)
        System.arraycopy(outAb, 0, prevAb, 0, 2 * n)
        hasPrev = true
    }

    /** Clears temporal history, e.g. before a seek. */
    fun reset() { hasPrev = false; sinceModel = 0; confAvg = 0f }

    /** Serialisable temporal state so a resumed job continues without a colour jump. */
    fun exportState(): FloatArray? {
        if (!hasPrev) return null
        val s = FloatArray(n + 2 * n + 2 * n + HIST_BINS + 1)
        System.arraycopy(prevNorm, 0, s, 0, n)
        System.arraycopy(prevAb, 0, s, n, 2 * n)
        System.arraycopy(modelAb, 0, s, 3 * n, 2 * n)
        System.arraycopy(prevHist, 0, s, 5 * n, HIST_BINS)
        s[5 * n + HIST_BINS] = sinceModel.toFloat()
        return s
    }

    fun importState(s: FloatArray?) {
        if (s == null || s.size != 5 * n + HIST_BINS + 1) { reset(); return }
        System.arraycopy(s, 0, prevNorm, 0, n)
        System.arraycopy(s, n, prevAb, 0, 2 * n)
        System.arraycopy(s, 3 * n, modelAb, 0, 2 * n)
        System.arraycopy(s, 5 * n, prevHist, 0, HIST_BINS)
        sinceModel = s[5 * n + HIST_BINS].toInt()
        hasPrev = true
    }

    private fun warpAb() {
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            val px = (x + flowX[i]).coerceIn(0f, size - 1f)
            val py = (y + flowY[i]).coerceIn(0f, size - 1f)
            val ix = minOf(px.toInt(), size - 2); val iy = minOf(py.toInt(), size - 2)
            val fx = px - ix; val fy = py - iy
            val j = iy * size + ix
            for (ch in 0..1) {
                val o = ch * n
                val t = prevAb[o + j] + (prevAb[o + j + 1] - prevAb[o + j]) * fx
                val b = prevAb[o + j + size] + (prevAb[o + j + size + 1] - prevAb[o + j + size]) * fx
                warped[o + i] = t + (b - t) * fy
            }
        }
    }

    /** Box mean of radius WIDE_R over one plane of [src] (offset [o]) into [dst] at the same offset. */
    private fun localMean(src: FloatArray, o: Int, dst: FloatArray) {
        val r = WIDE_R
        for (y in 0 until size) {
            var s = 0f; var c = 0
            val row = o + y * size
            for (x in 0..minOf(r, size - 1)) { s += src[row + x]; c++ }
            for (x in 0 until size) {
                boxTmp[y * size + x] = s / c
                if (x + r + 1 < size) { s += src[row + x + r + 1]; c++ }
                if (x - r >= 0) { s -= src[row + x - r]; c-- }
            }
        }
        for (x in 0 until size) {
            var s = 0f; var c = 0
            for (y in 0..minOf(r, size - 1)) { s += boxTmp[y * size + x]; c++ }
            for (y in 0 until size) {
                dst[o + y * size + x] = s / c
                if (y + r + 1 < size) { s += boxTmp[(y + r + 1) * size + x]; c++ }
                if (y - r >= 0) { s -= boxTmp[(y - r) * size + x]; c-- }
            }
        }
    }

    private fun box3(a: FloatArray, tmp: FloatArray) {
        for (y in 0 until size) for (x in 0 until size) {
            val l = maxOf(0, x - 1); val r = minOf(size - 1, x + 1)
            val row = y * size
            tmp[row + x] = (a[row + l] + a[row + x] + a[row + r]) / 3f
        }
        for (y in 0 until size) for (x in 0 until size) {
            val u = maxOf(0, y - 1); val d = minOf(size - 1, y + 1)
            a[y * size + x] = (tmp[u * size + x] + tmp[y * size + x] + tmp[d * size + x]) / 3f
        }
    }

    companion object {
        /** Photometric tolerance in units of the frame's luminance standard deviation. */
        const val SIGMA = 0.25f
        /** Fraction of unreliable pixels above which the model is re-run on a non-key frame. */
        const val LOW_CONF_LIMIT = 0.015f
        /** Radius (analysis pixels) of the surrounding-colour fallback. */
        const val WIDE_R = 10
        const val HIST_BINS = 32

        fun normalise(src: FloatArray, dst: FloatArray) {
            var mean = 0.0
            for (v in src) mean += v
            mean /= src.size
            var varSum = 0.0
            for (v in src) { val d = v - mean; varSum += d * d }
            val std = maxOf(sqrt(varSum / src.size), 0.02).toFloat()
            val m = mean.toFloat()
            for (i in src.indices) dst[i] = (src[i] - m) / std
        }

        fun histogram(norm: FloatArray, out: FloatArray) {
            out.fill(0f)
            for (v in norm) {
                val b = (((v + 3f) / 6f) * HIST_BINS).toInt().coerceIn(0, HIST_BINS - 1)
                out[b] += 1f
            }
            for (i in out.indices) out[i] /= norm.size
        }

        /** Half L1 distance between normalised histograms, in 0..1. */
        fun histDistance(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += abs(a[i] - b[i])
            return 0.5f * s
        }
    }
}
