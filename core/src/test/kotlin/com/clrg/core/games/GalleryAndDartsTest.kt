package com.clrg.core.games

import com.clrg.core.game.CpuLevel
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.PunchKind
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shooting Gallery and Darts rules at the game-input level (scripted). Says nothing about real camera tracking. */
class GalleryAndDartsTest {
    private fun <G : com.clrg.core.game.MiniGame> cpuMatch(make: (com.clrg.core.game.GameEnv) -> G, seed: Long, shotName: String? = null): GameDriver<G> {
        val d = GameDriver(make, seed = seed)
        val rng = arrayOf(Rng(seed), Rng(seed + 21))
        var shot = false
        d.runUntilOver(300.0) { t ->
            d.game.autoplay(0, d.inputs[0], CpuLevel.HARD.skill, rng[0])
            d.game.autoplay(1, d.inputs[1], CpuLevel.EASY.skill, rng[1])
            if (shotName != null && !shot && t > 25.0) { shot = true; d.screenshot(shotName) }
        }
        assertTrue(d.game.isOver)
        return d
    }

    @Test
    fun galleryHardBeatsEasy() {
        var wins = 0
        for (seed in 1L..6L) {
            val d = cpuMatch({ ShootingGalleryGame(it) }, seed, if (seed == 1L) "gallery_mid" else null)
            if (d.game.points[0] > d.game.points[1]) wins++
            if (seed == 1L) println("[gallery] ${d.game.points.toList()} ${d.game.result().details}")
        }
        assertTrue(wins >= 5, "hard won $wins of 6")
    }

    @Test
    fun galleryEarlierShotTakesTheTarget() {
        val d = GameDriver({ ShootingGalleryGame(it) }, seed = 2)
        for (i in d.inputs) { i.gunHand = RIGHT; i.aimSeen[RIGHT] = true }
        d.run(ShootingGalleryGame.INTRO_SEC + 1.0)
        val tg = d.game.targets.first { it.alive(d.time) && it.kind == ShootingGalleryGame.Kind.DUCK }
        d.run(0.4) { t -> for (k in 0..1) { val (ax, ay) = d.game.aim[k].aimFor(d.inputs[k], tg.x(t), tg.y(t)); d.inputs[k].aimX[RIGHT] = ax; d.inputs[k].aimY[RIGHT] = ay } }
        val t0 = d.time
        d.inputs[0].events.add(GestureEvent.Shot(0.0, RIGHT, Float.NaN, Float.NaN, true).also { it.at = t0 })
        d.run(0.01)
        assertEquals(0, tg.hitBy)
        d.inputs[1].events.add(GestureEvent.Shot(0.0, RIGHT, Float.NaN, Float.NaN, true).also { it.at = t0 - 0.05 })
        d.run(0.4)
        assertEquals(1, tg.hitBy, "the shot made first wins")
        assertEquals(tg.kind.points, d.game.points[1])
        assertEquals(0, d.game.points[0])
    }

    @Test
    fun galleryAmmoAndReload() {
        val d = GameDriver({ ShootingGalleryGame(it) }, seed = 3)
        d.run(ShootingGalleryGame.INTRO_SEC + 0.5)
        repeat(8) { d.inputs[0].events.add(GestureEvent.Shot(0.0, RIGHT, Float.NaN, Float.NaN, true)); d.run(0.05) }
        assertEquals(ShootingGalleryGame.ROUNDS_IN_GUN, d.game.shotsFired[0], "six shots, then empty")
        d.run(ShootingGalleryGame.RELOAD_SEC + 0.1)
        d.inputs[0].events.add(GestureEvent.Shot(0.0, RIGHT, Float.NaN, Float.NaN, true)); d.run(0.05)
        assertEquals(ShootingGalleryGame.ROUNDS_IN_GUN + 1, d.game.shotsFired[0], "reloaded")
    }

    @Test
    fun dartboardScoring() {
        assertEquals(50 to "BULL", DartsGame.score(0f, 0f))
        assertEquals(25 to "25", DartsGame.score(10f, 0f))
        assertEquals(60 to "T20", DartsGame.score(0f, 103f))
        assertEquals(40 to "D20", DartsGame.score(0f, 166f))
        assertEquals(20 to "20", DartsGame.score(0f, 60f))
        assertEquals(6 to "6", DartsGame.score(60f, 0f), "6 is at three o'clock")
        assertEquals(3 to "3", DartsGame.score(0f, -60f), "3 is at the bottom")
        assertEquals(11 to "11", DartsGame.score(-60f, 0f), "11 is at nine o'clock")
        assertEquals(0 to "Miss", DartsGame.score(0f, 175f))
    }

    @Test
    fun theDartGoesWhereYouHeldSteadyNotWhereTheThrowEnded() {
        val d = GameDriver({ DartsGame(it) }, seed = 5)
        d.inputs[0].dominant = RIGHT
        d.inputs[0].aimSeen[RIGHT] = true
        // hold the sight on treble 20 for a moment
        val (ax, ay) = d.game.aim[0].aimFor(d.inputs[0], 0f, -10f - 103f * DartsGame.PX_PER_MM)
        d.inputs[0].aimX[RIGHT] = ax; d.inputs[0].aimY[RIGHT] = ay
        d.run(1.2)
        // the throw drags the hand down and to the side, then the throw is recognised
        d.run(0.2) { t -> d.inputs[0].aimY[RIGHT] = ay - 0.6f * ((t % 1.0).toFloat()); d.inputs[0].aimX[RIGHT] = ax + 0.2f }
        d.inputs[0].events.add(GestureEvent.Punch(0.0, RIGHT, PunchKind.STRAIGHT, 0.8f, 0f, 0f))
        d.run(0.6)
        val dart = d.game.darts.last()
        assertEquals("T20", dart.label, "landed at ${dart.x}, ${dart.y}")
        // a floppy throw drops
        d.run(1.0)
        d.inputs[0].events.add(GestureEvent.Punch(0.0, RIGHT, PunchKind.STRAIGHT, 0.05f, 0f, 0f))
        d.run(0.6)
        assertTrue(d.game.darts.last().y < 103f - 30f, "weak throw lands low: ${d.game.darts.last().y}")
    }

    @Test
    fun dartsHardBeatsEasy() {
        var wins = 0
        for (seed in 1L..6L) {
            val d = cpuMatch({ DartsGame(it) }, seed, if (seed == 1L) "darts_mid" else null)
            if (d.game.totals[0] > d.game.totals[1]) wins++
            if (seed == 1L) println("[darts] ${d.game.totals.toList()} ${d.game.result().details}")
        }
        assertTrue(wins >= 5, "hard won $wins of 6")
    }
}
