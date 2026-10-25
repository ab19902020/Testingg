package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.game.TimingCalibrator
import com.clrg.core.gesture.RIGHT
import com.clrg.core.party.MemoryStore
import com.clrg.core.party.Settings
import com.clrg.core.pose.PoseFrame
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.Motions
import com.clrg.core.sim.Puppet
import com.clrg.core.math.Rng
import com.clrg.core.tracking.Calibration
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.screens.TimingTestScreen
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The timing test through the real app loop (camera mode, not simulated input): a player whose TV shows the picture
 * 140 ms late swings as the snowball reaches the ring on their TV; their camera frames arrive 120 ms after capture.
 * The test must measure ~140 ms (the camera part is already known from the frame timestamps) and save it.
 */
class TimingTestFlowTest {
    private val store = MemoryStore()
    private val platform = NullPlatform(store)
    private val app = GameApp(platform, AudioEngine()).also { it.mode = com.clrg.core.games.GameMode.CHRISTMAS }
    private val g = NullGfx()
    private var now = 1_000_000_000L
    private val p1 = Puppet(0.55f, 0.35f, 0.25f).apply { trackHint = 11; appearance = floatArrayOf(0.8f, 0.1f, 0.1f, 0.8f, 0.1f, 0.7f, 0.1f, 0.1f, 0.8f, 0.1f) }
    private val p2 = Puppet(1.25f, 0.36f, 0.26f).apply { trackHint = 12; appearance = floatArrayOf(0.1f, 0.2f, 0.8f, 0.1f, 0.2f, 0.1f, 0.2f, 0.8f, 0.1f, 0.2f) }
    private val rng = Rng(5)
    private val pending = ArrayDeque<PoseFrame>()
    private var tick = 0
    /** Real times at which the player's swings are fastest. */
    private val peaks = ArrayList<Double>()
    private val cameraDelay = 0.12
    private val tvDelay = 0.14

    private fun armAt(t: Double) {
        val dur = 0.3
        for (pk in peaks) {
            val u = (t - (pk - dur / 2)) / dur
            if (u in 0.0..1.0) { Motions.swing(p1, RIGHT, 0.9f, -0.5f, -0.2f, u.toFloat()); return }
            val back = (t - (pk + dur / 2)) / 0.7
            if (back in 0.0..1.0) { p1.setWrist(RIGHT, -0.5f + 1.4f * back.toFloat(), -0.2f); return }
        }
        Motions.swing(p1, RIGHT, 0.9f, -0.5f, -0.2f, 0f)
    }

    private fun frames(n: Int, each: () -> Unit = {}) = repeat(n) {
        now += 16_666_667L
        platform.clock = now
        tick++
        if (tick % 2 == 0) { // 30 fps camera
            armAt(now / 1e9)
            pending.addLast(PoseFrame(now, 16f / 9f, listOf(p1.pose(0.003f, rng), p2.pose(0.003f, rng))))
        }
        while (pending.isNotEmpty() && pending.first().timestampNanos + (cameraDelay * 1e9).toLong() <= now) app.submitPoseFrame(pending.removeFirst())
        each()
        app.frame(g, now)
    }

    private fun shot(name: String) {
        val awt = com.clrg.core.render.AwtGfx(1920, 1080)
        now += 16_666_667L; platform.clock = now
        app.frame(awt, now)
        awt.writePng(java.io.File("build/screenshots/app_$name.png"))
    }

    private fun tap(id: String) {
        frames(2)
        val r = assertNotNull(app.ui.boxRect(id), "button '$id' not on screen ${app.current?.javaClass?.simpleName}: ${app.ui.boxIds()}")
        app.submitInput(InputEvent.Touch(InputEvent.Touch.DOWN, r.cx, r.cy))
        app.submitInput(InputEvent.Touch(InputEvent.Touch.UP, r.cx, r.cy))
        frames(2)
    }

    private fun calibrate() {
        frames(60)
        assertTrue(app.tracker.allPlaying, "both players found: ${app.tracker.slots.map { it.state }}")
        for (k in 0..1) {
            val cal = Calibration.fromNeutral(app.tracker.slots[k].metrics!!, Hand.RIGHT, MovementMode.STANDARD)
            app.tracker.slots[k].calibration = cal
            app.controls[k].calibration = cal
        }
        app.tracker.lock()
        assertTrue(app.calibrated)
    }

    @Test
    fun measuresTheTvDelayAndSavesIt() {
        app.settings.simulatedInput = false
        app.applySettings()
        calibrate()
        val screen = TimingTestScreen(app)
        app.push(screen)
        frames(20)
        shot("timing_intro")
        tap("start")
        val cal = assertNotNull(screen.cal)
        // the player sees each snowball reach the ring tvDelay after the game draws it, and swings then
        for (i in 0 until cal.cues) peaks.add(cal.cueTime(i) + tvDelay)
        frames(((cal.cueTime(4) - 0.25 - now / 1e9) * 60).toInt())
        shot("timing_run")
        frames(((cal.lead + cal.cues * cal.period + 1.0) * 60).toInt())
        shot("timing_result")
        val res = cal.result()
        assertTrue(res is TimingCalibrator.Result.Measured, "result: $res, swings ${cal.offsets.size}")
        println("TIMING measured ${res.delayMs} ms (spread ${res.spreadMs} ms, ${res.swings} swings) for a ${(tvDelay * 1000).toInt()} ms TV delay and ${(cameraDelay * 1000).toInt()} ms camera delay")
        assertTrue(abs(res.delayMs - tvDelay * 1000) <= 25, "measured ${res.delayMs} ms for a ${(tvDelay * 1000).toInt()} ms TV delay")
        tap("use")
        assertEquals(res.delayMs, app.settings.displayDelayMs)
        assertTrue(app.settings.timingMeasured)
        assertEquals(res.delayMs, Settings.load(store).displayDelayMs, "saved")
        assertEquals(res.delayMs / 1000.0, app.timing.displayDelay, 1e-9)
    }

    @Test
    fun calibratorRejectsTooFewAndIrregularSwings() {
        val c = TimingCalibrator(0.0)
        listOf(0.1, 0.12, 0.09).forEachIndexed { i, o -> c.swing(c.cueTime(i) + o) }
        assertEquals(TimingCalibrator.Result.TooFewSwings, c.result())
        val d = TimingCalibrator(0.0)
        listOf(0.1, -0.3, 0.4, -0.2, 0.35, 0.0, -0.45, 0.5).forEachIndexed { i, o -> d.swing(d.cueTime(i) + o) }
        assertEquals(TimingCalibrator.Result.TooIrregular, d.result())
        val e = TimingCalibrator(0.0)
        listOf(0.2, 0.21, 0.19, 0.22, 0.95, 0.2, 0.18, 0.2).forEachIndexed { i, o -> e.swing(e.cueTime(i) + o) }
        val m = e.result()
        assertTrue(m is TimingCalibrator.Result.Measured && m.delayMs in 195..205, "a stray swing does not move the median: $m")
        assertTrue(e.swing(e.cueTime(9) + 5.0).isNaN(), "a swing far from any snowball is ignored")
    }
}
