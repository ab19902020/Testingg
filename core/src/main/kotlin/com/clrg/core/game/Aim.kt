package com.clrg.core.game

import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.OneEuroFilter
import com.clrg.core.tracking.MovementMode
import kotlin.math.hypot

/**
 * One player's crosshair for aiming games. The gun hand works like a small touch pad held in the air: moving it a
 * hand's width or so covers the whole screen, which is steadier and less tiring than pointing the exact direction.
 * "Straight ahead" is wherever that player pointed when they took their first shot (see [recentre]), so each player
 * aims from their own comfortable position.
 *
 * Positions are in *view units*: 0, 0 = screen centre, 540 = half the screen height, y down. A short history lets a
 * game judge a shot by where the crosshair was when the player fired, not where it is when the shot reaches it.
 */
class AimCursor {
    var x = 0f
        private set
    var y = 0f
        private set
    /** The player's gun hand is seen. */
    var seen = false
        private set
    /** Aim (reach units) that means "screen centre"; NaN until set. */
    var centreX = Float.NaN
        private set
    var centreY = Float.NaN
        private set
    private val fx = OneEuroFilter(MIN_CUTOFF, BETA, D_CUTOFF)
    private val fy = OneEuroFilter(MIN_CUTOFF, BETA, D_CUTOFF)
    private val hT = DoubleArray(HIST)
    private val hX = FloatArray(HIST)
    private val hY = FloatArray(HIST)
    private var hN = 0
    private var lostSec = 0f
    /** Filtered hand position (before aim assist) and the assist offset added to it. */
    private var bx = 0f
    private var by = 0f
    private var offX = 0f
    private var offY = 0f

    fun reset() { seen = false; fx.reset(); fy.reset(); hN = 0; centreX = Float.NaN; centreY = Float.NaN; x = 0f; y = 0f; bx = 0f; by = 0f; offX = 0f; offY = 0f }

    /** Half the screen width in view units for a [width] x 1080 screen. */
    fun halfWidth(width: Float) = width / 2f

    private fun gainX(mode: MovementMode) = 540f * ASPECT / (if (mode == MovementMode.STANDARD) BOX_HALF_W else BOX_HALF_W * GENTLE_BOX)
    private fun gainY(mode: MovementMode) = 540f / (if (mode == MovementMode.STANDARD) BOX_HALF_H else BOX_HALF_H * GENTLE_BOX)

    /** Default "straight ahead" for the gun hand: in front of that shoulder, at shoulder height. */
    private fun defaultCentre(hand: Int) = (if (hand == RIGHT) 0.15f else -0.15f) to -0.02f

    /** Makes the gun hand's current aim the screen centre. */
    fun recentre(inp: PlayerInput) {
        val h = inp.gunHand
        if (!inp.aimSeen[h]) return
        centreX = inp.aimX[h]; centreY = inp.aimY[h]
    }

    /** Sets the centre from a shot's aim (the moment a player fires at the centre target). */
    fun recentre(aimX: Float, aimY: Float) { centreX = aimX; centreY = aimY }

    /** Where the crosshair would be for gun-hand aim ([ax], [ay]) - unfiltered. */
    fun map(inp: PlayerInput, ax: Float, ay: Float): Pair<Float, Float> {
        val (dx0, dy0) = defaultCentre(inp.gunHand)
        val cx = if (centreX.isNaN()) dx0 else centreX
        val cy = if (centreY.isNaN()) dy0 else centreY
        val lim = 540f * ASPECT * 1.02f
        return ((ax - cx) * gainX(inp.mode)).coerceIn(-lim, lim) to (-(ay - cy) * gainY(inp.mode)).coerceIn(-560f, 560f)
    }

    /** The gun-hand aim (reach units) that puts the crosshair at ([sx], [sy]) - the inverse of [map]. */
    fun aimFor(inp: PlayerInput, sx: Float, sy: Float): Pair<Float, Float> {
        val (dx0, dy0) = defaultCentre(inp.gunHand)
        val cx = if (centreX.isNaN()) dx0 else centreX
        val cy = if (centreY.isNaN()) dy0 else centreY
        return (cx + sx / gainX(inp.mode)) to (cy - sy / gainY(inp.mode))
    }

    /** One simulation step at game time [time]. */
    fun update(inp: PlayerInput, time: Double, dt: Float) {
        val h = inp.gunHand
        if (!inp.tracked || !inp.aimSeen[h]) {
            lostSec += dt
            if (lostSec > 0.4f) seen = false
            push(time)
            return
        }
        lostSec = 0f
        val (tx, ty) = map(inp, inp.aimX[h], inp.aimY[h])
        val nx = fx.filter(tx, dt.coerceAtLeast(1f / 240f))
        val ny = fy.filter(ty, dt.coerceAtLeast(1f / 240f))
        if (!seen || hypot(nx - bx, ny - by) > STILL) { bx = nx; by = ny }
        x = bx + offX; y = by + offY
        seen = true
        push(time)
    }

    /** Puts the crosshair at ([px], [py]) directly (computer players). */
    fun set(px: Float, py: Float, time: Double) { bx = px; by = py; x = px + offX; y = py + offY; seen = true; push(time) }

    /** Where the hand alone puts the crosshair (without aim assist). */
    val handX: Float get() = bx
    val handY: Float get() = by

    /** Aim assist: the crosshair sits ([dx], [dy]) from where the hand puts it (until changed). */
    fun assist(dx: Float, dy: Float) {
        offX = dx; offY = dy
        x = bx + offX; y = by + offY
        if (hN > 0) { val i = (hN - 1) % HIST; hX[i] = x; hY[i] = y }
    }

    private fun push(time: Double) {
        val i = hN % HIST
        hT[i] = time; hX[i] = x; hY[i] = y; hN++
    }

    /** The crosshair at game time [time] (the newest record at or before it). */
    fun at(time: Double): Pair<Float, Float> {
        val n = minOf(hN, HIST)
        var best = -1; var bestT = Double.NEGATIVE_INFINITY; var oldest = -1; var oldestT = Double.POSITIVE_INFINITY
        for (k in 0 until n) {
            val t = hT[k]
            if (t <= time && t > bestT) { best = k; bestT = t }
            if (t < oldestT) { oldest = k; oldestT = t }
        }
        val i = if (best >= 0) best else oldest
        return if (i < 0) x to y else hX[i] to hY[i]
    }

    companion object {
        const val ASPECT = 16f / 9f
        /** Hand travel (reach units) from the centre to the side / top edge of the screen. */
        const val BOX_HALF_W = 0.34f
        const val BOX_HALF_H = 0.22f
        /** Reduced-movement and seated players: smaller movements cover the screen. */
        const val GENTLE_BOX = 0.7f
        const val MIN_CUTOFF = 1.1f
        const val BETA = 0.006f
        const val D_CUTOFF = 1.0f
        const val STILL = 2.5f
        const val HIST = 120
    }
}
