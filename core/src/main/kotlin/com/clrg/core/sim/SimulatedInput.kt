package com.clrg.core.sim

import com.clrg.core.gesture.PoseShape
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.pose.PoseFrame
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx
import com.clrg.core.ui.Ui

enum class SimAction(val label: String) {
    SWING("Swing"), THROW("Throw"), BOWL("Bowl"), RAISE("Raise"), HANDS_UP("Both up"),
    LEAN_LEFT("Lean L"), LEAN_RIGHT("Lean R"), PUMP("Pump"), TUCK("Tuck"), POSE("Pose"), GOLF("Golf"), PUTT("Putt"), REST("Rest"),
}

/**
 * DEVELOPER-ONLY simulated input. Two synthetic puppets stand in the player areas and produce pose frames that go
 * through the *same* tracker and gesture system as the camera. It exists to test game logic and menus without a
 * camera and is always shown with a "SIMULATED INPUT" banner. It is not, and is never presented as, motion tracking.
 */
class SimulatedInput(private val aspect: Float = 16f / 9f) {
    val puppets = arrayOf(
        Puppet(aspect * 0.27f, 0.36f, 0.24f).apply { trackHint = 901; appearance = floatArrayOf(0.8f, 0.2f, 0.2f, 0.66f, 0.17f, 0.3f, 0.3f, 0.6f, 0.25f, 0.25f) },
        Puppet(aspect * 0.73f, 0.36f, 0.24f).apply { trackHint = 902; appearance = floatArrayOf(0.2f, 0.3f, 0.8f, 0.15f, 0.23f, 0.4f, 0.3f, 0.2f, 0.45f, 0.33f) },
    )
    /** When on, games are played by their built-in scripted players (input layer), not by the puppets. */
    var autoplay = false
    /** Solo mode: a single puppet stands in the middle of the picture (the opponent is the computer). */
    var solo = false
        set(value) {
            field = value
            puppets[0].cx = aspect * (if (value) 0.5f else 0.27f)
        }
    /** The developer control strip can be collapsed so it does not cover buttons near the bottom of the screen. */
    var controlsVisible = true
    private var timeNanos = 0L
    private var acc = 0.0
    private val rng = Rng(77)
    private val motion = arrayOfNulls<SimAction>(2)
    private val motionT = FloatArray(2)
    private val pumping = BooleanArray(2)
    private val tucking = BooleanArray(2)
    private val lean = FloatArray(2)
    private val shapeIdx = IntArray(2)
    private var t = 0.0
    private val reachHold = BooleanArray(2)
    /** How high the simulated golfer's backswing goes (body units; ~1.35 = full power). */
    var golfRise = 1.35f
    /** How far back the simulated putting stroke goes (body units; ~0.6 = longest putt). */
    var puttBack = 0.3f

    fun trigger(p: Int, a: SimAction) {
        when (a) {
            SimAction.LEAN_LEFT -> lean[p] = if (lean[p] < 0f) 0f else -0.3f
            SimAction.LEAN_RIGHT -> lean[p] = if (lean[p] > 0f) 0f else 0.3f
            SimAction.PUMP -> pumping[p] = !pumping[p]
            SimAction.TUCK -> tucking[p] = !tucking[p]
            SimAction.POSE -> { shapeIdx[p] = (shapeIdx[p] + 1) % PoseShape.STANDARD_SET.size; motion[p] = SimAction.POSE; motionT[p] = 0f; reachHold[p] = false }
            SimAction.REST -> { motion[p] = null; pumping[p] = false; tucking[p] = false; lean[p] = 0f; reachHold[p] = false; puppets[p].relaxArms() }
            else -> { motion[p] = a; motionT[p] = 0f; reachHold[p] = false }
        }
    }

    /**
     * Moves one of player [p]'s hands to a reach-space point (1 = comfortable reach) while held - the hand on that
     * side, as a person would (the other hand rests).
     */
    fun reach(p: Int, rx: Float, ry: Float) {
        reachHold[p] = true
        motion[p] = null
        val hand = if (rx < -0.1f) 0 else 1
        puppets[p].setWrist(hand, rx * 1.5f, ry * 1.5f)
        puppets[p].setWrist(1 - hand, if (hand == 1) -0.42f else 0.42f, -1.05f)
    }

    fun releaseReach(p: Int) { reachHold[p] = false }

    /**
     * Raises one of player [p]'s hands to point at screen point ([sx], [sy]) of a [w] x [h] screen, through the
     * same pointing box the camera players use (left half of the screen: left hand; right half: right hand).
     */
    fun pointAt(p: Int, sx: Float, sy: Float, w: Float, h: Float) {
        val hand = if (sx < w / 2f) com.clrg.core.gesture.LEFT else com.clrg.core.gesture.RIGHT
        val (rx, ry) = pointerMath.reachFor(hand, sx, sy, w, h)
        reachHold[p] = true
        motion[p] = null
        puppets[p].setWrist(hand, rx * 1.5f, ry * 1.5f)
        puppets[p].setWrist(1 - hand, if (hand == 1) -0.42f else 0.42f, -1.05f)
    }

    private val pointerMath = com.clrg.core.ui.HandPointer()

    /** Advances time and returns the pose frames (30 Hz) generated during [dt]. */
    fun update(dt: Float): List<PoseFrame> {
        t += dt
        for (p in 0..1) animate(p, dt)
        val out = ArrayList<PoseFrame>()
        acc += dt
        while (acc >= 1.0 / 30.0) {
            acc -= 1.0 / 30.0
            timeNanos += 33_333_333L
            val people = (if (solo) puppets.take(1) else puppets.toList()).map { it.pose(0.002f, rng) }.toMutableList()
            rng.shuffle(people)
            out.add(PoseFrame(timeNanos, aspect, people, simulated = true))
        }
        return out
    }

