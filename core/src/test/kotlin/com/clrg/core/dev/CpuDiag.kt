package com.clrg.core.dev

import com.clrg.core.game.CpuLevel
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameSession
import com.clrg.core.game.Phase
import com.clrg.core.game.PlayerInfo
import com.clrg.core.games.GameRegistry
import com.clrg.core.gesture.PlayerControls
import com.clrg.core.party.Settings
import com.clrg.core.render.PlayerColors
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import com.clrg.core.tracking.PlayerTracker
import kotlin.test.Test

/** Developer diagnostic (only runs with -Dclrg.cpudiag=<gameId>): prints CPU match details per level. */
class CpuDiag {
    @Test
    fun diag() {
        val id = System.getProperty("clrg.cpudiag")
        org.junit.jupiter.api.Assumptions.assumeTrue(id != null, "developer diagnostic: run with -Pclrg.cpudiag=<gameId>")
        id!!
        val pairs = listOf(CpuLevel.EASY to CpuLevel.MEDIUM, CpuLevel.EASY to CpuLevel.HARD, CpuLevel.MEDIUM to CpuLevel.HARD)
        for ((a, b) in pairs) for (seed in 0 until 4L) {
            val env = GameEnv(listOf(
                PlayerInfo(0, -1, a.label, PlayerColors.get(0), Hand.RIGHT, MovementMode.STANDARD, true, a.skill),
                PlayerInfo(1, -2, b.label, PlayerColors.get(1), Hand.RIGHT, MovementMode.STANDARD, true, b.skill)), Settings(), 1000 + seed)
            val s = GameSession(GameRegistry.byId(id)!!.create(env), env)
            val c = arrayOf(PlayerControls(0), PlayerControls(1)); val slots = PlayerTracker(2).slots
            var t = 0.0
            while (s.phase != Phase.DONE && t < 900) { s.update(1.0 / 60, c, slots); t += 1.0 / 60 }
            val r = s.game.result()
            println("[diag] $id ${a.label} vs ${b.label} seed $seed: scores ${r.scores.toList()} winner ${r.winner} | ${r.details.joinToString(" | ")}")
        }
    }
}
