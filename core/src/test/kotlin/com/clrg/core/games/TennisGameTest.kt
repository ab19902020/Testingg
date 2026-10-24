package com.clrg.core.games

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TennisGameTest {
    /** Swings for [player] at the perfect moment (plus [offset] seconds). */
    private fun GameDriver<TennisGame>.autoSwing(player: Int, offset: Double = 0.0) {
        val g = game
        if (g.state == RallyGame.State.SERVE && g.server == player && g.stateTime > 0.8f) swing(player)
        if (g.state == RallyGame.State.RALLY && g.receiver == player && !g.hitTime.isNaN() && abs(time - (g.hitTime + offset)) < dt / 2 + 1e-9) swing(player)
    }

    @Test
    fun perfectTimingKeepsTheRallyGoing() {
        val d = GameDriver(::TennisGame)
        d.run(30.0) { d.autoSwing(0); d.autoSwing(1) }
        assertEquals(0, d.game.scores.sum(), "no points lost with perfect play")
        assertTrue(d.game.rally >= 12, "rally length ${d.game.rally}")
        d.screenshot("tennis_rally")
    }

    @Test
    fun receiverWhoNeverSwingsLosesThePoint() {
        val d = GameDriver(::TennisGame)
        d.run(6.0) { d.autoSwing(0) } // only player 1 plays; P1 serves first
        assertEquals(1, d.game.scores[0])
        assertEquals(RallyGame.PointReason.MISSED, d.game.pointReason)
    }

    @Test
    fun farTooEarlyGoesOutAndFarTooLateHitsTheNet() {
        val d = GameDriver(::TennisGame)
        // P2 swings 0.27 s early (beyond 70% of the 0.32 s window) -> long -> OUT -> point to P1
        d.run(8.0) { d.autoSwing(0); d.autoSwing(1, offset = -0.27) }
        assertEquals(1, d.game.scores[0], "early return should be out")
        assertEquals(RallyGame.PointReason.OUT, d.game.pointReason)
        val e = GameDriver(::TennisGame)
        e.run(8.0) { e.autoSwing(0); e.autoSwing(1, offset = 0.28) }
        assertEquals(1, e.game.scores[0], "late return should hit the net")
        assertEquals(RallyGame.PointReason.NET, e.game.pointReason)
    }

    @Test
    fun slightlyOffTimingStillReturnsTheBall() {
        val d = GameDriver(::TennisGame)
        d.run(12.0) { d.autoSwing(0, offset = 0.12); d.autoSwing(1, offset = -0.15) }
        assertEquals(0, d.game.scores.sum())
    }

    @Test
    fun firstToSevenWinsAndServeAlternates() {
        val d = GameDriver(::TennisGame)
        val servers = ArrayList<Int>()
        var lastState = d.game.state
        d.runUntilOver(200.0) { _ ->
            d.autoSwing(0) // P2 never returns: P1 wins every rally P1 starts... and P2 serves are missed by nobody
            if (d.game.state == RallyGame.State.SERVE && lastState != RallyGame.State.SERVE) servers.add(d.game.server)
            lastState = d.game.state
            // P2 serves too (but never returns)
            if (d.game.state == RallyGame.State.SERVE && d.game.server == 1 && d.game.stateTime > 0.8f) d.swing(1)
        }
        assertTrue(d.game.isOver)
        assertEquals(7, d.game.scores.max())
        assertEquals(0, d.game.result().winner)
        assertTrue(servers.zipWithNext().all { (a, b) -> a != b }, "serve alternates every point: $servers")
        d.screenshot("tennis_over")
    }

    @Test
    fun autoServeKeepsTheGameMoving() {
        val d = GameDriver(::TennisGame)
        d.run(9.0)
        assertTrue(d.game.state != RallyGame.State.SERVE || d.game.scores.sum() > 0, "server did nothing; game auto-served")
    }

    @Test
    fun rematchStartsFresh() {
        val d = GameDriver(::TennisGame)
        d.runUntilOver(200.0) { d.autoSwing(0); if (d.game.state == RallyGame.State.SERVE && d.game.server == 1 && d.game.stateTime > 0.8f) d.swing(1) }
        val again = GameDriver(::TennisGame)
        assertEquals(0, again.game.scores.sum())
        assertEquals(RallyGame.State.SERVE, again.game.state)
    }
}

class TennisScreenshots {
    @Test
    fun captureRallyMoments() {
        val d = GameDriver(::TennisGame)
        var shot = 0
        d.run(6.0) { t ->
            val g = d.game
            if (g.state == RallyGame.State.SERVE && g.server == 0 && g.stateTime > 0.8f) d.swing(0)
            for (p in 0..1) if (g.state == RallyGame.State.RALLY && g.receiver == p && !g.hitTime.isNaN() && kotlin.math.abs(t - g.hitTime) < d.dt / 2 + 1e-9) d.swing(p)
            if (g.inWindow() && shot == 0) { d.screenshot("tennis_window"); shot++ }
        }
        d.screenshot("tennis_midrally")
    }
}
