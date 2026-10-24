package com.clrg.core.gesture

import com.clrg.core.sim.Motions
import com.clrg.core.sim.Rig
import com.clrg.core.tracking.CalibrationProcess
import com.clrg.core.tracking.CalibrationStep
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GestureTest {

    private inline fun <reified T : GestureEvent> Rig.count(player: Int) = events[player].count { it is T }

    /** Moves a hand smoothly (slowly) to a position, so getting into place never looks like a gesture. */
    private fun Rig.moveHand(p: com.clrg.core.sim.Puppet, hand: Int, x: Float, y: Float, dur: Float = 0.8f) {
        val x0 = p.wristX[hand]; val y0 = p.wristY[hand]
        seconds(dur) { t -> val k = (t / dur).toFloat().coerceIn(0f, 1f); p.setWrist(hand, x0 + (x - x0) * k, y0 + (y - y0) * k) }
    }

    private fun Rig.swingOnce(player: Int, hand: Int, from: Float, to: Float, dur: Float) {
        val p = if (player == 0) p1 else p2
        moveHand(p, hand, from, -0.2f)
        clearEvents()
        seconds(dur) { t -> Motions.swing(p, hand, from, to, -0.2f, (t / dur).toFloat()) }
        seconds(0.3f) { }
        val rest = if (hand == 0) -0.42f else 0.42f
        val keep = events.map { ArrayList(it) }
        moveHand(p, hand, rest, -1.05f, 1.0f)
        for (k in 0..1) { events[k].clear(); events[k].addAll(keep[k]) }
    }

    @Test
    fun forehandSwingDetectedForRightHander() {
        val r = Rig()
        r.acquireAndLock()
        r.swingOnce(0, RIGHT, 0.9f, -0.5f, 0.25f)
        val s = r.events[0].filterIsInstance<GestureEvent.Swing>()
        assertEquals(1, s.size, "events: ${r.events[0]}")
        assertTrue(s[0].forehand)
        assertTrue(s[0].dirX < 0f)
        assertEquals(0, r.events[1].size, "player 2 did nothing")
    }

    @Test
    fun backhandAndLeftHandedForehand() {
        val r = Rig()
        r.acquireAndLock(hands = arrayOf(Hand.LEFT, Hand.RIGHT))
        // left-hander swings left hand from their left side across to the right: forehand
        r.swingOnce(0, LEFT, -0.9f, 0.5f, 0.25f)
        val a = r.events[0].filterIsInstance<GestureEvent.Swing>().single()
        // right-hander swings right hand from left to right: backhand
        r.swingOnce(1, RIGHT, -0.4f, 0.9f, 0.25f)
        val b = r.events[1].filterIsInstance<GestureEvent.Swing>().single()
        assertTrue(a.forehand, "left-handed forehand")
        assertTrue(!b.forehand, "right-handed backhand")
    }

    @Test
    fun slowArmMovementIsNotASwing() {
        val r = Rig()
        r.acquireAndLock()
        r.swingOnce(0, RIGHT, 0.9f, -0.5f, 1.6f)
        assertEquals(0, r.count<GestureEvent.Swing>(0))
    }

    @Test
    fun cooldownAndHysteresisPreventRepeatedTriggers() {
        val r = Rig()
        r.acquireAndLock()
        // frantic waving: 6 direction changes per second for 2 seconds
        r.moveHand(r.p1, RIGHT, 0.2f, -0.1f)
        r.clearEvents()
        r.seconds(2f) { t -> r.p1.setWrist(RIGHT, 0.2f + 0.7f * kotlin.math.sin(t * 2 * Math.PI * 3).toFloat(), -0.1f) }
        val n = r.count<GestureEvent.Swing>(0)
        assertTrue(n in 1..6, "swings while waving: $n (cooldown ${r.controls[0].tuning.swingCooldown}s)")
    }

    @Test
    fun reducedMovementModeAcceptsSmallerSwings() {
        for ((mode, expected) in listOf(MovementMode.STANDARD to 0, MovementMode.REDUCED to 1)) {
            val r = Rig()
            r.acquireAndLock(mode)
            r.swingOnce(0, RIGHT, 0.4f, -0.05f, 0.25f)
            assertEquals(expected, r.count<GestureEvent.Swing>(0), "mode $mode")
        }
    }

    @Test
    fun sameBodyRelativeSwingWorksForSmallAndLargePlayers() {
        for (scale in listOf(0.13f, 0.3f)) {
            val r = Rig()
            r.p1.scale = scale
            r.acquireAndLock()
            r.swingOnce(0, RIGHT, 0.9f, -0.5f, 0.25f)
            assertEquals(1, r.count<GestureEvent.Swing>(0), "scale $scale")
        }
    }

    @Test
    fun throwDetectedAndNotConfusedWithLoweringArm() {
        val r = Rig()
        r.acquireAndLock()
        r.moveHand(r.p1, RIGHT, 0.4f, -0.9f)
        r.clearEvents()
        r.seconds(0.45f) { t -> Motions.throwMotion(r.p1, RIGHT, (t / 0.45).toFloat()) }
        r.seconds(0.3f) { }
        assertEquals(1, r.count<GestureEvent.Throw>(0), "events ${r.events[0]}")
        r.clearEvents()
        // slowly lowering a raised arm is not a throw
        r.moveHand(r.p1, RIGHT, 0.4f, 0.8f, 1.5f)
        r.clearEvents()
        r.seconds(2f) { t -> r.p1.setWrist(RIGHT, 0.4f, 0.8f - (t / 2).toFloat() * 1.8f) }
        assertEquals(0, r.count<GestureEvent.Throw>(0))
    }

    @Test
    fun underarmBowlWithAimDirection() {
        val r = Rig()
        r.acquireAndLock()
        r.moveHand(r.p1, RIGHT, 0.42f, -1.05f)
        r.clearEvents()
        r.seconds(0.7f) { t -> Motions.bowl(r.p1, RIGHT, (t / 0.7).toFloat(), aim = -0.6f) }
        r.seconds(0.2f) { }
        val b = r.events[0].filterIsInstance<GestureEvent.Bowl>()
        assertEquals(1, b.size, "events ${r.events[0]}")
        assertTrue(b[0].aimX < -0.2f, "aim ${b[0].aimX}")
        r.moveHand(r.p1, RIGHT, 0.45f, -1.05f, 1.5f)
        r.clearEvents()
        r.seconds(3f) { t -> r.p1.setWrist(RIGHT, 0.45f, -1.05f + (t / 3).toFloat() * 0.9f) } // slow raise
        assertEquals(0, r.count<GestureEvent.Bowl>(0))
    }

    @Test
    fun seatedBowlUsesLapHeight() {
        val r = Rig()
        r.p1.seated = true; r.p1.hipsVisible = false
        r.acquireAndLock(MovementMode.SEATED)
        r.moveHand(r.p1, RIGHT, 0.3f, -0.8f, 1.0f) // hands resting in lap
        r.clearEvents()
        r.seconds(0.4f) { t -> r.p1.setWrist(RIGHT, 0.3f, -0.8f + (t / 0.4).toFloat() * 0.75f) }
        assertEquals(1, r.count<GestureEvent.Bowl>(0))
    }

    @Test
    fun leaningLeftAndRight() {
        val r = Rig()
        r.acquireAndLock()
        r.seconds(0.6f) { r.p1.lean = 0.3f }
        assertTrue(r.controls[0].lean > 0.6f, "lean right ${r.controls[0].lean}")
        r.seconds(0.6f) { r.p1.lean = -0.3f }
        assertTrue(r.controls[0].lean < -0.6f, "lean left ${r.controls[0].lean}")
        r.seconds(0.6f) { r.p1.lean = 0.02f }
        assertEquals(0f, r.controls[0].lean, 0.1f)
    }

    @Test
    fun armPumpSpeedDependsOnCadenceNotAmplitude() {
        fun speed(cadence: Float, amp: Float): Float {
            val r = Rig()
            r.acquireAndLock()
            r.seconds(3f) { t -> Motions.pump(r.p1, t, cadence, amp) }
            return r.controls[0].pumpSpeed
        }
        val normal = speed(2f, 0.4f)
        val huge = speed(2f, 0.9f)
        val fast = speed(6f, 0.4f)
        val tiny = speed(2f, 0.1f)
        assertTrue(normal in 0.45f..0.85f, "normal $normal")
        assertEquals(normal, huge, 0.12f, "bigger arm movement must not dominate")
        assertTrue(fast <= 1f && fast >= normal, "fast $fast")
        assertTrue(tiny < 0.1f, "movement below the minimum amplitude does not count: $tiny")
    }

    @Test
    fun reducedModePumpReachesFullSpeedWithSmallMovements() {
        val r = Rig()
        r.acquireAndLock(MovementMode.REDUCED)
        r.seconds(3f) { t -> Motions.pump(r.p1, t, 2.2f, 0.18f) }
        assertTrue(r.controls[0].pumpSpeed > 0.75f, "reduced speed ${r.controls[0].pumpSpeed}")
    }

    @Test
    fun raiseAndHoldFiresOnce() {
        val r = Rig()
        r.acquireAndLock()
        r.seconds(0.5f) { Motions.raise(r.p1, LEFT) }
        assertEquals(0, r.count<GestureEvent.RaiseHold>(0), "needs a full second")
        r.seconds(1.5f) { Motions.raise(r.p1, LEFT) }
        assertEquals(1, r.count<GestureEvent.RaiseHold>(0))
        r.seconds(0.6f) { r.p1.relaxArms() }
        r.seconds(1.2f) { Motions.raise(r.p1, LEFT) }
        assertEquals(2, r.count<GestureEvent.RaiseHold>(0))
    }

    @Test
    fun handsUpEdgeEvent() {
        val r = Rig()
        r.acquireAndLock()
        r.seconds(0.5f) { Motions.handsUp(r.p2) }
        r.seconds(0.5f) { r.p2.relaxArms() }
        r.events[1].removeAll { it is GestureEvent.Swing || it is GestureEvent.Bowl || it is GestureEvent.Throw } // instant puppet jumps
        assertEquals(1, r.count<GestureEvent.HandsUp>(1))
        assertTrue(!r.controls[1].handsUp)
    }

    @Test
    fun standingStillWithTrackerJitterProducesNoActions() {
        val r = Rig()
        r.cam.jitter = 0.012f
        r.acquireAndLock()
        r.seconds(20f) { t -> r.p1.lean = 0.03f * kotlin.math.sin(t).toFloat() }
        val actions = r.events[0].count { it is GestureEvent.Swing || it is GestureEvent.Throw || it is GestureEvent.Bowl || it is GestureEvent.RaiseHold }
        assertEquals(0, actions, "accidental actions: ${r.events[0]}")
    }

    @Test
    fun danceShapesAreDistinguishable() {
        for (mode in listOf(MovementMode.STANDARD, MovementMode.SEATED)) {
            val set = PoseShape.setFor(mode)
            for (target in set) {
                val r = Rig()
                r.acquireAndLock(mode)
                r.seconds(0.5f) { Motions.shape(r.p1, target) }
                val sims = set.associateWith { it.similarity(r.controls[0].avatar, PoseShape.toleranceFor(mode)) }
                val best = sims.maxByOrNull { it.value }!!.key
                assertEquals(target.id, best.id, "mode $mode target ${target.name}: $sims")
                assertTrue(sims[target]!! > 0.75f, "${target.name} similarity ${sims[target]}")
                val runnerUp = sims.filterKeys { it != target }.values.max()
                assertTrue(runnerUp < sims[target]!! - 0.2f, "${target.name} margin ${sims[target]} vs $runnerUp")
            }
        }
    }

    @Test
    fun reachSpaceUsesCalibratedReach() {
        val r = Rig()
        r.acquireAndLock()
        val reach = r.controls[0].calibration!!.reach
        r.seconds(0.5f) { Motions.reach(r.p1, RIGHT, 0.7f, 0.5f, reach) }
        assertEquals(0.7f, r.controls[0].handX[RIGHT], 0.08f)
        assertEquals(0.5f, r.controls[0].handY[RIGHT], 0.08f)
    }

    @Test
    fun calibrationProcessMeasuresReachAndPlayingHand() {
        val r = Rig()
        r.seconds(1f)
        val proc = CalibrationProcess(MovementMode.STANDARD)
        val slot = r.tracker.slots[0]
        fun run(sec: Float, pose: () -> Unit) = r.seconds(sec) { pose(); proc.update(slot, 1f / 30f) }
        run(1.5f) { r.p1.relaxArms() }
        assertEquals(CalibrationStep.ARMS_OUT, proc.step)
        run(1.2f) { r.p1.setWrist(LEFT, -1.0f, 0f); r.p1.setWrist(RIGHT, 1.0f, 0f) }
        assertEquals(CalibrationStep.REACH_UP, proc.step)
        run(1.2f) { r.p1.setWrist(LEFT, -0.3f, 1.0f); r.p1.setWrist(RIGHT, 0.3f, 1.0f) }
        assertEquals(CalibrationStep.PLAYING_HAND, proc.step)
        run(0.5f) { r.p1.relaxArms() }
        run(1.2f) { Motions.raise(r.p1, LEFT) }
        assertTrue(proc.isDone)
        val cal = assertNotNull(proc.result)
        assertEquals(Hand.LEFT, cal.dominantHand)
        assertTrue(cal.reachSide in 0.75f..1.2f, "side reach ${cal.reachSide}")
        assertTrue(cal.reach in 0.8f..1.6f, "reach ${cal.reach}")
        assertEquals(r.p1.scale, cal.bodyScale, 0.02f)
    }
}
