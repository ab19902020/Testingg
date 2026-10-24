package com.clrg.core.pose

import com.clrg.core.json.Json
import com.clrg.core.tracking.PlayerTracker
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the complete Kotlin MultiPosePipeline with the REAL BlazePose models (inference delegated to a Python
 * ai-edge-litert helper process, tools/pose-validation/tflite_server.py) on real two-person images, and compares the
 * result with the official MediaPipe PoseLandmarker. Skipped unless the validation environment is configured:
 *   -Dclrg.validation.python=/path/to/python -Dclrg.validation.models=/path/to/models -Dclrg.validation.scenes=/dir
 * (tools/pose-validation/run_real_model_tests.sh sets everything up).
 */
class RealModelPipelineTest {
    private val python = System.getProperty("clrg.validation.python")
    private val models = System.getProperty("clrg.validation.models")
    private val scenes = System.getProperty("clrg.validation.scenes")
    private var proc: Process? = null

    private inner class ServerRunner(val variant: Char) : PoseModelRunner {
        val p: Process = proc ?: ProcessBuilder(python, "-I", File("../tools/pose-validation/tflite_server.py").absolutePath, models)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start().also { proc = it }
        val out = BufferedOutputStream(p.outputStream, 1 shl 20)
        val inp = DataInputStream(BufferedInputStream(p.inputStream, 1 shl 20))
        var detectorCalls = 0
        var landmarkCalls = 0

        private fun send(op: Char, data: FloatArray) {
            out.write(op.code); out.write(variant.code)
            val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(data)
            out.write(bb.array()); out.flush()
        }

        private fun recv(n: Int): FloatArray {
            val bytes = ByteArray(n * 4)
            inp.readFully(bytes)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(n).also { fb.get(it) }
        }

        override fun runDetector(input: FloatArray, regressors: FloatArray, scores: FloatArray) {
            detectorCalls++
            send('D', input)
            recv(regressors.size).copyInto(regressors)
            recv(scores.size).copyInto(scores)
        }

        override fun runLandmarks(input: FloatArray, landmarks: FloatArray, flag: FloatArray, heatmap: FloatArray?) {
            landmarkCalls++
            send('L', input)
            recv(landmarks.size).copyInto(landmarks)
            recv(1).copyInto(flag)
            val hm = recv(BlazePose.HEATMAP_SIZE * BlazePose.HEATMAP_SIZE * BlazePose.NUM_LANDMARKS_ALL)
            heatmap?.let { hm.copyInto(it) }
        }
    }

    @AfterTest
    fun shutdown() {
        proc?.let {
            try { it.outputStream.write(byteArrayOf('Q'.code.toByte(), 'Q'.code.toByte())); it.outputStream.close() } catch (_: java.io.IOException) {}
            if (!it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
        }
    }

    private fun configured(): Boolean = python != null && models != null && scenes != null && File(scenes, "sceneA_two_players.png").exists()

    private fun load(name: String): RgbImage {
        val bi = ImageIO.read(File(scenes, name))
        val img = RgbImage(bi.width, bi.height)
        for (y in 0 until bi.height) for (x in 0 until bi.width) img.pixels[y * bi.width + x] = bi.getRGB(x, y) and 0xFFFFFF
        return img
    }

    /** Rotates an image 90 degrees counter-clockwise (so a 90-degree clockwise rotation makes it upright again). */
    private fun rotateCcw(src: RgbImage): RgbImage {
        val out = RgbImage(src.height, src.width)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            // (x, y) -> (y, W-1-x)
            out.pixels[(src.width - 1 - x) * out.width + y] = src.pixels[y * src.width + x]
        }
        return out
    }

