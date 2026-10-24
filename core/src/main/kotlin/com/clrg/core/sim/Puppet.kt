package com.clrg.core.sim

import com.clrg.core.math.Rng
import com.clrg.core.pose.Lm
import com.clrg.core.pose.PersonPose
import com.clrg.core.pose.PoseFrame
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthetic person that produces BlazePose-style landmarks in frame units. Used by unit tests and by the
 * developer-only simulated-input mode. It is NOT camera tracking and is always labelled as simulated.
 *
 * Wrist targets are in body units relative to the shoulder centre (1 = torso length, y up, x = screen right);
 * elbows are solved with two-link inverse kinematics.
 */
class Puppet(var cx: Float, var shoulderY: Float, var scale: Float) {
    /** [LEFT, RIGHT] wrist targets (body units). Default: arms hanging relaxed. */
    val wristX = floatArrayOf(-0.42f, 0.42f)
    val wristY = floatArrayOf(-1.05f, -1.05f)
    /** Wrist depth (body units, negative = toward camera). */
    val wristZ = floatArrayOf(0f, 0f)
    /** Upper-body sideways shift in body units (+ = screen right). Hips stay put, so this tilts the torso. */
    var lean = 0f
    var seated = false
    var hipsVisible = true
    var visibility = 0.95f
    /** Visibility of elbows, wrists and hands (e.g. arms outside the frame). NaN = same as [visibility]. */
    var armVisibility = Float.NaN
    var score = 0.99f
    var trackHint = 1
    var appearance: FloatArray? = null
    var shoulderWidthRatio = 1f / 1.35f
    val upperArm = 0.62f
    val forearm = 0.56f

    fun relaxArms() {
        wristX[0] = -0.42f; wristX[1] = 0.42f; wristY[0] = -1.05f; wristY[1] = -1.05f
        wristZ[0] = 0f; wristZ[1] = 0f
    }

    fun setWrist(hand: Int, x: Float, y: Float) { wristX[hand] = x; wristY[hand] = y }

