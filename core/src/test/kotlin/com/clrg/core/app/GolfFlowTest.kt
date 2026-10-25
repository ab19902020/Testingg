package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.golf.Club
import com.clrg.core.golf.GolfMatch
import com.clrg.core.party.MemoryStore
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.SimAction
import com.clrg.core.ui.screens.GolfScreen
import com.clrg.core.ui.screens.GolfSetupScreen
import com.clrg.core.ui.screens.MainMenuScreen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golf, played without touching the phone: from the main menu, a person (the developer-mode simulated body, going
 * through the same tracker and gestures as the camera) points with a raised hand to open Golf, choose the golfers
 * and tee off, steps up and raises a hand to start their turn, picks a club by hovering, and swings.
 */
class GolfFlowTest {
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

    private fun shot(name: String) {
        val awt = AwtGfx(1920, 1080)
        now += 16_666_667L; platform.clock = now
        app.frame(awt, now)
        awt.writePng(File("build/screenshots/golf_$name.png"))
    }

    /** Raises player 1's hand to point at button [id] and holds it there until it is chosen, then lowers it. */
    private fun hover(id: String) {
        frames(2)
        val r = assertNotNull(app.ui.boxRect(id), "button '$id' not on screen ${app.current?.javaClass?.simpleName}: ${app.ui.boxIds()}")
        app.sim.pointAt(0, r.cx, r.cy, g.width, g.height)
        seconds(app.settings.dwellSeconds + 0.9f)
        app.sim.releaseReach(0)
        seconds(1.2f)
    }

    @Test
    fun golfFromTheMainMenuHandsFree() {
        seconds(1.5f)
        assertTrue(app.current is MainMenuScreen)
        assertTrue(!app.calibrated, "nobody has been set up")
        hover("golf")
        assertTrue(app.current is GolfSetupScreen, "hovering a raised hand over Golf opens it: ${app.current}")
        shot("setup")
        // just Holly, against the computer golfer Sprout
        val second = app.activePlayers[1].id
        hover("golf_p$second")
        hover("golf_cpu_EASY")
        hover("golf_go")
        val gs = app.current as GolfScreen
        val m = gs.match
        assertEquals(listOf(app.roster.players[0].name, "Sprout"), m.players.map { it.name })
        // Holly steps up: the game finds her and she raises a hand to start
        seconds(2.5f)
        shot("step_up")
        assertEquals(GolfMatch.Phase.TURN_START, m.phase)
        app.sim.trigger(0, SimAction.RAISE)
        seconds(2f)
        assertEquals(GolfMatch.Phase.AIM, m.phase, "a raised hand starts the turn")
        assertEquals(Club.IRON5, m.club, "148 m to the pin: the 5 iron is suggested")
        // change club by hovering over it
        hover("club_IRON9")
        assertEquals(Club.IRON9, m.club, "club chosen by hovering")
        hover("club_IRON5")
        assertEquals(Club.IRON5, m.club)
        shot("aim")
        // the swing: address, full backswing, downswing
        app.sim.golfRise = 1.3f
        app.sim.trigger(0, SimAction.GOLF)
        var guard = 0
        while (m.phase == GolfMatch.Phase.AIM && guard++ < 300) frames(1)
        assertEquals(GolfMatch.Phase.FLIGHT, m.phase, "the swing struck the ball")
        assertEquals(1, m.strokesThisHole)
        val plan = assertNotNull(m.plan)
        assertTrue(plan.carry > 110f, "a full 5 iron flies a long way: ${plan.carry} m")
        seconds(0.9f)
        shot("flight")
        // let the developer autoplay finish the round (both golfers), then check the scorecard
        app.sim.autoplay = true
        guard = 0
        val taken = HashSet<String>()
        var aimFrames = 0
        while (m.phase != GolfMatch.Phase.COURSE_DONE && guard++ < 2400) {
            seconds(0.25f)
            if (m.phase == GolfMatch.Phase.HOLE_DONE && m.holeIndex == 0 && taken.add("scorecard")) shot("scorecard")
            // a moment into aiming (the view has settled)
            aimFrames = if (m.phase == GolfMatch.Phase.AIM) aimFrames + 1 else 0
            if (aimFrames == 3 && m.club == Club.PUTTER && taken.add("putt")) shot("putt")
            if (aimFrames == 3 && m.holeIndex == 1 && m.strokesThisHole == 1 && taken.add("approach")) shot("approach")
        }
        assertEquals(GolfMatch.Phase.COURSE_DONE, m.phase, "round finished")
        for (i in 0..1) for (h in 0..2) assertTrue(m.strokes[i][h] in 1..m.course.holes[h].par + 4)
        shot("final")
        app.sim.autoplay = false
        hover("golf_menu")
        assertTrue(app.current is MainMenuScreen, "back to the menu by hovering")
        assertTrue(!app.tracker.solo, "two-player tracking again")
    }
}
