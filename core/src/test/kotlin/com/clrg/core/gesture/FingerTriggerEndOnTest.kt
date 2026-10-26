package com.clrg.core.gesture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The thumb trigger with a finger gun pointing at the camera - the way a player holds it, and the hardest view for
 * the hand model. The hand is the REAL MediaPipe hand model's 3D landmarks of a real photo of a pointing hand
 * (pointing_up.jpg, full model; its thumb lies against the fingers), with the thumb swung out to "cocked" and the
 * whole hand turned to point at the camera at several angles. Like the real model, depth is squashed (x0.4) and every
 * landmark wobbles from frame to frame. A model of a real camera view, not a real camera: blur and how the model
 * really reads an end-on hand can only be seen on a phone.
 */
class FingerTriggerEndOnTest {
    /** pointing_up.jpg through the full hand model (x, y, z in image pixels). */
    private val photo = floatArrayOf(
        171.7f, 279.6f, 0.0f, 194.5f, 250.0f, -7.5f, 207.7f, 211.5f, -11.9f, 187.9f, 191.4f, -15.9f, 160.2f, 185.9f, -19.6f,
        169.0f, 167.5f, -6.2f, 172.4f, 125.3f, -13.0f, 172.9f, 97.7f, -16.4f, 170.4f, 73.8f, -18.6f,
        141.3f, 179.2f, -6.7f, 150.4f, 157.7f, -17.3f, 164.1f, 195.3f, -19.4f, 159.8f, 209.3f, -18.2f,
        120.4f, 195.2f, -8.0f, 131.0f, 183.7f, -19.7f, 146.5f, 216.6f, -17.7f, 139.6f, 224.5f, -13.5f,
        101.0f, 216.6f, -9.7f, 111.4f, 202.9f, -16.8f, 128.1f, 223.1f, -13.7f, 124.1f, 230.8f, -9.6f,
    )

