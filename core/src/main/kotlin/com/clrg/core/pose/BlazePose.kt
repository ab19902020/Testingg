package com.clrg.core.pose

import com.clrg.core.math.normalizeRadians
import com.clrg.core.math.sigmoid
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Decoding for the MediaPipe BlazePose two-stage models (pose_detector.tflite + pose_landmarks_detector.tflite,
 * Apache-2.0, bundled from the official MediaPipe pose_landmarker_{lite,full}.task files).
 *
 * This is a Kotlin port of the parts of the MediaPipe pose graph that we need (SSD anchors, detection decoding,
 * weighted NMS, alignment-point ROIs, landmark projection and heat-map refinement). It is verified against the
 * official MediaPipe PoseLandmarker in tools/pose-validation and by BlazePoseFixtureTest.
 */
object BlazePose {
    const val DETECTOR_SIZE = 224
    const val LANDMARK_SIZE = 256
    const val NUM_ANCHORS = 2254
    const val DETECTOR_COORDS = 12
    const val NUM_DETECTOR_KEYPOINTS = 4
    const val NUM_LANDMARKS_ALL = 39 // 33 body landmarks + 6 auxiliary
    const val LANDMARK_VALUES = 5     // x, y, z, visibility, presence
    const val HEATMAP_SIZE = 64
    const val ROI_SCALE = 1.25f

    /** SSD anchor centres (x, y interleaved) for the 224x224 pose detector. */
    val anchors: FloatArray by lazy { generateAnchors() }

    private fun generateAnchors(): FloatArray {
        val strides = intArrayOf(8, 16, 32, 32, 32)
        val out = ArrayList<Float>(NUM_ANCHORS * 2)
        var layer = 0
        while (layer < strides.size) {
            var perCell = 0
            var last = layer
            while (last < strides.size && strides[last] == strides[layer]) {
                perCell += 2 // aspect ratio 1.0 plus interpolated scale; fixed anchor size so only centres matter
                last++
            }
            val fm = ceil(DETECTOR_SIZE.toDouble() / strides[layer]).toInt()
            for (y in 0 until fm) for (x in 0 until fm) repeat(perCell) {
                out.add((x + 0.5f) / fm)
                out.add((y + 0.5f) / fm)
            }
            layer = last
        }
        check(out.size == NUM_ANCHORS * 2) { "anchor count ${out.size / 2}" }
        return out.toFloatArray()
    }
}

/** Indices of the 33 BlazePose body landmarks (plus the two auxiliary alignment points). */
object Lm {
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val COUNT = 33
    const val AUX_HIP_CENTER = 33
    const val AUX_FULL_BODY_SCALE = 34

    /** Landmarks drawn in the debug overlay and used for confidence: shoulders, elbows, wrists, hips. */
    val CORE_UPPER = intArrayOf(LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST)
    val SKELETON_EDGES = arrayOf(
        intArrayOf(LEFT_SHOULDER, RIGHT_SHOULDER), intArrayOf(LEFT_SHOULDER, LEFT_ELBOW), intArrayOf(LEFT_ELBOW, LEFT_WRIST),
        intArrayOf(RIGHT_SHOULDER, RIGHT_ELBOW), intArrayOf(RIGHT_ELBOW, RIGHT_WRIST), intArrayOf(LEFT_SHOULDER, LEFT_HIP),
        intArrayOf(RIGHT_SHOULDER, RIGHT_HIP), intArrayOf(LEFT_HIP, RIGHT_HIP), intArrayOf(LEFT_HIP, LEFT_KNEE),
        intArrayOf(RIGHT_HIP, RIGHT_KNEE), intArrayOf(LEFT_KNEE, LEFT_ANKLE), intArrayOf(RIGHT_KNEE, RIGHT_ANKLE),
    )
}

/** A square region of interest rotated about its centre, in image pixel units (continuous coordinates). */
data class RotatedRect(val cx: Float, val cy: Float, val side: Float, val rotation: Float) {
    companion object {
        /**
         * MediaPipe AlignmentPointsRectsCalculator + RectTransformationCalculator(scale 1.25, square_long):
         * centre at p0, size = 2 * |p1 - p0| * scale, rotation aligning p0->p1 with "up" (90 degrees).
         */
        fun fromAlignment(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float = BlazePose.ROI_SCALE): RotatedRect {
            val side = 2f * hypot(x1 - x0, y1 - y0) * scale
            val rot = normalizeRadians((Math.PI / 2).toFloat() - atan2(-(y1 - y0), x1 - x0))
            return RotatedRect(x0, y0, side, rot)
        }
    }

    /** Affine (tensor pixel index -> image continuous coordinate) for an N x N crop of this rect. */
    fun cropTransform(n: Int): Affine {
        val c = cos(rotation)
        val s = sin(rotation)
        val k = side / n
        val h = side / 2f
        // x = cx + c*((u+0.5)k - h) - s*((v+0.5)k - h)
        val a = c * k
        val b = -s * k
        val tx = cx + c * (0.5f * k - h) - s * (0.5f * k - h)
        val d = s * k
        val e = c * k
        val ty = cy + s * (0.5f * k - h) + c * (0.5f * k - h)
        return Affine(a, b, tx, d, e, ty)
    }
}

