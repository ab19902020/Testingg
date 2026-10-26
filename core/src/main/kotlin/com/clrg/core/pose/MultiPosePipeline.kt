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
    /** Move each person's crop along with their recent motion (fewer losses when someone moves quickly). */
    val predictMotion: Boolean = false,
    /** When the landmark model loses a tracked person, try once more with a wider crop before giving up. */
    val retryWider: Boolean = false,
    /** Smooth the tracking crop while a person is still (steadier landmarks), follow it closely when they move. */
    val smoothRoi: Boolean = false,
    /** Presence needed to keep following someone already tracked (new people still need [minPresence]). */
    val keepPresence: Float = 0.5f,
    /** Low-light boost: brighten clearly dark crops before the models see them (see [AutoLevel]). */
    val autoLevel: Boolean = true,
    /** Also brighten the person detector's input (not only the landmark crops). */
    val autoLevelDetector: Boolean = true,
    /** How much of a person's recent motion to move the crop on by (0..1), and how much wider it may get. */
    val predictFraction: Float = 1f,
    val predictGrow: Float = 0.22f,
    /** How quickly the smoothed crop catches up with movement (higher = follows sooner). */
    val smoothGain: Float = 6f,
    /** When above 0: smooth only while the crop moves less than this (fraction of its size per frame), else follow exactly. */
    val smoothStill: Float = 0f,
    /**
     * Guard against a track jumping onto the other player when both move quickly side by side: a result far from
     * where the person's own motion says they should be is checked again at the expected place, and when two
     * tracks end up on the same person the one that jumped is dropped (not simply the newer one).
     */
    val guardJumps: Boolean = false,
    /** A track result further than this from its own motion's prediction (fraction of the crop) is re-checked. */
    val jumpDev: Float = 0.06f,
    /**
     * Re-check a jump only when it moved the track towards another tracked person (the case that swaps players),
     * so ordinary fast movement costs no extra model runs.
     */
    val jumpTargeted: Boolean = true,
) {
    /**
     * The extra tracking aids added in 1.8.0 (wider retry, steadier crops, a lower bar for keeping someone, the jump
     * guard). They measured better on photo sequences but are not proven with a live camera, so they are a setting,
     * off by default; off is the tracking of 1.7.0.
     */
    fun withExtraAids(on: Boolean): PipelineConfig =
        copy(retryWider = on, smoothRoi = on, keepPresence = if (on) 0.4f else minPresence, guardJumps = on)
}

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
    /** Object held in one hand, in upright pixels (set by the held-object tracker), else null. */
    var held: HeldObservation? = null,
    /** Finger landmarks per hand (0 = left, 1 = right) when finger tracking ran for this person, else null. */
    var hands: Array<HandObservation?>? = null,
)

class PipelineStats {
    var preprocessMs = 0f
    var detectorMs = 0f
    var landmarkMs = 0f
    var totalMs = 0f
    var detectorRuns = 0
    var landmarkRuns = 0
    var tracks = 0
    /** Strongest low-light brightening applied to a body crop this frame (1 = none). */
    var boostGain = 1f
}

/**
 * Multi-person BlazePose pipeline. Each person is tracked with their own landmark ROI derived from the previous
 * frame (temporal continuity, as in MediaPipe's tracking mode); the detector only looks for people who are not
 * already tracked, cycling over overlapping search regions. Thread-confined: call [process] from one thread.
 */
class MultiPosePipeline(private val runner: PoseModelRunner, var config: PipelineConfig = PipelineConfig()) {
    private class Track(val id: Int, var roi: RotatedRect, var frames: Int = 0, var lastPresence: Float = 1f) {
        /** Smoothed motion of the crop centre, pixels per processed frame. */
        var vx = 0f
        var vy = 0f
        /** Frames in a row the wider retry had to rescue this person. */
        var rescued = 0
        /** The crop the landmark model itself gave last frame (before smoothing). */
        var measured: RotatedRect = roi
        /** How far this frame's result was from where the person's motion predicted (fraction of crop size). */
        var dev = 0f
        /** Frame this track was last updated (its `measured` is already this frame's). */
        var seenFrame = -1L
        /** Low-light boost for this person's crops, smoothed over frames. */
        val level = AutoLevel.State()
    }

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
    /** Lifetime counters (diagnostics and tests): crops brightened, people rescued by the wider retry. */
    var levelledCrops = 0L
        private set
    var rescues = 0L
        private set
    var jumpFixes = 0L
        private set

    /** Finds held objects (sticks, toy rackets) next to the players' hands when set. */
    var heldObjects: HeldObjectTracker? = null

    /** Follows the fingers of the players' hands when set (finger-gun games). */
    var handLandmarks: HandLandmarkTracker? = null

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
        stats.boostGain = 1f
        val toBuffer = orientation.uprightToBuffer()
        val uw = orientation.uprightWidth.toFloat()
        val uh = orientation.uprightHeight.toFloat()
        val results = ArrayList<RawPose>()
        lastRegions.clear()
        lastRois.clear()

