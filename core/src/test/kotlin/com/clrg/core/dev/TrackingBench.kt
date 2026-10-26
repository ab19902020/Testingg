package com.clrg.core.dev

import com.clrg.core.json.Json
import com.clrg.core.pose.AutoLevel
import com.clrg.core.pose.FrameOrientation
import com.clrg.core.pose.Lm
import com.clrg.core.pose.MultiPosePipeline
import com.clrg.core.pose.PipelineConfig
import com.clrg.core.pose.RawPose
import com.clrg.core.pose.RealModelServer
import com.clrg.core.pose.RgbImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Developer tool (-Pclrg.trackbench=1 plus the real-model validation properties): runs the real pose models on
 * photo sequences made harder on purpose - fast movement, jumps, walking towards the camera, dropped frames, low
 * light, backlight, sensor noise - with the old and new pipeline settings, and prints how often both people were
 * found, landmark error against MediaPipe, jitter and cost. Synthetic sequences built from still photos: a proxy for
 * a real camera, not a substitute for one.
 */
class TrackingBench {
    private val python = System.getProperty("clrg.validation.python")
    private val models = System.getProperty("clrg.validation.models")
    private val scenes = System.getProperty("clrg.validation.scenes")

    class Scenario(val name: String, val frames: Int, val photometric: Boolean, val f: (Int) -> Xf)

    /** One frame's transform: geometry (shift, zoom about the centre) and light (gain, gamma, noise, a bright window). */
    class Xf(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val gain: Float = 1f, val gamma: Float = 1f,
             val noise: Float = 0f, val window: Float = 0f, val blank: Boolean = false)

    private fun load(name: String): RgbImage {
        val bi = ImageIO.read(File(scenes, name))
        val img = RgbImage(bi.width, bi.height)
        for (y in 0 until bi.height) for (x in 0 until bi.width) img.pixels[y * bi.width + x] = bi.getRGB(x, y) and 0xFFFFFF
        return img
    }

    private fun render(base: RgbImage, t: Xf, seed: Int): RgbImage {
        val w = base.width; val h = base.height
        val out = RgbImage(w, h)
        if (t.blank) return out
        val cx = w / 2f; val cy = h / 2f
        var hs = seed * 747796405 + 1
        for (y in 0 until h) for (x in 0 until w) {
            val sx = ((x - cx) / t.scale + cx - t.dx).toInt().coerceIn(0, w - 1)
            val sy = ((y - cy) / t.scale + cy - t.dy).toInt().coerceIn(0, h - 1)
            val p = base.pixels[sy * w + sx]
            val inWindow = t.window > 0f && (y < h * 0.1f || x < w * 0.06f || x > w * 0.94f)
            var rgb = 0
            for (ch in 0..2) {
                var v = ((p shr (16 - ch * 8)) and 0xFF) / 255f
                v = (v * t.gain).coerceIn(0f, 1f)
                if (t.gamma != 1f) v = Math.pow(v.toDouble(), t.gamma.toDouble()).toFloat()
                if (inWindow) v = v * (1f - t.window) + 0.97f * t.window
                if (t.noise > 0f) {
                    hs = hs xor (hs shl 13); hs = hs xor (hs ushr 17); hs = hs xor (hs shl 5)
                    v += (hs * 4.656613e-10f) * t.noise / 255f * 1.7f
                }
                rgb = (rgb shl 8) or (v.coerceIn(0f, 1f) * 255f).toInt()
            }
            out.pixels[y * w + x] = rgb
        }
        return out
    }

    private val keyJoints = intArrayOf(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW, Lm.LEFT_WRIST, Lm.RIGHT_WRIST, Lm.LEFT_HIP, Lm.RIGHT_HIP)