    private fun animate(p: Int, dt: Float) {
        val pup = puppets[p]
        pup.lean += (lean[p] - pup.lean) * minOf(1f, dt * 8f)
        val m = motion[p]
        if (m != null) {
            motionT[p] += dt
            val k = motionT[p]
            when (m) {
                SimAction.SWING -> { Motions.swing(pup, 1, 0.9f, -0.6f, -0.15f, k / 0.25f); if (k > 0.6f) done(p) }
                SimAction.THROW -> { Motions.throwMotion(pup, 1, k / 0.45f); if (k > 0.8f) done(p) }
                SimAction.BOWL -> { Motions.bowl(pup, 1, k / 0.75f); if (k > 1.1f) done(p) }
                SimAction.RAISE -> { Motions.raise(pup, 1); if (k > 1.4f) done(p) }
                SimAction.HANDS_UP -> { Motions.handsUp(pup); if (k > 0.9f) done(p) }
                SimAction.POSE -> Motions.shape(pup, PoseShape.STANDARD_SET[shapeIdx[p]])
                SimAction.GOLF -> if (k < 0.9f) Motions.golfAddress(pup) else if (!Motions.golfSwing(pup, (k - 0.9f).toDouble(), rise = golfRise)) done(p)
                SimAction.PUTT -> if (k < 0.9f) Motions.golfAddress(pup) else if (!Motions.golfPutt(pup, (k - 0.9f).toDouble(), back = puttBack)) done(p)
                else -> done(p)
            }
        } else if (pumping[p]) {
            Motions.pump(pup, t, 2.2f, 0.45f)
        } else if (tucking[p]) {
            pup.setWrist(0, -0.12f, -0.35f); pup.setWrist(1, 0.12f, -0.35f)
        } else if (!reachHold[p]) {
            // relax slowly so that returning to rest is never mistaken for a gesture
            for (h in 0..1) {
                val tx = if (h == 0) -0.42f else 0.42f
                pup.wristX[h] += (tx - pup.wristX[h]) * minOf(1f, dt * 2f)
                pup.wristY[h] += (-1.05f - pup.wristY[h]) * minOf(1f, dt * 2f)
                pup.wristZ[h] *= 0.9f
            }
        }
    }

    private fun done(p: Int) { motion[p] = null }

    /** Developer control strip (bottom of the screen) and touch-drag reach. */
    fun drawControls(g: Gfx, ui: Ui) {
        if (ui.button(g, "simToggle", Rect(g.width / 2f + 570f, 4f, g.width / 2f + 720f, 50f), if (controlsVisible) "SIM v" else "SIM ^", size = 24f, accent = Colors.CANDY)) controlsVisible = !controlsVisible
        if (!controlsVisible) return
        val h = 84f
        val top = g.height - h - 10f
        g.fillRect(0f, top - 10f, g.width, g.height, 0xCC1A0000.toInt())
        val actions = listOf(SimAction.SWING, SimAction.THROW, SimAction.BOWL, SimAction.RAISE, SimAction.HANDS_UP, SimAction.LEAN_LEFT, SimAction.LEAN_RIGHT, SimAction.PUMP, SimAction.TUCK, SimAction.POSE, SimAction.GOLF, SimAction.PUTT, SimAction.REST)
        val bw = (g.width / 2f - 40f) / actions.size
        for (p in 0..1) {
            val x0 = 20f + p * g.width / 2f
            actions.forEachIndexed { i, a ->
                val r = Rect(x0 + i * bw, top, x0 + (i + 1) * bw - 6f, top + h)
                if (ui.button(g, "sim$p${a.name}", r, a.label, size = 24f, accent = if (p == 0) Colors.CANDY else Colors.ICE)) trigger(p, a)
            }
        }
        if (ui.button(g, "simAuto", Rect(g.width / 2f - 150f, top - 80f, g.width / 2f + 150f, top - 14f), if (autoplay) "Autoplay: ON" else "Autoplay: OFF", size = 28f, selected = autoplay)) autoplay = !autoplay
        // touch-drag above the strip moves that side's right hand (reach games)
        for (e in ui.touches) {
            if (e.y > top - 90f) continue
            val p = if (e.x < g.width / 2f) 0 else 1
            val halfW = g.width / 2f
            val cx = halfW * (p + 0.5f)
            val rx = ((e.x - cx) / (halfW * 0.4f)).coerceIn(-1.3f, 1.3f)
            val ry = ((650f - e.y) / 380f).coerceIn(-0.8f, 1.3f)
            if (e.action == com.clrg.core.ui.InputEvent.Touch.UP || e.action == com.clrg.core.ui.InputEvent.Touch.CANCEL) releaseReach(p) else reach(p, rx, ry)
        }
    }

    fun banner(g: Gfx) {
        val text = if (autoplay) "SIMULATED INPUT + AUTOPLAY (developer mode) - not camera tracking" else "SIMULATED INPUT (developer mode) - not camera tracking"
        val w = g.measureText(text, 30f) + 40f
        g.fillRoundRect(g.width / 2f - w / 2f, 4f, g.width / 2f + w / 2f, 50f, 12f, 0xEEB71C1C.toInt())
        g.text(text, g.width / 2f, 27f, 30f, Colors.WHITE)
    }
}