    fun pose(noise: Float = 0f, rng: Rng? = null): PersonPose {
        val n = Lm.COUNT
        val x = FloatArray(n)
        val y = FloatArray(n)
        val z = FloatArray(n)
        val vis = FloatArray(n) { visibility }
        val pres = FloatArray(n) { 0.99f }
        val s = scale
        val sw = s * shoulderWidthRatio
        val scx = cx + lean * s
        val scy = shoulderY
        val hipCx = cx
        val hipCy = shoulderY + s * (if (seated) 0.95f else 1f)
        fun set(i: Int, px: Float, py: Float, v: Float = visibility) { x[i] = px; y[i] = py; vis[i] = v }
        // head
        set(Lm.NOSE, scx, scy - 0.42f * s)
        set(1, scx - 0.04f * s, scy - 0.48f * s); set(Lm.LEFT_EYE, scx - 0.07f * s, scy - 0.48f * s); set(3, scx - 0.1f * s, scy - 0.48f * s)
        set(4, scx + 0.04f * s, scy - 0.48f * s); set(Lm.RIGHT_EYE, scx + 0.07f * s, scy - 0.48f * s); set(6, scx + 0.1f * s, scy - 0.48f * s)
        set(Lm.LEFT_EAR, scx - 0.14f * s, scy - 0.45f * s); set(Lm.RIGHT_EAR, scx + 0.14f * s, scy - 0.45f * s)
        set(9, scx - 0.04f * s, scy - 0.34f * s); set(10, scx + 0.04f * s, scy - 0.34f * s)
        // shoulders (person's left = screen left in the mirror view)
        val lsx = scx - sw / 2f
        val rsx = scx + sw / 2f
        set(Lm.LEFT_SHOULDER, lsx, scy); set(Lm.RIGHT_SHOULDER, rsx, scy)
        // arms via IK
        for (h in 0..1) {
            val jx = if (h == 0) lsx else rsx
            val jy = scy
            var tx = scx + wristX[h] * s
            var ty = scy - wristY[h] * s
            val l1 = upperArm * s
            val l2 = forearm * s
            var d = hypot(tx - jx, ty - jy)
            val maxD = (l1 + l2) * 0.999f
            val minD = kotlin.math.abs(l1 - l2) * 1.01f + 1e-5f
            if (d > maxD) { tx = jx + (tx - jx) * maxD / d; ty = jy + (ty - jy) * maxD / d; d = maxD }
            if (d < minD) { d = minD }
            val base = atan2(ty - jy, tx - jx)
            val cosA = ((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d)).coerceIn(-1f, 1f)
            val a = acos(cosA)
            val e1x = jx + l1 * cos(base + a); val e1y = jy + l1 * sin(base + a)
            val e2x = jx + l1 * cos(base - a); val e2y = jy + l1 * sin(base - a)
            val outward1 = kotlin.math.abs(e1x - scx); val outward2 = kotlin.math.abs(e2x - scx)
            val pickFirst = if (ty < jy) outward1 >= outward2 else e1y >= e2y // above shoulder: elbow out; else elbow down
            val ex = if (pickFirst) e1x else e2x
            val ey = if (pickFirst) e1y else e2y
            val wIdx = if (h == 0) Lm.LEFT_WRIST else Lm.RIGHT_WRIST
            val eIdx = if (h == 0) Lm.LEFT_ELBOW else Lm.RIGHT_ELBOW
            val av = if (armVisibility.isNaN()) visibility else armVisibility
            set(eIdx, ex, ey, av); set(wIdx, tx, ty, av)
            z[wIdx] = wristZ[h] * s
            // hand points slightly beyond the wrist along the forearm
            val fx = (tx - ex) / l2; val fy = (ty - ey) / l2
            val handBase = if (h == 0) 17 else 18
            set(handBase, tx + fx * 0.12f * s, ty + fy * 0.12f * s, av)         // pinky
            set(handBase + 2, tx + fx * 0.14f * s, ty + fy * 0.14f * s, av)     // index
            set(handBase + 4, tx + fx * 0.08f * s - fy * 0.04f * s, ty + fy * 0.08f * s, av) // thumb
        }
        // hips and legs
        val hipV = if (hipsVisible) visibility else 0.05f
        set(Lm.LEFT_HIP, hipCx - sw * 0.4f, hipCy, hipV); set(Lm.RIGHT_HIP, hipCx + sw * 0.4f, hipCy, hipV)
        val legV = if (seated || !hipsVisible) 0.05f else visibility
        val kneeY = if (seated) hipCy + 0.15f * s else hipCy + 0.9f * s
        val ankleY = if (seated) hipCy + 0.95f * s else hipCy + 1.8f * s
        set(Lm.LEFT_KNEE, hipCx - sw * 0.4f, kneeY, legV); set(Lm.RIGHT_KNEE, hipCx + sw * 0.4f, kneeY, legV)
        set(Lm.LEFT_ANKLE, hipCx - sw * 0.4f, ankleY, legV); set(Lm.RIGHT_ANKLE, hipCx + sw * 0.4f, ankleY, legV)
        set(29, hipCx - sw * 0.42f, ankleY + 0.05f * s, legV); set(30, hipCx + sw * 0.42f, ankleY + 0.05f * s, legV)
        set(31, hipCx - sw * 0.38f, ankleY + 0.08f * s, legV); set(32, hipCx + sw * 0.38f, ankleY + 0.08f * s, legV)
        if (noise > 0f && rng != null) for (i in 0 until n) {
            x[i] += (rng.nextFloat() - 0.5f) * 2f * noise * s
            y[i] += (rng.nextFloat() - 0.5f) * 2f * noise * s
        }
        return PersonPose(x, y, z, vis, pres, score, trackHint, appearance?.copyOf())
    }
}

/**
 * Produces [PoseFrame]s from puppets at a fixed rate. Options shuffle detection order, add jitter and drop people
 * (occlusion) to exercise the tracker like a real camera would.
 */
class SimCamera(val aspect: Float = 16f / 9f, val fps: Float = 30f, seed: Long = 1) {
    val rng = Rng(seed)
    var timeNanos = 0L
        private set
    var shuffle = true
    var jitter = 0.004f

    fun frame(people: List<Puppet>, visible: (Puppet) -> Boolean = { true }): PoseFrame {
        timeNanos += (1e9 / fps).toLong()
        val persons = people.filter(visible).map { it.pose(jitter, rng) }.toMutableList()
        if (shuffle) rng.shuffle(persons)
        return PoseFrame(timeNanos, aspect, persons, simulated = true)
    }

