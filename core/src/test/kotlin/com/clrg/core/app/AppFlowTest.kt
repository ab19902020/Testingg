package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.game.Phase
import com.clrg.core.party.MemoryStore
import com.clrg.core.party.Tournament
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.SimAction
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.Key
import com.clrg.core.ui.screens.CalibrationScreen
import com.clrg.core.ui.screens.FramingScreen
import com.clrg.core.ui.screens.GameScreen
import com.clrg.core.ui.screens.GameSelectScreen
import com.clrg.core.ui.screens.MainMenuScreen
import com.clrg.core.ui.screens.PlayerPickScreen
import com.clrg.core.ui.screens.ReadyScreen
import com.clrg.core.ui.screens.ResultsScreen
import com.clrg.core.ui.screens.TournamentHubScreen
import com.clrg.core.ui.screens.TournamentSetupScreen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the real app controller with simulated input (developer mode) and synthetic touches. This verifies menu
 * navigation, the setup flow, match flow and persistence - not camera tracking.
 */
class AppFlowTest {
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

    private fun frames(n: Int, each: () -> Unit = {}) = repeat(n) { each(); now += 16_666_667L; platform.clock = now; app.frame(g, now) }
    private fun seconds(s: Float, each: () -> Unit = {}) = frames((s * 60).toInt(), each)

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
        awt.writePng(File("build/screenshots/app_$name.png"))
    }

    @Test
    fun quickMatchFromMenuToResultsAndRematch() {
        frames(10)
        assertTrue(app.current is MainMenuScreen)
        shot("main_menu")
        tap("quick")
        assertTrue(app.current is GameSelectScreen)
        shot("game_select")
        tap("game_tennis")
        assertTrue(app.current is PlayerPickScreen)
        shot("player_pick")
        tap("play")
        assertTrue(app.current is FramingScreen, "setup starts at framing: ${app.current}")
        seconds(0.5f)
        shot("framing")
        seconds(2.5f)
        assertTrue(app.current is CalibrationScreen, "auto-continues once both players are steady")
        shot("calibration")
        tap("skip0"); tap("skip1")
        seconds(1.2f)
        assertTrue(app.current is ReadyScreen)
        assertTrue(app.calibrated)
        shot("ready")
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE)
        seconds(1.6f)
        val gs = app.current as GameScreen
        assertEquals(Phase.INSTRUCTIONS, gs.session.phase)
        shot("instructions")
        seconds(0.8f)
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE)
        seconds(1.8f)
        assertEquals(Phase.COUNTDOWN, gs.session.phase, "both raised hands -> countdown")
        shot("countdown")
        app.sim.autoplay = true
        seconds(5f)
        assertEquals(Phase.PLAYING, gs.session.phase)
        shot("tennis_live")
        // user pause and resume via the Back key
        app.key(Key.BACK); frames(3)
        assertEquals(Phase.PAUSED, gs.session.phase)
        shot("pause_menu")
        tap("resume")
        seconds(4f)
        assertEquals(Phase.PLAYING, gs.session.phase)
        var guard = 0
        while (app.current !is ResultsScreen && guard++ < 400) seconds(1f)
        assertTrue(app.current is ResultsScreen)
        shot("results")
        app.sim.autoplay = false
        tap("rematch")
        assertTrue(app.current is ReadyScreen, "rematch keeps calibration and goes straight to 'raise a hand'")
    }

    @Test
    fun tvUnplugAndBackgroundingPauseTheMatchFairly() {
        platform.display = DisplayStatus(externalConnected = true, externalName = "HDMI", externalWidth = 1920, externalHeight = 1080, externalRefreshHz = 60f)
        app.submitLifecycle("display")
        frames(5)
        tap("quick"); tap("game_tennis"); tap("play")
        seconds(3f); tap("skip0"); tap("skip1"); seconds(1.2f)
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE); seconds(1.6f)
        val gs = app.current as GameScreen
        app.sim.autoplay = true
        seconds(5f)
        assertEquals(Phase.PLAYING, gs.session.phase)

        // HDMI cable pulled out mid-match
        platform.display = DisplayStatus()
        app.submitLifecycle("display")
        frames(3)
        assertTrue(app.displayLost, "TV-disconnected notice shown")
        assertEquals(Phase.PAUSED, gs.session.phase)
        val t0 = gs.session.game.playTime
        seconds(2f)
        assertEquals(t0, gs.session.game.playTime, "nothing moves while the TV is gone")
        tap("displayOk") // carry on on the phone screen
        assertTrue(!app.displayLost)
        tap("resume")
        seconds(3.5f)
        assertEquals(Phase.PLAYING, gs.session.phase)

        // app sent to the background (home button, phone call) and back
        app.submitLifecycle("pause")
        frames(3)
        assertEquals(Phase.PAUSED, gs.session.phase)
        app.submitLifecycle("resume")
        seconds(2f)
        assertEquals(Phase.PAUSED, gs.session.phase, "returning to the app does not restart play by itself")
        tap("resume")
        seconds(3.5f)
        assertEquals(Phase.PLAYING, gs.session.phase)
    }

    @Test
    fun trackingLossPausesTheMatchAndResumes() {
        frames(5)
        tap("quick"); tap("game_catch"); tap("play")
        seconds(3f); tap("skip0"); tap("skip1"); seconds(1.2f)
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE); seconds(1.6f)
        val gs = app.current as GameScreen
        app.sim.autoplay = true
        seconds(5f)
        assertEquals(Phase.PLAYING, gs.session.phase)
        app.sim.autoplay = false // tracking must matter again
        seconds(1f)
        // player 2 walks out of view
        app.sim.puppets[1].visibility = 0f
        seconds(1.5f)
        assertEquals(Phase.TRACKING_PAUSED, gs.session.phase)
        assertEquals(listOf(1), gs.session.missing.toList())
        shot("tracking_lost")
        val t0 = gs.session.game.playTime
        seconds(2f)
        assertEquals(t0, gs.session.game.playTime, "game time is frozen while paused")
        app.sim.puppets[1].visibility = 0.95f
        seconds(1.8f)
        assertEquals(Phase.RESUMING, gs.session.phase)
        seconds(3.5f)
        assertEquals(Phase.PLAYING, gs.session.phase)
        assertTrue(app.tracker.slots[1].reacquisitions >= 1)
    }

    @Test
    fun partyTournamentRotatesPairsAndPersists() {
        app.roster.add("Ivy"); app.saveRoster()
        frames(5)
        tap("tournament")
        assertTrue(app.current is TournamentSetupScreen)
        shot("tournament_setup")
        tap("len0") // quick
        tap("start")
        assertTrue(app.current is TournamentHubScreen)
        shot("tournament_hub")
        val t = assertNotNull(app.tournament)
        val first = t.current!!
        tap("play")
        seconds(3f); tap("skip0"); tap("skip1"); seconds(1.2f)
        app.sim.autoplay = true
        seconds(1f)
        var guard = 0
        while (app.current !is ResultsScreen && guard++ < 400) seconds(1f)
        shot("tournament_result")
        tap("continue")
        assertTrue(app.current is TournamentHubScreen)
        assertEquals(1, t.currentIndex)
        assertTrue(first.played)
        val second = t.current!!
        assertTrue(second.p1 != first.p1 || second.p2 != first.p2, "pairs rotate")
        // persisted
        val reloaded = assertNotNull(Tournament.load(store))
        assertEquals(1, reloaded.currentIndex)
        assertEquals(first.winnerId, reloaded.matches[0].winnerId)
    }
}
