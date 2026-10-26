package com.clrg.core.ui

import com.clrg.core.gesture.LEFT
import com.clrg.core.gesture.PlayerControls
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.OneEuroFilter

/**
 * Turns one player's hand into a steady, easy-to-aim screen pointer.
 *
 *  - **Comfortable box per hand** (like a touch pad in the air): the right hand's box sits in front of the right
 *    side of the chest, the left hand's in front of the left. Small, relaxed movements cover the whole screen - no
 *    stretching across the body for the far edge, and the bottom of the screen is at chest height, well above the
 *    point where the pointer switches off.
 *  - **On / off with a margin**: the pointer comes on when a hand is lifted to the lower chest and only goes off
 *    when it is clearly lowered again, so it never flickers at the edge. A hand that is lost by the camera for a
 *    moment keeps its pointer where it was.
 *  - **Steady when still, quick when moving**: an adaptive filter smooths away tracking jitter while the hand is
 *    held still (so holding over a button is easy) but follows a deliberate movement straight away. It uses the
 *    measured hand, not the forward-predicted one used for the characters, so it never overshoots.
 *  - **Sticks to the hand in use**: the other hand only takes over when it is raised clearly higher.
 */
class HandPointer {
    /** Hand driving the pointer (LEFT / RIGHT), -1 when none. */
    var hand = -1
        private set
    var x = Float.NaN
        private set
    var y = Float.NaN
        private set
    /** The pointer can select (hand raised). */
    var active = false
        private set
    /** A hand is seen (the pointer is drawn, faded when not [active]). */
    var visible = false
        private set
    private val fx = OneEuroFilter(MIN_CUTOFF, BETA, D_CUTOFF)
    private val fy = OneEuroFilter(MIN_CUTOFF, BETA, D_CUTOFF)
    private var lostSec = 0f

    fun reset() {
        hand = -1; active = false; visible = false; x = Float.NaN; y = Float.NaN; lostSec = 0f
        fx.reset(); fy.reset()
    }

    /** Screen position (virtual px) that hand [h] at reach-space point ([rx], [ry]) points to. */
    fun target(h: Int, rx: Float, ry: Float, w: Float, hgt: Float): Pair<Float, Float> {
        val side = if (h == RIGHT) 1f else -1f
        val u = (rx - side * BOX_CX) / BOX_HALF_W
        val v = (ry - BOX_CY) / BOX_HALF_H
        val tx = (w / 2f + u * w / 2f * EDGE_GAIN).coerceIn(0f, w)
        val ty = (hgt / 2f - v * hgt / 2f * EDGE_GAIN).coerceIn(0f, hgt)
        return tx to ty
    }

    /** The reach-space point (rx, ry) at which hand [h] points to screen point ([sx], [sy]) - the inverse of [target]. */
    fun reachFor(h: Int, sx: Float, sy: Float, w: Float, hgt: Float): Pair<Float, Float> {
        val side = if (h == RIGHT) 1f else -1f
        val u = (sx - w / 2f) / (w / 2f * EDGE_GAIN)
        val v = (hgt / 2f - sy) / (hgt / 2f * EDGE_GAIN)
        return (side * BOX_CX + u * BOX_HALF_W) to (BOX_CY + v * BOX_HALF_H)
    }

    /**
     * One display frame. [tracked]: the player is being followed by the camera. Reads the measured (not predicted)
     * hand positions from [ctl].
     */
    fun update(ctl: PlayerControls, tracked: Boolean, w: Float, hgt: Float, dt: Float) {
        if (!tracked || !ctl.hasPose) { reset(); return }
        val seen = BooleanArray(2) { ctl.pointSeen[it] }
        // which hand: keep the one in use while it stays up; otherwise the higher raised hand
        val cur = hand
        val curUp = cur >= 0 && seen[cur] && ctl.pointY[cur] > (if (active) OFF_Y else ON_Y)
        var pick = cur
        if (cur >= 0 && !seen[cur] && active) {
            // the pointing hand is lost for a moment (blur, out of shot): hold the pointer unless the other hand is up
            val other = 1 - cur
            if (seen[other] && ctl.pointY[other] > ON_Y) pick = other
            else {
                lostSec += dt
                if (lostSec > HOLD_LOST_SEC) { active = false; visible = false; hand = -1 }
                return
            }
        } else if (!curUp) {
            pick = -1
            for (h in intArrayOf(RIGHT, LEFT)) if (seen[h] && (pick < 0 || ctl.pointY[h] > ctl.pointY[pick])) pick = h
        } else {
            val other = 1 - cur
            if (seen[other] && ctl.pointY[other] > ON_Y && ctl.pointY[other] > ctl.pointY[cur] + SWITCH_MARGIN) pick = other
        }
        if (pick < 0) {
            // no hand seen: hold the pointer where it was for a moment (a blurred or hidden hand), then let go
            lostSec += dt
            if (lostSec > HOLD_LOST_SEC) { active = false; visible = false }
            return
        }
        lostSec = 0f
        if (pick != hand) {
            hand = pick
            fx.reset(); fy.reset()
        }
        val ry = ctl.pointY[hand]
        active = if (active) ry > OFF_Y else ry > ON_Y
        // a resting arm shows no pointer; a hand on its way up shows a faded one
        visible = active || ry > GHOST_Y
        val (tx, ty) = target(hand, ctl.pointX[hand], ry, w, hgt)
        val step = dt.coerceIn(1f / 240f, 0.1f)
        val nx = fx.filter(tx, step)
        val ny = fy.filter(ty, step)
        if (x.isNaN() || kotlin.math.hypot(nx - x, ny - y) > STILL_PX) { x = nx; y = ny }
    }

    companion object {
        /** Box centre for the right hand, in reach units from the shoulder centre (x to the player's right, y up). */
        const val BOX_CX = 0.28f
        const val BOX_CY = -0.05f
        /** Half sizes of the box (reach units): the screen's width and height. */
        const val BOX_HALF_W = 0.62f
        const val BOX_HALF_H = 0.45f
        /** A little beyond the box still reaches the very edge of the screen. */
        const val EDGE_GAIN = 1.06f
        /** Pointer on when the hand rises above this (reach units), off only below [OFF_Y]. A relaxed arm hangs at about -0.7. */
        const val ON_Y = -0.58f
        const val OFF_Y = -0.68f
        /** The other hand takes over when it is this much higher (reach units). */
        const val SWITCH_MARGIN = 0.22f
        /** A faded pointer shows from this hand height (reach units), just below where it switches on. */
        const val GHOST_Y = -0.66f
        const val HOLD_LOST_SEC = 0.3f
        /** Movements smaller than this (virtual px) are jitter: the pointer stays put. */
        const val STILL_PX = 3f
        /** Adaptive filter: steady when still (cutoff Hz), opening up with speed (per px/s). */
        const val MIN_CUTOFF = 1.4f
        const val BETA = 0.006f
        const val D_CUTOFF = 1.2f
    }
}