        // 1) Track existing people using ROIs from their previous landmarks (moved along with their motion).
        val it = tracks.iterator()
        while (it.hasNext()) {
            val tr = it.next()
            val crop = if (config.predictMotion) predicted(tr) else tr.roi
            var lm = runLandmarks(src, toBuffer, crop, clock, config.keepPresence, tr.level)
            if (lm == null && config.retryWider) {
                // lost them: one more look with a wider crop around where they were
                val m = tr.measured
                lm = runLandmarks(src, toBuffer, m.copy(side = m.side * 1.45f), clock, config.keepPresence, tr.level)
                if (lm != null) { rescues++; tr.rescued++ }
            } else tr.rescued = 0
            if (lm == null || tr.rescued > 8) { it.remove(); continue }
            var presence = lmFlag[0]
            var measured = lm.trackingRoi()
            if (!roiPlausible(measured, uw, uh)) { it.remove(); continue }
            // where this person should be by their own motion
            val pcx = tr.measured.cx + tr.vx
            val pcy = tr.measured.cy + tr.vy
            tr.dev = kotlin.math.hypot(measured.cx - pcx, measured.cy - pcy) / measured.side
            if (config.guardJumps && tr.frames > 2 && tr.dev > config.jumpDev &&
                (!config.jumpTargeted || jumpedTowardsOther(tr, measured, pcx, pcy))) {
                // a sudden jump: look again where they should be; keep whichever result fits their motion
                val again = runLandmarks(src, toBuffer, tr.roi.copy(cx = pcx, cy = pcy), clock, config.keepPresence, tr.level)
                if (again != null) {
                    val m2 = again.trackingRoi()
                    val dev2 = kotlin.math.hypot(m2.cx - pcx, m2.cy - pcy) / m2.side
                    if (roiPlausible(m2, uw, uh) && dev2 < tr.dev * 0.6f) {
                        lm = again; measured = m2; presence = lmFlag[0]; tr.dev = dev2; jumpFixes++
                    }
                }
            }
            tr.frames++
            tr.lastPresence = presence
            val prev = tr.roi
            val roi = if (config.smoothRoi) smoothed(prev, measured) else measured
            // motion of the crop centre, smoothed over a few frames
            tr.vx += 0.6f * ((measured.cx - tr.measured.cx) - tr.vx)
            tr.vy += 0.6f * ((measured.cy - tr.measured.cy) - tr.vy)
            tr.roi = roi
            tr.measured = measured
            tr.seenFrame = frameIndex
            results.add(RawPose(tr.id, lm, presence, roi, Appearance.compute(src, toBuffer, lm), tr.frames))
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
                var dets: List<PoseDetection> = detect(src, toBuffer, region, clock, brighten = false)!!
                // nobody new in the picture as it is: in a dark room, look again at a brightened copy
                if (config.autoLevel && config.autoLevelDetector && dets.none { d -> tracks.none { overlaps(it.roi, d.roi()) } }) {
                    detect(src, toBuffer, region, clock, brighten = true)?.let { dets = it }
                }
                for (det in dets) {
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
        heldObjects?.let { h -> if (results.isNotEmpty()) h.process(src, toBuffer, results) }
        handLandmarks?.let { h -> if (results.isNotEmpty()) h.process(src, toBuffer, results, clock) }
        frameIndex++
        stats.tracks = tracks.size
        stats.totalMs = (clock() - t0) / 1e6f
        return results
    }

    /**
     * Runs the person detector over [region]. With [brighten], only when the picture there is dark: it is brightened
     * first, and null is returned (no model run) when it is not dark.
     */
    private fun detect(src: PixelSource, toBuffer: Affine, region: CropRegion, clock: () -> Long, brighten: Boolean): List<PoseDetection>? {
        val a = clock()
        CropSampler.sample(src, region.detectorTransform().andThen(toBuffer), BlazePose.DETECTOR_SIZE, detInput, 1f / 127.5f, -1f, zeroBorder = true)
        if (brighten) {
            // only worth a second detector run when the picture there is clearly dark
            if (!AutoLevel.apply(detInput, BlazePose.DETECTOR_SIZE * BlazePose.DETECTOR_SIZE, -1f, 1f, minChange = 0.4f, search = true)) return null
            levelledCrops++
        }
        observer?.invoke(region, null)
        lastRegions.add(region)
        val b = clock()
        runner.runDetector(detInput, detRegressors, detScores)
        val c = clock()
        stats.preprocessMs += (b - a) / 1e6f
        stats.detectorMs += (c - b) / 1e6f
        stats.detectorRuns++
        return DetectionDecoder.decode(detRegressors, detScores, region, config.minDetectionScore, config.maxPoses)
    }

    /**
     * Where to look for a tracked person this frame: last frame's crop moved on by their recent motion, a little
     * wider while they move fast (so a sudden lunge stays inside the crop).
     */
    private fun predicted(tr: Track): RotatedRect {
        val r = tr.roi
        val speed = kotlin.math.hypot(tr.vx, tr.vy)
        if (speed < 0.004f * r.side) return r
        val grow = 1f + (1.2f * speed / r.side).coerceAtMost(config.predictGrow)
        val k = config.predictFraction
        return RotatedRect(r.cx + tr.vx * k, r.cy + tr.vy * k, r.side * grow, r.rotation)
    }

    /**
     * The landmark model's own crop wobbles a little from frame to frame even when a person stands still, and that
     * wobble feeds back into the landmarks. Ease the crop towards the new measurement: slowly when the change is
     * small (standing still), immediately when it is large (moving).
     */
    private fun smoothed(prev: RotatedRect, m: RotatedRect): RotatedRect {
        val move = kotlin.math.hypot(m.cx - prev.cx, m.cy - prev.cy) / prev.side
        val grow = kotlin.math.abs(m.side - prev.side) / prev.side
        val change = maxOf(move, grow)
        val k = if (config.smoothStill > 0f) (if (change < config.smoothStill) 0.35f else 1f)
            else (0.3f + config.smoothGain * change).coerceAtMost(1f)
        var dr = m.rotation - prev.rotation
        while (dr > Math.PI) dr -= (2 * Math.PI).toFloat()
        while (dr < -Math.PI) dr += (2 * Math.PI).toFloat()
        return RotatedRect(prev.cx + (m.cx - prev.cx) * k, prev.cy + (m.cy - prev.cy) * k, prev.side + (m.side - prev.side) * k, prev.rotation + dr * k)
    }

    private fun runLandmarks(src: PixelSource, toBuffer: Affine, roi: RotatedRect, clock: () -> Long, minPresence: Float = config.minPresence,
                             level: AutoLevel.State? = null): RawLandmarks? {
        observer?.invoke(null, roi)
        lastRois.add(roi)
        val a = clock()
        CropSampler.sample(src, roi.cropTransform(BlazePose.LANDMARK_SIZE).andThen(toBuffer), BlazePose.LANDMARK_SIZE, lmInput, 1f / 255f, 0f, zeroBorder = false)
        if (config.autoLevel && AutoLevel.apply(lmInput, BlazePose.LANDMARK_SIZE * BlazePose.LANDMARK_SIZE, 0f, 1f, level)) {
            levelledCrops++
            // gamma lift counted as its equivalent brightening of a mid-grey
            stats.boostGain = maxOf(stats.boostGain, AutoLevel.lastGain * Math.pow(0.18, (AutoLevel.lastGamma - 1.0)).toFloat())
        }
        val b = clock()
        val hm = if (config.refineWithHeatmap) heatmap else null
        runner.runLandmarks(lmInput, lmRaw, lmFlag, hm)
        val c = clock()
        stats.preprocessMs += (b - a) / 1e6f
        stats.landmarkMs += (c - b) / 1e6f
        stats.landmarkRuns++
        if (lmFlag[0] < minPresence) return null
        return LandmarkDecoder.decode(lmRaw, hm, roi)
    }

    /** Did this result move [tr] mostly towards someone else's position (rather than along its own way)? */
    private fun jumpedTowardsOther(tr: Track, m: RotatedRect, pcx: Float, pcy: Float): Boolean {
        val jx = m.cx - pcx
        val jy = m.cy - pcy
        val jump = kotlin.math.hypot(jx, jy)
        if (jump <= 0f) return false
        for (o in tracks) {
            if (o === tr) continue
            // the other person's position this frame (measured already, or expected from their motion)
            val ox = if (o.seenFrame == frameIndex) o.measured.cx else o.measured.cx + o.vx
            val oy = if (o.seenFrame == frameIndex) o.measured.cy else o.measured.cy + o.vy
            val tx = ox - pcx
            val ty = oy - pcy
            val dist = kotlin.math.hypot(tx, ty)
            if (dist <= 0f || dist > (m.side + o.measured.side) * 0.9f) continue
            // jump direction within about 60 degrees of the direction to the other person
            if ((jx * tx + jy * ty) / (jump * dist) > 0.5f) return true
        }
        return false
    }

    private fun dedupe(results: MutableList<RawPose>) {
        if (tracks.size < 2) return
        val remove = HashSet<Int>()
        for (i in tracks.indices) for (j in i + 1 until tracks.size) {
            val a = tracks[i]
            val b = tracks[j]
            if (a.id in remove || b.id in remove) continue
            if (!overlaps(a.roi, b.roi, strict = true)) continue
            // two tracks on one person: drop the one that jumped there (both settled: the newer one)
            val jumped = when {
                !config.guardJumps || a.frames <= 3 || b.frames <= 3 -> if (a.id > b.id) a else b
                else -> if (a.dev > b.dev) a else b
            }
            remove.add(jumped.id)
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
