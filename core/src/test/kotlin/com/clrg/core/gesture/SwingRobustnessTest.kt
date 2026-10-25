package com.clrg.core.gesture

import com.clrg.core.sim.Motions
import com.clrg.core.sim.Puppet
import com.clrg.core.sim.Rig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Swings as a real camera sees them: with a backswing first, a fast wrist that blurs out of the picture for a frame
 * or two (or the whole swing), a forehand swung at the TV (mostly towards the camera), and one-frame glitches that
 * must not count. All with simulated bodies - the shapes of the problems, not proof of real-camera performance.
 */
class SwingRobustnessTest {
    private fun Rig.swings() = events[0].filterIsInstance<GestureEvent.Swing>()

    /** Gets the right hand to (x, y) slowly and holds it there, then clears events. */
    private fun Rig.ready(x: Float, y: Float) {
        val p = p1
        val x0 = p.wristX[RIGHT]; val y0 = p.wristY[RIGHT]
        seconds(0.9f) { t -> val k = (t / 0.9).toFloat().coerceIn(0f, 1f); p.setWrist(RIGHT, x0 + (x - x0) * k, y0 + (y - y0) * k) }
        seconds(0.3f) { }
        clearEvents()
    }

    /** A smooth stroke of the right hand from (x0, y0, z0) to (x1, y1, z1) over [dur] s. */
    private fun Rig.stroke(p: Puppet, x0: Float, x1: Float, y: Float, dur: Float, z0: Float = 0f, z1: Float = 0f, during: (Double) -> Unit = {}) {
        seconds(dur) { t ->
            val u = (t / dur).coerceIn(0.0, 1.0)
            val e = ((1 - cos(PI * u)) / 2).toFloat()
            p.setWrist(RIGHT, x0 + (x1 - x0) * e, y)
            p.wristZ[RIGHT] = z0 + (z1 - z0) * e
            during(u)
        }
    }

    @Test
    fun aBackswingDoesNotSwallowTheRealSwing() {
        val r = Rig()
        r.acquireAndLock()
        r.ready(0.2f, -0.2f)
        // quick backswing out to the side, then the forehand across the body
        r.stroke(r.p1, 0.2f, 1.0f, -0.2f, 0.22f)
        val turn = r.cam.tSec
        r.stroke(r.p1, 1.0f, -0.5f, -0.2f, 0.26f)
        r.seconds(0.4f) { }
        val s = r.swings()
        assertTrue(s.size >= 2, "backswing and swing both seen: $s")
        val last = s.last()
        assertTrue(last.dirX < 0f && last.forehand, "the last one is the forehand")
        assertTrue(kotlin.math.abs(last.t - (turn + 0.13)) < 0.04, "stamped at the forehand's fastest moment: ${last.t - turn}")
    }

    @Test
    fun aWristThatBlursOutForTwoFramesStillCounts() {
        val r = Rig()
        r.acquireAndLock()
        r.ready(0.9f, -0.2f)
        r.stroke(r.p1, 0.9f, -0.5f, -0.2f, 0.28f) { u -> r.p1.wristVisibility[RIGHT] = if (u in 0.35..0.6) 0.05f else Float.NaN }
        r.p1.wristVisibility[RIGHT] = Float.NaN
        r.seconds(0.3f) { }
        assertEquals(1, r.swings().size, "swing across a 2-frame wrist dropout")
    }

    @Test
    fun theElbowCarriesASwingWhenTheWristIsNeverSeen() {
        val r = Rig()
        r.acquireAndLock()
        r.ready(0.9f, -0.2f)
        r.p1.wristVisibility[RIGHT] = 0.05f
        r.stroke(r.p1, 0.9f, -0.5f, -0.2f, 0.28f)
        r.seconds(0.3f) { }
        r.p1.wristVisibility[RIGHT] = Float.NaN
        val s = r.swings()
        assertEquals(1, s.size, "elbow-only swing: $s")
        assertEquals(1, r.controls[0].lastSwingSource, "seen by the elbow")
    }

    @Test
    fun aForehandSwungAtTheTvCountsFromDepth() {
        // mostly towards the camera: only 0.5 body units across, 1.5 towards the TV
        val flat = Rig()
        flat.acquireAndLock()
        flat.ready(0.6f, -0.2f)
        flat.stroke(flat.p1, 0.6f, 0.1f, -0.2f, 0.25f)
        flat.seconds(0.3f) { }
        assertEquals(0, flat.swings().size, "the same sideways movement without depth is too small")
        val r = Rig()
        r.acquireAndLock()
        r.ready(0.6f, -0.2f)
        r.p1.wristZ[RIGHT] = 0.6f
        r.stroke(r.p1, 0.6f, 0.1f, -0.2f, 0.25f, z0 = 0.6f, z1 = -0.9f)
        r.seconds(0.3f) { }
        assertEquals(1, r.swings().size, "swing towards the camera")
    }

    @Test
    fun aOneFrameJumpIsNotASwing() {
        val r = Rig()
        r.acquireAndLock()
        r.ready(0.3f, -0.4f)
        var i = 0
        r.seconds(1.0f) {
            i++
            // the model puts the wrist somewhere else for a single frame now and then
            if (i % 10 == 5) r.p1.setWrist(RIGHT, -0.8f, 0.4f) else r.p1.setWrist(RIGHT, 0.3f, -0.4f)
        }
        assertEquals(0, r.swings().size, "glitches: ${r.swings()}")
    }

    @Test
    fun aGentleSwingCountsWhenTheGameIsWaitingForOne() {
        fun gentle(boost: Float): Int {
            val r = Rig()
            r.acquireAndLock()
            r.ready(0.55f, -0.2f)
            r.controls[0].swingBoost = boost
            r.stroke(r.p1, 0.55f, 0.05f, -0.2f, 0.3f)
            r.seconds(0.3f) { }
            return r.swings().size
        }
        assertEquals(0, gentle(0f), "a small, slow movement is not a swing normally")
        assertEquals(1, gentle(1f), "but it is when a ball is arriving")
    }

    @Test
    fun aWristThatVanishesMidSwingCountsWhenExpected() {
        fun vanish(boost: Float): Int {
            val r = Rig()
            r.acquireAndLock()
            r.ready(0.9f, -0.2f)
            r.controls[0].swingBoost = boost
            // fast start, then the wrist and the elbow are lost (blur) and stay lost for a while
            r.stroke(r.p1, 0.9f, -0.5f, -0.2f, 0.3f) { u ->
                if (u > 0.4) { r.p1.wristVisibility[RIGHT] = 0.02f; r.p1.armVisibility = 0.02f }
            }
            r.seconds(0.2f) { }
            r.p1.wristVisibility[RIGHT] = Float.NaN; r.p1.armVisibility = Float.NaN
            return r.swings().size
        }
        assertEquals(1, vanish(1f), "counted from the fast start while a swing is expected")
    }

    @Test
    fun standingStillWithJitterAndBoostIsStillQuiet() {
        val r = Rig()
        r.acquireAndLock()
        r.cam.jitter = 0.012f
        r.controls[0].swingBoost = 1f
        r.seconds(4f) { }
        assertEquals(0, r.swings().size, "no swings from jitter: ${r.swings()}")
    }
}
