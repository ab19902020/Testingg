package com.clrg.core.pose

import com.clrg.core.math.normalizeRadians
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Runs the MediaPipe hand landmark network. Implemented with TensorFlow Lite on Android; faked in unit tests. */
interface HandModelRunner {
    /**
     * input: 224*224*3 floats in [0, 1] (an upright crop around one hand, fingers pointing up). Writes 63 values
     * (21 landmarks: x, y in crop pixels, z relative depth on the same scale) and [presence]`[0]` (0..1).
     */
    fun runHand(input: FloatArray, landmarks: FloatArray, presence: FloatArray)
}

/** Hand landmark indices (MediaPipe hand topology). */
object HandLm {
    const val SIZE = 224
    const val COUNT = 21
    const val WRIST = 0
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_TIP = 20

    /** The landmarks MediaPipe uses to place the next frame's crop (no fingertips: they move too much). */
    val ROI_POINTS = intArrayOf(0, 1, 2, 3, 5, 6, 9, 10, 13, 14, 17, 18)

    /** Pose landmarks of each hand (BlazePose): wrist, pinky, index. Index 0 = the person's left hand. */
    val POSE_WRIST = intArrayOf(Lm.LEFT_WRIST, Lm.RIGHT_WRIST)
    val POSE_PINKY = intArrayOf(17, 18)
    val POSE_INDEX = intArrayOf(Lm.LEFT_INDEX, Lm.RIGHT_INDEX)
}

/**
 * The 21 landmarks of one hand, in the same units as the person's body landmarks. [z] is relative depth on the same
 * scale as x and y (smaller = closer to the camera), so distances and angles between joints can be measured in 3D.
 */
class HandObservation(
    /** Which of the person's hands: 0 = left, 1 = right (same convention as the body landmarks). */
    val hand: Int,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    /** 0..1 how sure the hand model is that a hand is in its crop. */
    val presence: Float,
) {
    fun mapped(fx: (Float) -> Float, fy: (Float) -> Float, scale: Float): HandObservation =
        HandObservation(hand, FloatArray(HandLm.COUNT) { fx(x[it]) }, FloatArray(HandLm.COUNT) { fy(y[it]) }, FloatArray(HandLm.COUNT) { z[it] * scale }, presence)
}

/**
 * Finger tracking for the players who need it (finger-gun games): runs the hand landmark model on a crop around a
 * player's hand. The first crop comes from the body pose (wrist, index and pinky points); after that each frame's
 * crop follows the hand's own landmarks, as MediaPipe does when tracking. Only the hands asked for are processed, so
 * the cost is zero in games that do not use fingers. Thread-confined: [process] runs on the inference thread; the
 * request fields are written from the game thread.
 */
class HandLandmarkTracker(private val runner: HandModelRunner) {
    /** Pipeline track id of the person in each player slot (-1 = none). */
    @Volatile var tracks: IntArray = intArrayOf(-1, -1)
    /** Which hands to follow per player slot: bit 0 = left hand, bit 1 = right hand; bits 2 / 3: the same hands on every other frame only. */
    @Volatile var masks: IntArray = intArrayOf(0, 0)

    /** Time spent in the hand model during the last [process] call (ms), and how many crops it ran on. */
    var lastMs = 0f
        private set
    var lastRuns = 0
        private set

    private val input = FloatArray(HandLm.SIZE * HandLm.SIZE * 3)
    private val raw = FloatArray(HandLm.COUNT * 3)
    private val presence = FloatArray(1)
    /** Crop that followed each hand last frame, per [player][hand], with the pose track it belonged to. */
    private val prevRoi = Array(2) { arrayOfNulls<RotatedRect>(2) }
    private val prevTrack = Array(2) { IntArray(2) { -1 } }
    /** The body model's wrist position when each hand crop was last made, per [player][hand * 2 + x/y]. */
    private val prevWrist = Array(2) { FloatArray(4) { Float.NaN } }
    /** Low-light boost: brighten clearly dark hand crops before the model sees them (as the body pipeline does). */
    var autoLevel = true
    /** Move the tracked hand crop by the body wrist's movement (one of the extra tracking aids; off = as 1.7.0). */
    var followWrist = false
    /** Smoothed low-light boost per [player][hand]. */
    private val level = Array(2) { Array(2) { AutoLevel.State() } }

