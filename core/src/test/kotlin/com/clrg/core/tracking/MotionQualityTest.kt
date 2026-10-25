package com.clrg.core.tracking

import com.clrg.core.pose.Lm
import com.clrg.core.pose.PersonPose
import com.clrg.core.sim.Rig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Motion-capture refinements: size-independent smoothing, glitch rejection, latency compensation, body extras. */
class MotionQualityTest {
    private fun pose(scale: Float, wristX: Float): PersonPose {
        val n = Lm.COUNT
        val p = PersonPose(FloatArray(n) { 0.5f }, FloatArray(n) { 0.5f }, FloatArray(n), FloatArray(n) { 0.95f }, FloatArray(n) { 0.95f }, 0.95f, 1, null)
        p.x[Lm.LEFT_WRIST] = 0.5f + wristX * scale
        return p
    }

    /** Lag (in body lengths) of the smoothed wrist while it moves at 3 body lengths per second. */
    private fun lagBodyUnits(scale: Float): Float {
        val s = SmoothedPose()
        val dt = 1f / 30f
        var x = 0f
        repeat(30) { s.update(pose(scale, x), dt, scale); x += 3f * dt }
        return (0.5f + (x - 3f * dt) * scale - s.x[Lm.LEFT_WRIST]) / scale
    }

    @Test
    fun farAndNearPlayersGetTheSameResponsiveness() {
        val near = lagBodyUnits(0.3f)
        val far = lagBodyUnits(0.1f)
        assertTrue(abs(near - far) < 0.25f * near + 0.01f, "lag in body lengths should not depend on distance: near $near far $far")
    }

    @Test
    fun singleFrameGlitchIsIgnoredButRealMovementIsFollowed() {
        val s = SmoothedPose()
        val dt = 1f / 30f
        repeat(20) { s.update(pose(0.25f, 0f), dt, 0.25f) }
        val before = s.x[Lm.LEFT_WRIST]
        s.update(pose(0.25f, 3f), dt, 0.25f) // 3 body lengths in one frame: a mis-detection
        assertEquals(before, s.x[Lm.LEFT_WRIST], 1e-4f, "a one-frame teleport is ignored")
        s.update(pose(0.25f, 0f), dt, 0.25f)
        repeat(8) { s.update(pose(0.25f, 3f), dt, 0.25f) } // it really is over there now
        assertTrue(s.x[Lm.LEFT_WRIST] > before + 0.5f * 0.25f, "a lasting change is followed")
    }

    @Test
    fun shownHandsAreMovedForwardToTheRenderTime() {
        val rig = Rig()
        rig.acquireAndLock()
        // right hand sweeps sideways at a steady speed
        rig.seconds(0.6f) { t -> rig.p1.setWrist(1, 0.42f + 1.2f * t.toFloat(), -0.3f) }
        val c = rig.controls[0]
        val measured = c.handX[1]
        c.predict(rig.cam.tSec + 0.06)
        val ahead = c.handX[1] - measured
        assertTrue(ahead > 0.01f, "prediction moves the hand along its motion: $ahead")
        // standing still: prediction changes (almost) nothing
        rig.seconds(1f)
        val still = c.handX[1]
        c.predict(rig.cam.tSec + 0.06)
        assertTrue(abs(c.handX[1] - still) < 0.02f, "no drift when still")
        // clocks that don't match (e.g. simulated input vs display clock): shown as measured
        c.predict(rig.cam.tSec + 100.0)
        assertTrue(abs(c.handX[1] - still) < 1e-4f)
    }

    @Test
    fun avatarFollowsCrouchingAndHeadTilt() {
        val rig = Rig()
        rig.acquireAndLock()
        rig.seconds(0.5f) { rig.p1.shoulderY = 0.35f + 0.08f } // shoulders lower in the picture = crouching
        val a = rig.controls[0].avatar
        assertTrue(a.rise < -0.15f, "crouch shows as negative rise: ${a.rise}")
        rig.seconds(0.5f) { rig.p1.shoulderY = 0.35f }
        assertTrue(abs(rig.controls[0].avatar.rise) < 0.1f)
    }

    @Test
    fun qualityMeterGivesTheRightTip() {
        val rig = Rig()
        rig.acquireAndLock()
        val good = TrackingQuality.of(rig.tracker.slots[0], 30f)
        assertTrue(good.score > 0.62f, "clear, well-sized player: ${good.label} ${good.score}")
        assertEquals(null, good.tip)
        rig.p1.armVisibility = 0.35f
        rig.seconds(0.5f)
        assertEquals("Keep both hands in the picture", TrackingQuality.of(rig.tracker.slots[0], 30f).tip)
        rig.p1.armVisibility = Float.NaN
        rig.seconds(0.5f)
        assertEquals("Tracking is slow - let the phone cool or close other apps", TrackingQuality.of(rig.tracker.slots[0], 12f).tip)
    }
}
