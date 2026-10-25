package com.clrg.core.gesture

import com.clrg.core.tracking.MovementMode
import kotlin.math.hypot

/**
 * Archery with your arms, facing the TV: the bow hand points at the TV (it aims, like a gun hand), the other hand
 * nocks an arrow by touching it, pulls back towards your face (how far = how much power) and lets go:
 *  - open the drawing hand (when the finger tracker can see it), or
 *  - let the drawing hand fly back (the natural follow-through of a release), or
 *  - for reduced-movement and seated players: hold a good draw still for a moment.
 * Bringing the hands back together without letting go puts the arrow down again.
 */
class BowDetector {
    enum class State { IDLE, NOCKED, DRAWING }

    var state = State.IDLE
        private set
    /** 0..1 how far the string is drawn. */
    var draw = 0f
        private set
    var bowHand = LEFT
        private set
    val drawHand: Int get() = 1 - bowHand
    private var togetherSec = 0f
    private var letDownSec = 0f
    private var holdSec = 0f
    private var cooldownUntil = -1.0
    private var lastT = Double.NaN
    private var lastDX = 0f
    private var lastDY = 0f
    private var drawSpeed = 0f
    private var peakDraw = 0f
    private var wasClosed = false
    private val shape = HandShape()

    fun reset() { state = State.IDLE; draw = 0f; togetherSec = 0f; letDownSec = 0f; holdSec = 0f; lastT = Double.NaN; peakDraw = 0f; wasClosed = false }

    /**
     * One pose frame. [dominant]: the drawing hand (a right-handed archer draws with the right). [aim]: where the bow
     * hand aimed just before a release, from the gun-style aim tracking.
     */
    fun update(f: BodyFrame, dominant: Int, mode: MovementMode, sensitivity: Float, aimAt: (Int, Double) -> Pair<Float, Float>, out: MutableList<GestureEvent>) {
        val dt = if (lastT.isNaN()) 0f else (f.t - lastT).toFloat().coerceIn(0f, 0.2f)
        lastT = f.t
        bowHand = 1 - dominant
        val d = drawHand
        val seen = f.wv[0] > 0.3f && f.wv[1] > 0.3f
        if (!seen) { if (state == State.NOCKED) state = State.IDLE; drawSpeed = 0f; return }
        val gentle = mode != MovementMode.STANDARD
        val k = (if (gentle) 0.65f else 1f) / sensitivity.coerceIn(0.5f, 2f)
        // hands apart, with a little of the (rougher) depth: pulling straight back from a bow arm pointed at the TV
        // moves the drawing hand away from the camera more than across the picture
        val dz = (f.wz[0] - f.wz[1]) * DEPTH_W
        val gap = kotlin.math.sqrt((f.wx[0] - f.wx[1]) * (f.wx[0] - f.wx[1]) + (f.wy[0] - f.wy[1]) * (f.wy[0] - f.wy[1]) + dz * dz)
        // raw drawing-hand position relative to the bow hand, for its speed
        val rx = f.rwx[d] - f.rwx[bowHand]; val ry = f.rwy[d] - f.rwy[bowHand]
        if (dt > 0f) {
            val v = hypot(rx - lastDX, ry - lastDY) / dt
            drawSpeed += 0.6f * (v - drawSpeed)
        }
        val dist = hypot(rx, ry)
        val growing = dist > hypot(lastDX, lastDY)
        lastDX = rx; lastDY = ry
        val raised = f.wy[bowHand] > -0.7f && f.wy[d] > -0.7f
        draw = ((gap - NOCK_GAP * k) / (FULL_DRAW * k)).coerceIn(0f, 1f)
        // the drawing hand's fingers, when the finger tracker sees them: a closed hand holds the string, opening lets go
        val fingersOpen = if (f.fingSeen[d]) { shape.measure(f.fing[d]); (0 until 4).count { shape.extended(it) } >= 3 } else false
        val fingersClosed = f.fingSeen[d] && !fingersOpen
        when (state) {
            State.IDLE -> {
                togetherSec = if (gap < NOCK_GAP * k && raised && f.t >= cooldownUntil) togetherSec + dt else 0f
                if (togetherSec >= NOCK_SEC) { state = State.NOCKED; togetherSec = 0f; peakDraw = 0f; wasClosed = false }
            }
            State.NOCKED -> {
                if (!raised) { state = State.IDLE; return }
                if (draw > 0.12f) { state = State.DRAWING; holdSec = 0f; letDownSec = 0f }
            }
            State.DRAWING -> {
                peakDraw = maxOf(peakDraw, draw)
                if (fingersClosed) wasClosed = true
                // let down: hands back together without a release
                letDownSec = if (draw < 0.05f) letDownSec + dt else 0f
                if (letDownSec > 0.3f || !raised) { state = if (raised) State.NOCKED else State.IDLE; return }
                holdSec = if (draw >= 0.6f && drawSpeed < 0.6f) holdSec + dt else 0f
                val byFingers = wasClosed && fingersOpen && peakDraw > 0.2f
                val byFlick = drawSpeed > FLICK_V * k && growing && peakDraw > 0.3f
                val byHold = gentle && holdSec >= HOLD_SEC
                if (byFingers || byFlick || byHold) {
                    // the power is the draw held just before letting go (the flick itself pulls the hand further)
                    val power = if (byFlick) peakDrawBefore() else maxOf(draw, peakDraw * 0.9f)
                    val (ax, ay) = aimAt(bowHand, f.t - 0.08)
                    out.add(GestureEvent.Release(f.t, bowHand, power.coerceIn(0f, 1f), ax, ay))
                    state = State.IDLE
                    cooldownUntil = f.t + 0.4
                    draw = 0f
                }
            }
        }
    }

    private fun peakDrawBefore() = (peakDraw * 0.9f).coerceAtLeast(0.2f)

    companion object {
        /** Hands this close (body units) count as together (nocking). */
        const val NOCK_GAP = 0.32f
        const val NOCK_SEC = 0.15f
        /** Hands this much further apart than [NOCK_GAP] is a full draw. */
        const val FULL_DRAW = 0.55f
        /** Drawing hand flying back this fast (body units per second) is a release. */
        const val FLICK_V = 2.4f
        const val HOLD_SEC = 1.0f
        const val DEPTH_W = 0.6f
    }
}
