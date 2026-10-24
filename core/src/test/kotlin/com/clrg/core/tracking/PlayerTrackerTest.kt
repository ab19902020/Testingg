package com.clrg.core.tracking

import com.clrg.core.sim.Puppet
import com.clrg.core.sim.Rig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTrackerTest {

    private fun Rig.assertIdentity() {
        assertTrue(abs(tracker.slots[0].shoulderX - p1.cx) < 0.12f, "slot 0 should follow the left player")
        assertTrue(abs(tracker.slots[1].shoulderX - p2.cx) < 0.12f, "slot 1 should follow the right player")
    }

    @Test
    fun assignsPlayersFromTheirStartingAreas() {
        val r = Rig()
        r.seconds(0.15f)
        assertFalse(r.tracker.allPlaying, "acquisition requires a short stable period")
        r.seconds(0.6f)
        assertTrue(r.tracker.allPlaying)
        r.assertIdentity()
    }

    @Test
    fun identitiesStableWhenDetectionOrderChangesEveryFrame() {
        val r = Rig()
        r.acquireAndLock()
        r.cam.shuffle = true
        r.seconds(10f) { t ->
            r.p1.cx = 0.55f + 0.08f * kotlin.math.sin(t * 2).toFloat()
            r.p2.cx = 1.25f + 0.08f * kotlin.math.cos(t * 2.3).toFloat()
            r.p1.trackHint = (t * 10).toInt() // even the pipeline hint changes: identity must not rely on it
            r.p2.trackHint = 1000 + (t * 10).toInt()
            r.assertIdentity()
        }
        assertEquals(0, r.tracker.slots[0].lossEvents)
        assertEquals(0, r.tracker.slots[1].lossEvents)
    }

    @Test
    fun spectatorInBackgroundIsIgnored() {
        val r = Rig()
        r.extras.add(Puppet(0.9f, 0.45f, 0.12f).apply { trackHint = 99 }) // small = further away, centre
        r.seconds(1f)
        r.assertIdentity()
        r.acquireAndLock()
        r.seconds(3f) { t -> r.extras[0].cx = 0.4f + 1.0f * ((t / 3).toFloat()) }
        r.assertIdentity()
        assertTrue(r.tracker.others.isNotEmpty(), "spectator is reported as 'other'")
    }

    @Test
    fun spectatorBesideAPlayerNeverReplacesThem() {
        val r = Rig()
        r.acquireAndLock()
        val spec = Puppet(0.3f, 0.36f, 0.25f).apply { trackHint = 77; appearance = floatArrayOf(0.2f, 0.7f, 0.2f, 0.2f, 0.6f, 0.2f, 0.7f, 0.2f, 0.2f, 0.6f) }
        r.extras.add(spec)
        r.seconds(4f) { t -> spec.cx = 0.25f + 0.1f * kotlin.math.sin(t * 3).toFloat() }
        r.assertIdentity()
        assertEquals(SlotState.ACTIVE, r.tracker.slots[0].state)
    }

    @Test
    fun briefOcclusionIsBridgedWithoutPausing() {
        val r = Rig()
        r.acquireAndLock()
        r.hidden.add(r.p1)
        r.seconds(0.25f)
        assertEquals(SlotState.MISSING, r.tracker.slots[0].state)
        assertTrue(r.tracker.slots[0].isPlaying)
        r.hidden.clear()
        r.seconds(0.2f)
        assertEquals(SlotState.ACTIVE, r.tracker.slots[0].state)
        assertEquals(0, r.tracker.slots[0].lossEvents)
        r.assertIdentity()
    }

    @Test
    fun longLossThenReacquisitionWithoutSwappingPlayers() {
        val r = Rig()
        r.acquireAndLock()
        r.hidden.add(r.p1)
        r.seconds(1f)
        assertEquals(SlotState.LOST, r.tracker.slots[0].state)
        assertEquals(LostReason.NOT_SEEN, r.tracker.slots[0].lostReason)
        assertEquals(SlotState.ACTIVE, r.tracker.slots[1].state, "the other player keeps being tracked")
        // player 1 comes back slightly displaced in their own area
        r.p1.cx = 0.62f
        r.hidden.clear()
        r.seconds(0.3f)
        assertEquals(SlotState.LOST, r.tracker.slots[0].state, "reacquisition waits until the person is stable")
        r.seconds(0.6f)
        assertEquals(SlotState.ACTIVE, r.tracker.slots[0].state)
        assertEquals(1, r.tracker.slots[0].reacquisitions)
        r.assertIdentity()
    }

    @Test
    fun bothPlayersLostAndReturnToTheCorrectSlots() {
        val r = Rig()
        r.acquireAndLock()
        r.hidden.add(r.p1); r.hidden.add(r.p2)
        r.seconds(1f)
        assertTrue(r.tracker.slots.all { it.state == SlotState.LOST })
        r.hidden.clear()
        r.seconds(1.2f)
        assertTrue(r.tracker.allPlaying)
        r.assertIdentity()
    }

    @Test
    fun differentSizedOrDifferentlyDressedPersonCannotTakeOverLostPlayer() {
        val r = Rig()
        r.acquireAndLock()
        r.hidden.add(r.p1)
        r.seconds(1f)
        // a child-sized person walks into player 1's area
        val small = Puppet(0.55f, 0.5f, 0.13f).apply { trackHint = 50; appearance = r.p1.appearance!!.copyOf() }
        r.extras.add(small)
        r.seconds(2f)
        assertEquals(SlotState.LOST, r.tracker.slots[0].state)
        // same size but very different clothing
        r.extras.clear()
        val other = Puppet(0.55f, 0.35f, 0.25f).apply { trackHint = 51; appearance = floatArrayOf(0.1f, 0.8f, 0.1f, 0.1f, 0.8f, 0.1f, 0.8f, 0.1f, 0.1f, 0.8f) }
        r.extras.add(other)
        r.seconds(2f)
        assertEquals(SlotState.LOST, r.tracker.slots[0].state)
        // the real player returns
        r.extras.clear()
        r.hidden.clear()
        r.seconds(1f)
        assertEquals(SlotState.ACTIVE, r.tracker.slots[0].state)
        r.assertIdentity()
    }

    @Test
    fun playersStandingCloseTogetherDoNotSwap() {
        val r = Rig()
        r.acquireAndLock()
        r.seconds(2f) { t ->
            val k = (t / 2).toFloat().coerceIn(0f, 1f)
            r.p1.cx = 0.55f + 0.28f * k  // move toward the middle
            r.p2.cx = 1.25f - 0.28f * k
            r.assertIdentity()
        }
        r.seconds(2f) { t -> r.p1.lean = 0.3f * kotlin.math.sin(t * 6).toFloat(); r.p2.lean = -0.3f * kotlin.math.sin(t * 6).toFloat(); r.assertIdentity() }
    }

    @Test
    fun lowConfidenceTriggersLossWithReason() {
        val r = Rig()
        r.acquireAndLock()
        r.p2.armVisibility = 0.1f // shoulders seen, arms not: tracking is not good enough to play
        r.p2.visibility = 0.6f
        r.seconds(1.5f)
        assertEquals(SlotState.LOST, r.tracker.slots[1].state)
        assertEquals(LostReason.LOW_CONFIDENCE, r.tracker.slots[1].lostReason)
        r.p2.visibility = 0.95f
        r.p2.armVisibility = Float.NaN
        r.seconds(1.2f)
        assertEquals(SlotState.ACTIVE, r.tracker.slots[1].state)
    }

    @Test
    fun childAndAdultTrackedSideBySide() {
        val r = Rig()
        r.p1.scale = 0.15f; r.p1.shoulderY = 0.5f   // child, smaller in frame
        r.p2.scale = 0.28f                          // tall adult
        r.acquireAndLock()
        r.seconds(3f) { r.assertIdentity() }
        assertEquals(0.15f, r.tracker.slots[0].scale, 0.02f)
        assertEquals(0.28f, r.tracker.slots[1].scale, 0.03f)
    }

    @Test
    fun setupModeReleasesPlayerWhoLeavesTheirArea() {
        val r = Rig()
        r.seconds(1f)
        assertTrue(r.tracker.allPlaying)
        r.seconds(1f) { t -> r.p1.cx = 0.55f + 0.8f * (t.toFloat()).coerceAtMost(1f) * 0.6f }
        assertEquals(SlotState.EMPTY, r.tracker.slots[0].state)
    }

    @Test
    fun seatedPlayerWithHiddenHipsIsTracked() {
        val r = Rig()
        r.p1.seated = true; r.p1.hipsVisible = false
        r.acquireAndLock(MovementMode.SEATED)
        r.seconds(2f) { r.assertIdentity() }
        assertEquals(SlotState.ACTIVE, r.tracker.slots[0].state)
    }
}
