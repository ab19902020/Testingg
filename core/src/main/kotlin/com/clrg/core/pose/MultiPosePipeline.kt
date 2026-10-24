package com.clrg.core.pose

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Runs the two BlazePose networks. Implemented with TensorFlow Lite on Android; faked in unit tests. */
interface PoseModelRunner {
    /** input: 224*224*3 floats in [-1, 1]. Writes regressors (2254*12) and raw scores (2254). */
    fun runDetector(input: FloatArray, regressors: FloatArray, scores: FloatArray)

    /**
     * input: 256*256*3 floats in [0, 1]. Writes 195 landmark values, the pose presence flag (already 0..1) and,
     * when [heatmap] is non-null, the 64*64*39 heat map.
     */
    fun runLandmarks(input: FloatArray, landmarks: FloatArray, flag: FloatArray, heatmap: FloatArray?)
}

data class PipelineConfig(
    /** Maximum simultaneous people tracked (2 players + room to notice a spectator). */
    val maxPoses: Int = 3,
    /** Number of people we expect; while fewer are tracked the detector runs every frame. */
    val desiredPoses: Int = 2,
    /** When [desiredPoses] are tracked, look for new people only every N frames (saves power/heat). */
    val detectIntervalWhenSatisfied: Int = 4,
    val minDetectionScore: Float = 0.5f,
    val minPresence: Float = 0.5f,
    val refineWithHeatmap: Boolean = true,
    /**
     * Overlapping crops the detector cycles through. The 224px detector misses people smaller than ~60% of a
     * 16:9 frame's height when run on the whole frame, but finds them down to ~35% on half-width crops
     * (measured with tools/pose-validation/scale_test.py).
     */
    val searchRegions: List<RegionSpec> = listOf(RegionSpec(0f, 0.56f), RegionSpec(0.44f, 1f), RegionSpec(0f, 1f)),
)

/** Horizontal slice of the upright frame (fractions of width), full height. */
data class RegionSpec(val x0: Float, val x1: Float)

/** One person's landmarks in upright image pixels, as produced by the pipeline. */
class RawPose(
    val trackId: Int,
    val landmarks: RawLandmarks,
    val presence: Float,
    val roi: RotatedRect,
    /** Clothing colour signature (see [Appearance]), or null if it could not be sampled. */
    val appearance: FloatArray?,
    val framesTracked: Int,
)

class PipelineStats {
    var preprocessMs = 0f
    var detectorMs = 0f
    var landmarkMs = 0f
    var totalMs = 0f
    var detectorRuns = 0
    var landmarkRuns = 0
    var tracks = 0
}

/**
 * Multi-person BlazePose pipeline. Each person is tracked with their own landmark ROI derived from the previous
 * frame (temporal continuity, as in MediaPipe's tracking mode); the detector only looks for people who are not
 * already tracked, cycling over overlapping search regions. Thread-confined: call [process] from one thread.
 */
class MultiPosePipeline(private val runner: PoseModelRunner, var config: PipelineConfig = PipelineConfig()) {
    private class Track(val id: Int, var roi: RotatedRect, var frames: Int = 0, var lastPresence: Float = 1f)

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var frameIndex = 0L
    private var regionCursor = 0
    private var lastDetectFrame = -1000L

    private val detInput = FloatArray(BlazePose.DETECTOR_SIZE * BlazePose.DETECTOR_SIZE * 3)
    private val detRegressors = FloatArray(BlazePose.NUM_ANCHORS * BlazePose.DETECTOR_COORDS)
    private val detScores = FloatArray(BlazePose.NUM_ANCHORS)
    private val lmInput = FloatArray(BlazePose.LANDMARK_SIZE * BlazePose.LANDMARK_SIZE * 3)
    private val lmRaw = FloatArray(BlazePose.NUM_LANDMARKS_ALL * BlazePose.LANDMARK_VALUES)
    private val lmFlag = FloatArray(1)
    private val heatmap = FloatArray(BlazePose.HEATMAP_SIZE * BlazePose.HEATMAP_SIZE * BlazePose.NUM_LANDMARKS_ALL)

    val stats = PipelineStats()

    /** Optional observer told which detector region / landmark ROI is about to be processed (debug overlay, tests). */
    var observer: ((region: CropRegion?, roi: RotatedRect?) -> Unit)? = null

    /** Search regions and ROIs used during the last [process] call (for the debug overlay), upright pixels. */
    val lastRegions = ArrayList<CropRegion>()
    val lastRois = ArrayList<RotatedRect>()

