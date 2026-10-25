package com.clrg.core.ui

import com.clrg.core.gesture.LEFT
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rect
import com.clrg.core.sim.Rig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hand pointer with simulated bodies and simulated tracking jitter: comfortable to aim everywhere on screen,
 * steady when held still, no flicker at the bottom of the screen, and quick when the hand moves.
 */
class HandPointerTest {
    private val w = 1920f
    private val h = 1080f
    private val pointer = HandPointer()

    /** Runs [sec] of 60 Hz display frames over 30 Hz camera frames, placing the right hand with [place]. */
    private fun Rig.drive(sec: Float, onFrame: (Double) -> Unit = {}, place: (Double) -> Unit) {
        val frames = (sec * 60).toInt()
        val t0 = cam.tSec
        for (i in 0 until frames) {
            val t = i / 60.0
            if (i % 2 == 0) step(1) { place(t) }
            pointer.update(controls[0], tracker.slots[0].isPlaying, w, h, 1f / 60f)
            onFrame(t)
        }
        @Suppress("UNUSED_VARIABLE") val unused = t0
    }

    private fun rigWithJitter(j: Float = 0.006f) = Rig().apply { acquireAndLock(); cam.jitter = j }

    @Test
    fun everyCornerAndTheBottomRowAreInEasyReach() {
        for ((sx, sy) in listOf(60f to 60f, w - 60f to 60f, 60f to 1020f, w - 60f to 1020f, w / 2f to 990f, w / 2f to h / 2f)) {
            val hand = if (sx < w / 2f) LEFT else RIGHT
            val (rx, ry) = pointer.reachFor(hand, sx, sy, w, h)
            val side = if (hand == RIGHT) rx else -rx
            assertTrue(side in -0.4f..0.95f, "($sx, $sy): hand ${"%.2f".format(side)} reach across - no stretching across the body")
            assertTrue(ry in -0.56f..0.45f, "($sx, $sy): hand height ${"%.2f".format(ry)} reach - between the chest and the face")
            assertTrue(ry > HandPointer.ON_Y, "($sx, $sy): the pointer is on there")
        }
    }

    @Test
    fun aStillHandWithTrackingJitterHoldsSteady() {
        val r = rigWithJitter(0.01f)
        val (rx, ry) = pointer.reachFor(RIGHT, w * 0.7f, h * 0.6f, w, h)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        r.drive(3f, onFrame = { t -> if (t > 1.0 && pointer.active) { xs.add(pointer.x); ys.add(pointer.y) } }) { r.p1.setWrist(RIGHT, rx * 1.5f, ry * 1.5f) }
        assertTrue(xs.size > 100, "pointer active while the hand is up: ${xs.size}")
        fun sd(v: List<Float>): Double { val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m).toDouble() } / v.size) }
        val spread = hypot(sd(xs), sd(ys))
        println("POINTER jitter: %.1f px spread with tracking noise of 1%% of the body size".format(spread))
        assertTrue(spread < 6.0, "steady: $spread px")
        assertTrue(abs(xs.average() - w * 0.7) < 40 && abs(ys.average() - h * 0.6) < 40, "points where the hand points: ${xs.average()}, ${ys.average()}")
    }

    @Test
    fun theBottomOfTheScreenDoesNotFlicker() {
        val r = rigWithJitter(0.01f)
        val (rx, ry) = pointer.reachFor(RIGHT, w * 0.5f, 1040f, w, h)
        var off = 0
        r.drive(3f, onFrame = { t -> if (t > 0.5 && !pointer.active) off++ }) { t ->
            // a tired arm sagging and recovering a little at the bottom of the screen
            val sag = (0.06 * kotlin.math.sin(t * 4.0)).toFloat()
            r.p1.setWrist(RIGHT, rx * 1.5f, (ry + sag) * 1.5f)
        }
        assertTrue(off == 0, "pointer switched off $off times at the bottom of the screen")
    }

    @Test
    fun aBlurredHandForAFewFramesKeepsThePointer() {
        val r = rigWithJitter()
        val (rx, ry) = pointer.reachFor(RIGHT, w * 0.6f, h * 0.4f, w, h)
        var off = 0
        r.drive(2f, onFrame = { t -> if (t > 0.6 && !pointer.active) off++ }) { t ->
            r.p1.setWrist(RIGHT, rx * 1.5f, ry * 1.5f)
            r.p1.wristVisibility[RIGHT] = if (t in 1.0..1.15) 0.05f else Float.NaN
        }
        assertTrue(off == 0, "pointer dropped for $off frames during a 0.15 s blur")
    }

    @Test
    fun aDeliberateMoveIsFollowedQuickly() {
        val r = rigWithJitter()
        val a = pointer.reachFor(RIGHT, w * 0.25f, h * 0.5f, w, h)
        val b = pointer.reachFor(RIGHT, w * 0.75f, h * 0.5f, w, h)
        var reachedAt = Double.NaN
        r.drive(2.5f, onFrame = { t -> if (t > 1.0 && reachedAt.isNaN() && pointer.x > w * 0.75f - 60f) reachedAt = t }) { t ->
            val u = ((t - 1.0) / 0.3).coerceIn(0.0, 1.0).toFloat()
            r.p1.setWrist(RIGHT, (a.first + (b.first - a.first) * u) * 1.5f, a.second * 1.5f)
        }
        val lag = reachedAt - 1.3
        println("POINTER follows a 0.3 s move across half the screen within %.0f ms of the hand arriving".format(lag * 1000))
        assertTrue(!reachedAt.isNaN() && lag < 0.2, "followed within 0.2 s: $lag")
    }

    @Test
    fun aRelaxedArmDoesNotPoint() {
        val r = rigWithJitter()
        var on = 0
        r.drive(2f, onFrame = { if (pointer.active) on++ }) { r.p1.relaxArms() }
        assertTrue(on == 0, "arms by the sides: pointer off")
    }

    @Test
    fun holdingOverAButtonSelectsItWithJitter() {
        val r = rigWithJitter(0.012f)
        val ui = Ui(Theme()) {}
        ui.dwellSeconds = 1.2f
        val button = Rect(1500f, 900f, 1800f, 1020f)
        val (rx, ry) = pointer.reachFor(RIGHT, button.cx, button.cy, w, h)
        var chosen = Double.NaN
        r.drive(3f, onFrame = { t ->
            val c = ui.cursors[0]
            c.visible = pointer.visible; c.active = pointer.active; c.x = pointer.x; c.y = pointer.y
            ui.begin(1f / 60f, emptyList(), true)
            if (ui.activated == "btn" && chosen.isNaN()) chosen = t
            ui.register("btn", button, true)
            ui.end()
        }) { r.p1.setWrist(RIGHT, rx * 1.5f, ry * 1.5f) }
        assertTrue(!chosen.isNaN() && chosen < 2.0, "bottom-right button chosen by holding: $chosen s")
    }
}
