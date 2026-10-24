package com.clrg.core.pose

import com.clrg.core.json.JObj
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the Kotlin BlazePose decoding against fixtures exported by tools/pose-validation/export_fixtures.py.
 * Those fixtures hold *real* model outputs for a two-person scene and the expected results of the Python
 * reference pipeline, which matches the official MediaPipe PoseLandmarker to ~0.1% of the image size.
 */
class BlazePoseFixtureTest {
    private val meta: JObj = JObj.parse(resourceText("blazepose_fixture.json"))!!

    @Test
    fun anchorCountMatchesModel() {
        assertEquals(BlazePose.NUM_ANCHORS * 2, BlazePose.anchors.size)
        // first anchor: stride 8 grid cell (0,0)
        assertEquals(0.5f / 28f, BlazePose.anchors[0], 1e-6f)
        // last anchor: stride 32 grid cell (6,6)
        assertEquals(6.5f / 7f, BlazePose.anchors[BlazePose.anchors.size - 2], 1e-6f)
    }

    @Test
    fun detectorDecodingMatchesReference() {
        for (d in meta.objList("detector")) {
            val roi = d.list("roi").map { (it as Number).toFloat() }
            val region = CropRegion(roi[0], roi[1], roi[2], roi[3])
            val dets = DetectionDecoder.decode(floats(d.str("raw")), floats(d.str("scores")), region, 0.5f, 4, 0.3f)
            val expected = d.objList("expected")
            assertEquals(expected.size, dets.size, "detections for ${d.str("name")}")
            for ((e, got) in expected.zip(dets)) {
                assertEquals(e.float("score"), got.score, 1e-4f)
                val kps = e.list("kps").map { p -> (p as List<*>).map { (it as Number).toFloat() } }
                for (k in 0 until 4) {
                    assertEquals(kps[k][0], got.kpX[k], 0.05f, "kp$k x")
                    assertEquals(kps[k][1], got.kpY[k], 0.05f, "kp$k y")
                }
                val rect = e.list("rect").map { (it as Number).toFloat() }
                val r = got.roi()
                assertEquals(rect[0], r.cx, 0.05f); assertEquals(rect[1], r.cy, 0.05f)
                assertEquals(rect[2], r.side, 0.1f); assertEquals(rect[3], r.rotation, 1e-3f)
            }
        }
    }

    @Test
    fun halfFrameDetectionsAgreeWithFullFrame() {
        val det = meta.objList("detector").associateBy { it.str("name") }
        val full = det["full"]!!.objList("expected").map { it.list("kps")[0] as List<*> }
        val halves = (det["left"]!!.objList("expected") + det["right"]!!.objList("expected")).map { it.list("kps")[0] as List<*> }
        assertEquals(2, full.size)
        assertEquals(2, halves.size)
        // every person found on the full frame is found at (nearly) the same hip centre by a half-frame crop
        for (f in full) {
            val fx = (f[0] as Number).toFloat(); val fy = (f[1] as Number).toFloat()
            assertTrue(halves.any { h -> abs((h[0] as Number).toFloat() - fx) < 12f && abs((h[1] as Number).toFloat() - fy) < 12f })
        }
    }

    @Test
    fun landmarkDecodingMatchesReference() {
        for (l in meta.objList("landmarks")) {
            val rect = l.list("rect").map { (it as Number).toFloat() }.let { RotatedRect(it[0], it[1], it[2], it[3]) }
            val raw = l.list("raw").map { (it as Number).toFloat() }.toFloatArray()
            assertEquals(195, raw.size)
            val plain = LandmarkDecoder.decode(raw, null, rect)
            val exp = l.list("expectedNoRefine").map { row -> (row as List<*>).map { (it as Number).toFloat() } }
            for (i in 0 until 39) {
                assertEquals(exp[i][0], plain.x[i], 0.01f, "x$i")
                assertEquals(exp[i][1], plain.y[i], 0.01f, "y$i")
                assertEquals(exp[i][3], plain.visibility[i], 1e-4f, "vis$i")
                assertEquals(exp[i][4], plain.presence[i], 1e-4f, "pres$i")
            }
            val track = l.list("expectedTrackingRect").map { (it as Number).toFloat() }
            val tr = plain.trackingRoi()
            assertEquals(track[0], tr.cx, 0.05f); assertEquals(track[1], tr.cy, 0.05f)
            assertEquals(track[2], tr.side, 0.1f); assertEquals(track[3], tr.rotation, 1e-3f)
            if (l.has("heatmap")) {
                val hm = floats(l.str("heatmap"))
                val refined = LandmarkDecoder.decode(raw, hm, rect)
                val expR = l.list("expectedRefined").map { row -> (row as List<*>).map { (it as Number).toFloat() } }
                var moved = 0
                for (i in 0 until 39) {
                    assertEquals(expR[i][0], refined.x[i], 0.02f, "refined x$i")
                    assertEquals(expR[i][1], refined.y[i], 0.02f, "refined y$i")
                    if (abs(refined.x[i] - plain.x[i]) > 0.01f) moved++
                }
                assertTrue(moved > 5, "heat-map refinement should adjust several landmarks")
            }
        }
    }

    @Test
    // OpenCV interpolates with 1/32-pixel fixed-point weights, so allow one grey level of difference.
    fun cropSamplerMatchesOpenCvWarpAffine() {
        val s = meta.obj("sampler")!!
        val w = s.int("width"); val h = s.int("height")
        val img = proceduralImage(w, h)
        for (r in s.objList("rotated")) {
            val rr = r.list("rect").map { (it as Number).toFloat() }
            val rect = RotatedRect(rr[0], rr[1], rr[2], rr[3])
            val out = FloatArray(256 * 256 * 3)
            CropSampler.sample(img, rect.cropTransform(256), 256, out, 1f, 0f, zeroBorder = false)
            for (p in r.list("points")) {
                val q = (p as List<*>).map { (it as Number).toFloat() }
                val u = q[0].toInt(); val v = q[1].toInt()
                for (c in 0..2) assertEquals(q[2 + c], out[(v * 256 + u) * 3 + c], 1.0f, "rotated crop ($u,$v) c$c")
            }
        }
        val out = FloatArray(224 * 224 * 3)
        CropSampler.sample(img, CropRegion(0f, 0f, w.toFloat(), h.toFloat()).detectorTransform(), 224, out, 1f, 0f, zeroBorder = true)
        for (p in s.list("letterbox")) {
            val q = (p as List<*>).map { (it as Number).toFloat() }
            val u = q[0].toInt(); val v = q[1].toInt()
            for (c in 0..2) assertEquals(q[2 + c], out[(v * 224 + u) * 3 + c], 1.0f, "letterbox ($u,$v) c$c")
        }
    }

    private fun proceduralImage(w: Int, h: Int): RgbImage {
        val img = RgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val r = (x * 7 + y * 3) % 256
            val g = (x * 2 + y * 5 + 40) % 256
            val b = ((x / 8 + y / 8) % 2) * 200 + 20
            img.pixels[y * w + x] = (r shl 16) or (g shl 8) or b
        }
        return img
    }

    companion object {
        fun resourceText(name: String): String =
            BlazePoseFixtureTest::class.java.getResourceAsStream("/pose/$name")!!.bufferedReader().readText()

        fun floats(name: String): FloatArray {
            val bytes = GZIPInputStream(BlazePoseFixtureTest::class.java.getResourceAsStream("/pose/$name")!!).use { DataInputStream(it).readBytes() }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }
}