    fun reset() {
        tracks.clear()
        frameIndex = 0
        lastDetectFrame = -1000
    }

    /**
     * Processes one frame. [src] is in buffer coordinates; [orientation] maps it upright. Returned landmarks are in
     * upright pixel coordinates.
     */
    fun process(src: PixelSource, orientation: FrameOrientation, clock: () -> Long = System::nanoTime): List<RawPose> {
        val t0 = clock()
        stats.detectorRuns = 0
        stats.landmarkRuns = 0
        stats.preprocessMs = 0f
        stats.detectorMs = 0f
        stats.landmarkMs = 0f
        val toBuffer = orientation.uprightToBuffer()
        val uw = orientation.uprightWidth.toFloat()
        val uh = orientation.uprightHeight.toFloat()
        val results = ArrayList<RawPose>()
        lastRegions.clear()
        lastRois.clear()

        // 1) Track existing people using ROIs from their previous landmarks.
        val it = tracks.iterator()
        while (it.hasNext()) {
            val tr = it.next()
            val lm = runLandmarks(src, toBuffer, tr.roi, clock)
            if (lm == null) { it.remove(); continue }
            tr.frames++
            tr.lastPresence = lmFlag[0]
            val roi = lm.trackingRoi()
            if (!roiPlausible(roi, uw, uh)) { it.remove(); continue }
            tr.roi = roi
            results.add(RawPose(tr.id, lm, lmFlag[0], roi, Appearance.compute(src, toBuffer, lm), tr.frames))
        }

        // 2) Remove duplicate tracks that converged on the same person (keep the older one).
        dedupe(results)

        // 3) Look for new people.
        val needDetection = tracks.size < config.maxPoses && (
            tracks.size < config.desiredPoses || frameIndex - lastDetectFrame >= config.detectIntervalWhenSatisfied)
        if (needDetection && config.searchRegions.isNotEmpty()) {
            lastDetectFrame = frameIndex
            val runs = if (tracks.isEmpty()) 2 else 1
            repeat(runs) {
                if (tracks.size >= config.maxPoses) return@repeat
                val spec = config.searchRegions[regionCursor % config.searchRegions.size]
                regionCursor++
                val region = CropRegion(spec.x0 * uw, 0f, (spec.x1 - spec.x0) * uw, uh)
                for (det in detect(src, toBuffer, region, clock)) {
                    if (tracks.size >= config.maxPoses) break
                    val roi = det.roi()
                    if (!roiPlausible(roi, uw, uh)) continue
                    if (tracks.any { overlaps(it.roi, roi) }) continue
                    val lm = runLandmarks(src, toBuffer, roi, clock) ?: continue
                    val next = lm.trackingRoi()
                    if (!roiPlausible(next, uw, uh) || tracks.any { overlaps(it.roi, next) }) continue
                    val tr = Track(nextId++, next, 1, lmFlag[0])
                    tracks.add(tr)
                    results.add(RawPose(tr.id, lm, lmFlag[0], next, Appearance.compute(src, toBuffer, lm), 1))
                }
            }
        }
        frameIndex++
        stats.tracks = tracks.size
        stats.totalMs = (clock() - t0) / 1e6f
        return results
    }

    private fun detect(src: PixelSource, toBuffer: Affine, region: CropRegion, clock: () -> Long): List<PoseDetection> {
        observer?.invoke(region, null)
        lastRegions.add(region)
        val a = clock()
        CropSampler.sample(src, region.detectorTransform().andThen(toBuffer), BlazePose.DETECTOR_SIZE, detInput, 1f / 127.5f, -1f, zeroBorder = true)
        val b = clock()
        runner.runDetector(detInput, detRegressors, detScores)
        val c = clock()
        stats.preprocessMs += (b - a) / 1e6f
        stats.detectorMs += (c - b) / 1e6f
        stats.detectorRuns++
        return DetectionDecoder.decode(detRegressors, detScores, region, config.minDetectionScore, config.maxPoses)
    }

    private fun runLandmarks(src: PixelSource, toBuffer: Affine, roi: RotatedRect, clock: () -> Long): RawLandmarks? {
        observer?.invoke(null, roi)
        lastRois.add(roi)
        val a = clock()
        CropSampler.sample(src, roi.cropTransform(BlazePose.LANDMARK_SIZE).andThen(toBuffer), BlazePose.LANDMARK_SIZE, lmInput, 1f / 255f, 0f, zeroBorder = false)
        val b = clock()
        val hm = if (config.refineWithHeatmap) heatmap else null
        runner.runLandmarks(lmInput, lmRaw, lmFlag, hm)
        val c = clock()
        stats.preprocessMs += (b - a) / 1e6f
        stats.landmarkMs += (c - b) / 1e6f
        stats.landmarkRuns++
        if (lmFlag[0] < config.minPresence) return null
        return LandmarkDecoder.decode(lmRaw, hm, roi)
    }

