package com.clrg.core.gesture

import com.clrg.core.sim.Motions
import com.clrg.core.sim.Rig
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Golf swings and putts from simulated body movement (synthetic poses through the real tracker and smoothing). */
class GolfSwingTest {
    private fun rig(fps: Float = 30f, mode: MovementMode = MovementMode.STANDARD): Rig {
        val r = Rig(fps = fps)
        r.acquireAndLock(mode, arrayOf(Hand.RIGHT, Hand.RIGHT))
        return r
    }

    /** Address for a moment, then run [motion] (time from its start) until it says it is over. */
    private fun Rig.perform(stroke: GolfStroke, motion: (Double) -> Boolean): List<GolfSwingDetector.Shot> {
        val c = controls[0]
        c.golfStroke = stroke
        seconds(1.0f) { Motions.golfAddress(p1) }
        val shots = ArrayList<GolfSwingDetector.Shot>()
        c.drainGolfShots(shots); shots.clear()
        val t0 = cam.tSec
        var going = true
        while (going) {
            step(1) { going = motion(cam.tSec + 1.0 / cam.fps - t0) }
            c.drainGolfShots(shots)
        }
        seconds(1.2f) { Motions.golfAddress(p1, 0f) }
        c.drainGolfShots(shots)
        return shots
    }

    @Test
    fun fullSwingPowerFollowsHowHighTheHandsGo() {
        for (fps in floatArrayOf(30f, 60f)) {
            val r = rig(fps)
            val full = r.perform(GolfStroke.FULL) { t -> Motions.golfSwing(r.p1, t, rise = 1.35f) }
            assertEquals(1, full.size, "one full swing at $fps fps")
            assertTrue(abs(full[0].power - 1f) < 0.15f, "full backswing ~ full power: ${full[0].power}")
            assertTrue(abs(full[0].offset) < 0.2f, "through the address point: straight (${full[0].offset})")
            val half = r.perform(GolfStroke.FULL) { t -> Motions.golfSwing(r.p1, t, rise = 0.7f, side = -1f) }
            assertEquals(1, half.size, "a half swing to the other side counts too")
            assertTrue(half[0].power in 0.35f..0.6f, "half backswing ~ half power: ${half[0].power}")
            println("GOLF fps=$fps full power ${full[0].power} half power ${half[0].power}")
        }
    }

    @Test
    fun handsComingThroughOffToTheSideSendTheBallThatWay() {
        val r = rig()
        val right = r.perform(GolfStroke.FULL) { t -> Motions.golfSwing(r.p1, t, offset = 0.45f) }.single()
        val left = r.perform(GolfStroke.FULL) { t -> Motions.golfSwing(r.p1, t, offset = -0.45f) }.single()
        assertTrue(right.offset > 0.3f, "hands through right of address: ${right.offset}")
        assertTrue(left.offset < -0.3f, "hands through left of address: ${left.offset}")
    }

    @Test
    fun slowDownswingIsAPracticeSwingAndRaisingAHandIsNothing() {
        val r = rig()
        val practice = r.perform(GolfStroke.FULL) { t -> Motions.golfSwing(r.p1, t, down = 1.6) }
        assertEquals(0, practice.size, "lowering the hands slowly is not a shot")
        // picking a club: one hand up to the side and back down, the other hand hanging
        val c = r.controls[0]
        c.golfStroke = GolfStroke.FULL
        r.seconds(1f) { Motions.golfAddress(r.p1) }
        r.seconds(1.2f) { t -> r.p1.setWrist(1, 0.6f, -1.1f + 1.8f * kotlin.math.sin(Math.PI * t / 1.2).toFloat()); r.p1.setWrist(0, -0.42f, -1.05f) }
        r.seconds(0.5f) { Motions.golfAddress(r.p1) }
        val shots = ArrayList<GolfSwingDetector.Shot>()
        c.drainGolfShots(shots)
        assertEquals(0, shots.size, "reaching for a club is not a swing")
    }

    @Test
    fun puttStrengthFollowsHowFarBackTheHandsGo() {
        val r = rig()
        val short = r.perform(GolfStroke.PUTT) { t -> Motions.golfPutt(r.p1, t, back = 0.15f) }.single()
        val long = r.perform(GolfStroke.PUTT) { t -> Motions.golfPutt(r.p1, t, back = 0.5f, side = -1f) }.single()
        assertEquals(GolfStroke.PUTT, long.stroke)
        assertTrue(short.power in 0.1f..0.35f, "short putt ${short.power}")
        assertTrue(long.power in 0.7f..0.95f, "long putt ${long.power}")
        // a full swing is not taken as a putt while putting, and vice versa
        assertEquals(0, r.perform(GolfStroke.NONE) { t -> Motions.golfSwing(r.p1, t) }.size)
    }

    @Test
    fun reducedMovementNeedsASmallerSwing() {
        val r = rig(mode = MovementMode.REDUCED)
        val s = r.perform(GolfStroke.FULL) { t -> Motions.golfSwing(r.p1, t, rise = 0.8f) }.single()
        assertTrue(s.power > 0.85f, "a chest-high swing is full power in reduced-movement mode: ${s.power}")
    }

    @Test
    fun theSwingStartsOnlyFromAGripWithBothHandsTogether() {
        val r = rig()
        val c = r.controls[0]
        c.golfStroke = GolfStroke.FULL
        // hands low in front but apart (not holding a club): no address, and the guide says how far apart
        r.seconds(1.5f) { r.p1.setWrist(LEFT, -0.45f, -1.1f); r.p1.setWrist(RIGHT, 0.45f, -1.1f) }
        assertEquals(GolfSwingDetector.Phase.IDLE, c.golf.phase, "no grip, no address")
        assertTrue(!c.golf.grip && c.golf.handsGap > 0.6f, "hands seen apart: ${c.golf.handsGap}")
        // hands together: gripped, and the address follows
        r.seconds(1.2f) { Motions.golfAddress(r.p1) }
        assertTrue(c.golf.grip, "hands together = grip")
        assertEquals(GolfSwingDetector.Phase.ADDRESS, c.golf.phase)
    }

    @Test
    fun handsHiddenAtTheTopOfTheBackswingDoNotCancelIt() {
        val r = rig()
        val shots = r.perform(GolfStroke.FULL) { t ->
            val going = Motions.golfSwing(r.p1, t, rise = 1.35f)
            // around the top (0.75 - 1.1 s) both wrists are lost behind the head / out of shot
            val hidden = t in 0.75..1.1
            r.p1.wristVisibility[LEFT] = if (hidden) 0.05f else Float.NaN
            r.p1.wristVisibility[RIGHT] = if (hidden) 0.05f else Float.NaN
            going
        }
        assertEquals(1, shots.size, "the swing is still struck after the hands come back into view")
        assertTrue(shots[0].power > 0.7f, "with the power of the backswing: ${shots[0].power}")
    }
}
