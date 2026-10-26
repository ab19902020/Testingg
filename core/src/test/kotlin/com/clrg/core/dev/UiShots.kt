package com.clrg.core.dev

import com.clrg.core.app.GameApp
import com.clrg.core.audio.AudioEngine
import com.clrg.core.game.Phase
import com.clrg.core.games.GameMode
import com.clrg.core.party.MemoryStore
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.SimAction
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.Key
import com.clrg.core.ui.screens.GameScreen
import com.clrg.core.ui.screens.ResultsScreen
import com.clrg.core.app.NullPlatform
import java.io.File
import kotlin.test.Test

/**
 * Developer tool (-Pclrg.uishots=<dir>): drives the app with simulated input and saves settled screenshots of the
 * menus and game screens for design review. Not a test of tracking.
 */
class UiShots {
    private val dir = System.getProperty("clrg.uishots")
    private val store = MemoryStore()
    private val platform = NullPlatform(store)
    private val app = GameApp(platform, AudioEngine())
    private val g = NullGfx()
    private var now = 0L

    private fun frames(n: Int) = repeat(n) { now += 16_666_667L; platform.clock = now; app.frame(g, now) }
    private fun seconds(s: Float) = frames((s * 60).toInt())
    private fun tap(id: String) {
        frames(2)
        val r = app.ui.boxRect(id) ?: error("no $id on ${app.current?.javaClass?.simpleName}: ${app.ui.boxIds()}")
        app.submitInput(InputEvent.Touch(InputEvent.Touch.DOWN, r.cx, r.cy))
        app.submitInput(InputEvent.Touch(InputEvent.Touch.UP, r.cx, r.cy))
        frames(2)
    }
    private fun shot(name: String, settle: Int = 40) {
        frames(settle)
        val awt = AwtGfx(1920, 1080)
        now += 16_666_667L; platform.clock = now
        app.frame(awt, now)
        awt.writePng(File(dir, "$name.png"))
    }

    private fun toGame(id: String) {
        tap("quick"); tap("game_$id"); tap("play")
        seconds(3f); runCatching { tap("skip0"); tap("skip1") }; seconds(1.2f)
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE); seconds(1.6f)
    }

    @Test
    fun shots() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null, "developer tool: run with -Pclrg.uishots=<dir>")
        File(dir!!).mkdirs()
        app.settings.simulatedInput = true
        app.applySettings()
        app.sim.controlsVisible = false
        app.mode = GameMode.SPORTS
        frames(10)
        shot("menu_sports")
        app.ui.focusId = "solo"
        shot("menu_sports_focus", 30)
        app.ui.focusId = null
        tap("modeXmas")
        shot("menu_xmas")
        tap("modeSports")
        tap("quick")
        shot("select_sports")
        tap("modeXmas")
        shot("select_xmas")
        tap("modeSports")
        app.key(Key.BACK); frames(5)
        toGame("boxing")
        val gs = app.current as GameScreen
        shot("instructions_boxing")
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE); seconds(1.8f)
        app.sim.autoplay = true
        seconds(4f)
        shot("live_boxing", 1)
        app.key(Key.BACK); frames(3)
        shot("pause", 20)
        tap("resume")
        var guard = 0
        while (app.current !is ResultsScreen && guard++ < 300) seconds(1f)
        shot("results", 90)
        check(gs.session.phase == Phase.DONE || app.current is ResultsScreen)
        app.sim.autoplay = false
        tap("menu")
        tap("settings")
        shot("settings")
        app.key(Key.BACK); frames(5)
        tap("golf")
        shot("golf_setup")
        tap("golf_course3")
        tap("golf_go")
        shot("golf_fly1", 50)
        shot("golf_fly2", 80)
        app.popToRoot(); frames(5)
        for (id in listOf("players", "help", "solo", "tournament")) {
            tap(id); shot("screen_$id"); app.key(Key.BACK); frames(5)
        }
        toGame("clays")
        shot("instructions_clays")
        app.key(Key.BACK); frames(5); app.popToRoot(); frames(5)
        tap("modeXmas")
        tap("quick"); tap("game_snowball"); tap("play")
        seconds(0.6f); shot("framing", 1)
        seconds(3f); shot("calibration", 1)
        runCatching { tap("skip0"); tap("skip1") }; seconds(1.2f)
        shot("ready", 1)
        app.sim.trigger(0, SimAction.RAISE); app.sim.trigger(1, SimAction.RAISE); seconds(1.6f)
        shot("instructions_snowball")
    }
}
