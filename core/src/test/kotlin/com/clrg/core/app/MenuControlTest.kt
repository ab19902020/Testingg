package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rng
import com.clrg.core.party.MemoryStore
import com.clrg.core.pose.PoseFrame
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.Puppet
import com.clrg.core.ui.MenuControl
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Only Player 1 - whoever stands in the red (left) area - steers the menus, and only after waving at the screen.
 * Simulated people sent through the real tracker, pointer and menus as camera frames (not the developer simulation).
 */
class MenuControlTest {
    private val aspect = 16f / 9f
    private val rng = Rng(5)
    /** Player 1 stands in the red (left) half, player 2 in the blue (right) half. */
    private val red = Puppet(aspect * 0.27f, 0.36f, 0.24f).apply { trackHint = 11 }
    private val blue = Puppet(aspect * 0.73f, 0.36f, 0.24f).apply { trackHint = 12 }
    private val app = GameApp(NullPlatform(MemoryStore()), AudioEngine()).also {
        it.settings.simulatedInput = false
        it.settings.directionChecked.add("0") // the left/right check was done before
        it.applySettings()
    }
    private val g = NullGfx()
    private var now = 0L
    private var frameNo = 0

    private fun frames(seconds: Float, move: (Double) -> Unit = {}) = repeat((seconds * 60).toInt()) {
        now += 16_666_667L
        (app.platform as NullPlatform).clock = now
        move(now / 1e9)
        if (frameNo++ % 2 == 0) app.submitPoseFrame(PoseFrame(now, aspect, listOf(red.pose(0.002f, rng), blue.pose(0.002f, rng))))
        app.frame(g, now)
    }

    /** A hand raised in front of the chest, still (pointing, not waving). */
    private fun raise(p: Puppet) = p.setWrist(RIGHT, 0.3f, 0.0f)

    /** A wave: the raised hand swinging side to side about twice a second. */
    private fun wave(p: Puppet, t: Double) = p.setWrist(RIGHT, 0.35f + 0.28f * sin(t * 2 * Math.PI * 2).toFloat(), 0.35f)

    @Test
    fun onlyPlayerOneGetsAPointerAndOnlyAfterWaving() {
        frames(1.5f) // both walk in
        raise(red); raise(blue)
        frames(2f)
        assertFalse(app.menuControl.engaged, "a raised hand is not a wave")
        assertFalse(app.ui.cursors[0].visible || app.ui.cursors[1].visible, "no pointer before waving")
        // the blue player waves: nothing (Player 1 steers the menus)
        frames(2.5f) { t -> wave(blue, t) }
        assertFalse(app.menuControl.engaged, "a wave from the blue side does not take control")
        assertFalse(app.ui.cursors[1].visible, "player 2 never gets a pointer on the menus")
        raise(blue)
        // the red player waves: control
        frames(2.5f) { t -> wave(red, t) }
        assertTrue(app.menuControl.engaged, "Player 1 waved: control")
        raise(red)
        frames(1f)
        assertTrue(app.ui.cursors[0].visible && app.ui.cursors[0].active, "Player 1's pointer")
        assertFalse(app.ui.cursors[1].visible, "still no pointer for player 2")
    }

    @Test
    fun controlIsGivenUpAfterAWhileWithHandsDown() {
        frames(1.5f)
        frames(2.5f) { t -> wave(red, t) }
        assertTrue(app.menuControl.engaged)
        red.relaxArms()
        frames(MenuControl.IDLE_SEC - 2f)
        assertTrue(app.menuControl.engaged, "a short rest keeps control")
        frames(3f)
        assertFalse(app.menuControl.engaged, "hands down for a long while: wave again to take control")
    }

    @Test
    fun movingAHandAboutIsNotAWave() {
        frames(1.5f)
        // raising the hand, pointing around slowly, scratching the head: no wave
        frames(3f) { t -> red.setWrist(RIGHT, 0.2f + 0.3f * sin(t * 1.2).toFloat(), -0.2f + 0.4f * (t % 3 / 3).toFloat()) }
        assertFalse(app.menuControl.engaged, "slow movements are not a wave")
    }
}
