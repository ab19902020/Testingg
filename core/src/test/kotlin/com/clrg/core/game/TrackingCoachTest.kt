package com.clrg.core.game

import com.clrg.core.tracking.TrackingQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The in-game tracking tip: shown only for weak tracking that lasts, gone once tracking is good again. */
class TrackingCoachTest {
    private val weak = TrackingQuality(0.3f, "Weak", "Keep both hands in the picture")
    private val good = TrackingQuality(0.9f, "Excellent", null)
    private val dt = 1f / 60f

    private fun run(c: TrackingCoach, k: Int, q: TrackingQuality?, sec: Float) = repeat((sec / dt).toInt()) { c.update(k, q, dt) }

    @Test
    fun aLastingProblemShowsATipThatGoesWhenFixed() {
        val c = TrackingCoach()
        run(c, 0, weak, 1.5f)
        assertNull(c.tip(0), "not straight away")
        run(c, 0, weak, 0.6f)
        assertEquals("Keep both hands in the picture", c.tip(0))
        run(c, 0, good, 0.5f)
        assertEquals("Keep both hands in the picture", c.tip(0), "stays a moment (no flicker)")
        run(c, 0, good, 0.6f)
        assertNull(c.tip(0), "gone once tracking is good")
    }

    @Test
    fun shortDipsAndComputerPlayersNeverShowATip() {
        val c = TrackingCoach()
        repeat(10) { run(c, 1, weak, 0.5f); run(c, 1, good, 0.3f) }
        assertNull(c.tip(1), "a blurred hand for a moment is not a problem to tell anyone about")
        run(c, 0, null, 5f)
        assertNull(c.tip(0), "computer players get no tips")
    }
}
