package com.clrg.core.gesture

import com.clrg.core.sim.FingerShape
import com.clrg.core.sim.Rig
import com.clrg.core.tracking.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bow gesture with a scripted (simulated) body: nock, draw, release, let down. Not evidence of camera behaviour. */
class BowTest {
    /** Right-handed archer: left (bow) hand out in front, right hand at [drawX], [drawY]. */
    private fun pose(r: Rig, drawX: Float, drawY: Float) {
        r.p1.setWrist(LEFT, -0.2f, 0.05f); r.p1.wristZ[LEFT] = -0.9f
        r.p1.setWrist(RIGHT, drawX, drawY)
        // the drawing hand comes back from the bow hand (far forward) towards the face
        val k = ((drawX + 0.12f) / 0.62f).coerceIn(0f, 1.3f)
        r.p1.wristZ[RIGHT] = -0.9f + 0.8f * k
    }

    private fun releases(r: Rig) = r.events[0].filterIsInstance<GestureEvent.Release>()

    private fun nockAndDraw(r: Rig, sec: Float = 0.8f, to: Float = 1f) {
        pose(r, -0.12f, 0.05f) // hands together
        r.seconds(0.4f)
        assertEquals(BowDetector.State.NOCKED, r.controls[0].bow.state, "nocked")
        r.seconds(sec) { t -> val k = (t / sec).toFloat().coerceIn(0f, 1f) * to; pose(r, -0.12f + 0.62f * k, 0.05f + 0.2f * k) }
    }

    @Test
    fun drawThenFlickBackReleases() {
        val r = Rig()
        r.acquireAndLock()
        nockAndDraw(r)
        assertEquals(BowDetector.State.DRAWING, r.controls[0].bow.state)
        assertTrue(r.controls[0].bow.draw > 0.85f, "full draw ${r.controls[0].bow.draw}")
        r.seconds(0.6f) // hold and aim
        assertEquals(0, releases(r).size, "holding still does not release (standard mode)")
        // let go: the string hand flies back past the face
        r.seconds(0.08f) { t -> pose(r, 0.5f + 0.4f * (t / 0.08).toFloat(), 0.25f + 0.05f * (t / 0.08).toFloat()) }
        r.seconds(0.3f)
        val rel = releases(r)
        assertEquals(1, rel.size, "$rel")
        assertTrue(rel[0].power > 0.8f, "full-power arrow: ${rel[0].power}")
        assertEquals(LEFT, rel[0].hand, "aimed with the bow hand")
        assertEquals(BowDetector.State.IDLE, r.controls[0].bow.state)
    }

    @Test
    fun openingTheFingersReleases() {
        val r = Rig()
        r.acquireAndLock()
        r.p1.fingers[RIGHT] = FingerShape.FIST
        nockAndDraw(r, to = 0.6f)
        r.seconds(0.3f)
        assertEquals(0, releases(r).size)
        r.p1.fingers[RIGHT] = FingerShape.OPEN
        r.seconds(0.2f)
        val rel = releases(r)
        assertEquals(1, rel.size, "open hand lets go")
        assertTrue(rel[0].power in 0.3f..0.7f, "partial draw power ${rel[0].power}")
    }

    @Test
    fun bringingTheHandsBackPutsTheArrowDown() {
        val r = Rig()
        r.acquireAndLock()
        nockAndDraw(r, to = 0.7f)
        r.seconds(0.8f) { t -> val k = 1f - (t / 0.8).toFloat().coerceIn(0f, 1f); pose(r, -0.12f + 0.62f * 0.7f * k, 0.05f + 0.14f * k) }
        r.seconds(0.5f)
        assertEquals(0, releases(r).size, "slowly letting down is not a shot")
        assertTrue(r.controls[0].bow.state != BowDetector.State.DRAWING)
    }

    @Test
    fun seatedArcherReleasesByHoldingStill() {
        val r = Rig()
        r.acquireAndLock(MovementMode.SEATED)
        nockAndDraw(r, to = 0.7f)
        r.seconds(1.5f)
        assertEquals(1, releases(r).size, "a steady draw lets go by itself")
    }

    @Test
    fun theDrawingHandsFingersAreTracked() {
        val r = Rig()
        r.acquireAndLock()
        r.controls[0].wantFingers = true
        nockAndDraw(r, to = 0.5f)
        val m = r.controls[0].fingerMask
        assertTrue(m and (1 shl RIGHT) != 0, "drawing hand every frame: $m")
    }
}