    val tSec: Double get() = timeNanos / 1e9

    fun advance(sec: Float) { timeNanos += (sec * 1e9).toLong() }
}

/** Library of scripted motions (wrist trajectories) shared by tests and the in-app simulator. */
object Motions {
    /** Horizontal swing of [hand] from fromX to toX at shoulder-ish height over [dur] seconds; t in [0,1]. */
    fun swing(p: Puppet, hand: Int, fromX: Float, toX: Float, y: Float, t: Float) {
        val e = (1 - cos(PI * t.coerceIn(0f, 1f)).toFloat()) / 2f
        p.setWrist(hand, fromX + (toX - fromX) * e, y + 0.15f * sin(PI * t).toFloat())
    }

    /** Overhand throw: hand from behind the head to forward/low. */
    fun throwMotion(p: Puppet, hand: Int, t: Float) {
        val side = if (hand == 0) -1f else 1f
        val tt = t.coerceIn(0f, 1f)
        if (tt < 0.4f) {
            val u = tt / 0.4f
            p.setWrist(hand, side * (0.45f - 0.05f * u), -0.9f + u * 1.55f) // wind up to above head
            p.wristZ[hand] = 0.3f * u
        } else {
            val u = (tt - 0.4f) / 0.6f
            p.setWrist(hand, side * 0.4f - side * 0.5f * u, 0.65f - 1.25f * u) // forward and down
            p.wristZ[hand] = 0.3f - 0.9f * u
        }
    }

    /** Underarm bowl: hand swings low then forward/up to chest height; [aim] shifts the hand sideways. */
    fun bowl(p: Puppet, hand: Int, t: Float, aim: Float = 0f) {
        val side = if (hand == 0) -1f else 1f
        val tt = t.coerceIn(0f, 1f)
        if (tt < 0.45f) {
            val u = tt / 0.45f
            p.setWrist(hand, side * (0.42f + 0.1f * u), -1.05f - 0.05f * u)
        } else {
            val u = (tt - 0.45f) / 0.55f
            p.setWrist(hand, side * 0.52f + aim * 0.5f * u, -1.1f + 1.0f * u)
        }
    }

    /** Alternating arm pump at [cadence] strokes per second with amplitude [amp] (body units). */
    fun pump(p: Puppet, timeSec: Double, cadence: Float, amp: Float) {
        val phase = sin(PI * cadence * timeSec).toFloat()
        p.setWrist(0, -0.45f, -0.55f + amp * phase)
        p.setWrist(1, 0.45f, -0.55f - amp * phase)
    }

    /** Both hands overhead. */
    fun handsUp(p: Puppet) { p.setWrist(0, -0.25f, 0.95f); p.setWrist(1, 0.25f, 0.95f) }

    /** One hand raised above the head. */
    fun raise(p: Puppet, hand: Int) { p.setWrist(hand, if (hand == 0) -0.3f else 0.3f, 0.9f) }

    /** Arms placed to match a dance pose shape (angles in degrees). */
    fun shape(p: Puppet, shape: com.clrg.core.gesture.PoseShape) {
        fun place(hand: Int, upperDeg: Float, foreDeg: Float) {
            val sx = if (hand == 0) -p.shoulderWidthRatio / 2f else p.shoulderWidthRatio / 2f
            val u = (upperDeg * PI / 180).toFloat()
            val f = (foreDeg * PI / 180).toFloat()
            val ex = sx + p.upperArm * cos(u)
            val ey = p.upperArm * sin(u)
            p.setWrist(hand, ex + p.forearm * cos(f) * 0.999f, ey + p.forearm * sin(f) * 0.999f)
        }
        place(0, shape.leftUpper, shape.leftFore)
        place(1, shape.rightUpper, shape.rightFore)
    }

    /** Reach toward a point in reach space (x, y in units of the player's reach, relative to shoulders). */
    fun reach(p: Puppet, hand: Int, rx: Float, ry: Float, reach: Float) {
        p.setWrist(hand, rx * reach, ry * reach)
    }

    fun clampReach(v: Float, r: Float) = max(-r, min(r, v))
}
