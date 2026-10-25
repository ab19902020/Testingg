package com.clrg.core.gesture

import com.clrg.core.math.OneEuroFilter
import com.clrg.core.math.Rng
import com.clrg.core.sim.Motions
import com.clrg.core.sim.Rig
import com.clrg.core.tracking.SmoothedPose
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Swings are stamped with the moment the hand moved fastest (where a racket meets the ball), whatever the swing
 * speed or camera frame rate, and are recognised soon after.
 */
class SwingTimingTest {
    private class Seen(val stampMs: Double, val detectMs: Double)

    /** One forehand of [dur] seconds; times relative to its fastest point. */
    private fun swing(fps: Float, dur: Float): Seen {
        val r = Rig(fps = fps)
        r.acquireAndLock()
        val p = r.p1
        r.seconds(0.8f) { Motions.swing(p, RIGHT, 0.9f, -0.5f, -0.2f, 0f) } // hand ready at the start of the swing
        r.clearEvents()
        val start = r.cam.tSec
        var detectedAt = Double.NaN
        r.seconds(dur + 0.4f) { t ->
            Motions.swing(p, RIGHT, 0.9f, -0.5f, -0.2f, (t / dur).toFloat())
            if (detectedAt.isNaN() && r.events[0].any { it is GestureEvent.Swing }) detectedAt = r.cam.tSec
        }
        val s = assertNotNull(r.events[0].filterIsInstance<GestureEvent.Swing>().singleOrNull(), "fps $fps dur $dur: one swing")
        val mid = start + dur / 2.0
        val detected = if (detectedAt.isNaN()) r.cam.tSec else detectedAt
        return Seen((s.t - mid) * 1000.0, (detected - mid) * 1000.0)
    }

    @Test
    fun swingTimeIsTheFastestMomentForQuickAndSlowSwings() {
        for (fps in floatArrayOf(30f, 60f)) for (dur in floatArrayOf(0.2f, 0.3f, 0.45f)) {
            val s = swing(fps, dur)
            println("SWING fps=${fps.toInt()} dur=${dur}s stamped %+.0f ms, recognised %+.0f ms after the fastest moment".format(s.stampMs, s.detectMs))
            assertTrue(abs(s.stampMs) <= 25.0, "fps $fps dur $dur: stamped ${s.stampMs} ms from the fastest moment")
            assertTrue(s.detectMs <= 110.0, "fps $fps dur $dur: recognised ${s.detectMs} ms after the fastest moment")
        }
    }

    @Test
    fun quickerSmoothingStaysSteadyWhenStill() {
        // the faster speed estimate (4 Hz) adds very little jitter to a hand held still
        fun rms(dCutoff: Float): Double {
            val f = OneEuroFilter(1.6f, 4.17f, dCutoff)
            val rng = Rng(3)
            var sum = 0.0; var n = 0
            for (i in 0 until 600) {
                val v = f.filter(0.5f + rng.gaussian() * 0.004f, 1f / 30f)
                if (i > 60) { sum += (v - 0.5) * (v - 0.5); n++ }
            }
            return sqrt(sum / n)
        }
        assertTrue(rms(SmoothedPose.D_CUTOFF) < rms(1f) * 1.15, "jitter ${rms(SmoothedPose.D_CUTOFF)} vs ${rms(1f)}")
    }
}
