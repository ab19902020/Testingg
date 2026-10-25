package com.clrg.core.gesture

import com.clrg.core.sim.Rig
import com.clrg.core.tracking.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Punch recognition with scripted (simulated) movement - not evidence of how it works with a real camera. */
class PunchTest {
    private fun guard(r: Rig) {
        r.p1.setWrist(0, -0.18f, 0.25f); r.p1.wristZ[0] = -0.25f
        r.p1.setWrist(1, 0.18f, 0.25f); r.p1.wristZ[1] = -0.25f
    }

    private fun punches(r: Rig) = r.events[0].filterIsInstance<GestureEvent.Punch>()

    private fun move(r: Rig, h: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, sec: Float) {
        r.seconds(sec) { t ->
            val k = (t / sec).toFloat().coerceIn(0f, 1f)
            val e = k * k * (3 - 2 * k)
            r.p1.setWrist(h, x0 + (x1 - x0) * e, y0 + (y1 - y0) * e)
            r.p1.wristZ[h] = z0 + (z1 - z0) * e
        }
    }

    private fun ready(): Rig {
        val r = Rig()
        r.acquireAndLock()
        guard(r)
        r.seconds(0.6f)
        r.clearEvents()
        return r
    }

    @Test
    fun guardIsSeen() {
        val r = ready()
        assertTrue(r.controls[0].punch.guard)
        r.p1.relaxArms(); r.seconds(0.3f)
        assertTrue(!r.controls[0].punch.guard)
    }

    @Test
    fun straightPunchTowardsTheCamera() {
        val r = ready()
        move(r, 1, 0.18f, 0.25f, -0.25f, 0.08f, 0.08f, -1.25f, 0.16f)
        r.seconds(0.2f)
        val p = punches(r)
        assertEquals(1, p.size, "$p")
        assertEquals(PunchKind.STRAIGHT, p[0].kind)
        assertEquals(RIGHT, p[0].hand)
        // pulling it back is not another punch
        r.clearEvents()
        move(r, 1, 0.08f, 0.08f, -1.25f, 0.18f, 0.25f, -0.25f, 0.2f)
        r.seconds(0.3f)
        assertEquals(0, punches(r).size, "retracting")
    }

    @Test
    fun hookAndUppercut() {
        val r = ready()
        // hook: the right fist pulls back to the side (wind-up), then comes round across the face
        move(r, 1, 0.18f, 0.25f, -0.25f, 0.75f, 0.2f, -0.2f, 0.3f)
        move(r, 1, 0.75f, 0.2f, -0.2f, -0.05f, 0.25f, -0.5f, 0.2f)
        r.seconds(0.25f)
        assertEquals(listOf(PunchKind.HOOK), punches(r).map { it.kind })
        guard(r); r.seconds(0.6f); r.clearEvents()
        // uppercut: the left fist drives up from below the chest
        r.p1.setWrist(0, -0.2f, -0.55f); r.seconds(0.6f); r.clearEvents()
        move(r, 0, -0.2f, -0.55f, -0.3f, -0.12f, 0.4f, -0.45f, 0.18f)
        r.seconds(0.25f)
        assertEquals(listOf(PunchKind.UPPERCUT), punches(r).map { it.kind })
    }

    @Test
    fun slowMovesAndWavingDoNotPunch() {
        val r = ready()
        move(r, 1, 0.18f, 0.25f, -0.25f, 0.08f, 0.08f, -1.2f, 1.2f) // slow reach forward
        move(r, 1, 0.08f, 0.08f, -1.2f, 0.6f, 0.3f, -0.2f, 1.0f)
        r.cam.jitter = 0.006f
        r.seconds(3f)
        assertEquals(0, punches(r).size, "${punches(r)}")
    }

    @Test
    fun gentleModeTakesSmallerPunches() {
        val r = Rig()
        r.acquireAndLock(MovementMode.REDUCED)
        guard(r); r.seconds(0.6f); r.clearEvents()
        move(r, 1, 0.18f, 0.25f, -0.25f, 0.12f, 0.15f, -0.75f, 0.2f)
        r.seconds(0.25f)
        assertEquals(1, punches(r).size, "small, slower punch in reduced mode")
    }
}
