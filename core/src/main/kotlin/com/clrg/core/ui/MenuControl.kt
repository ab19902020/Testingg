package com.clrg.core.ui

import com.clrg.core.gesture.PlayerControls
import kotlin.math.abs

/**
 * Who steers the menus with a hand: Player 1 - whoever stands in the red (left-hand) player area - after waving at
 * the screen to take control. Nobody else gets a pointer on the menus, and a hand that happens to be raised (someone
 * stretching, pointing at the TV to talk about it) does nothing until its owner has waved.
 *
 * A wave is a raised hand moving side to side: three changes of direction, each a clear swing (not tracking jitter),
 * in quick succession. Control is kept while Player 1 is in front of the camera; it is given up when they leave for a
 * moment or keep their hands down for a while, and taken back with another wave.
 */
class MenuControl {
    /** Player 1 has waved and steers the menus. */
    var engaged = false
        private set
    /** 0..1: how far through a wave the player is (for the on-screen prompt). */
    var waveProgress = 0f
        private set
    /** Seconds since control was taken (large when not engaged); for a "you have control" moment on screen. */
    var sinceEngaged = 99f
        private set

    private val extreme = FloatArray(2) { Float.NaN }
    private val dir = IntArray(2)
    private val turns = IntArray(2)
    private val lastTurnT = DoubleArray(2) { -9.0 }
    private var time = 0.0
    private var downSec = 0f
    private var awaySec = 0f

    fun release() {
        engaged = false; waveProgress = 0f; sinceEngaged = 99f; downSec = 0f
        for (h in 0..1) resetHand(h)
    }

    /** For tests and touch fallbacks: Player 1 has control without waving. */
    fun grant() { engaged = true; sinceEngaged = 0f; downSec = 0f; awaySec = 0f; waveProgress = 1f }

    private fun resetHand(h: Int) { extreme[h] = Float.NaN; dir[h] = 0; turns[h] = 0 }

    /**
     * One display frame with Player 1's controls. [tracked]: Player 1 is followed by the camera.
     */
    fun update(ctl: PlayerControls, tracked: Boolean, dt: Float) {
        time += dt
        sinceEngaged += dt
        if (!tracked || !ctl.hasPose) {
            awaySec += dt
            if (awaySec > AWAY_SEC) release()
            for (h in 0..1) resetHand(h)
            waveProgress = 0f
            return
        }
        awaySec = 0f
        if (engaged) {
            val up = (0..1).any { ctl.pointSeen[it] && ctl.pointY[it] > HandPointer.OFF_Y }
            downSec = if (up) 0f else downSec + dt
            if (downSec > IDLE_SEC) release()
            return
        }
        var best = 0
        for (h in 0..1) {
            if (!ctl.pointSeen[h] || ctl.pointY[h] < WAVE_MIN_Y) { resetHand(h); continue }
            val x = ctl.pointX[h]
            if (extreme[h].isNaN()) { extreme[h] = x; dir[h] = 0; continue }
            if (time - lastTurnT[h] > TURN_GAP_SEC) turns[h] = 0
            when {
                dir[h] == 0 -> if (abs(x - extreme[h]) > SWING) { dir[h] = if (x > extreme[h]) 1 else -1; extreme[h] = x; lastTurnT[h] = time }
                (x - extreme[h]) * dir[h] > 0f -> extreme[h] = x // still going the same way: the swing gets wider
                abs(x - extreme[h]) > SWING -> {
                    // turned back by a clear swing: one change of direction
                    dir[h] = -dir[h]; extreme[h] = x
                    turns[h]++; lastTurnT[h] = time
                }
            }
            best = maxOf(best, turns[h])
        }
        waveProgress = (best / TURNS.toFloat()).coerceIn(0f, 1f)
        if (best >= TURNS) {
            engaged = true; sinceEngaged = 0f; downSec = 0f
            for (h in 0..1) resetHand(h)
        }
    }

    companion object {
        /** The waving hand must be at least this high (reach units, 0 = shoulders): around the chest or above. */
        const val WAVE_MIN_Y = -0.35f
        /** One swing of a wave: this far (reach units) before turning back. */
        const val SWING = 0.09f
        /** Changes of direction that make a wave, each within [TURN_GAP_SEC] of the last. */
        const val TURNS = 3
        const val TURN_GAP_SEC = 0.9
        /** Control is given up after this long with hands down, or this long out of view. */
        const val IDLE_SEC = 12f
        const val AWAY_SEC = 2f
    }
}
