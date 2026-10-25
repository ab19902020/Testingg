package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.game.ObjectKind
import com.clrg.core.game.Phase
import com.clrg.core.games.GameMode
import com.clrg.core.party.MemoryStore
import com.clrg.core.pose.HeldLearnStatus
import com.clrg.core.pose.ObjectColorModel
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.SimAction
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.screens.CalibrationScreen
import com.clrg.core.ui.screens.GameScreen
import com.clrg.core.ui.screens.GameSelectScreen
import com.clrg.core.ui.screens.HeldObjectScreen
import com.clrg.core.ui.screens.MainMenuScreen
import com.clrg.core.ui.screens.PlayerPickScreen
import com.clrg.core.ui.screens.ReadyScreen
import com.clrg.core.ui.screens.ResultsScreen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sports & Arcade mode driven through the real app with simulated input (developer mode): the mode switch, the
 * sports game list, teaching a toy gun from a game's start screen, and a whole Clay Shooting match. Verifies menus
 * and flow - not camera tracking.
 */
class SportsFlowTest {
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
        awt.writePng(File("build/screenshots/app_$name.png"))
    }

    /** From the player pick screen through framing, calibration and "raise a hand" to the game's start screen. */
    private fun setUpAndReady(): GameScreen {
        tap("play")
        seconds(3f)
        if (app.current is CalibrationScreen) { tap("skip0"); tap("skip1"); seconds(1.2f) }
        assertTrue(app.current is ReadyScreen, "ready: ${app.current}")
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE)
        seconds(1.6f)
        return app.current as GameScreen
    }

    @Test
    fun modeSwitchAndSportsGames() {
        frames(10)
        assertTrue(app.current is MainMenuScreen)
        assertEquals(GameMode.SPORTS, app.mode, "a new console opens in Sports & Arcade")
        shot("sports_menu")
        tap("modeXmas")
        assertEquals(GameMode.CHRISTMAS, app.mode)
        assertEquals("CHRISTMAS", app.settings.mode, "remembered")
        tap("modeSports")
        tap("quick")
        assertTrue(app.current is GameSelectScreen)
        seconds(0.5f)
        val ids = app.ui.boxIds()
        for (id in listOf("game_clays", "game_boxing", "game_archery", "game_tennis_s", "game_tabletennis_s", "game_bowling_s")) assertTrue(id in ids, "$id offered: $ids")
        assertTrue("game_snowball" !in ids, "no Christmas games in sports mode")
        shot("sports_select")
    }

    @Test
    fun teachAToyGunBeforeClayShootingThenPlayIt() {
        frames(10)
        tap("quick")
        tap("game_clays")
        assertTrue(app.current is PlayerPickScreen)
        val gs = setUpAndReady()
        assertEquals(Phase.INSTRUCTIONS, gs.session.phase)
        assertEquals(ObjectKind.GUN, app.objectKind)
        assertTrue(app.controls.all { it.wantFingers }, "finger tracking asked for")
        shot("clays_instructions")
        // Holly teaches her toy gun straight from the start screen
        app.settings.directionChecked.add("0")
        tap("objTeach0")
        assertTrue(app.current is HeldObjectScreen)
        assertEquals(ObjectKind.GUN, app.objectKind, "still a gun game underneath")
        shot("toy_gun_teach")
        tap("heldLearn")
        seconds(3.4f)
        assertEquals(1, platform.heldLearnRequests)
        val model = ObjectColorModel(FloatArray(ObjectColorModel.BINS) { if (it == 12) 1f else 0f }, 0.45f)
        platform.heldStatus = HeldLearnStatus(0, 1f, true, true, "Got it!", model)
        frames(10)
        tap("heldKeep")
        frames(5)
        assertTrue(app.current === gs, "back to the clay shooting start screen")
        assertTrue(app.hasHeldObject(0, ObjectKind.GUN))
        assertTrue(!app.hasHeldObject(0, ObjectKind.RACKET), "a toy gun is not a racket")
        assertTrue(app.controls.all { it.wantFingers }, "game controls restored after the detour")
        assertNotNull(app.heldModelFor(0), "the toy gun is looked for")
        shot("clays_instructions_gun")
        // she can still choose her finger gun instead
        tap("objHands0")
        assertNull(app.heldModelFor(0))
        tap("objUse0")
        assertNotNull(app.heldModelFor(0))
        // play the match through (scripted players)
        app.sim.autoplay = true
        var guard = 0
        while (app.current !is ResultsScreen && guard++ < 200) seconds(1f)
        assertTrue(app.current is ResultsScreen, "match finished")
        shot("clays_results")
        assertNull(app.objectKind, "no game object outside the game")
        assertTrue(app.controls.none { it.wantFingers }, "finger tracking off again")
    }

    @Test
    fun boxingAndArcheryRunThroughTheApp() {
        for (id in listOf("game_boxing", "game_archery")) {
            app.popToRoot(); frames(5)
            tap("quick")
            tap(id)
            val gs = setUpAndReady()
            app.sim.autoplay = true
            var guard = 0
            while (app.current !is ResultsScreen && guard++ < 400) {
                seconds(1f)
                if (guard == 12) shot("${id.removePrefix("game_")}_live")
            }
            assertTrue(app.current is ResultsScreen, "$id finished")
            app.sim.autoplay = false
            assertTrue(gs.session.game.isOver)
        }
    }
}
