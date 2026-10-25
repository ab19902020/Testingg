package com.clrg.core.dev

import com.clrg.core.games.GameDriver
import com.clrg.core.games.RallyGame
import com.clrg.core.games.TableTennisGame
import com.clrg.core.games.TennisGame
import com.clrg.core.math.Rng
import kotlin.test.Test

/** Developer diagnostic (-Dclrg.rallydiag=1): match length and rally lengths for CPU pairs. */
class RallyDiag {
    @Test
    fun diag() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("clrg.rallydiag") != null, "developer diagnostic")
        for (game in listOf("tennis", "tabletennis")) for ((a, b) in listOf(0.9f to 0.6f, 0.5f to 0.5f, 0.2f to 0.2f)) {
            var total = 0.0; var points = 0; var rallies = 0; var longest = 0
            for (seed in 1..4L) {
                val d = if (game == "tennis") GameDriver(::TennisGame, seed = seed) else GameDriver(::TableTennisGame, seed = seed)
                val g = d.game as RallyGame
                val r0 = Rng(seed * 3); val r1 = Rng(seed * 7)
                var lastScore = 0
                var t = 0.0
                d.runUntilOver(900.0) { tt -> g.autoplay(0, d.inputs[0], a, r0); g.autoplay(1, d.inputs[1], b, r1); t = tt }
                total += d.time; points += g.scores.sum(); longest = maxOf(longest, g.longestRally)
                rallies += g.hitsMade.sum()
            }
            println("[rally] $game $a vs $b: match %.0f s, %.1f s/point, %.1f hits/point, longest $longest".format(total / 4, total / points, rallies.toDouble() / points))
        }
    }
}
