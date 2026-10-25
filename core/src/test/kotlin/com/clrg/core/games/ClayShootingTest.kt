package com.clrg.core.games

import com.clrg.core.game.CpuLevel
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rng
import com.clrg.core.tracking.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clay Shooting rules at the game-input level (scripted aim and shots). Says nothing about real camera tracking.
 */
class ClayShootingTest {
    private fun driver(modes: Array<MovementMode> = arrayOf(MovementMode.STANDARD, MovementMode.STANDARD)) =
        GameDriver({ ClayShootingGame(it) }, seed = 11, modes = modes).apply {
            for (i in inputs) { i.gunHand = RIGHT; i.aimSeen[RIGHT] = true; i.aimX[RIGHT] = 0.15f; i.aimY[RIGHT] = -0.02f }
        }

    /** Points player [k]'s gun hand where the crosshair should be at ([sx], [sy]). */
    private fun aimAt(d: GameDriver<ClayShootingGame>, k: Int, sx: Float, sy: Float) {
        val (ax, ay) = d.game.aim[k].aimFor(d.inputs[k], sx, sy)
        d.inputs[k].aimX[RIGHT] = ax; d.inputs[k].aimY[RIGHT] = ay
    }

    private fun shot(d: GameDriver<ClayShootingGame>, k: Int, at: Double = Double.NaN) {
        val e = GestureEvent.Shot(0.0, RIGHT, d.inputs[k].aimX[RIGHT], d.inputs[k].aimY[RIGHT], byThumb = true)
        e.at = at
        d.inputs[k].events.add(e)
    }

    /** Past the intro, both players' aim set; then wait until the first clay has flown for [into] seconds. */
    private fun firstClay(d: GameDriver<ClayShootingGame>, into: Double = 1.0): ClayShootingGame.Clay {
        d.run(0.5)
        shot(d, 0); shot(d, 1)
        d.run(0.1)
        assertTrue(d.game.loaded.all { it }, "centre shots load the guns")
        val c = d.game.clays.first()
        d.run(c.launch + into - d.time)
        return c
    }

    /** Follows clay [c] with player [k]'s hand for [sec] seconds (so the steadying filter catches up). */
    private fun track(d: GameDriver<ClayShootingGame>, k: Int, c: ClayShootingGame.Clay, sec: Double) {
        d.run(sec) { t -> d.game.screenOf(c, t)?.let { aimAt(d, k, it[0], it[1]) } }
    }

    @Test
    fun theCentreShotSetsWhereStraightAheadIs() {
        val d = driver()
        d.inputs[0].aimX[RIGHT] = 0.32f; d.inputs[0].aimY[RIGHT] = 0.08f
        d.run(0.5)
        shot(d, 0)
        d.run(0.6)
        // the hand where it was for the centre shot now puts the crosshair in the middle
        assertTrue(kotlin.math.abs(d.game.aim[0].x) < 15f && kotlin.math.abs(d.game.aim[0].y) < 15f, "centred: ${d.game.aim[0].x}, ${d.game.aim[0].y}")
        assertEquals(0, d.game.shotsFired[0], "the centre shot is not a scored shot")
    }

    @Test
    fun aShotOnTheClayBreaksItAndAMissDoesNot() {
        val d = driver()
        val c = firstClay(d)
        track(d, 0, c, 0.4)
        shot(d, 0)
        d.run(0.05)
        assertEquals(1, d.game.hits[0], "on target")
        assertEquals(0, c.hitBy)
        // the other player shoots well wide of the next clay
        val c2 = d.game.clays[1]
        d.run(c2.launch + 1.0 - d.time)
        d.run(0.5) { t -> d.game.screenOf(c2, t)?.let { aimAt(d, 1, it[0] + 260f, it[1] - 200f) } }
        shot(d, 1)
        d.run(0.05)
        assertEquals(0, d.game.hits[1], "wide shot misses")
        assertEquals(1, d.game.shotsFired[1])
    }

    @Test
    fun shotsAreJudgedWhenTheyWereMade() {
        val d = driver()
        val c = firstClay(d, 0.8)
        track(d, 0, c, 0.4)
        val madeAt = d.time
        // the hand stops while the shot travels through the camera and the TV (0.3 s); the clay flies on
        d.run(0.3)
        shot(d, 0, at = madeAt)
        d.run(0.05)
        assertEquals(1, d.game.hits[0], "judged as the player saw it")
        // the same late shot judged "now" would have missed
        val d2 = driver()
        val c2 = firstClay(d2, 0.8)
        track(d2, 0, c2, 0.4)
        d2.run(0.3)
        shot(d2, 0)
        d2.run(0.05)
        assertEquals(0, d2.game.hits[0], "without the delay allowance the clay has moved on")
    }

    @Test
    fun theFirstShotMadeWinsTheClay() {
        val d = driver()
        val c = firstClay(d)
        d.run(0.4) { t -> d.game.screenOf(c, t)?.let { aimAt(d, 0, it[0], it[1]); aimAt(d, 1, it[0], it[1]) } }
        val t0 = d.time
        // Holly's shot arrives first, but Jasper pulled the trigger 50 ms earlier (his camera picture was later)
        shot(d, 0, at = t0)
        d.run(0.01)
        assertEquals(0, c.hitBy)
        shot(d, 1, at = t0 - 0.05)
        d.run(0.4)
        assertEquals(1, c.hitBy, "the earlier shot takes the clay")
        assertEquals(0, d.game.points[0])
        assertEquals(1, d.game.points[1])
    }

    @Test
    fun twoShellsThenAShortReload() {
        val d = driver()
        firstClay(d)
        repeat(3) { shot(d, 0); d.run(0.05) }
        assertEquals(2, d.game.shotsFired[0], "third pull clicks empty")
        d.run(1.2)
        shot(d, 0); d.run(0.05)
        assertEquals(3, d.game.shotsFired[0], "reloaded")
    }

    @Test
    fun gentleModeFiresWhenTheCrosshairRestsOnAClay() {
        val d = driver(arrayOf(MovementMode.SEATED, MovementMode.STANDARD))
        val c = firstClay(d, 0.6)
        track(d, 0, c, 0.9)
        assertTrue(d.game.hits[0] >= 1, "auto fire for seated players")
    }

    @Test
    fun harderComputerShootsBetter() {
        var hardWins = 0
        for (seed in 1L..6L) {
            val d = GameDriver({ ClayShootingGame(it) }, seed = seed)
            val rng = arrayOf(Rng(seed), Rng(seed + 50))
            d.runUntilOver(200.0) { _ ->
                d.game.autoplay(0, d.inputs[0], CpuLevel.HARD.skill, rng[0])
                d.game.autoplay(1, d.inputs[1], CpuLevel.EASY.skill, rng[1])
            }
            assertTrue(d.game.isOver)
            if (d.game.points[0] > d.game.points[1]) hardWins++
            if (seed == 1L) println("[clays] hard ${d.game.points[0]} (${d.game.result().details}) vs easy ${d.game.points[1]}")
        }
        assertTrue(hardWins >= 5, "hard beat easy $hardWins of 6")
    }

    @Test
    fun midFlightScreenshot() {
        val d = driver()
        val c = firstClay(d, 0.9)
        track(d, 0, c, 0.3)
        d.screenshot("clays_flight")
    }
}
