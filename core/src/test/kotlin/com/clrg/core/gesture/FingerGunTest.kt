package com.clrg.core.gesture

import com.clrg.core.sim.FakeHand
import com.clrg.core.sim.FingerShape
import com.clrg.core.sim.Rig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Finger guns with scripted (simulated) hands: shape reading, the thumb trigger, the recoil flick and the choice of
 * gun hand. Real hand photos are checked in RealModelHandTest; neither says how it behaves with a real camera.
 */
class FingerGunTest {
    private fun shapeOf(shape: FingerShape, thumb: Float): HandShape {
        val o = FakeHand.make(1, 0f, 0f, -1.2f, 0.2f, shape, thumb)
        return HandShape().apply { measure(FloatArray(63) { i -> when (i % 3) { 0 -> o.x[i / 3]; 1 -> o.y[i / 3]; else -> o.z[i / 3] } }) }
    }

    @Test
    fun fakeHandsReadLikeTheRealPhotos() {
        val gunUp = shapeOf(FingerShape.GUN, 1f)
        val gunDown = shapeOf(FingerShape.GUN, 0f)
        assertTrue(gunUp.gun && gunDown.gun)
        // the same thumb levels as the real hand model gives on real photos (HandProbe): ~0.2-0.35 down, ~1-1.5 up
        assertTrue(gunUp.thumbLevel > 0.85f, "raised thumb ${gunUp.thumbLevel}")
        assertTrue(gunDown.thumbLevel < 0.4f, "dropped thumb ${gunDown.thumbLevel}")
        assertFalse(shapeOf(FingerShape.FIST, 1f).gun)
        assertFalse(shapeOf(FingerShape.OPEN, 1f).gun)
    }

    /** Right hand held out in front, pointing at the TV. */
    private fun aimPose(r: Rig, x: Float = 0.3f, y: Float = 0.05f) {
        r.p1.setWrist(1, x, y)
        r.p1.wristZ[1] = -0.8f
    }

    private fun shots(r: Rig) = r.events[0].filterIsInstance<GestureEvent.Shot>()

    @Test
    fun droppingTheThumbFiresOncePerClick() {
        val r = Rig()
        r.acquireAndLock()
        aimPose(r)
        r.p1.fingers[1] = FingerShape.GUN
        r.p1.thumb[1] = 1f
        r.seconds(0.6f)
        assertTrue(r.controls[0].gunCtl.gun[1], "gun seen")
        assertEquals(RIGHT, r.controls[0].gunCtl.gunHand)
        r.clearEvents()
        val clickTimes = ArrayList<Double>()
        repeat(3) {
            // drop the thumb over 0.1 s, hold it down, raise it again
            val t0 = r.cam.tSec
            r.seconds(0.1f) { t -> r.p1.thumb[1] = (1f - (t / 0.1).toFloat()).coerceIn(0f, 1f) }
            clickTimes.add(t0 + 0.05) // halfway down
            r.seconds(0.4f)
            r.seconds(0.15f) { t -> r.p1.thumb[1] = (t / 0.15).toFloat().coerceIn(0f, 1f) }
            r.seconds(0.4f)
        }
        val s = shots(r)
        assertEquals(3, s.size, "one shot per click: $s")
        assertTrue(s.all { it.byThumb && it.hand == RIGHT })
        for (i in 0 until 3) assertTrue(abs(s[i].t - clickTimes[i]) < 0.04, "shot stamped at the thumb drop: ${s[i].t} vs ${clickTimes[i]}")
        // aim: where the hand was (reach units)
        val reach = r.controls[0].calibration!!.reach
        assertTrue(abs(s[0].aimX - r.controls[0].gunCtl.aimX[1]) < 0.05f && abs(s[0].aimY - r.controls[0].gunCtl.aimY[1]) < 0.05f, "aim ${s[0].aimX},${s[0].aimY} reach $reach")
    }

    @Test
    fun thumbHeldDownOrAFistNeverFires() {
        val r = Rig()
        r.acquireAndLock()
        aimPose(r)
        r.p1.fingers[1] = FingerShape.GUN
        r.p1.thumb[1] = 0f
        r.seconds(2f)
        assertEquals(0, shots(r).size, "thumb never raised")
        // a fist opening and closing its thumb is not a gun
        r.p1.fingers[1] = FingerShape.FIST
        repeat(3) {
            r.seconds(0.3f) { r.p1.thumb[1] = 1f }
            r.seconds(0.3f) { r.p1.thumb[1] = 0f }
        }
        assertEquals(0, shots(r).size, "a fist does not fire")
        // a steady cocked gun with camera jitter does not fire by itself
        r.p1.fingers[1] = FingerShape.GUN
        r.p1.thumb[1] = 1f
        r.cam.jitter = 0.006f
        r.seconds(5f)
        assertEquals(0, shots(r).size, "jitter does not fire")
    }

    @Test
    fun recoilFlickFiresWithoutFingers() {
        val r = Rig()
        r.acquireAndLock()
        aimPose(r, 0.3f, 0.0f)
        r.seconds(0.8f)
        r.clearEvents()
        // quick kick up and back down
        val t0 = r.cam.tSec
        r.seconds(0.1f) { t -> aimPose(r, 0.3f, (0.25 * t / 0.1).toFloat()) }
        r.seconds(0.15f) { t -> aimPose(r, 0.3f, (0.25 * (1 - t / 0.15)).toFloat().coerceAtLeast(0f)) }
        r.seconds(0.6f)
        val s = shots(r)
        assertEquals(1, s.size, "one recoil shot: $s")
        assertFalse(s[0].byThumb)
        assertTrue(s[0].t <= t0 + 0.07, "stamped when the kick started")
        assertTrue(abs(s[0].aimY) < 0.08f, "aim from before the kick: ${s[0].aimY}")
        // slowly moving the aim up is not a shot
        r.clearEvents()
        r.seconds(1.5f) { t -> aimPose(r, 0.3f, (0.4 * t / 1.5).toFloat()) }
        assertEquals(0, shots(r).size, "slow aiming")
        // raising the arm from the side at normal speed is not a shot either
        r.p1.relaxArms(); r.seconds(0.8f); r.clearEvents()
        r.seconds(0.5f) { t -> r.p1.setWrist(1, 0.42f - 0.1f * (t / 0.5).toFloat(), -1.05f + 1.1f * (t / 0.5).toFloat()) }
        r.seconds(0.5f)
        assertEquals(0, shots(r).size, "raising the arm")
    }

    @Test
    fun theGunHandIsTheOneMakingTheGun() {
        val r = Rig()
        r.acquireAndLock()
        r.controls[0].wantFingers = true
        r.p1.setWrist(0, -0.3f, 0.05f); r.p1.fingers[0] = FingerShape.GUN
        r.p1.setWrist(1, 0.35f, -0.4f); r.p1.fingers[1] = FingerShape.OPEN
        r.seconds(0.6f)
        assertEquals(LEFT, r.controls[0].gunCtl.gunHand)
        // the finger tracker is asked for the gun hand every frame, the other (raised) hand on alternate frames
        val m = r.controls[0].fingerMask
        assertTrue(m and 1 != 0, "left every frame: $m")
        assertTrue(m and 8 != 0, "right on alternate frames: $m")
        r.controls[0].wantFingers = false
        assertEquals(0, r.controls[0].fingerMask)
    }
}
