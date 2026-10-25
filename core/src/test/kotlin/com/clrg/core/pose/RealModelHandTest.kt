package com.clrg.core.pose

import com.clrg.core.gesture.HandShape
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Finger tracking with the REAL MediaPipe hand landmark model (lite), run through the Kotlin crop / decode / tracking
 * code, on real photos (MediaPipe's Apache-2.0 test images). Skipped unless the validation environment is set up
 * (tools/pose-validation/run_real_model_tests.sh). These are still photos: they say nothing about motion blur, light
 * or how small hands are in a real living room - that needs testing on a phone.
 */
class RealModelHandTest {
    private val python = System.getProperty("clrg.validation.python")
    private val models = System.getProperty("clrg.validation.models")
    private val scenes = System.getProperty("clrg.validation.scenes")
    private var proc: Process? = null

    private fun configured(): Boolean = python != null && models != null && scenes != null &&
        File(models, "hand/hand_landmark_lite.tflite").exists() && File(scenes, "pointing_up.jpg").exists()

    private inner class Server : PoseModelRunner, HandModelRunner {
        val p: Process = proc ?: ProcessBuilder(python, "-I", File("../tools/pose-validation/tflite_server.py").absolutePath, models)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start().also { proc = it }
        val out = BufferedOutputStream(p.outputStream, 1 shl 20)
        val inp = DataInputStream(BufferedInputStream(p.inputStream, 1 shl 20))
        var handCalls = 0

        private fun send(op: Char, v: Char, data: FloatArray) {
            out.write(op.code); out.write(v.code)
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
            send('D', 'f', input); recv(regressors.size).copyInto(regressors); recv(scores.size).copyInto(scores)
        }

        override fun runLandmarks(input: FloatArray, landmarks: FloatArray, flag: FloatArray, heatmap: FloatArray?) {
            send('L', 'f', input); recv(landmarks.size).copyInto(landmarks); recv(1).copyInto(flag)
            val hm = recv(BlazePose.HEATMAP_SIZE * BlazePose.HEATMAP_SIZE * BlazePose.NUM_LANDMARKS_ALL)
            heatmap?.let { hm.copyInto(it) }
        }

        override fun runHand(input: FloatArray, landmarks: FloatArray, presence: FloatArray) {
            handCalls++
            send('H', 'l', input); recv(63).copyInto(landmarks); recv(1).copyInto(presence)
        }
    }

    @AfterTest
    fun shutdown() {
        proc?.let {
            try { it.outputStream.write(byteArrayOf('Q'.code.toByte(), 'Q'.code.toByte())); it.outputStream.close() } catch (_: java.io.IOException) {}
            if (!it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
        }
    }

    private fun toRgb(bi: BufferedImage): RgbImage {
        val img = RgbImage(bi.width, bi.height)
        for (y in 0 until bi.height) for (x in 0 until bi.width) img.pixels[y * bi.width + x] = bi.getRGB(x, y) and 0xFFFFFF
        return img
    }

    private fun load(name: String, scale: Float = 1f): RgbImage {
        var bi = ImageIO.read(File(scenes, name))
        if (scale != 1f) {
            // shrink (as a hand far from the camera), keeping the picture size by padding with the wall colour
            val w = (bi.width * scale).toInt(); val h = (bi.height * scale).toInt()
            val small = BufferedImage(bi.width, bi.height, BufferedImage.TYPE_INT_RGB)
            val g = small.createGraphics()
            g.color = java.awt.Color(bi.getRGB(2, 2))
            g.fillRect(0, 0, bi.width, bi.height)
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(bi, (bi.width - w) / 2, (bi.height - h) / 2, w, h, null)
            g.dispose()
            bi = small
        }
        return toRgb(bi)
    }

    /** Hand landmarks of a hand photo: the crop is the whole (square-padded) photo, as a first guess would be. */
    private fun handIn(server: Server, img: RgbImage, side: Float = maxOf(img.width, img.height).toFloat()): HandObservation? {
        val roi = RotatedRect(img.width / 2f, img.height / 2f, side, 0f)
        val input = FloatArray(224 * 224 * 3)
        CropSampler.sample(img, roi.cropTransform(224), 224, input, 1f / 255f, 0f, zeroBorder = false)
        val raw = FloatArray(63); val pres = FloatArray(1)
        server.runHand(input, raw, pres)
        if (pres[0] < HandLandmarkTracker.MIN_PRESENCE) return null
        return HandLandmarkTracker.decode(raw, roi, 1, pres[0])
    }

    /** The crop follows the hand's size (as the tracker's does): a shrunk hand gets a proportionally smaller crop. */
    private fun crop(scale: Float) = 400f * scale

    private fun shapeOf(o: HandObservation) = HandShape().apply { measure(FloatArray(63) { i -> when (i % 3) { 0 -> o.x[i / 3]; 1 -> o.y[i / 3]; else -> o.z[i / 3] } }) }

    @Test
    fun fingerShapesOnRealHands() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val s = Server()
        // full size (hand ~250 px) and shrunk so the hand is ~50 px across, like a hand 2.5 m from a 720p camera
        for (scale in listOf(1f, 0.2f)) {
            val point = shapeOf(assertNotNull(handIn(s, load("pointing_up.jpg", scale), crop(scale)), "pointing hand found at $scale"))
            println("[real-hand] pointing x$scale: bend ${point.bend.map { it.toInt() }} thumbGap ${"%.2f".format(point.thumbGap)}")
            assertTrue(point.extended(0), "index straight")
            assertTrue(point.curled(1) && point.curled(2) && point.curled(3), "other fingers curled")
            assertTrue(point.gun, "pointing hand reads as a gun")
            assertTrue(point.thumbGap < 0.33f, "thumb down against the fingers")

            val fist = shapeOf(assertNotNull(handIn(s, load("fist.jpg", scale), crop(scale))))
            println("[real-hand] fist x$scale: bend ${fist.bend.map { it.toInt() }} thumbGap ${"%.2f".format(fist.thumbGap)}")
            assertFalse(fist.gun, "a fist is not a gun")
            assertFalse(fist.extended(0))

            val two = shapeOf(assertNotNull(handIn(s, load("victory.jpg", scale), crop(scale))))
            println("[real-hand] victory x$scale: bend ${two.bend.map { it.toInt() }} thumbGap ${"%.2f".format(two.thumbGap)}")
            assertTrue(two.extended(0) && two.extended(1), "two fingers out")
            assertTrue(two.gun, "two-finger gun")
        }
        // the raised thumb: clear at full size; the hand is smaller in a room, so also at 0.3 (~75 px)
        for (scale in listOf(1f, 0.3f)) {
            val up = shapeOf(assertNotNull(handIn(s, load("thumb_up.jpg", scale), crop(scale))))
            println("[real-hand] thumb up x$scale: bend ${up.bend.map { it.toInt() }} thumbGap ${"%.2f".format(up.thumbGap)}")
            assertTrue(up.thumbGap > 0.55f, "thumb raised")
            assertFalse(up.extended(0), "index curled")
        }
    }

    @Test
    fun noHandMeansNoHand() {
        assumeTrue(configured(), "real-model validation environment not configured")
        assertEquals(null, handIn(Server(), load("cats_and_dogs.jpg")))
    }

    /** Sideways pointing hand: the crop has to be turned the hand's way up (from the body pose, then from the hand). */
    @Test
    fun turnedHandIsFollowedOnceTheCropTurnsWithIt() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val s = Server()
        val img = load("pointing_up_rotated.jpg")
        // first guess: the whole picture, not turned - the model is unsure
        val first = assertNotNull(handIn(s, img))
        // then follow the hand with its own landmarks for a few frames, as the tracker does
        var roi = HandLandmarkTracker.trackingRoi(first)
        var obs: HandObservation = first
        repeat(3) {
            val input = FloatArray(224 * 224 * 3)
            CropSampler.sample(img, roi.cropTransform(224), 224, input, 1f / 255f, 0f, zeroBorder = false)
            val raw = FloatArray(63); val pres = FloatArray(1)
            s.runHand(input, raw, pres)
            obs = HandLandmarkTracker.decode(raw, roi, 1, pres[0])
            roi = HandLandmarkTracker.trackingRoi(obs)
        }
        val shape = shapeOf(obs)
        println("[real-hand] turned pointing hand: presence ${"%.2f".format(obs.presence)} first ${"%.2f".format(first.presence)}, crop turned ${"%.0f".format(Math.toDegrees(roi.rotation.toDouble()))} deg, bend ${shape.bend.map { it.toInt() }} thumbGap ${"%.2f".format(shape.thumbGap)}")
        assertTrue(obs.presence > 0.9f, "sure of the hand once the crop follows it")
        assertTrue(shape.gun, "reads as pointing")
        // the index finger points to the right in the picture: tip well right of the knuckle
        assertTrue(obs.x[HandLm.INDEX_TIP] - obs.x[HandLm.INDEX_MCP] > 60f)
    }

    /** The whole chain on a real two-person photo: body pose -> hand crops -> finger landmarks next to the wrists. */
    @Test
    fun handsFromBodyPoseOnRealPeople() {
        assumeTrue(configured() && File(scenes, "sceneE_real_pair.png").exists(), "real-model validation environment not configured")
        val bi = ImageIO.read(File(scenes, "sceneE_real_pair.png"))
        val img = toRgb(bi)
        val s = Server()
        val pipe = MultiPosePipeline(s)
        val hands = HandLandmarkTracker(s)
        pipe.handLandmarks = hands
        val o = FrameOrientation(img.width, img.height, 0)
        var poses: List<RawPose> = emptyList()
        repeat(3) {
            poses = pipe.process(img, o)
            val sorted = poses.sortedBy { it.landmarks.x[Lm.NOSE] }
            hands.tracks = IntArray(2) { k -> sorted.getOrNull(k)?.trackId ?: -1 }
            hands.masks = intArrayOf(3, 3)
        }
        assertEquals(2, poses.size)
        var found = 0
        for (p in poses) for (h in 0..1) {
            val obs = p.hands?.get(h) ?: continue
            found++
            val w = HandLm.POSE_WRIST[h]
            val d = hypot(obs.x[HandLm.WRIST] - p.landmarks.x[w], obs.y[HandLm.WRIST] - p.landmarks.y[w])
            val shoulder = hypot(p.landmarks.x[Lm.LEFT_SHOULDER] - p.landmarks.x[Lm.RIGHT_SHOULDER], p.landmarks.y[Lm.LEFT_SHOULDER] - p.landmarks.y[Lm.RIGHT_SHOULDER])
            println("[real-hand] person ${p.trackId} hand $h: presence ${"%.2f".format(obs.presence)}, hand wrist ${"%.0f".format(d)} px from body wrist (shoulders ${"%.0f".format(shoulder)} px)")
            assertTrue(d < shoulder * 0.5f, "hand landmarks next to the body's wrist")
        }
        // the two raised hands making OK signs are in clear view
        assertTrue(found >= 2, "found $found hands")
    }
}