    private fun v(p: FloatArray, i: Int) = floatArrayOf(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun add(a: FloatArray, b: FloatArray, k: Float = 1f) = floatArrayOf(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k)
    private fun len(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun unit(a: FloatArray): FloatArray { val l = len(a); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun slerp(a0: FloatArray, b0: FloatArray, u: Float): FloatArray {
        val a = unit(a0); val b = unit(b0)
        val w = acos(dot(a, b).coerceIn(-1f, 1f))
        if (w < 1e-4f) return a
        val ka = sin((1 - u) * w) / sin(w); val kb = sin(u * w) / sin(w)
        return floatArrayOf(a[0] * ka + b[0] * kb, a[1] * ka + b[1] * kb, a[2] * ka + b[2] * kb)
    }

    /** Rotation of [p] about unit axis [k] by [ang] (Rodrigues). */
    private fun rot(p: FloatArray, k: FloatArray, ang: Float): FloatArray {
        val c = cos(ang); val s = sin(ang); val kxp = cross(k, p); val kd = dot(k, p) * (1 - c)
        return floatArrayOf(p[0] * c + kxp[0] * s + k[0] * kd, p[1] * c + kxp[1] * s + k[1] * kd, p[2] * c + kxp[2] * s + k[2] * kd)
    }

    /**
     * The hand with its thumb [u] of the way up (0 = against the fingers as in the photo, 1 = cocked: pointing out
     * across the knuckles, a little forward), pointing towards the camera tilted [tiltDeg] off-axis and rolled [rollDeg]
     * about the index finger.
     */
    private fun hand(u: Float, tiltDeg: Float, rollDeg: Float, twoFingers: Boolean = false): FloatArray {
        val p = photo.copyOf()
        if (twoFingers) {
            // the middle finger straightened alongside the index finger (a two-finger gun)
            val dir = unit(sub(v(photo, 8), v(photo, 5)))
            var prev = v(photo, 9)
            for ((k, i) in (10..12).withIndex()) {
                val q = add(prev, dir, len(sub(v(photo, 6 + k), v(photo, 5 + k))) * 1.05f)
                p[i * 3] = q[0]; p[i * 3 + 1] = q[1]; p[i * 3 + 2] = q[2]
                prev = q
            }
        }
        val radial = unit(sub(v(photo, 5), v(photo, 17)))
        val fwd = unit(sub(v(photo, 8), v(photo, 5)))
        val upDir = unit(add(radial, fwd, 0.35f))
        var prev = v(photo, 1)
        for (i in 2..4) {
            val seg = sub(v(photo, i), v(photo, i - 1))
            val d = slerp(seg, upDir, u)
            val q = add(prev, d, len(seg))
            p[i * 3] = q[0]; p[i * 3 + 1] = q[1]; p[i * 3 + 2] = q[2]
            prev = q
        }
        // turn the index direction onto the camera axis (z points away from the camera), tilted, then roll about it
        val c = FloatArray(3)
        for (i in 0 until 21) for (k in 0..2) c[k] += p[i * 3 + k] / 21f
        val t = Math.toRadians(tiltDeg.toDouble()).toFloat()
        val target = floatArrayOf(sin(t), 0f, -cos(t))
        val axis0 = cross(fwd, target)
        val ang0 = acos(dot(fwd, target).coerceIn(-1f, 1f))
        val out = FloatArray(63)
        for (i in 0 until 21) {
            var q = sub(v(p, i), c)
            if (len(axis0) > 1e-6f) q = rot(q, unit(axis0), ang0)
            q = rot(q, target, Math.toRadians(rollDeg.toDouble()).toFloat())
            out[i * 3] = q[0] + 200f; out[i * 3 + 1] = q[1] + 200f; out[i * 3 + 2] = q[2]
        }
        return out
    }

    private val kw: Float = run { val a = v(photo, 5); val b = v(photo, 17); len(sub(a, b)) }

    /** What the hand model would report: depth squashed, every landmark wobbling by [noise] knuckle widths. */
    private fun observe(p: FloatArray, rnd: java.util.Random, noise: Float, zScale: Float = 0.4f): HandShape {
        val o = FloatArray(63)
        for (i in 0 until 21) {
            o[i * 3] = p[i * 3] + (rnd.nextGaussian() * noise * kw).toFloat()
            o[i * 3 + 1] = p[i * 3 + 1] + (rnd.nextGaussian() * noise * kw).toFloat()
            o[i * 3 + 2] = p[i * 3 + 2] * zScale + (rnd.nextGaussian() * noise * 2 * kw).toFloat()
        }
        return HandShape().apply { measure(o) }
    }

    /** The trigger as it was up to 1.8.1 (fixed 3D thumb-gap limits, and a gun shape required), for comparison. */
    private class OldTrigger {
        var cocked = false; var since = -1.0; var votes = 0; var gun = false
        fun update(s: HandShape, t: Double): Boolean {
            votes = if (s.gun) minOf(votes + 1, 4) else maxOf(votes - 1, -3)
            if (votes >= 2) gun = true else if (votes <= -2) gun = false
            if (!cocked) { if (s.thumbGap > 0.55f) { cocked = true; since = t } }
            else if (s.thumbGap < 0.33f) { cocked = false; return gun && t - since >= 0.08 }
            return false
        }
    }

    private class Run(val shots: List<Double>, val oldShots: Int)

    /** Plays [script] (thumb position over time) at 30 fps. */
    private fun play(seconds: Double, tilt: Float, roll: Float, noise: Float, seed: Long, script: (Double) -> Float, rollAt: ((Double) -> Float)? = null,
                     twoFingers: Boolean = false): Run {
        val trig = FingerTrigger(); val old = OldTrigger()
        val rnd = java.util.Random(seed)
        val shots = ArrayList<Double>(); var oldShots = 0
        var t = 0.0
        while (t < seconds) {
            val shape = observe(hand(script(t), tilt, rollAt?.invoke(t) ?: roll, twoFingers), rnd, noise)
            val s = trig.update(shape, t)
            if (!s.isNaN()) shots.add(s)
            if (old.update(shape, t)) oldShots++
            t += 1.0 / 30
        }
        return Run(shots, oldShots)
    }

    /** Thumb up, then [clicks] times: drop over 0.12 s, hold 0.35 s, raise over 0.15 s, hold 0.6 s. */
    private fun clicks(t: Double, clicks: Int, up: Float = 1f, down: Float = 0f, warm: Double = 1.0): Float {
        if (t < warm) return up
        val k = ((t - warm) / 1.22).toInt()
        if (k >= clicks) return up
        val x = (t - warm) - k * 1.22
        return when {
            x < 0.12 -> up + (down - up) * (x / 0.12).toFloat()
            x < 0.47 -> down
            x < 0.62 -> down + (up - down) * ((x - 0.47) / 0.15).toFloat()
            else -> up
        }
    }

    private val angles = listOf(0f, 20f, 35f).flatMap { tilt -> listOf(0f, 45f, 90f, 135f).map { tilt to it } }

    @Test
    fun debugTrace() {
        if (System.getProperty("clrg.trackbench") == null) return
        val cases = listOf(
            Quint("clicks 35/45", 35f, 45f, { t: Double -> clicks(t, 4) }, 0.05f, 109L, 6.5),
            Quint("small 35/45", 35f, 45f, { t: Double -> clicks(t, 4, up = 0.55f, down = 0.15f, warm = 2.0) }, 0.05f, 209L, 7.5),
            Quint("two 35/135", 35f, 135f, { t: Double -> clicks(t, 4) }, 0.05f, 611L, 6.5),
            Quint("misread 35/45", 35f, 45f, { t: Double -> if (t > 1 && ((t * 30).toInt() % 23 == 0)) 0f else 1f }, 0.05f, 409L, 8.0),
        )
        for (c in cases) {
            val trig = FingerTrigger(); val rnd = java.util.Random(c.seed)
            var t = 0.0
            val sb = StringBuilder("TRACE ${c.a}\n")
            while (t < c.len) {
                val shape = observe(hand(c.d(t), c.b, c.c, c.a.startsWith("two")), rnd, c.e)
                val sh = trig.update(shape, t)
                sb.append("TRACE t=%.2f u=%.2f raw=%.2f lvl=%.2f up=%.2f dn=%.2f cockL=%.2f fireL=%.2f cock=%b canFire=%b side=%b open=%b %s\n".format(t, c.d(t), shape.thumbLevel, trig.level, trig.upLevel, trig.downLevel, trig.upLine, trig.fireLine, trig.cocked, shape.canFire, shape.sideOn, shape.open, if (sh.isNaN()) "" else "SHOT"))
                t += 1.0 / 30
            }
            println(sb)
        }
    }
    private data class Quint(val a: String, val b: Float, val c: Float, val d: (Double) -> Float, val e: Float, val seed: Long, val len: Double)

    @Test
    fun aThumbDropFiresWithTheGunPointingAtTheCamera() {
        var newHits = 0; var oldHits = 0; var total = 0
        for ((i, a) in angles.withIndex()) {
            val r = play(6.5, a.first, a.second, 0.05f, 100L + i, { clicks(it, 4) })
            total += 4; newHits += r.shots.size; oldHits += r.oldShots
            assertEquals(4, r.shots.size, "tilt ${a.first} roll ${a.second}: ${r.shots}")
            // stamped close to when the thumb passed halfway down (0.06 s into each 0.12 s drop)
            for ((k, s) in r.shots.withIndex()) {
                val half = 1.0 + k * 1.22 + 0.06
                assertTrue(abs(s - half) < 0.06, "shot $k at $s, thumb halfway at $half")
            }
        }
        println("[finger-gun] pointing at the camera, 12 angles: new trigger $newHits/$total shots, old trigger $oldHits/$total")
    }

    @Test
    fun aTwoFingerGunWorksTheSameWay() {
        // index and middle finger out, thumb up; thumb down onto the side of the fingers to shoot
        var hits = 0; var oldHits = 0
        for ((i, a) in angles.withIndex()) {
            val r = play(6.5, a.first, a.second, 0.05f, 600L + i, { clicks(it, 4) }, twoFingers = true)
            hits += r.shots.size; oldHits += r.oldShots
            assertEquals(4, r.shots.size, "two fingers, tilt ${a.first} roll ${a.second}: ${r.shots}")
            assertEquals(0, play(6.0, a.first, a.second, 0.08f, 700L + i, { 1f }, twoFingers = true).shots.size, "two fingers held up, $a")
        }
        println("[finger-gun] two-finger gun pointing at the camera, 12 angles: new trigger $hits/48 shots, old trigger $oldHits/48")
    }

    @Test
    fun anUnhurriedThumbDropStillFires() {
        // "when the thumb goes down, it shoots": an unhurried drop taking 0.35 s counts too
        for ((i, a) in angles.withIndex()) {
            val r = play(4.0, a.first, a.second, 0.05f, 500L + i, { t -> (1 - ((t - 1.5) / 0.35)).toFloat().coerceIn(0f, 1f) })
            assertEquals(1, r.shots.size, "0.35 s drop, $a: ${r.shots}")
        }
    }

    @Test
    fun aPlayerWhoseThumbMovesLessIsLearned() {
        // raises the thumb only half way and does not lower it all the way: held up for 2 s while aiming first
        var hits = 0
        for ((i, a) in angles.withIndex()) {
            val r = play(7.5, a.first, a.second, 0.05f, 200L + i, { clicks(it, 4, up = 0.55f, down = 0.15f, warm = 2.0) })
            hits += r.shots.size
            assertTrue(r.shots.size >= 3, "tilt ${a.first} roll ${a.second}: ${r.shots.size} of 4")
        }
        println("[finger-gun] small thumb movements (half raised, not fully lowered), 12 angles: $hits/48 shots")
    }

    @Test
    fun noShotsWithoutARealThumbDrop() {
        for ((i, a) in angles.withIndex()) {
            val seed = 300L + i
            // thumb held up, heavier wobble
            assertEquals(0, play(8.0, a.first, a.second, 0.08f, seed, { 1f }).shots.size, "held up, $a")
            // thumb held down
            assertEquals(0, play(4.0, a.first, a.second, 0.08f, seed, { 0f }).shots.size, "held down, $a")
            // little twitches of the thumb (a quarter of the way down and back)
            assertEquals(0, play(8.0, a.first, a.second, 0.05f, seed, { t -> if (t > 1 && (t % 1.0) < 0.1) 0.75f else 1f }).shots.size, "twitches, $a")
            // the thumb drifting down over 3 s: never a shot while the thumb is still up (at most one, once it is
            // well on its way down - "thumb goes down, it shoots")
            val drift = { t: Double -> (1 - ((t - 1) / 3.0)).toFloat().coerceIn(0f, 1f) }
            val d = play(5.0, a.first, a.second, 0.05f, seed, drift).shots
            assertTrue(d.size <= 1 && d.all { drift(it) < 0.6f }, "drifting, $a: ${d.map { "%.2f (thumb %.2f)".format(it, drift(it)) }}")
            // turning the hand slowly with the thumb up is not a shot
            assertEquals(0, play(5.0, a.first, a.second, 0.05f, seed, { 1f }, { t -> a.second + 90f * (t / 5.0).toFloat() }).shots.size, "turning, $a")
        }
    }

    /** The hand crop's direction, frame to frame, with the gun pointing at the camera: the old way and the new way. */
    @Test
    fun theHandCropStaysSteadyEndOn() {
        fun spreadDeg(v: List<Float>): Float {
            // circular spread: how far each frame's direction is from the average direction
            val cx = v.map { cos(it) }.average(); val sx = v.map { sin(it) }.average()
            val mean = kotlin.math.atan2(sx, cx).toFloat()
            return Math.toDegrees(sqrt(v.map { val d = com.clrg.core.math.normalizeRadians(it - mean); d * d }.average())).toFloat()
        }
        var oldSum = 0f; var newSum = 0f
        val results = ArrayList<Float>()
        for ((i, a) in angles.withIndex()) {
            val rnd = java.util.Random(800L + i)
            val olds = ArrayList<Float>(); val news = ArrayList<Float>()
            var prev = Float.NaN
            repeat(60) { k ->
                val p = hand(if (k % 20 < 10) 1f else 0f, a.first, a.second)
                val o = FloatArray(63)
                for (j in 0 until 21) {
                    o[j * 3] = p[j * 3] + (rnd.nextGaussian() * 0.05 * kw).toFloat()
                    o[j * 3 + 1] = p[j * 3 + 1] + (rnd.nextGaussian() * 0.05 * kw).toFloat()
                    o[j * 3 + 2] = p[j * 3 + 2] * 0.4f
                }
                val obs = com.clrg.core.pose.HandObservation(1, FloatArray(21) { o[it * 3] }, FloatArray(21) { o[it * 3 + 1] }, FloatArray(21) { o[it * 3 + 2] }, 0.95f)
                // as MediaPipe (and this game up to 1.8.1) turned it: wrist to knuckles
                val x1 = ((obs.x[5] + obs.x[13]) / 2f + obs.x[9]) / 2f; val y1 = ((obs.y[5] + obs.y[13]) / 2f + obs.y[9]) / 2f
                olds.add(com.clrg.core.math.normalizeRadians((Math.PI / 2).toFloat() - kotlin.math.atan2(-(y1 - obs.y[0]), x1 - obs.x[0])))
                val r = com.clrg.core.pose.HandLandmarkTracker.trackingRoi(obs, prev)
                news.add(r.rotation); prev = r.rotation
            }
            val so = spreadDeg(olds); val sn = spreadDeg(news)
            oldSum += so; newSum += sn
            val p0 = hand(1f, a.first, a.second)
            val pl = kotlin.math.hypot(((p0[15] + p0[39]) / 2f + p0[27]) / 2f - p0[0], ((p0[16] + p0[40]) / 2f + p0[28]) / 2f - p0[1])
            val kwi = kotlin.math.hypot(p0[51] - p0[15], p0[52] - p0[16])
            println("[crop] $a palm/knuckles %.2f  spread new %.1f old %.1f".format(pl / kwi, sn, so))
            results.add(sn)
        }
        println("[finger-gun] hand crop direction wobble pointing at the camera, average over 12 angles: %.1f deg (was %.1f deg)".format(newSum / 12, oldSum / 12))
        assertTrue(results.all { it < 12f }, "crop direction steady at every angle: $results")
    }

    @Test
    fun aSingleMisreadFrameDoesNotFire() {
        // the model misplaces the thumb for one frame now and then (blur): it looks fully down for that frame
        for ((i, a) in angles.withIndex()) {
            val r = play(8.0, a.first, a.second, 0.05f, 400L + i, { t -> if (t > 1 && ((t * 30).toInt() % 23 == 0)) 0f else 1f })
            assertEquals(0, r.shots.size, "one-frame misreads, $a")
        }
    }
}