/** 2D affine map: x' = a*u + b*v + c ; y' = d*u + e*v + f. */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun mapX(u: Float, v: Float) = a * u + b * v + c
    fun mapY(u: Float, v: Float) = d * u + e * v + f

    /** Returns this map followed by [then] (then o this). */
    fun andThen(then: Affine): Affine = Affine(
        then.a * a + then.b * d, then.a * b + then.b * e, then.a * c + then.b * f + then.c,
        then.d * a + then.e * d, then.d * b + then.e * e, then.d * c + then.e * f + then.f,
    )

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 0f, 1f, 0f)
    }
}

/** Axis-aligned crop region (in upright image pixels) that the detector is run on. */
data class CropRegion(val x: Float, val y: Float, val w: Float, val h: Float) {
    /** Letterbox scale/offset that fits this region into the detector input while keeping aspect ratio. */
    fun letterbox(size: Int = BlazePose.DETECTOR_SIZE): Letterbox {
        val s = size / max(w, h)
        return Letterbox(s, (size - w * s) / 2f, (size - h * s) / 2f)
    }

    /** Tensor pixel index -> image continuous coordinate for the letterboxed detector input. */
    fun detectorTransform(size: Int = BlazePose.DETECTOR_SIZE): Affine {
        val lb = letterbox(size)
        return Affine(1f / lb.scale, 0f, x + (0.5f - lb.offX) / lb.scale, 0f, 1f / lb.scale, y + (0.5f - lb.offY) / lb.scale)
    }
}

data class Letterbox(val scale: Float, val offX: Float, val offY: Float)

/** Decoded pose detection in image pixel coordinates. Keypoint 0 = hip centre, 1 = full-body scale point. */
class PoseDetection(
    val score: Float,
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
    val kpX: FloatArray, val kpY: FloatArray,
) {
    fun roi(): RotatedRect = RotatedRect.fromAlignment(kpX[0], kpY[0], kpX[1], kpY[1])
}

object DetectionDecoder {
    /**
     * Decodes raw detector tensors (regressors [2254*12], scores [2254]) for a detector run over [region].
     * Mirrors MediaPipe TensorsToDetections (reverse_output_order, scale 224, sigmoid scores clipped to +-100),
     * weighted NMS (IoU 0.3) and DetectionProjection.
     */
    fun decode(
        regressors: FloatArray,
        scores: FloatArray,
        region: CropRegion,
        minScore: Float = 0.5f,
        maxDetections: Int = 4,
        iouThreshold: Float = 0.3f,
    ): List<PoseDetection> {
        val anchors = BlazePose.anchors
        val sz = BlazePose.DETECTOR_SIZE.toFloat()
        val cands = ArrayList<Cand>()
        for (i in 0 until BlazePose.NUM_ANCHORS) {
            val raw = scores[i].coerceIn(-100f, 100f)
            val score = sigmoid(raw)
            if (score < minScore) continue
            val o = i * BlazePose.DETECTOR_COORDS
            val ax = anchors[i * 2]
            val ay = anchors[i * 2 + 1]
            val xc = regressors[o] / sz + ax
            val yc = regressors[o + 1] / sz + ay
            val w = regressors[o + 2] / sz
            val h = regressors[o + 3] / sz
            val kx = FloatArray(BlazePose.NUM_DETECTOR_KEYPOINTS)
            val ky = FloatArray(BlazePose.NUM_DETECTOR_KEYPOINTS)
            for (k in 0 until BlazePose.NUM_DETECTOR_KEYPOINTS) {
                kx[k] = regressors[o + 4 + 2 * k] / sz + ax
                ky[k] = regressors[o + 5 + 2 * k] / sz + ay
            }
            cands.add(Cand(score, xc - w / 2f, yc - h / 2f, w, h, kx, ky))
        }
        cands.sortByDescending { it.score }
        val merged = ArrayList<Cand>()
        var remaining: List<Cand> = cands
        while (remaining.isNotEmpty() && merged.size < maxDetections) {
            val head = remaining[0]
            val cluster = ArrayList<Cand>()
            val rest = ArrayList<Cand>()
            for (c in remaining) if (iou(head, c) > iouThreshold) cluster.add(c) else rest.add(c)
            var tot = 0f
            var bx = 0f; var by = 0f; var bw = 0f; var bh = 0f
            val kx = FloatArray(BlazePose.NUM_DETECTOR_KEYPOINTS)
            val ky = FloatArray(BlazePose.NUM_DETECTOR_KEYPOINTS)
            for (c in cluster) {
                tot += c.score
                bx += c.score * c.x; by += c.score * c.y; bw += c.score * c.w; bh += c.score * c.h
                for (k in kx.indices) { kx[k] += c.score * c.kx[k]; ky[k] += c.score * c.ky[k] }
            }
            for (k in kx.indices) { kx[k] /= tot; ky[k] /= tot }
            merged.add(Cand(head.score, bx / tot, by / tot, bw / tot, bh / tot, kx, ky))
            remaining = rest
        }
        val lb = region.letterbox()
        fun px(t: Float) = region.x + (t * sz - lb.offX) / lb.scale
        fun py(t: Float) = region.y + (t * sz - lb.offY) / lb.scale
        return merged.map { c ->
            PoseDetection(
                c.score, px(c.x), py(c.y), px(c.x + c.w), py(c.y + c.h),
                FloatArray(c.kx.size) { px(c.kx[it]) }, FloatArray(c.ky.size) { py(c.ky[it]) },
            )
        }
    }