    fun reset() {
        for (p in 0..1) for (h in 0..1) { prevRoi[p][h] = null; prevTrack[p][h] = -1; prevWrist[p][h * 2] = Float.NaN; level[p][h] = AutoLevel.State() }
    }

    fun process(src: PixelSource, toBuffer: Affine, poses: List<RawPose>, clock: () -> Long = System::nanoTime) {
        val t0 = clock()
        lastRuns = 0
        val tr = tracks
        val ms = masks
        for (p in 0..1) {
            val mask = ms.getOrElse(p) { 0 }
            val pose = if (mask == 0) null else poses.firstOrNull { it.trackId == tr.getOrElse(p) { -1 } }
            if (pose == null) { prevRoi[p][0] = null; prevRoi[p][1] = null; continue }
            val lm = pose.landmarks
            for (h in 0..1) {
                val every = mask and (1 shl h) != 0
                val alternate = mask and (4 shl h) != 0
                if (!every && !alternate) { prevRoi[p][h] = null; continue }
                if (!every && (frame + p + h) % 2L != 0L) continue
                // where is the hand? the previous frame's crop if it still matches the body's wrist, else from the pose
                val fromPose = poseRoi(lm, h)
                // the tracked crop, moved by however far the body model's wrist moved since it was made: the hand
                // stays inside its crop during a fast movement instead of the crop trailing a frame behind
                val w = HandLm.POSE_WRIST[h]
                val moved = prevRoi[p][h]?.let { r ->
                    val px = prevWrist[p][h * 2]; val py = prevWrist[p][h * 2 + 1]
                    if (followWrist && !px.isNaN() && lm.visibility[w] >= 0.3f) r.copy(cx = r.cx + (lm.x[w] - px), cy = r.cy + (lm.y[w] - py)) else r
                }
                if (lm.visibility[w] >= 0.3f) { prevWrist[p][h * 2] = lm.x[w]; prevWrist[p][h * 2 + 1] = lm.y[w] } else prevWrist[p][h * 2] = Float.NaN
                var roi = moved?.takeIf { prevTrack[p][h] == pose.trackId && fromPose != null && near(it, lm, h) } ?: fromPose
                if (roi == null) { prevRoi[p][h] = null; continue }
                var obs = run(src, toBuffer, roi, p, h)
                if (obs == null && roi !== fromPose && fromPose != null) {
                    // lost the hand with the tracked crop: one more try from the body pose
                    roi = fromPose
                    obs = run(src, toBuffer, roi, p, h)
                }
                if (obs == null) { prevRoi[p][h] = null; continue }
                prevRoi[p][h] = trackingRoi(obs, prevRoi[p][h]?.rotation ?: Float.NaN)
                prevTrack[p][h] = pose.trackId
                val arr = pose.hands ?: arrayOfNulls<HandObservation>(2).also { pose.hands = it }
                arr[h] = obs
            }
        }
        frame++
        lastMs = (clock() - t0) / 1e6f
    }

    private var frame = 0L

    private fun run(src: PixelSource, toBuffer: Affine, roi: RotatedRect, player: Int, hand: Int): HandObservation? {
        CropSampler.sample(src, roi.cropTransform(HandLm.SIZE).andThen(toBuffer), HandLm.SIZE, input, 1f / 255f, 0f, zeroBorder = false)
        if (autoLevel) AutoLevel.apply(input, HandLm.SIZE * HandLm.SIZE, 0f, 1f, level[player][hand])
        runner.runHand(input, raw, presence)
        lastRuns++
        if (presence[0] < MIN_PRESENCE) return null
        return decode(raw, roi, hand, presence[0])
    }

