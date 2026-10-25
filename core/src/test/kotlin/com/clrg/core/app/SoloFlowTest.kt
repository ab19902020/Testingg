package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.game.CpuLevel
import com.clrg.core.game.Phase
import com.clrg.core.party.MemoryStore
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.SimAction
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.screens.CalibrationScreen
import com.clrg.core.ui.screens.FramingScreen
import com.clrg.core.ui.screens.GameScreen
import com.clrg.core.ui.screens.GameSelectScreen
import com.clrg.core.ui.screens.MainMenuScreen
import com.clrg.core.ui.screens.PlayerPickScreen
import com.clrg.core.ui.screens.ReadyScreen
import com.clrg.core.ui.screens.ResultsScreen
import com.clrg.core.ui.screens.SoloSetupScreen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Solo play through the real app controller: one (simulated) person at the camera against a computer player.
 * Verifies menus, single-player setup, the CPU taking part in the match and the switch back to two players.
 */
class SoloFlowTest {
    private val store = MemoryStore()
    private val platform = NullPlatform(store)
    private val app = GameApp(platform, AudioEngine()).also { it.mode = com.clrg.core.games.GameMode.CHRISTMAS }
    private val g = NullGfx()
    private var now = 0L

    init {
        app.settings.simulatedInput = true
        app.applySettings()
        app.sim.controlsVisible = false
    }

    private fun frames(n: Int) = repeat(n) { now += 16_666_667L; platform.clock = now; app.frame(g, now) }
    private fun seconds(s: Float) = frames((s * 60).toInt())

    private fun tap(id: String) {
        frames(2)
        val r = assertNotNull(app.ui.boxRect(id), "button '$id' not on screen ${app.current?.javaClass?.simpleName}: ${app.ui.boxIds()}")
        app.submitInput(InputEvent.Touch(InputEvent.Touch.DOWN, r.cx, r.cy))
        app.submitInput(InputEvent.Touch(InputEvent.Touch.UP, r.cx, r.cy))
        frames(2)
    }

    private fun shot(name: String) {
        val awt = AwtGfx(1920, 1080)
        now += 16_666_667L; platform.clock = now
        app.frame(awt, now)
        awt.writePng(File("build/screenshots/solo_$name.png"))
    }

    @Test
    fun quickMatchOfferJustMeAgainstTheComputer() {
        frames(10)
        tap("quick"); tap("game_bowling")
        assertTrue(app.current is PlayerPickScreen)
        shot("player_pick")
        tap("justme")
        assertTrue(app.solo && app.tracker.solo, "'Just me' switches to solo")
        assertTrue(app.current is FramingScreen)
        seconds(3f)
        assertTrue(app.current is CalibrationScreen, "one person is enough")
    }

    @Test
    fun soloMatchAgainstTheComputerThenBackToTwoPlayers() {
        frames(10)
        shot("main_menu")
        tap("solo")
        assertTrue(app.current is SoloSetupScreen)
        tap("level_HARD")
        shot("setup")
        tap("solo_go")
        assertTrue(app.solo && app.tracker.solo)
        assertEquals(CpuLevel.HARD, app.cpuLevel)
        assertEquals("Blitz", app.activePlayers[1].name)
        assertTrue(app.current is GameSelectScreen)
        tap("game_catch")
        assertTrue(app.current is FramingScreen, "solo set-up starts with framing: ${app.current}")
        seconds(0.5f)
        shot("framing")
        seconds(2.5f)
        assertTrue(app.current is CalibrationScreen, "one steady player is enough to continue")
        shot("calibration")
        assertTrue(app.ui.boxRect("skip1") == null, "no calibration panel for the computer player")
        tap("skip0")
        seconds(1.2f)
        assertTrue(app.current is ReadyScreen)
        assertTrue(app.calibrated, "solo calibration needs only the camera player")
        shot("ready")
        app.sim.trigger(0, SimAction.RAISE)
        seconds(1.6f)
        val gs = app.current as GameScreen
        assertTrue(gs.session.cpu[1] && !gs.session.cpu[0])
        assertTrue(gs.session.ready[1], "the computer is ready at once")
        shot("instructions")
        seconds(0.8f)
        app.sim.trigger(0, SimAction.RAISE)
        seconds(1.8f)
        assertEquals(Phase.COUNTDOWN, gs.session.phase, "the camera player's raised hand starts the match")
        app.sim.autoplay = true // developer autoplay plays the camera player's side; the CPU plays its own
        seconds(12f)
        assertEquals(Phase.PLAYING, gs.session.phase)
        shot("live")
        var guard = 0
        while (app.current !is ResultsScreen && guard++ < 200) seconds(1f)
        assertTrue(app.current is ResultsScreen)
        shot("results")
        val r = (app.current as ResultsScreen).result
        assertTrue(r.scores[0] > 0 && r.scores[1] > 0, "both sides played: ${r.scores.toList()}")
        app.sim.autoplay = false
        // another solo game: still against the computer, calibration kept
        tap("another")
        assertTrue(app.current is GameSelectScreen)
        tap("game_tennis")
        assertTrue(app.current is ReadyScreen, "same player, same calibration: straight to 'raise a hand'")
        assertTrue(app.solo)
        // back to the menu and a two-player quick match: solo mode ends
        app.popToRoot()
        frames(3)
        assertTrue(app.current is MainMenuScreen)
        tap("quick"); tap("game_tennis")
        assertTrue(app.current is PlayerPickScreen)
        tap("play")
        assertFalse(app.solo)
        assertFalse(app.tracker.solo)
        assertTrue(app.tracker.slots[1].enabled)
        assertTrue(app.activePlayers[1].id >= 0, "player 2 is a family member again, not the computer")
        assertTrue(app.current is FramingScreen, "two players need a fresh framing/calibration")
    }
}
