package com.clrg.core.games

import com.clrg.core.game.CpuLevel
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.LEFT
import com.clrg.core.math.Rng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Archery rules at the game-input level (scripted aim and releases). Says nothing about real camera tracking. */
class ArcheryTest {
    private fun driver() = GameDriver({ ArcheryGame(it) }, seed = 4).apply {
        for (i in inputs) { i.bowHand = LEFT; i.aimSeen[LEFT] = true; i.aimX[LEFT] = -0.15f; i.aimY[LEFT] = -0.02f }
        run(0.5)
    }

    /** Aims player [k]'s bow hand at target point ([tx], [ty]) (m from the centre) and lets the sight settle. */
    private fun aimAtTarget(d: GameDriver<ArcheryGame>, k: Int, tx: Float, ty: Float) {
        // search the screen point that maps to the target point (the mapping is smooth and monotonic)
        var sx = 0f; var sy = 0f
        val dist = ArcheryGame.DISTANCE[d.game.end(k)]
        repeat(60) {
            val (px, py) = d.game.targetPoint(sx, sy, dist)
            sx += (tx - px) * 300f; sy -= (ty - py) * 300f
        }
        val (ax, ay) = d.game.aim[k].aimFor(d.inputs[k], sx, sy)
        d.inputs[k].aimX[LEFT] = ax; d.inputs[k].aimY[LEFT] = ay
        d.run(0.8)
    }

    private fun release(d: GameDriver<ArcheryGame>, k: Int, power: Float) {
        d.inputs[k].events.add(GestureEvent.Release(0.0, LEFT, power, d.inputs[k].aimX[LEFT], d.inputs[k].aimY[LEFT]))
        d.run(1.5)
    }

    @Test
    fun ringScoring() {
        assertEquals(10, ArcheryGame.ringScore(0f))
        assertEquals(10, ArcheryGame.ringScore(ArcheryGame.FACE_R * 0.09f))
        assertEquals(9, ArcheryGame.ringScore(ArcheryGame.FACE_R * 0.11f))
        assertEquals(1, ArcheryGame.ringScore(ArcheryGame.FACE_R * 0.99f))
        assertEquals(0, ArcheryGame.ringScore(ArcheryGame.FACE_R * 1.01f))
    }

    @Test
    fun aFullDrawFliesWhereYouAimAndAWeakOneDrops() {
        val d = driver()
        aimAtTarget(d, 0, 0f, 0f)
        release(d, 0, 1f)
        val a = d.game.arrows.last()
        assertTrue(abs(a.ix) < 0.05f && abs(a.iy) < 0.05f, "full draw on the gold: ${a.ix}, ${a.iy}")
        assertEquals(10, a.score)
        release(d, 0, 0.5f)
        val b = d.game.arrows.last()
        assertTrue(b.iy < -0.15f, "half draw drops: ${b.iy}")
        assertTrue(b.score < 10)
    }

    @Test
    fun windPushesTheArrowOnLongerEnds() {
        val d = driver()
        aimAtTarget(d, 0, 0f, 0f)
        repeat(3) { release(d, 0, 1f) }
        assertEquals(1, d.game.end(0), "second end")
        aimAtTarget(d, 0, 0f, 0f)
        release(d, 0, 1f)
        val a = d.game.arrows.last()
        assertTrue(abs(a.ix) > 0.02f, "wind drift ${a.ix}")
        assertTrue(a.ix * d.game.wind(0) > 0f, "drifts downwind")
    }

    @Test
    fun nineArrowsEachThenTheMatchEnds() {
        val d = driver()
        for (k in 0..1) aimAtTarget(d, k, 0.1f, 0.05f)
        repeat(9) { release(d, 0, 1f); release(d, 1, 0.9f) }
        assertEquals(9, d.game.shots[0]); assertEquals(9, d.game.shots[1])
        d.run(2.0)
        assertTrue(d.game.isOver)
        assertTrue(d.game.scores[0] > 0)
        // more arrows do nothing
        release(d, 0, 1f)
        assertEquals(9, d.game.shots[0])
    }

    @Test
    fun harderComputerScoresMore() {
        var wins = 0
        for (seed in 1L..6L) {
            val d = GameDriver({ ArcheryGame(it) }, seed = seed)
            val rng = arrayOf(Rng(seed), Rng(seed + 3))
            var shot = false
            d.runUntilOver(300.0) { t ->
                d.game.autoplay(0, d.inputs[0], CpuLevel.HARD.skill, rng[0])
                d.game.autoplay(1, d.inputs[1], CpuLevel.EASY.skill, rng[1])
                if (seed == 1L && !shot && t > 16.0) { shot = true; d.screenshot("archery_mid") }
            }
            assertTrue(d.game.isOver)
            if (d.game.scores[0] > d.game.scores[1]) wins++
            if (seed == 1L) println("[archery] ${d.game.scores.toList()} ${d.game.result().details}")
        }
        assertTrue(wins >= 5, "hard won $wins of 6")
    }
}
