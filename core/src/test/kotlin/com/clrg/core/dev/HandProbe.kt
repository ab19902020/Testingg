package com.clrg.core.dev

import com.clrg.core.gesture.HandShape
import com.clrg.core.pose.CropSampler
import com.clrg.core.pose.HandLandmarkTracker
import com.clrg.core.pose.HandLm
import com.clrg.core.pose.HandObservation
import com.clrg.core.pose.RealModelServer
import com.clrg.core.pose.RgbImage
import com.clrg.core.pose.RotatedRect
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Developer tool (-Pclrg.trackbench=1 plus the validation properties): how well the real hand landmark models read
 * the thumb on hands as small as they are in a living room. Real photos (MediaPipe test images) are shrunk so the hand
 * is 33 px (2.5 m from a 720p camera), 50 px (1080p) or 75 px across, given camera noise, and the crop is wobbled as
 * a tracking crop does. Prints each thumb measure's mean and spread for a raised thumb and a lowered one, and how far
 * apart they are in spreads (d'). Still photos: no motion blur, and hands seen side-on, not pointing at the camera.
 */
class HandProbe {
    private val python = System.getProperty("clrg.validation.python")
    private val models = System.getProperty("clrg.validation.models")
    private val scenes = System.getProperty("clrg.validation.scenes")

    private fun shrunk(name: String, scale: Float, noise: Float, seed: Int): RgbImage {
        val bi = ImageIO.read(File(scenes, name))
        val w = (bi.width * scale).toInt(); val h = (bi.height * scale).toInt()
        val small = BufferedImage(bi.width, bi.height, BufferedImage.TYPE_INT_RGB)
        val g = small.createGraphics()
        g.color = java.awt.Color(bi.getRGB(2, 2)); g.fillRect(0, 0, bi.width, bi.height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(bi, (bi.width - w) / 2, (bi.height - h) / 2, w, h, null)
        g.dispose()
        val rnd = java.util.Random(seed.toLong())
        val img = RgbImage(bi.width, bi.height)
        for (y in 0 until bi.height) for (x in 0 until bi.width) {
            val p = small.getRGB(x, y)
            var rgb = 0
            for (ch in 0..2) {
                val v = (((p shr (16 - ch * 8)) and 0xFF) + rnd.nextGaussian() * noise).toInt().coerceIn(0, 255)
                rgb = rgb or (v shl (16 - ch * 8))
            }
            img.pixels[y * bi.width + x] = rgb
        }
        return img
    }

    private fun run(runner: com.clrg.core.pose.HandModelRunner, img: RgbImage, roi: RotatedRect): HandObservation? {
        val input = FloatArray(224 * 224 * 3)
        CropSampler.sample(img, roi.cropTransform(224), 224, input, 1f / 255f, 0f, zeroBorder = false)
        val raw = FloatArray(63); val pres = FloatArray(1)
        runner.runHand(input, raw, pres)
        if (pres[0] < HandLandmarkTracker.MIN_PRESENCE) return null
        return HandLandmarkTracker.decode(raw, roi, 1, pres[0])
    }

    /** Thumb measures: the 3D one used up to 1.8.1, and 2D / 3D ones in knuckle widths. */
    private fun measures(o: HandObservation): FloatArray {
        val p = FloatArray(63) { i -> when (i % 3) { 0 -> o.x[i / 3]; 1 -> o.y[i / 3]; else -> o.z[i / 3] } }
        val old = HandShape().apply { measure(p) }.thumbGap
        fun d2(a: Int, b: Int) = hypot(o.x[a] - o.x[b], o.y[a] - o.y[b])
        fun d3(a: Int, b: Int) = sqrt((o.x[a] - o.x[b]).let { it * it } + (o.y[a] - o.y[b]).let { it * it } + (o.z[a] - o.z[b]).let { it * it })
        fun seg2(q: Int, a: Int, b: Int): Float {
            val abx = o.x[b] - o.x[a]; val aby = o.y[b] - o.y[a]
            val t = (((o.x[q] - o.x[a]) * abx + (o.y[q] - o.y[a]) * aby) / (abx * abx + aby * aby).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            return hypot(o.x[q] - o.x[a] - t * abx, o.y[q] - o.y[a] - t * aby)
        }
        val kw2 = d2(HandLm.INDEX_MCP, HandLm.PINKY_MCP)
        val kw3 = d3(HandLm.INDEX_MCP, HandLm.PINKY_MCP)
        val s2 = seg2(HandLm.THUMB_TIP, HandLm.INDEX_MCP, HandLm.INDEX_PIP) / kw2
        val tipMcp2 = d2(HandLm.THUMB_TIP, HandLm.INDEX_MCP) / kw2
        val tipMcp3 = d3(HandLm.THUMB_TIP, HandLm.INDEX_MCP) / kw3
        // thumb tip to the middle finger's knuckle (where a lowered thumb rests), 2D
        val tipMid2 = d2(HandLm.THUMB_TIP, HandLm.MIDDLE_PIP) / kw2
        return floatArrayOf(old, s2, tipMcp2, tipMcp3, tipMid2)
    }

    @Test
    fun probe() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("clrg.trackbench") != null && python != null && models != null &&
            File(models, "hand/hand_landmark_full.tflite").exists(), "developer tool")
        val names = listOf("thumb_up.jpg", "pointing_up.jpg", "fist.jpg")
        val labels = listOf("old3D/palm", "2Dseg/kw", "2Dmcp/kw", "3Dmcp/kw", "2Dmid/kw")
        RealModelServer(python!!, models!!).use { server ->
            for (variant in listOf('l', 'f')) {
                val runner = server.handRunner(variant)
                // the hand's crop at full size, from the model itself
                val base = HashMap<String, RotatedRect>()
                for (n in names) {
                    val img = shrunk(n, 1f, 0f, 0)
                    val first = run(runner, img, RotatedRect(img.width / 2f, img.height / 2f, maxOf(img.width, img.height).toFloat(), 0f))!!
                    var roi = HandLandmarkTracker.trackingRoi(first)
                    repeat(2) { roi = HandLandmarkTracker.trackingRoi(run(runner, img, roi)!!) }
                    base[n] = roi
                    if (variant == 'f') {
                        val o = run(runner, img, roi)!!
                        println("BENCH LM $n " + (0 until 21).joinToString(",") { i -> "%.1f,%.1f,%.1f".format(o.x[i], o.y[i], o.z[i]) })
                    }
                    val handPx = hypot(first.x[HandLm.INDEX_MCP] - first.x[HandLm.PINKY_MCP], first.y[HandLm.INDEX_MCP] - first.y[HandLm.PINKY_MCP])
                    println("BENCH hand $n: knuckle width %.0f px at full size, crop %.0f px".format(handPx, roi.side))
                }
                for (kwPx in listOf(18f, 27f, 40f)) {
                    val stats = HashMap<String, List<FloatArray>>()
                    for (n in names) {
                        val b = base[n]!!
                        val img0 = ImageIO.read(File(scenes, n))
                        val kwFull = 1f // filled below
                        val list = ArrayList<FloatArray>()
                        var seen = 0
                        // scale so the knuckle width is kwPx
                        val probeObs = run(runner, shrunk(n, 1f, 0f, 0), b)!!
                        val kw = hypot(probeObs.x[HandLm.INDEX_MCP] - probeObs.x[HandLm.PINKY_MCP], probeObs.y[HandLm.INDEX_MCP] - probeObs.y[HandLm.PINKY_MCP])
                        val scale = kwPx / kw * kwFull
                        for (seed in 1..16) {
                            val img = shrunk(n, scale, 6f, seed)
                            val rnd = java.util.Random(seed * 31L)
                            val cx = (b.cx - img0.width / 2f) * scale + img0.width / 2f
                            val cy = (b.cy - img0.height / 2f) * scale + img0.height / 2f
                            val side = b.side * scale
                            val roi = RotatedRect(cx + side * (rnd.nextFloat() - 0.5f) * 0.12f, cy + side * (rnd.nextFloat() - 0.5f) * 0.12f,
                                side * (1f + (rnd.nextFloat() - 0.5f) * 0.16f), b.rotation + (rnd.nextFloat() - 0.5f) * 0.4f)
                            val o = run(runner, img, roi) ?: continue
                            seen++
                            list.add(measures(o))
                        }
                        stats[n] = list
                        val line = labels.indices.joinToString("  ") { k ->
                            val v = list.map { it[k] }
                            if (v.isEmpty()) "%s -".format(labels[k]) else {
                                val m = v.average(); val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
                                "%s %.2f±%.2f".format(labels[k], m, sd)
                            }
                        }
                        println("BENCH ${if (variant == 'l') "lite" else "full"} kw=${kwPx.toInt()}px (hand ~${(kwPx * 1.25f).toInt()}px) %-16s seen %2d/16  %s".format(n, seen, line))
                    }
                    // separation of raised (thumb_up) from lowered (pointing_up) thumb, per measure
                    val up = stats["thumb_up.jpg"]!!; val down = stats["pointing_up.jpg"]!!
                    if (up.size > 2 && down.size > 2) {
                        val dp = labels.indices.joinToString("  ") { k ->
                            val a = up.map { it[k].toDouble() }; val c = down.map { it[k].toDouble() }
                            val ma = a.average(); val mc = c.average()
                            val sa = sqrt(a.sumOf { (it - ma) * (it - ma) } / a.size); val sc = sqrt(c.sumOf { (it - mc) * (it - mc) } / c.size)
                            "%s d'=%.1f".format(labels[k], (ma - mc) / sqrt((sa * sa + sc * sc) / 2 + 1e-9))
                        }
                        println("BENCH ${if (variant == 'l') "lite" else "full"} kw=${kwPx.toInt()}px separation up vs down: $dp")
                    }
                }
            }
        }
    }
}
