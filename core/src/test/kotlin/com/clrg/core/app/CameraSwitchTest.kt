package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.party.MemoryStore
import com.clrg.core.party.Settings
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.SimAction
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.screens.FramingScreen
import com.clrg.core.ui.screens.GameScreen
import com.clrg.core.ui.screens.MainMenuScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Front/back camera switching from the menus (the platform test double has one back and one front camera). */
class CameraSwitchTest {
    private val store = MemoryStore()
    private val platform = NullPlatform(store)
    private val app = GameApp(platform, AudioEngine())
    private val g = NullGfx()
    private var now = 0L

    init {
        app.settings.simulatedInput = true
        app.applySettings()
        app.sim.controlsVisible = false
    }

    private fun frames(n: Int) = repeat(n) { now += 16_666_667L; platform.clock = now; app.frame(g, now) }

    private fun tap(id: String) {
        frames(2)
        val r = assertNotNull(app.ui.boxRect(id), "button '$id' not on screen ${app.current?.javaClass?.simpleName}: ${app.ui.boxIds()}")
        app.submitInput(InputEvent.Touch(InputEvent.Touch.DOWN, r.cx, r.cy))
        app.submitInput(InputEvent.Touch(InputEvent.Touch.UP, r.cx, r.cy))
        frames(2)
    }

    @Test
    fun mainMenuSwitchesBetweenFrontAndBackAndRemembersIt() {
        frames(5)
        assertTrue(app.current is MainMenuScreen)
        assertTrue(app.canSwitchCamera)
        assertEquals("Back camera", app.cameraLabel(), "the back camera is the default")
        tap("camSwitch")
        assertEquals("1", app.settings.cameraId)
        assertEquals("Front camera", app.cameraLabel())
        assertEquals("1", Settings.load(store).cameraId, "the choice is saved")
        tap("camSwitch")
        assertEquals("Back camera", app.cameraLabel())
    }

    @Test
    fun switchingCameraForgetsCalibrationAndPauseMenuSwitchGoesBackToFraming() {
        frames(5)
        tap("quick"); tap("game_tennis"); tap("play")
        frames(180)
        tap("skip0"); tap("skip1")
        frames(80)
        assertTrue(app.calibrated)
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE)
        frames(100)
        assertTrue(app.current is GameScreen)
        frames(50)
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE)
        frames(110)
        assertEquals(com.clrg.core.game.Phase.COUNTDOWN, (app.current as GameScreen).session.phase)
        app.key(com.clrg.core.ui.Key.BACK); frames(3)
        assertEquals(com.clrg.core.game.Phase.PAUSED, (app.current as GameScreen).session.phase)
        tap("camSwitch")
        assertEquals("Front camera", app.cameraLabel())
        assertFalse(app.calibrated, "a different camera needs a fresh calibration")
        assertTrue(app.current is FramingScreen, "pause-menu switch goes straight to framing: ${app.current}")
    }
}