    private fun dedupe(results: MutableList<RawPose>) {
        if (tracks.size < 2) return
        val remove = HashSet<Int>()
        for (i in tracks.indices) for (j in i + 1 until tracks.size) {
            val a = tracks[i]
            val b = tracks[j]
            if (a.id in remove || b.id in remove) continue
            if (overlaps(a.roi, b.roi, strict = true)) remove.add(max(a.id, b.id))
        }
        if (remove.isEmpty()) return
        tracks.removeAll { it.id in remove }
        results.removeAll { it.trackId in remove }
    }

    /**
     * Same person if the ROI centres (hip centres) are close and the sizes similar. The ROI side is ~5x a person's
     * shoulder width, so two different people - even shoulder to shoulder - have hip centres > ~0.18 * side apart.
     */
    private fun overlaps(a: RotatedRect, b: RotatedRect, strict: Boolean = false): Boolean {
        val d = hypot(a.cx - b.cx, a.cy - b.cy)
        val s = min(a.side, b.side)
        val ratio = max(a.side, b.side) / max(1e-3f, s)
        return if (strict) d < 0.08f * s && ratio < 1.4f else d < 0.12f * s && ratio < 1.6f
    }

    private fun roiPlausible(r: RotatedRect, uw: Float, uh: Float): Boolean =
        r.side > 0.08f * uh && r.side < 4f * max(uw, uh) && abs(r.cx) < 2f * uw && abs(r.cy) < 2f * uh &&
            !r.side.isNaN() && !r.cx.isNaN()
}

/**
 * Coarse clothing-colour signature used as a soft re-identification cue after tracking loss: mean colour and
 * chromaticity of the upper and lower torso. Only kept in memory while the app runs; never stored.
 */
object Appearance {
    const val SIZE = 10

    fun compute(src: PixelSource, toBuffer: Affine, lm: RawLandmarks): FloatArray? {
        val ls = Lm.LEFT_SHOULDER; val rs = Lm.RIGHT_SHOULDER; val lh = Lm.LEFT_HIP; val rh = Lm.RIGHT_HIP
        if (lm.visibility[ls] < 0.5f || lm.visibility[rs] < 0.5f) return null
        val sx = (lm.x[ls] + lm.x[rs]) / 2f
        val sy = (lm.y[ls] + lm.y[rs]) / 2f
        val shoulderW = hypot(lm.x[ls] - lm.x[rs], lm.y[ls] - lm.y[rs])
        val hipsOk = lm.visibility[lh] > 0.5f && lm.visibility[rh] > 0.5f
        val hx = if (hipsOk) (lm.x[lh] + lm.x[rh]) / 2f else sx
        val hy = if (hipsOk) (lm.y[lh] + lm.y[rh]) / 2f else sy + shoulderW * 1.3f
        val out = FloatArray(SIZE)
        val xs = FloatArray(9)
        val ys = FloatArray(9)
        val col = FloatArray(3)
        for (part in 0..1) {
            var n = 0
            for (i in 0..2) for (j in 0..2) {
                val t = if (part == 0) 0.15f + 0.12f * i else 0.55f + 0.12f * i
                val across = (j - 1) * 0.22f
                val cx = sx + (hx - sx) * t
                val cy = sy + (hy - sy) * t
                // perpendicular offset across the torso
                xs[n] = cx + across * (lm.x[rs] - lm.x[ls])
                ys[n] = cy + across * (lm.y[rs] - lm.y[ls])
                n++
            }
            if (!CropSampler.meanColor(src, toBuffer, xs, ys, n, col)) return null
            val sum = max(1f, col[0] + col[1] + col[2])
            val o = part * 5
            out[o] = col[0] / 255f; out[o + 1] = col[1] / 255f; out[o + 2] = col[2] / 255f
            out[o + 3] = col[0] / sum; out[o + 4] = col[1] / sum
        }
        return out
    }

    /** Distance between signatures; chromaticity weighted above brightness so lighting changes matter less. */
    fun distance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (part in 0..1) {
            val o = part * 5
            for (k in 0..2) d += 0.5f * abs(a[o + k] - b[o + k])
            d += 3f * abs(a[o + 3] - b[o + 3]) + 3f * abs(a[o + 4] - b[o + 4])
        }
        return d / 2f
    }
}
