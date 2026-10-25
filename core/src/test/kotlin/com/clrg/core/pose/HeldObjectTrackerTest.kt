package com.clrg.core.pose

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Held-object learning and tracking on generated pictures: a cluttered room, a person (landmarks given, as the pose
 * model would), and a coloured stick in one hand - straight, at angles, blurred by a fast swing, and against a wall
 * of the same colour. Generated images test the method; how well it works with real cameras and real objects in
 * real rooms is not measured here.
 */
class HeldObjectTrackerTest {
    private val w = 1280
    private val h = 720
    private val s = 150f // torso length in pixels (someone ~2.5 m from the camera)
    private val shoulder = floatArrayOf(640f, 260f)

    private class Canvas(val w: Int, val h: Int) {
        val img = RgbImage(w, h)
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in maxOf(0, y0) until minOf(h, y1)) for (x in maxOf(0, x0) until minOf(w, x1)) img.pixels[y * w + x] = c }
        fun disc(cx: Float, cy: Float, r: Float, c: Int) = rect((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt(), c)
        /** A stick from (x0, y0) to (x1, y1), [width] px wide; [alpha] blends it over what is there (motion blur). */
        fun stick(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, c: Int, alpha: Float = 1f) {
            val len = hypot(x1 - x0, y1 - y0)
            val n = (len * 2).toInt()
            for (i in 0..n) {
                val t = i / n.toFloat()
                val cx = x0 + (x1 - x0) * t; val cy = y0 + (y1 - y0) * t
                for (yy in (cy - width / 2).toInt()..(cy + width / 2).toInt()) for (xx in (cx - width / 2).toInt()..(cx + width / 2).toInt()) {
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val k = yy * w + xx
                    img.pixels[k] = if (alpha >= 1f) c else blend(img.pixels[k], c, alpha)
                }
            }
        }
        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(v: Int, s: Int) = (v shr s) and 0xFF
            val r = (ch(a, 16) * (1 - t) + ch(b, 16) * t).toInt(); val g = (ch(a, 8) * (1 - t) + ch(b, 8) * t).toInt(); val bl = (ch(a, 0) * (1 - t) + ch(b, 0) * t).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        fun noise(seed: Long, amount: Int) {
            val r = java.util.Random(seed)
            for (k in img.pixels.indices) {
                val p = img.pixels[k]
                fun ch(s: Int) = (((p shr s) and 0xFF) + r.nextInt(2 * amount + 1) - amount).coerceIn(0, 255)
                img.pixels[k] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
    }

    /** A living room: wall, sofa, shelves of books, a rug, a window. */
    private fun room(seed: Long = 1, wall: Int = 0xFFB8A58A.toInt()): Canvas {
        val c = Canvas(w, h)
        c.rect(0, 0, w, h, wall)
        c.rect(0, 520, w, h, 0xFF6B4F3A.toInt())
        c.rect(80, 380, 480, 540, 0xFF3F5D7A.toInt()) // sofa
        c.rect(900, 120, 1200, 470, 0xFF5E4630.toInt()) // shelves
        val r = java.util.Random(seed)
        for (i in 0 until 40) {
            val x = 910 + (i % 10) * 28; val y = 130 + (i / 10) * 85
            val col = intArrayOf(0xFF8E2C2C.toInt(), 0xFF2C5E8E.toInt(), 0xFFD9C27A.toInt(), 0xFF3C7A3C.toInt())[r.nextInt(4)]
            c.rect(x, y, x + 22, y + 70, col)
        }
        c.rect(150, 60, 420, 300, 0xFFCFE3F2.toInt()) // window
        return c
    }

    /** A person standing at [shoulder]: grey jumper, blue jeans, skin; the right hand at (hx, hy). */
    private fun person(c: Canvas, hx: Float, hy: Float): RawLandmarks {
        val lm = RawLandmarks()
        fun set(i: Int, x: Float, y: Float) { lm.x[i] = x; lm.y[i] = y; lm.visibility[i] = 0.95f; lm.presence[i] = 0.99f }
        val sx = shoulder[0]; val sy = shoulder[1]
        c.rect((sx - 55).toInt(), sy.toInt(), (sx + 55).toInt(), (sy + s).toInt(), 0xFF707880.toInt()) // jumper
        c.rect((sx - 50).toInt(), (sy + s).toInt(), (sx + 50).toInt(), (sy + 2.4f * s).toInt(), 0xFF2E4A78.toInt()) // jeans
        c.disc(sx, sy - 60f, 34f, 0xFFE0B49A.toInt()) // head
        set(Lm.NOSE, sx, sy - 60f)
        set(Lm.LEFT_SHOULDER, sx - 50f, sy); set(Lm.RIGHT_SHOULDER, sx + 50f, sy)
        set(Lm.LEFT_HIP, sx - 35f, sy + s); set(Lm.RIGHT_HIP, sx + 35f, sy + s)
        set(Lm.LEFT_ELBOW, sx - 70f, sy + 80f); set(Lm.LEFT_WRIST, sx - 75f, sy + 150f)
        set(17, sx - 77f, sy + 165f); set(Lm.LEFT_INDEX, sx - 73f, sy + 168f)
        // right arm towards the hand
        val ex = (sx + 50f + hx) / 2f + 25f; val ey = (sy + hy) / 2f + 20f
        set(Lm.RIGHT_ELBOW, ex, ey)
        c.stick(sx + 50f, sy, ex, ey, 22f, 0xFF707880.toInt())
        c.stick(ex, ey, hx, hy, 18f, 0xFF707880.toInt())
        set(Lm.RIGHT_WRIST, hx, hy)
        set(18, hx + 4f, hy - 10f); set(Lm.RIGHT_INDEX, hx - 4f, hy - 12f)
        c.disc(hx, hy - 6f, 11f, 0xFFE0B49A.toInt()) // fist
        set(9, sx - 10f, sy - 45f); set(10, sx + 10f, sy - 45f)
        return lm
    }

    private fun pose(lm: RawLandmarks) = RawPose(5, lm, 0.99f, RotatedRect(640f, 400f, 600f, 0f), null, 10)

    /** Grip point the tracker uses for the right hand (wrist, index, pinky). */
    private fun grip(lm: RawLandmarks) = floatArrayOf((lm.x[Lm.RIGHT_WRIST] + lm.x[Lm.RIGHT_INDEX] + lm.x[18]) / 3f, (lm.y[Lm.RIGHT_WRIST] + lm.y[Lm.RIGHT_INDEX] + lm.y[18]) / 3f)

    private val stickColor = 0xFFF2C40F.toInt() // a yellow toy bat
    private val stickLen = 0.9f

    private fun learnStick(t: HeldObjectTracker, color: Int = stickColor, wall: Int = 0xFFB8A58A.toInt()): HeldLearnStatus {
        t.requestLearn(5, 0)
        for (f in 0 until HeldObjectTracker.MAX_ATTEMPTS + 2) {
            val c = room(f.toLong(), wall)
            val hx = 760f + (f % 3); val hy = 255f
            val lm = person(c, hx, hy)
            val g = grip(lm)
            val a = (8.0 + (f % 4)) * PI / 180.0 // held roughly upright, a little wobble
            c.stick(g[0], g[1] + 10f, g[0] + (sin(a) * stickLen * s).toFloat(), g[1] - (cos(a) * stickLen * s).toFloat(), 12f, color)
            c.disc(hx, hy - 6f, 11f, 0xFFE0B49A.toInt())
            c.noise(f.toLong(), 6)
            t.process(c.img, Affine.IDENTITY, listOf(pose(lm)))
            val st = t.status
            if (st != null && st.done) return st
        }
        return t.status!!
    }

    @Test
    fun aBrightStickIsLearnedFromHoldingItUp() {
        val t = HeldObjectTracker()
        val st = learnStick(t)
        assertTrue(st.ok, "learned: ${st.message}")
        val m = assertNotNull(st.model)
        assertTrue(abs(m.length - stickLen) < 0.25f, "length ${m.length} vs $stickLen body units")
        val again = assertNotNull(ObjectColorModel.decode(m.encode()), "the model survives being saved")
        assertTrue(abs(again.length - m.length) < 0.01f)
    }

    /** Tracks the stick held at [deg] (0 = up, 90 = out to the right); returns (angle error deg, tip error in body units). */
    private fun trackAt(t: HeldObjectTracker, m: ObjectColorModel, deg: Double, blurDeg: Double = 0.0, seed: Long = 3): Pair<Double, Double>? {
        val c = room(seed)
        val lm = person(c, 760f, 330f)
        val g = grip(lm)
        val a = deg * PI / 180.0
        if (blurDeg > 0) {
            // a fast swing smears the stick across this many degrees during the exposure
            val n = 7
            for (i in 0 until n) {
                val ai = (deg - blurDeg / 2 + blurDeg * i / (n - 1)) * PI / 180.0
                c.stick(g[0], g[1], g[0] + (sin(ai) * stickLen * s).toFloat(), g[1] - (cos(ai) * stickLen * s).toFloat(), 12f, stickColor, 0.45f)
            }
        } else c.stick(g[0], g[1], g[0] + (sin(a) * stickLen * s).toFloat(), g[1] - (cos(a) * stickLen * s).toFloat(), 12f, stickColor)
        c.disc(760f, 324f, 11f, 0xFFE0B49A.toInt())
        c.noise(seed, 6)
        t.models = arrayOf(m, null)
        t.tracks = intArrayOf(5, -1)
        val p = pose(lm)
        t.process(c.img, Affine.IDENTITY, listOf(p))
        val o = p.held ?: run { println("HELD rejected at $deg: ${t.lastReject}"); return null }
        val got = atan2((o.tipX - o.baseX).toDouble(), -(o.tipY - o.baseY).toDouble()) * 180.0 / PI
        val tipX = g[0] + (sin(a) * stickLen * s).toFloat(); val tipY = g[1] - (cos(a) * stickLen * s).toFloat()
        var err = got - deg
        while (err > 180) err -= 360
        while (err < -180) err += 360
        return abs(err) to (hypot(o.tipX - tipX, o.tipY - tipY) / s).toDouble()
    }

    @Test
    fun theStickIsFollowedAtEveryAngle() {
        val t = HeldObjectTracker()
        val m = assertNotNull(learnStick(t).model)
        for (deg in listOf(0.0, 30.0, 75.0, 110.0, 150.0, -40.0, -100.0)) {
            val r = assertNotNull(trackAt(t, m, deg), "stick at $deg degrees found")
            println("HELD angle %+4.0f deg: direction off by %.1f deg, tip off by %.2f body units".format(deg, r.first, r.second))
            assertTrue(r.first < 6.0, "direction at $deg: ${r.first} deg off")
            assertTrue(r.second < 0.15, "tip at $deg: ${r.second} body units off")
        }
    }

    @Test
    fun aBlurredFastSwingIsStillFoundRoughly() {
        val t = HeldObjectTracker()
        val m = assertNotNull(learnStick(t).model)
        val r = assertNotNull(trackAt(t, m, 60.0, blurDeg = 24.0), "a stick smeared over 24 degrees by motion blur")
        println("HELD blurred over 24 deg: direction off by %.1f deg".format(r.first))
        assertTrue(r.first < 12.0, "direction within the blur: ${r.first}")
    }

    @Test
    fun noStickNoObservation() {
        val t = HeldObjectTracker()
        val m = assertNotNull(learnStick(t).model)
        val c = room(9)
        val lm = person(c, 760f, 330f)
        c.noise(9, 6)
        t.models = arrayOf(m, null); t.tracks = intArrayOf(5, -1)
        val p = pose(lm)
        t.process(c.img, Affine.IDENTITY, listOf(p))
        assertNull(p.held, "empty hand: nothing found")
    }

    @Test
    fun aStickTheSameColourAsTheWallIsNotLearned() {
        val t = HeldObjectTracker()
        val st = learnStick(t, color = 0xFFB0A088.toInt())
        assertTrue(st.done && !st.ok, "honest failure: ${st.message}")
    }
}