    private fun reference(variant: String, scene: String): List<List<List<Float>>> {
        @Suppress("UNCHECKED_CAST")
        val all = Json.parse(File(scenes, "mp_reference.json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        return (all["$variant/$scene"] as List<List<List<Number>>>).map { p -> p.map { l -> l.map { it.toFloat() } } }
    }

    private fun error(p: RawPose, ref: List<List<List<Float>>>, w: Int, h: Int, t: Xf): Float = ref.minOf { r ->
        val joints = keyJoints.filter { j -> r[j][3] > 0.5f }
        joints.map { j ->
            val rx = (r[j][0] * w + t.dx - w / 2f) * t.scale + w / 2f
            val ry = (r[j][1] * h + t.dy - h / 2f) * t.scale + h / 2f
            (abs(p.landmarks.x[j] - rx) / w + abs(p.landmarks.y[j] - ry) / h) / 2f
        }.average().toFloat()
    }

    class Result(val found: Float, val err: Float, val jitter: Float, val ids: Int, val lm: Float, val det: Float, val levelled: Long, val rescues: Long, val recover: Int, val fixes: Long = 0)

    private fun run(server: RealModelServer, variant: Char, base: RgbImage, scene: String, sc: Scenario, cfg: PipelineConfig): Result {
        val pipe = MultiPosePipeline(server.runner(variant), cfg)
        val ref = reference(if (variant == 'l') "lite" else "full", scene)
        val o = FrameOrientation(base.width, base.height, 0)
        val lm0 = server.landmarkCalls; val det0 = server.detectorCalls
        var found = 0; var errSum = 0f; var errN = 0
        val ids = HashSet<Int>()
        // per person (sorted left to right) and key joint: positions over the frames, for jitter
        val track = Array(2) { Array(keyJoints.size) { ArrayList<Float>() } }
        var firstBlankEnd = -1; var recoveredAt = -1
        for (k in 0 until sc.frames) {
            val t = sc.f(k)
            val poses = pipe.process(render(base, t, k + 1), o)
            if (t.blank) { firstBlankEnd = k + 1; continue }
            if (firstBlankEnd >= 0 && recoveredAt < 0 && poses.size >= 2) recoveredAt = k - firstBlankEnd
            poses.forEach { ids.add(it.trackId) }
            if (System.getProperty("clrg.trackscene")?.contains("debug") == true)
                println("BENCH   f$k dx=${"%.0f".format(t.dx)} " + poses.joinToString { "id${it.trackId} p%.2f x%.0f".format(it.presence, it.landmarks.x[Lm.NOSE]) })
            if (poses.size >= 2) found++
            for (p in poses) { errSum += error(p, ref, base.width, base.height, t); errN++ }
            if (sc.photometric && poses.size >= 2 && k >= 3) {
                val sorted = poses.sortedBy { it.landmarks.x[Lm.NOSE] }
                for (pi in 0..1) for ((ji, j) in keyJoints.withIndex()) {
                    track[pi][ji].add(sorted[pi].landmarks.x[j]); track[pi][ji].add(sorted[pi].landmarks.y[j] + 1e6f)
                }
            }
        }
        val frames = sc.frames - (if (firstBlankEnd >= 0) 3 else 0)
        var jit = 0f; var jn = 0
        for (pi in 0..1) for (ji in keyJoints.indices) {
            val v = track[pi][ji]
            if (v.size < 6) continue
            val xs = v.filterIndexed { i, _ -> i % 2 == 0 }; val ys = v.filterIndexed { i, _ -> i % 2 == 1 }
            fun sd(a: List<Float>): Float { val m = a.average(); return sqrt(a.sumOf { (it - m) * (it - m) } / a.size).toFloat() }
            jit += (sd(xs) + sd(ys)) / 2f / base.height; jn++
        }
        return Result(found / frames.toFloat(), if (errN > 0) errSum / errN else Float.NaN, if (jn > 0) jit / jn else Float.NaN,
            ids.size, (server.landmarkCalls - lm0) / sc.frames.toFloat(), (server.detectorCalls - det0) / sc.frames.toFloat(),
            pipe.levelledCrops, pipe.rescues, recoveredAt, pipe.jumpFixes)
    }

    @Test
    fun bench() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("clrg.trackbench") != null && python != null && models != null && scenes != null,
            "developer tool: -Pclrg.trackbench=1 with the validation properties")
        val only = System.getProperty("clrg.trackbench")!!.split(',').filter { it != "1" }
        val scenarios = listOf(
            Scenario("still", 16, true) { Xf() },
            Scenario("noise", 24, true) { Xf(noise = 14f) },
            Scenario("dim", 16, true) { Xf(gain = 0.3f, noise = 5f) },
            Scenario("dark", 16, true) { Xf(gain = 0.15f, noise = 4f) },
            Scenario("very dark", 16, true) { Xf(gain = 0.09f, noise = 3f) },
            Scenario("backlit", 16, true) { Xf(gain = 0.32f, window = 0.85f, noise = 4f) },
            Scenario("sway fast", 30, false) { k -> Xf(dx = 160f * sin(k * 2 * Math.PI.toFloat() / 14f)) },
            Scenario("lunge", 30, false) { k -> Xf(dx = if ((k / 6) % 2 == 0) -110f else 110f) },
            Scenario("jumping", 30, false) { k -> Xf(dy = -70f * abs(sin(k * Math.PI.toFloat() / 6f))) },
            Scenario("approach", 30, false) { k -> Xf(scale = 0.75f + 0.55f * k / 29f) },
            Scenario("dropout", 24, false) { k -> Xf(blank = k in 8..10) },
            Scenario("dark+sway", 30, false) { k -> Xf(dx = 120f * sin(k * 2 * Math.PI.toFloat() / 14f), gain = 0.2f, noise = 5f) },
        ).filter { only.isEmpty() || it.name.replace(" ", "") in only }
        val off = PipelineConfig(predictMotion = false, retryWider = false, smoothRoi = false, keepPresence = 0.5f, autoLevel = false, guardJumps = false)
        val configs = linkedMapOf(
            "old" to off, "new" to PipelineConfig(),
            "pred" to off.copy(predictMotion = true), "smooth" to off.copy(smoothRoi = true),
            "retry" to off.copy(retryWider = true, keepPresence = 0.4f), "level" to off.copy(autoLevel = true),
            "lvlLm" to off.copy(autoLevel = true, autoLevelDetector = false),
            "pred50" to off.copy(predictMotion = true, predictFraction = 0.5f, predictGrow = 0.08f),
            "smRet" to off.copy(smoothRoi = true, retryWider = true, keepPresence = 0.4f),
            "best" to off.copy(smoothRoi = true, retryWider = true, keepPresence = 0.4f, autoLevel = true),
            "bestG20" to off.copy(smoothRoi = true, retryWider = true, keepPresence = 0.4f, autoLevel = true, smoothGain = 20f),
            "bestNoDet" to off.copy(smoothRoi = true, retryWider = true, keepPresence = 0.4f, autoLevel = true, autoLevelDetector = false),
            "bestG20ND" to off.copy(smoothRoi = true, retryWider = true, keepPresence = 0.4f, autoLevel = true, autoLevelDetector = false, smoothGain = 20f),
            "still1" to off.copy(smoothRoi = true, retryWider = true, keepPresence = 0.4f, autoLevel = true, smoothStill = 0.01f),
            "still2" to off.copy(smoothRoi = true, retryWider = true, keepPresence = 0.4f, autoLevel = true, smoothStill = 0.02f),
            "bestJ" to PipelineConfig(),
            "jAll" to PipelineConfig(jumpTargeted = false),
            "j10" to PipelineConfig(jumpDev = 0.1f),
            "j04" to PipelineConfig(jumpDev = 0.04f),
            "bestP" to off.copy(smoothRoi = true, retryWider = true, keepPresence = 0.4f, autoLevel = true, predictMotion = true, predictFraction = 0.5f, predictGrow = 0.08f),
        )
        System.getProperty("clrg.trackgain")?.let { AutoLevel.maxGain = it.toFloat() }
        System.getProperty("clrg.trackgamma")?.let { AutoLevel.minGamma = it.toFloat() }
        val pick = (System.getProperty("clrg.trackcfg") ?: "old,new").split(',')
        val scenePick = System.getProperty("clrg.trackscene")?.split(',')
        RealModelServer(python!!, models!!).use { server ->
            for (scene in listOf("sceneA_two_players.png", "sceneE_real_pair.png").filter { scenePick == null || scenePick.any { p -> it.startsWith(p) } }) {
                val base = load(scene)
                for (variant in listOf('f')) {
                    println("BENCH ---- $scene (${if (variant == 'l') "lite" else "full"})")
                    println("BENCH %-11s | ".format("scenario") + pick.joinToString(" | ") { "%-30s".format("$it: found err jitter ids") })
                    for (sc in scenarios) {
                        val cells = pick.map { name ->
                            val r = run(server, variant, base, scene, sc, configs[name]!!)
                            "%-30s".format("%4.0f%% %.4f %.4f %d".format(r.found * 100, r.err, r.jitter, r.ids) + (if (r.rescues > 0) " r${r.rescues}" else "") + (if (r.fixes > 0) " j${r.fixes}" else "") + " %.1f".format(r.lm))
                        }
                        println("BENCH %-11s | ".format(sc.name) + cells.joinToString(" | "))
                    }
                }
            }
        }
    }
}
