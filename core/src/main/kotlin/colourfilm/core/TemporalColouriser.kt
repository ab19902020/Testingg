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
    private var modelGap = 2
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
        var resumedShot = false
        if (hasPrev) {
            lowFraction = trackFrom(prevNorm)
            val histDist = histDistance(prevHist, curHist)
            lastHistDistance = histDist
            val c = lastMeanConfidence
            // A cut is a sharp drop in how well the previous frame explains this one, relative
            // to the recent norm for this shot (grainy or fast footage has lower typical values).
            val relDrop = confAvg > 0f && c < 0.7f * confAvg && c < 0.65f
            cut = histDist > 0.5f || c < 0.3f || relDrop || (histDist > 0.3f && c < 0.5f)
            if (cut && enableShotMemory) {
                // Remember how the shot we are leaving was coloured, then check whether the new
                // picture is a return to an earlier shot (e.g. cutting back and forth between two
                // people). If so, carry on from that shot's colours so outfits stay the same.
                if (shotFrames >= MIN_SHOT_FRAMES) rememberShot()
                val back = findEarlierShot()
                if (back != null) {
                    System.arraycopy(back.norm, 0, prevNorm, 0, n)
                    System.arraycopy(back.ab, 0, prevAb, 0, 2 * n)
                    lowFraction = trackFrom(prevNorm)
                    cut = false; resumedShot = true
                    shotsResumed++
                }
            }
            confAvg = if (cut) 0f else if (confAvg == 0f || resumedShot) lastMeanConfidence else 0.8f * confAvg + 0.2f * lastMeanConfidence
            shotFrames = if (cut || resumedShot) 0 else shotFrames + 1
        } else {
            lastMeanConfidence = 0f
        }

        val runModel = cut || resumedShot || sinceModel + 1 >= settings.speed.maxStride || lowFraction > LOW_CONF_LIMIT
        if (runModel) {
            model.predict(grey, modelAb)
            modelRuns++
            modelGap = sinceModel + 1
            sinceModel = 0
        } else {
            sinceModel++
        }

        if (cut) {
            cuts += if (hasPrev) 1 else 0
            System.arraycopy(modelAb, 0, outAb, 0, 2 * n)
        } else {
            warpAb()
            if (enableSearch && lowFraction < 0.5f && !resumedShot) patchSearch()
            if (runModel) {
                // Stability is defined per two frames, so colour memory lasts equally long whether
                // the model runs on every frame (Best) or every few frames (Balanced, Fast).
                val base = if (alphaOverride >= 0f) alphaOverride else settings.stability.alpha
                val alpha = Math.pow(base.toDouble(), modelGap / 2.0).toFloat()
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
        if (enableClean && settings.edgeRefine) cleanChroma(outAb)
        lastModelRun = runModel
        lastCut = cut && hasPrev

        System.arraycopy(curNorm, 0, prevNorm, 0, n)
        System.arraycopy(curHist, 0, prevHist, 0, HIST_BINS)
        System.arraycopy(outAb, 0, prevAb, 0, 2 * n)
        hasPrev = true
    }

    /** Clears temporal history, e.g. before a seek. */
    fun reset() { hasPrev = false; sinceModel = 0; confAvg = 0f; shotFrames = 0; shots.forEach { it.used = false } }

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

    /** Estimates motion from [from] to the current frame and fills [conf]; returns the low-confidence fraction. */
    private fun trackFrom(from: FloatArray): Float {
        motion.estimate(from, curNorm, flowX, flowY)
        var sum = 0f; var low = 0
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            val px = x + flowX[i]; val py = y + flowY[i]
            val d = curNorm[i] - Resample.sample(from, size, size, px, py)
            // pixels whose motion points outside the frame have no history
            val outside = px < -0.5f || py < -0.5f || px > size - 0.5f || py > size - 0.5f
            conf[i] = if (outside) 0f else exp(-(d * d) / (2f * SIGMA * SIGMA))
        }
        box3(conf, confTmp)
        for (i in 0 until n) { sum += conf[i]; if (conf[i] < 0.5f) low++ }
        lastMeanConfidence = sum / n
        return low.toFloat() / n
    }

    private class Shot(n: Int) { val norm = FloatArray(n); val ab = FloatArray(2 * n); val hist = FloatArray(HIST_BINS); var used = false }
    private val shots = Array(SHOT_MEMORY) { Shot(n) }
    private var shotNext = 0
    private var shotFrames = 0
    var shotsResumed = 0L; private set

    private fun rememberShot() {
        val s = shots[shotNext]; shotNext = (shotNext + 1) % SHOT_MEMORY
        System.arraycopy(prevNorm, 0, s.norm, 0, n); System.arraycopy(prevAb, 0, s.ab, 0, 2 * n)
        System.arraycopy(prevHist, 0, s.hist, 0, HIST_BINS); s.used = true
    }

    /** The remembered shot that explains the current frame best, if it does so as well as normal motion would. */
    private fun findEarlierShot(): Shot? {
        var best: Shot? = null; var bestConf = SHOT_MATCH
        val justSaved = shots[(shotNext + SHOT_MEMORY - 1) % SHOT_MEMORY]
        for (s in shots) {
            if (!s.used || (s === justSaved && shotFrames >= MIN_SHOT_FRAMES)) continue
            if (histDistance(s.hist, curHist) > 0.3f) continue
            trackFrom(s.norm)
            if (lastMeanConfidence > bestConf) { bestConf = lastMeanConfidence; best = s }
        }
        return best
    }

    /**
     * Second chance for pixels the block tracker could not follow (thin, fast things such as
     * walking legs or arms): look in a window of the previous frame for pixels whose small
     * luminance neighbourhood looks the same, and take their colour. It needs no motion model,
     * so a limb keeps the colour it had instead of being re-guessed (and flipping) every frame,
     * and background revealed behind it takes the neighbouring background's colour.
     */
    private fun patchSearch() {
        // Shape-tolerant descriptors: brightness of the pixel, of its 3x3 and of its 7x7
        // surroundings. A swinging leg changes shape between frames, but its pixels keep roughly
        // the same brightness and the same kind of surroundings.
        boxBlur(curNorm, cur3, 1); boxBlur(curNorm, cur7, 3)
        boxBlur(prevNorm, prev3, 1); boxBlur(prevNorm, prev7, 3)
        val r = SEARCH_R
        val inv = 1f / (4f * SEARCH_H * SEARCH_H)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            if (conf[i] >= SEARCH_BELOW) continue
            val cx = Math.round(x + flowX[i]).coerceIn(r, size - 1 - r)
            val cy = Math.round(y + flowY[i]).coerceIn(r, size - 1 - r)
            val c0 = curNorm[i]; val c3 = cur3[i]; val c7 = cur7[i]
            var wSum = 0f; var a = 0f; var b = 0f; var best = 0f
            for (dy in -r..r) {
                val row = (cy + dy) * size
                for (dx in -r..r) {
                    val j = row + cx + dx
                    val d0 = c0 - prevNorm[j]
                    var ssd = 2f * d0 * d0
                    if (ssd * inv > 4f) continue
                    val d3 = c3 - prev3[j]; val d7 = c7 - prev7[j]
                    ssd += d3 * d3 + d7 * d7
                    val w = exp(-ssd * inv)
                    if (w < 0.02f) continue
                    wSum += w; a += w * prevAb[j]; b += w * prevAb[n + j]
                    if (w > best) best = w
                }
            }
            if (wSum > 0f && best * SEARCH_TRUST > conf[i]) {
                warped[i] = a / wSum; warped[n + i] = b / wSum
                conf[i] = best * SEARCH_TRUST
            }
        }
    }

    private val cur3 = FloatArray(n); private val cur7 = FloatArray(n)
    private val prev3 = FloatArray(n); private val prev7 = FloatArray(n)

    private fun boxBlur(src: FloatArray, dst: FloatArray, r: Int) {
        for (y in 0 until size) {
            val row = y * size
            var s = 0f; var c = 0
            for (x in 0..minOf(r, size - 1)) { s += src[row + x]; c++ }
            for (x in 0 until size) {
                boxTmp[row + x] = s / c
                if (x + r + 1 < size) { s += src[row + x + r + 1]; c++ }
                if (x - r >= 0) { s -= src[row + x - r]; c-- }
            }
        }
        for (x in 0 until size) {
            var s = 0f; var c = 0
            for (y in 0..minOf(r, size - 1)) { s += boxTmp[y * size + x]; c++ }
            for (y in 0 until size) {
                dst[y * size + x] = s / c
                if (y + r + 1 < size) { s += boxTmp[(y + r + 1) * size + x]; c++ }
                if (y - r >= 0) { s -= boxTmp[(y - r) * size + x]; c-- }
            }
        }
    }

    private val cleanTmp = FloatArray(2 * n)

    /**
     * Edge-aware clean-up of the colour estimate: each pixel's colour becomes the average of
     * nearby pixels of similar brightness. The model's colour for a person spills a few pixels
     * onto the background around them (a halo); background pixels then mostly average with other
     * background, so the spill is diluted, while the person keeps their own colour.
     */
    private fun cleanChroma(ab: FloatArray) {
        val r = CLEAN_R
        val invR = 1f / (2f * CLEAN_RANGE * CLEAN_RANGE)
        for (y in 0 until size) {
            val y0 = maxOf(0, y - r); val y1 = minOf(size - 1, y + r)
            for (x in 0 until size) {
                val i = y * size + x
                val l = curNorm[i]
                val x0 = maxOf(0, x - r); val x1 = minOf(size - 1, x + r)
                var wSum = 0f; var a = 0f; var b = 0f
                for (yy in y0..y1) {
                    val row = yy * size
                    for (xx in x0..x1) {
                        val j = row + xx
                        val d = l - curNorm[j]
                        val w = cleanLut[minOf(CLEAN_LUT - 1, (d * d * invR * CLEAN_LUT_SCALE).toInt())]
                        wSum += w; a += w * ab[j]; b += w * ab[n + j]
                    }
                }
                cleanTmp[i] = a / wSum; cleanTmp[n + i] = b / wSum
            }
        }
        System.arraycopy(cleanTmp, 0, ab, 0, 2 * n)
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
        /** Patch search: window radius (analysis pixels), similarity bandwidth, trust given to a match. */
        const val SEARCH_R = 7
        const val SEARCH_H = 0.30f
        const val SEARCH_BELOW = 0.6f
        const val SEARCH_TRUST = 0.9f
        /** Chroma clean-up: radius and brightness tolerance (in frame standard deviations). */
        const val CLEAN_R = 4
        const val CLEAN_RANGE = 0.30f
        private const val CLEAN_LUT = 256
        private const val CLEAN_LUT_SCALE = 32f
        private val cleanLut = FloatArray(CLEAN_LUT) { exp(-it / CLEAN_LUT_SCALE) }
        /** Experiment switches (desktop benchmark only). */
        @JvmField var alphaOverride = -1f
        /** Shot memory: how many past shots are remembered, and how well one must match to be resumed. */
        const val SHOT_MEMORY = 6
        const val MIN_SHOT_FRAMES = 5
        const val SHOT_MATCH = 0.62f
        @JvmField var enableShotMemory = true
        @JvmField var enableSearch = true
        @JvmField var enableClean = true

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
