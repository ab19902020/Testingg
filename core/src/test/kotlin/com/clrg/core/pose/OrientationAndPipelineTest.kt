package com.clrg.core.pose

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrientationAndPipelineTest {

    @Test
    fun rotationFormulaForFrontAndBackCameras() {
        // Typical phone: back sensor mounted at 90, front at 270, app in landscape (display rotation 90 or 270).
        assertEquals(0, FrameOrientation.rotationFor(90, 90, frontFacing = false))
        assertEquals(180, FrameOrientation.rotationFor(90, 270, frontFacing = false))
        assertEquals(0, FrameOrientation.rotationFor(270, 90, frontFacing = true))
        assertEquals(180, FrameOrientation.rotationFor(270, 270, frontFacing = true))
        // portrait (not used by the game, but the maths must hold)
        assertEquals(90, FrameOrientation.rotationFor(90, 0, frontFacing = false))
        assertEquals(270, FrameOrientation.rotationFor(270, 0, frontFacing = true))
    }

    /** A bright marker in the buffer's top-left corner must appear where a clockwise rotation puts it. */
    @Test
    fun uprightToBufferMapsMarkerForAllRotations() {
        val wb = 8; val hb = 4
        val img = RgbImage(wb, hb)
        img.pixels[0] = 0xFFFFFF // buffer top-left
        val expectedUprightCorner = mapOf(0 to "TL", 90 to "TR", 180 to "BR", 270 to "BL")
        for ((rot, corner) in expectedUprightCorner) {
            val o = FrameOrientation(wb, hb, rot)
            val m = o.uprightToBuffer()
            val uw = o.uprightWidth; val uh = o.uprightHeight
            val (ux, uy) = when (corner) {
                "TL" -> 0.5f to 0.5f
                "TR" -> uw - 0.5f to 0.5f
                "BR" -> uw - 0.5f to uh - 0.5f
                else -> 0.5f to uh - 0.5f
            }
            val bx = m.mapX(ux, uy); val by = m.mapY(ux, uy)
            assertEquals(0.5f, bx, 1e-5f, "rot $rot x")
            assertEquals(0.5f, by, 1e-5f, "rot $rot y")
        }
    }

    @Test
    fun mirroringPutsPersonsLeftHandOnScreenLeft() {
        // Camera faces the player. In the true camera view the player's LEFT wrist appears on the image's RIGHT.
        val geo = FrameGeometry(1280, 720, mirror = true)
        val leftWristCameraX = 900f // right side of the camera image
        val rightWristCameraX = 300f
        assertTrue(geo.toFrameX(leftWristCameraX) < geo.toFrameX(rightWristCameraX))
        assertEquals(1280f / 720f, geo.aspect, 1e-6f)
        val noMirror = FrameGeometry(1280, 720, mirror = false)
        assertTrue(noMirror.toFrameX(leftWristCameraX) > noMirror.toFrameX(rightWristCameraX))
        // round trip
        assertEquals(900f, geo.toUprightX(geo.toFrameX(900f)), 1e-3f)
    }

    @Test
    fun yuvConversionRoundTripsColours() {
        val img = RgbImage(4, 4)
        val colours = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0x204060)
        // uniform 2x2 blocks so chroma subsampling is exact
        for (by in 0..1) for (bx in 0..1) {
            val c = colours[by * 2 + bx]
            for (y in 0..1) for (x in 0..1) img.pixels[(by * 2 + y) * 4 + bx * 2 + x] = c
        }
        val yuv = YuvImage.fromRgb(img)
        val a = FloatArray(3); val b = FloatArray(3)
        for (y in 0 until 4) for (x in 0 until 4) {
            img.readPixel(x, y, a, 0); yuv.readPixel(x, y, b, 0)
            for (c in 0..2) assertTrue(abs(a[c] - b[c]) <= 4f, "pixel ($x,$y) channel $c: ${a[c]} vs ${b[c]}")
        }
    }

    /** Fake model: one "person" whose landmarks follow a scripted hip position; detector fires near it. */
    private class FakeRunner(var people: List<Pair<Float, Float>>, val sidePx: Float) : PoseModelRunner {
        var detectorCalls = 0
        var landmarkCalls = 0
        var lastCropCenter = 0f to 0f
        var currentRegion: CropRegion? = null
        var currentRoi: RotatedRect? = null

        override fun runDetector(input: FloatArray, regressors: FloatArray, scores: FloatArray) {
            detectorCalls++
            scores.fill(-10f)
            val region = currentRegion!!
            val lb = region.letterbox()
            var slot = 0
            for ((hx, hy) in people) {
                if (hx < region.x || hx > region.x + region.w) continue
                // anchor near the person's hip in tensor space
                val tx = (lb.offX + (hx - region.x) * lb.scale) / 224f
                val ty = (lb.offY + (hy - region.y) * lb.scale) / 224f
                val i = nearestAnchor(tx, ty) + slot
                slot++
                val ax = BlazePose.anchors[i * 2]; val ay = BlazePose.anchors[i * 2 + 1]
                val o = i * 12
                regressors[o] = (tx - ax) * 224f; regressors[o + 1] = (ty - ay) * 224f
                regressors[o + 2] = 30f; regressors[o + 3] = 30f
                regressors[o + 4] = (tx - ax) * 224f; regressors[o + 5] = (ty - ay) * 224f
                // scale point straight up, |p1-p0| = side/2.5 in image px
                val up = sidePx / 2.5f * lb.scale
                regressors[o + 6] = (tx - ax) * 224f; regressors[o + 7] = (ty - ay) * 224f - up
                scores[i] = 6f
            }
        }

        private fun nearestAnchor(tx: Float, ty: Float): Int {
            var best = 0; var bd = Float.MAX_VALUE
            for (i in 0 until BlazePose.NUM_ANCHORS) {
                val d = abs(BlazePose.anchors[i * 2] - tx) + abs(BlazePose.anchors[i * 2 + 1] - ty)
                if (d < bd) { bd = d; best = i }
            }
            return best
        }

        override fun runLandmarks(input: FloatArray, landmarks: FloatArray, flag: FloatArray, heatmap: FloatArray?) {
            landmarkCalls++
            val roi = currentRoi!!
            // find a person inside this ROI
            val p = people.firstOrNull { abs(it.first - roi.cx) < roi.side * 0.3f && abs(it.second - roi.cy) < roi.side * 0.3f }
            if (p == null) { flag[0] = 0.01f; return }
            flag[0] = 0.99f
            heatmap?.fill(-10f)
            // express the person's hip in crop coordinates (rotation ~0 in this fake)
            for (i in 0 until 39) {
                val o = i * 5
                landmarks[o] = 128f + (p.first - roi.cx) / roi.side * 256f
                landmarks[o + 1] = 128f + (p.second - roi.cy) / roi.side * 256f
                landmarks[o + 3] = 5f; landmarks[o + 4] = 5f
            }
            // aux scale point straight above the hip
            landmarks[34 * 5 + 1] -= sidePx / 2.5f / roi.side * 256f
        }
    }

    @Test
    fun pipelineDetectsThenTracksWithoutRerunningDetectorEveryFrame() {
        val img = RgbImage(64, 36)
        val fake = FakeRunner(listOf(400f to 500f, 900f to 500f), sidePx = 500f)
        val pipe = MultiPosePipeline(object : PoseModelRunner {
            override fun runDetector(input: FloatArray, regressors: FloatArray, scores: FloatArray) = fake.runDetector(input, regressors, scores)
            override fun runLandmarks(input: FloatArray, landmarks: FloatArray, flag: FloatArray, heatmap: FloatArray?) = fake.runLandmarks(input, landmarks, flag, heatmap)
        }, PipelineConfig(searchRegions = listOf(RegionSpec(0f, 0.56f), RegionSpec(0.44f, 1f)), refineWithHeatmap = false, detectIntervalWhenSatisfied = 4))
        val orientation = FrameOrientation(1280, 720, 0)
        val geometryHook = GeometryHook(fake)
        val ids = HashSet<Int>()
        var poses: List<RawPose> = emptyList()
        for (frame in 0 until 12) {
            poses = geometryHook.run(pipe, img, orientation)
            poses.forEach { ids.add(it.trackId) }
        }
        assertEquals(2, poses.size, "both people tracked")
        assertEquals(2, ids.size, "track ids stay stable across frames")
        // detector ran on the first frames, then only every 4th frame (looking for spectators)
        assertTrue(fake.detectorCalls in 3..8, "detector calls ${fake.detectorCalls}")
        val xs = poses.map { it.landmarks.x[Lm.AUX_HIP_CENTER] }.sorted()
        assertEquals(400f, xs[0], 2f); assertEquals(900f, xs[1], 2f)
    }

    @Test
    fun pipelineDropsPersonWhoLeavesAndReacquiresWithNewTrack() {
        val img = RgbImage(64, 36)
        val fake = FakeRunner(listOf(400f to 500f, 900f to 500f), sidePx = 500f)
        val pipe = MultiPosePipeline(object : PoseModelRunner {
            override fun runDetector(input: FloatArray, regressors: FloatArray, scores: FloatArray) = fake.runDetector(input, regressors, scores)
            override fun runLandmarks(input: FloatArray, landmarks: FloatArray, flag: FloatArray, heatmap: FloatArray?) = fake.runLandmarks(input, landmarks, flag, heatmap)
        }, PipelineConfig(searchRegions = listOf(RegionSpec(0f, 0.56f), RegionSpec(0.44f, 1f)), refineWithHeatmap = false))
        val hook = GeometryHook(fake)
        val o = FrameOrientation(1280, 720, 0)
        repeat(3) { hook.run(pipe, img, o) }
        fake.people = listOf(400f to 500f)
        val p1 = hook.run(pipe, img, o)
        assertEquals(1, p1.size)
        fake.people = listOf(400f to 500f, 910f to 505f)
        var p2: List<RawPose> = emptyList()
        repeat(3) { p2 = hook.run(pipe, img, o) }
        assertEquals(2, p2.size)
    }

    /** The fake runner needs the crop geometry the pipeline is about to use; the pipeline observer provides it. */
    private class GeometryHook(val fake: FakeRunner) {
        fun run(pipe: MultiPosePipeline, img: PixelSource, o: FrameOrientation): List<RawPose> {
            pipe.observer = { region, roi -> if (region != null) fake.currentRegion = region; if (roi != null) fake.currentRoi = roi }
            return pipe.process(img, o)
        }
    }
}