    private fun near(roi: RotatedRect, lm: RawLandmarks, h: Int): Boolean {
        val w = HandLm.POSE_WRIST[h]
        if (lm.visibility[w] < 0.3f) return true // the body model lost the wrist: trust the hand crop
        return hypot(roi.cx - lm.x[w], roi.cy - lm.y[w]) < roi.side * 0.9f
    }

    companion object {
        const val MIN_PRESENCE = 0.5f
        /** Crop size from the body pose: times the wrist-to-knuckles distance (generous: those points are rough). */
        const val POSE_ROI_SIDE = 4.6f
        /** Crop centre from the body pose: this far from the wrist towards the knuckles (1 = at the knuckles). */
        const val POSE_ROI_CENTER = 0.9f
        /** Tracking crop from the hand's own landmarks (MediaPipe: 2x the box, shifted 0.1 towards the fingers). */
        const val TRACK_ROI_SCALE = 2.0f
        const val TRACK_ROI_SHIFT = -0.1f

        /** First crop for hand [h] (0 = left) from the body landmarks, or null when the wrist is not seen. */
        fun poseRoi(lm: RawLandmarks, h: Int): RotatedRect? {
            val w = HandLm.POSE_WRIST[h]; val ix = HandLm.POSE_INDEX[h]; val pk = HandLm.POSE_PINKY[h]
            if (lm.visibility[w] < 0.3f) return null
            val wx = lm.x[w]; val wy = lm.y[w]
            // estimated middle knuckle: two thirds of the way from the pinky point to the index point
            val mx = (2f * lm.x[ix] + lm.x[pk]) / 3f
            val my = (2f * lm.y[ix] + lm.y[pk]) / 3f
            var d = hypot(mx - wx, my - wy)
            // never smaller than a typical hand for this body (the hand points are unreliable when blurred)
            val shoulder = hypot(lm.x[Lm.LEFT_SHOULDER] - lm.x[Lm.RIGHT_SHOULDER], lm.y[Lm.LEFT_SHOULDER] - lm.y[Lm.RIGHT_SHOULDER])
            d = max(d, shoulder * 0.22f)
            if (d < 2f) return null
            val dir = atan2(my - wy, mx - wx)
            val cx = wx + cos(dir) * d * POSE_ROI_CENTER
            val cy = wy + sin(dir) * d * POSE_ROI_CENTER
            val rot = normalizeRadians((Math.PI / 2).toFloat() - atan2(-(my - wy), mx - wx))
            return RotatedRect(cx, cy, d * POSE_ROI_SIDE, rot)
        }

        /**
         * The next frame's crop from this frame's hand landmarks (MediaPipe HandLandmarksToRect + transformation).
         *
         * MediaPipe turns the crop so the wrist-to-knuckles direction points up. A finger gun pointing at the camera
         * is seen end-on: that direction is only a few pixels long and swings about from frame to frame, turning the
         * crop with it. So the more end-on the hand, the more the crop's direction comes from the knuckle line
         * instead (fingers up = across the knuckles), which stays long; and it eases from [prevRotation].
         */
        fun trackingRoi(o: HandObservation, prevRotation: Float = Float.NaN): RotatedRect {
            val x0 = o.x[HandLm.WRIST]; val y0 = o.y[HandLm.WRIST]
            val x1 = ((o.x[HandLm.INDEX_MCP] + o.x[HandLm.RING_MCP]) / 2f + o.x[HandLm.MIDDLE_MCP]) / 2f
            val y1 = ((o.y[HandLm.INDEX_MCP] + o.y[HandLm.RING_MCP]) / 2f + o.y[HandLm.MIDDLE_MCP]) / 2f
            val rot = cropRotation(o, x1 - x0, y1 - y0, prevRotation)
            // bounding box of the steady landmarks in the rotated frame
            val c = cos(-rot); val s = sin(-rot)
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (i in HandLm.ROI_POINTS) {
                val u = c * o.x[i] - s * o.y[i]; val v = s * o.x[i] + c * o.y[i]
                minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
            }
            val cu = (minU + maxU) / 2f; val cv0 = (minV + maxV) / 2f
            val w = maxU - minU; val hgt = maxV - minV
            val cv = cv0 + hgt * TRACK_ROI_SHIFT
            // back to image coordinates
            val cr = cos(rot); val sr = sin(rot)
            val cx = cr * cu - sr * cv; val cy = sr * cu + cr * cv
            return RotatedRect(cx, cy, max(w, hgt) * TRACK_ROI_SCALE, rot)
        }

        /** Crop rotation (0 = fingers up) for a hand whose wrist-to-knuckles vector is ([px], [py]). */
        private fun cropRotation(o: HandObservation, px: Float, py: Float, prevRotation: Float): Float {
            fun rotOf(dx: Float, dy: Float) = normalizeRadians((Math.PI / 2).toFloat() - atan2(-dy, dx))
            val kx = o.x[HandLm.PINKY_MCP] - o.x[HandLm.INDEX_MCP]; val ky = o.y[HandLm.PINKY_MCP] - o.y[HandLm.INDEX_MCP]
            val kw = hypot(kx, ky)
            val pl = hypot(px, py)
            if (kw < 1e-3f) return if (pl > 1e-3f) rotOf(px, py) else (if (prevRotation.isNaN()) 0f else prevRotation)
            // the two directions across the knuckle line; take the one on the fingers' side of the wrist, or (end-on,
            // no clear side) the one nearer the last crop's direction, else nearer upright
            var nx = ky / kw; var ny = -kx / kw
            val dotPalm = nx * px + ny * py
            val flip = when {
                // the palm clearly shows which side the fingers are on
                kotlin.math.abs(dotPalm) > 0.5f * kw -> dotPalm < 0f
                // otherwise keep to the side the crop was already facing (noise must not flip it over)
                !prevRotation.isNaN() -> kotlin.math.abs(angleDiff(rotOf(nx, ny), prevRotation)) > Math.PI / 2
                pl > 1e-3f -> dotPalm < 0f
                else -> kotlin.math.abs(rotOf(nx, ny)) > Math.PI / 2
            }
            if (flip) { nx = -nx; ny = -ny }
            // how end-on: palm at least a knuckle width long on the picture = seen from the side, use it as MediaPipe does
            val w = ((pl / kw - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val ux = if (pl > 1e-6f) px / pl else 0f; val uy = if (pl > 1e-6f) py / pl else 0f
            val dx = w * ux + (1 - w) * nx; val dy = w * uy + (1 - w) * ny
            var rot = if (hypot(dx, dy) < 1e-4f) rotOf(nx, ny) else rotOf(dx, dy)
            if (!prevRotation.isNaN()) {
                val d = angleDiff(rot, prevRotation)
                // ease small changes (jitter), follow big ones (the hand really turned)
                if (kotlin.math.abs(d) < 0.8f && w < 1f) rot = normalizeRadians(prevRotation + d * 0.4f)
            }
            return rot
        }

        private fun angleDiff(a: Float, b: Float): Float = normalizeRadians(a - b)

        /** Model output (crop pixels) -> image coordinates. */
        fun decode(raw: FloatArray, roi: RotatedRect, hand: Int, presence: Float): HandObservation {
            val t = roi.cropTransform(HandLm.SIZE)
            val k = roi.side / HandLm.SIZE
            val xs = FloatArray(HandLm.COUNT); val ys = FloatArray(HandLm.COUNT); val zs = FloatArray(HandLm.COUNT)
            for (i in 0 until HandLm.COUNT) {
                // crop pixel coordinates are continuous (0..224); the transform maps pixel centres
                val u = raw[i * 3] - 0.5f; val v = raw[i * 3 + 1] - 0.5f
                xs[i] = t.mapX(u, v); ys[i] = t.mapY(u, v); zs[i] = raw[i * 3 + 2] * k
            }
            return HandObservation(hand, xs, ys, zs, presence)
        }
    }
}
