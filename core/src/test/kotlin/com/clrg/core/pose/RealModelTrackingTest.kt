package com.clrg.core.pose

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 1.8.0 tracking aids measured with the REAL pose models on a real photo of two people, made into short
 * sequences that are hard on purpose: a dark room, both players swaying fast side by side, sensor noise. Skipped
 * unless the real-model validation environment is configured (see RealModelPipelineTest). Sequences built from a
 * still photo are a proxy for a camera, not a substitute for testing on a phone.
 */
class RealModelTrackingTest {
    private val python = System.getProperty("clrg.validation.python")
    private val models = System.getProperty("clrg.validation.models")
    private val scenes = System.getProperty("clrg.validation.scenes")
    private var server: RealModelServer? = null

    private fun configured() = python != null && models != null && scenes != null && File(scenes, "sceneE_real_pair.png").exists()

    @AfterTest
    fun close() { server?.close() }

    private fun runner(): PoseModelRunner = (server ?: RealModelServer(python!!, models!!).also { server = it }).runner('f')

    private fun load(): RgbImage {
        val bi = ImageIO.read(File(scenes, "sceneE_real_pair.png"))
        val img = RgbImage(bi.width, bi.height)
        for (y in 0 until bi.height) for (x in 0 until bi.width) img.pixels[y * bi.width + x] = bi.getRGB(x, y) and 0xFFFFFF
        return img
    }

    /** The photo shifted right by [dx] pixels, with brightness [gain] and sensor noise of about [noise] levels. */
    private fun frame(base: RgbImage, dx: Float, gain: Float, noise: Float, seed: Int): RgbImage {
        val out = RgbImage(base.width, base.height)
        var h = seed * 747796405 + 1
        for (y in 0 until base.height) for (x in 0 until base.width) {
            val p = base.pixels[y * base.width + (x - dx).toInt().coerceIn(0, base.width - 1)]
            var rgb = 0
            for (ch in 0..2) {
                var v = ((p shr (16 - ch * 8)) and 0xFF) * gain
                if (noise > 0f) { h = h xor (h shl 13); h = h xor (h ushr 17); h = h xor (h shl 5); v += h * 4.656613e-10f * noise * 1.7f }
                rgb = (rgb shl 8) or v.toInt().coerceIn(0, 255)
            }
            out.pixels[y * base.width + x] = rgb
        }
        return out
    }

    /** The tracking of 1.7.0: the default without the low-light boost. */
    private val old = PipelineConfig(autoLevel = false)
    /** With the extra tracking aids switched on (Settings -> Motion). */
    private val aids = PipelineConfig().withExtraAids(true)

    @Test
    fun aDarkRoomStillFindsBothPlayers() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val base = load()
        fun found(cfg: PipelineConfig): Int {
            val pipe = MultiPosePipeline(runner(), cfg)
            return (0 until 16).count { k -> pipe.process(frame(base, 0f, 0.15f, 4f, k + 1), FrameOrientation(base.width, base.height, 0)).size >= 2 }
        }
        val before = found(old)
        val now = found(PipelineConfig())
        println("[real-model] dark room (about 2.7 stops under): both players found in $now/16 frames (old settings: $before/16)")
        assertTrue(now >= 14, "both players found in a dark room: $now/16")
    }

    @Test
    fun playersSwayingFastKeepTheirOwnTracks() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val base = load()
        val o = FrameOrientation(base.width, base.height, 0)
        fun swaps(cfg: PipelineConfig): Int {
            val pipe = MultiPosePipeline(runner(), cfg)
            // the two people's noses in the photo, from the first frame
            val start = pipe.process(base, o).sortedBy { it.landmarks.x[Lm.NOSE] }
            check(start.size == 2)
            val nx = floatArrayOf(start[0].landmarks.x[Lm.NOSE], start[1].landmarks.x[Lm.NOSE])
            val side = HashMap<Int, Int>()
            var swaps = 0
            for (k in 1 until 30) {
                val dx = 160f * sin(k * 2 * Math.PI.toFloat() / 14f)
                for (p in pipe.process(frame(base, dx, 1f, 0f, k), o)) {
                    val x = p.landmarks.x[Lm.NOSE]
                    // which person is this, by where each one is in this frame? (clearly one of them: in between
                    // - a track trailing behind for a frame at the edge of the picture - is not counted either way)
                    val d0 = abs(x - (nx[0] + dx)); val d1 = abs(x - (nx[1] + dx))
                    val who = if (d0 < 0.4f * d1) 0 else if (d1 < 0.4f * d0) 1 else continue
                    val was = side.put(p.trackId, who)
                    if (was != null && was != who) { swaps++; println("[real-model]   swap: frame $k track ${p.trackId} nose x ${x.toInt()} (people at ${(nx[0] + dx).toInt()} and ${(nx[1] + dx).toInt()})") }
                }
            }
            return swaps
        }
        val before = swaps(old)
        val now = swaps(aids)
        println("[real-model] fast side-by-side sway: track jumped to the other person $now times with the extra aids (1.7.0 settings: $before)")
        assertTrue(now == 0, "no track jumps onto the other player: $now")
    }

    @Test
    fun landmarksAreSteadierOnANoisyPicture() {
        assumeTrue(configured(), "real-model validation environment not configured")
        val base = load()
        val o = FrameOrientation(base.width, base.height, 0)
        val joints = intArrayOf(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW, Lm.LEFT_WRIST, Lm.RIGHT_WRIST)
        fun jitter(cfg: PipelineConfig): Float {
            val pipe = MultiPosePipeline(runner(), cfg)
            val xs = Array(2) { Array(joints.size) { ArrayList<Float>() } }
            for (k in 0 until 24) {
                val poses = pipe.process(frame(base, 0f, 1f, 14f, k + 1), o).sortedBy { it.landmarks.x[Lm.NOSE] }
                if (k < 3 || poses.size < 2) continue
                for (p in 0..1) for ((i, j) in joints.withIndex()) xs[p][i].add(poses[p].landmarks.x[j])
            }
            var sum = 0f; var n = 0
            for (p in 0..1) for (v in xs[p]) {
                if (v.size < 5) continue
                val m = v.average()
                sum += sqrt(v.sumOf { (it - m) * (it - m) } / v.size).toFloat(); n++
            }
            return sum / n / base.height
        }
        val before = jitter(old)
        val boost = jitter(PipelineConfig())
        val now = jitter(aids)
        println("[real-model] noisy still picture: joint jitter %.4f of the frame height with the extra aids, %.4f by default (1.7.0 settings %.4f)".format(now, boost, before))
        assertTrue(now < before * 0.85f, "the extra aids are steadier than before: $now vs $before")
        // in normal light the low-light boost must stay out of the way: the default tracks as 1.7.0 did
        assertTrue(kotlin.math.abs(boost - before) < before * 0.05f, "a normally lit picture is not changed by the boost: $boost vs $before")
    }
}
