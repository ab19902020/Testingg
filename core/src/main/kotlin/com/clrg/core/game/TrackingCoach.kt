package com.clrg.core.game

import com.clrg.core.tracking.TrackingQuality

/**
 * During play, tells a player why their moves may not be picked up: when their tracking stays weak for a couple of
 * seconds (hands leaving the picture, standing too close or too far, a dark room), the single most useful tip is
 * shown on their side of the screen until tracking is good again. Short dips (a hand blurred by a fast swing) never
 * show anything.
 */
class TrackingCoach {
    private val weakSec = FloatArray(2)
    private val goodSec = FloatArray(2)
    private val shown = arrayOfNulls<String>(2)

    fun reset() { weakSec.fill(0f); goodSec.fill(0f); shown.fill(null) }

    /** One frame for player [k]: [q] is their current tracking quality, null for a computer player. */
    fun update(k: Int, q: TrackingQuality?, dt: Float) {
        val weak = q != null && q.tip != null && q.score < WEAK_SCORE
        if (weak) { weakSec[k] += dt; goodSec[k] = 0f } else { goodSec[k] += dt; weakSec[k] = 0f }
        if (weak && weakSec[k] >= SHOW_AFTER_SEC) shown[k] = q!!.tip
        if (!weak && goodSec[k] >= HIDE_AFTER_SEC) shown[k] = null
    }

    /** The tip to show for player [k] now, or null. */
    fun tip(k: Int): String? = shown[k]

    companion object {
        /** Tracking scored below this (0..1, see [TrackingQuality]) counts as weak. */
        const val WEAK_SCORE = 0.5f
        /** Weak for this long before a tip appears; good for this long before it goes. */
        const val SHOW_AFTER_SEC = 2f
        const val HIDE_AFTER_SEC = 1f
    }
}
