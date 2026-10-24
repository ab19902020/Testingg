package com.clrg.core.sim

import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.PlayerControls
import com.clrg.core.tracking.Calibration
import com.clrg.core.tracking.BodyMetrics
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import com.clrg.core.tracking.PlayerTracker

/** Test rig: puppets -> simulated camera frames -> tracker -> per-player controls. */
class Rig(seed: Long = 7, fps: Float = 30f) {
    val cam = SimCamera(fps = fps, seed = seed)
    val p1 = Puppet(0.55f, 0.35f, 0.25f).apply { trackHint = 11; appearance = floatArrayOf(0.8f, 0.1f, 0.1f, 0.8f, 0.1f, 0.7f, 0.1f, 0.1f, 0.8f, 0.1f) }
    val p2 = Puppet(1.25f, 0.36f, 0.26f).apply { trackHint = 12; appearance = floatArrayOf(0.1f, 0.2f, 0.8f, 0.1f, 0.2f, 0.1f, 0.2f, 0.8f, 0.1f, 0.2f) }
    val extras = ArrayList<Puppet>()
    val tracker = PlayerTracker(2)
    val controls = arrayOf(PlayerControls(0), PlayerControls(1))
    val events = arrayOf(ArrayList<GestureEvent>(), ArrayList<GestureEvent>())
    var hidden = HashSet<Puppet>()

    val people: List<Puppet> get() = listOf(p1, p2) + extras

    fun step(frames: Int = 1, animate: (Int) -> Unit = {}) {
        repeat(frames) { i ->
            animate(i)
            val f = cam.frame(people) { it !in hidden }
            tracker.update(f)
            for (k in 0..1) {
                controls[k].update(tracker.slots[k], cam.tSec)
                controls[k].drainEvents(events[k])
            }
        }
    }

    fun seconds(sec: Float, animate: (Double) -> Unit = {}) {
        val n = (sec * cam.fps).toInt()
        val t0 = cam.tSec
        step(n) { animate(cam.tSec + 1.0 / cam.fps - t0) }
    }

    /** Acquire both players from their areas and lock with quick calibrations. */
    fun acquireAndLock(mode: MovementMode = MovementMode.STANDARD, hands: Array<Hand> = arrayOf(Hand.RIGHT, Hand.RIGHT)) {
        seconds(1f)
        check(tracker.allPlaying) { "players not acquired: ${tracker.slots.map { it.state }}" }
        for (k in 0..1) {
            val m: BodyMetrics = tracker.slots[k].metrics!!
            val cal = Calibration.fromNeutral(m, hands[k], mode)
            tracker.slots[k].calibration = cal
            controls[k].calibration = cal
        }
        tracker.lock()
        events.forEach { it.clear() }
    }

    fun clearEvents() = events.forEach { it.clear() }
}