    private class Cand(val score: Float, val x: Float, val y: Float, val w: Float, val h: Float, val kx: FloatArray, val ky: FloatArray)

    private fun iou(a: Cand, b: Cand): Float {
        val ix = max(0f, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
        val iy = max(0f, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
        val inter = ix * iy
        val union = a.w * a.h + b.w * b.h - inter
        return if (union > 0f) inter / union else 0f
    }
}

/** 39 landmarks in image pixel coordinates (continuous), with sigmoid visibility/presence. */
class RawLandmarks(
    val x: FloatArray = FloatArray(BlazePose.NUM_LANDMARKS_ALL),
    val y: FloatArray = FloatArray(BlazePose.NUM_LANDMARKS_ALL),
    val z: FloatArray = FloatArray(BlazePose.NUM_LANDMARKS_ALL),
    val visibility: FloatArray = FloatArray(BlazePose.NUM_LANDMARKS_ALL),
    val presence: FloatArray = FloatArray(BlazePose.NUM_LANDMARKS_ALL),
) {
    /** ROI for the next frame, from the auxiliary hip-centre and full-body-scale landmarks. */
    fun trackingRoi(): RotatedRect = RotatedRect.fromAlignment(
        x[Lm.AUX_HIP_CENTER], y[Lm.AUX_HIP_CENTER], x[Lm.AUX_FULL_BODY_SCALE], y[Lm.AUX_FULL_BODY_SCALE],
    )
}

object LandmarkDecoder {
    /**
     * @param raw     195 values (39 x [x, y, z, visibility, presence]) in 256x256 crop pixel units.
     * @param heatmap optional 64*64*39 heat map (HWC) used to refine the 33 body landmarks.
     */
    fun decode(raw: FloatArray, heatmap: FloatArray?, rect: RotatedRect, out: RawLandmarks = RawLandmarks()): RawLandmarks {
        val n = BlazePose.LANDMARK_SIZE.toFloat()
        val c = cos(rect.rotation)
        val s = sin(rect.rotation)
        val tmp = FloatArray(2)
        for (i in 0 until BlazePose.NUM_LANDMARKS_ALL) {
            val o = i * BlazePose.LANDMARK_VALUES
            var lx = raw[o] / n
            var ly = raw[o + 1] / n
            if (heatmap != null && i < Lm.COUNT) {
                if (refineFromHeatmap(heatmap, i, lx, ly, tmp)) { lx = tmp[0]; ly = tmp[1] }
            }
            val nx = lx - 0.5f
            val ny = ly - 0.5f
            out.x[i] = rect.cx + (c * nx - s * ny) * rect.side
            out.y[i] = rect.cy + (s * nx + c * ny) * rect.side
            out.z[i] = raw[o + 2] / n * rect.side
            out.visibility[i] = sigmoid(raw[o + 3])
            out.presence[i] = sigmoid(raw[o + 4])
        }
        return out
    }

    /** MediaPipe RefineLandmarksFromHeatmapCalculator (kernel 7, min confidence 0.5). */
    fun refineFromHeatmap(hm: FloatArray, idx: Int, x: Float, y: Float, out: FloatArray, kernel: Int = 7, minConf: Float = 0.5f): Boolean {
        val size = BlazePose.HEATMAP_SIZE
        val ch = BlazePose.NUM_LANDMARKS_ALL
        // static_cast<int> in MediaPipe truncates toward zero
        val col = (x * size).toInt()
        val row = (y * size).toInt()
        if (col < 0 || col >= size || row < 0 || row >= size) return false
        val off = (kernel - 1) / 2
        val r0 = max(0, row - off)
        val r1 = min(size, row + off + 1)
        val c0 = max(0, col - off)
        val c1 = min(size, col + off + 1)
        var sum = 0f
        var maxV = 0f
        var wc = 0f
        var wr = 0f
        for (r in r0 until r1) for (cc in c0 until c1) {
            val conf = sigmoid(hm[(r * size + cc) * ch + idx])
            sum += conf
            if (conf > maxV) maxV = conf
            wc += cc * conf
            wr += r * conf
        }
        if (maxV < minConf || sum <= 0f) return false
        out[0] = wc / size / sum
        out[1] = wr / size / sum
        return true
    }
}