    private fun reference(variant: String, scene: String): List<List<List<Float>>> {
        @Suppress("UNCHECKED_CAST")
        val all = Json.parse(File(scenes, "mp_reference.json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        return (all["$variant/$scene"] as List<List<List<Number>>>).map { p -> p.map { l -> l.map { it.toFloat() } } }
    }

    private val keyJoints = intArrayOf(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW, Lm.LEFT_WRIST, Lm.RIGHT_WRIST, Lm.LEFT_HIP, Lm.RIGHT_HIP)

    /**
     * Mean normalised error over the shoulders/elbows/wrists/hips that the reference sees (visibility > 0.5),
     * against the best-matching reference pose.
     */
    private fun errorVsReference(p: RawPose, ref: List<List<List<Float>>>, w: Int, h: Int): Float =
        ref.minOf { r ->
            val joints = keyJoints.filter { j -> r[j][3] > 0.5f }
            joints.map { j -> (abs(p.landmarks.x[j] / w - r[j][0]) + abs(p.landmarks.y[j] / h - r[j][1])) / 2f }.average().toFloat()
        }

    private fun runFrames(pipe: MultiPosePipeline, src: PixelSource, o: FrameOrientation, n: Int): List<RawPose> {
        var last: List<RawPose> = emptyList()
        repeat(n) { last = pipe.process(src, o) }
        return last
    }

    @Test
    fun realModelsFindBothPlayersAndMatchMediaPipe() {
        assumeTrue(configured(), "real-model validation environment not configured")
        for ((variant, v) in listOf("lite" to 'l', "full" to 'f')) {
            val runner = ServerRunner(v)
            for (scene in listOf("sceneA_two_players.png", "sceneB_mixed.png", "sceneE_real_pair.png")) {
                val img = load(scene)
                val pipe = MultiPosePipeline(runner)
                val poses = runFrames(pipe, img, FrameOrientation(img.width, img.height, 0), 4)
                assertEquals(2, poses.size, "$variant $scene: two people")
                val ref = reference(variant, scene)
                for (p in poses) {
                    val err = errorVsReference(p, ref, img.width, img.height)
                    val limit = if (scene.startsWith("sceneE")) 0.06f else 0.02f // E: close-up, hips out of frame
                    assertTrue(err < limit, "$variant $scene error $err")
                }
                println("[real-model] $variant $scene: 2 people, errors ${poses.map { "%.4f".format(errorVsReference(it, ref, img.width, img.height)) }}")
            }
        }
    }

    /** Players ~45% of frame height: the full-frame detector misses them, the half-frame search regions do not. */
    @Test
    fun farPlayersNeedHalfFrameSearch() {
        assumeTrue(configured() && File(scenes, "sceneF_far_players.png").exists(), "real-model validation environment not configured")
        val img = load("sceneF_far_players.png")
        val runner = ServerRunner('f')
        val withHalves = runFrames(MultiPosePipeline(runner), img, FrameOrientation(img.width, img.height, 0), 4)
        val fullOnly = runFrames(MultiPosePipeline(runner, PipelineConfig(searchRegions = listOf(RegionSpec(0f, 1f)))), img, FrameOrientation(img.width, img.height, 0), 4)
        println("[real-model] far players: half-frame search finds ${withHalves.size}, full-frame only finds ${fullOnly.size}")
        assertEquals(2, withHalves.size)
        assertTrue(fullOnly.size < 2)
    }

    @Test
    fun emptyRoomHasNobody() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val img = load("sceneD_empty.png")
        val pipe = MultiPosePipeline(ServerRunner('f'))
        assertEquals(0, runFrames(pipe, img, FrameOrientation(img.width, img.height, 0), 4).size)
    }

    @Test
    fun rotatedCameraBufferGivesTheSameLandmarks() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val img = load("sceneA_two_players.png")
        val runner = ServerRunner('f')
        val upright = runFrames(MultiPosePipeline(runner), img, FrameOrientation(img.width, img.height, 0), 3).sortedBy { it.landmarks.x[Lm.NOSE] }
        val rotated = rotateCcw(img) // what a portrait-mounted sensor would deliver
        val viaRotation = runFrames(MultiPosePipeline(runner), rotated, FrameOrientation(rotated.width, rotated.height, 90), 3).sortedBy { it.landmarks.x[Lm.NOSE] }
        assertEquals(2, viaRotation.size)
        for (k in 0..1) for (j in keyJoints) {
            assertEquals(upright[k].landmarks.x[j], viaRotation[k].landmarks.x[j], 8f, "joint $j x")
            assertEquals(upright[k].landmarks.y[j], viaRotation[k].landmarks.y[j], 8f, "joint $j y")
        }
    }

    @Test
    fun yuvCameraPathMatchesRgb() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val img = load("sceneB_mixed.png")
        val runner = ServerRunner('f')
        val rgb = runFrames(MultiPosePipeline(runner), img, FrameOrientation(img.width, img.height, 0), 3).sortedBy { it.landmarks.x[Lm.NOSE] }
        val yuv = YuvImage.fromRgb(img)
        val fromYuv = runFrames(MultiPosePipeline(runner), yuv, FrameOrientation(img.width, img.height, 0), 3).sortedBy { it.landmarks.x[Lm.NOSE] }
        assertEquals(2, fromYuv.size)
        for (k in 0..1) for (j in keyJoints) assertEquals(rgb[k].landmarks.x[j], fromYuv[k].landmarks.x[j], 10f)
    }

    @Test
    fun trackingAcrossFramesKeepsIdentitiesAndSkipsTheDetector() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val img = load("sceneA_two_players.png")
        val runner = ServerRunner('l')
        val pipe = MultiPosePipeline(runner)
        val geo = FrameGeometry(img.width, img.height, mirror = true)
        val tracker = PlayerTracker(2)
        val ids = HashSet<Int>()
        var t = 0L
        repeat(20) {
            val raws = pipe.process(img, FrameOrientation(img.width, img.height, 0))
            raws.forEach { ids.add(it.trackId) }
            t += 33_000_000L
            tracker.update(PoseFrame(t, geo.aspect, raws.map { PersonPose.fromRaw(it, geo) }))
        }
        assertEquals(2, ids.size, "pipeline track ids are stable")
        assertTrue(tracker.allPlaying, "both players acquired from their areas")
        // mirrored view: the person on the camera image's right is player 1 (screen left)
        assertTrue(tracker.slots[0].shoulderX < tracker.slots[1].shoulderX)
        // after the initial search the detector only runs occasionally (looking for a third person)
        assertTrue(runner.detectorCalls < 20, "detector calls ${runner.detectorCalls} over 20 frames")
        println("[real-model] 20 frames: detector runs ${runner.detectorCalls}, landmark runs ${runner.landmarkCalls}")
    }
}
