package com.clrg.core.dev

import com.clrg.core.game.MiniGame
import com.clrg.core.games.GameDriver
import com.clrg.core.games.GameRegistry
import com.clrg.core.math.Rng
import java.io.File
import kotlin.test.Test

/**
 * Developer tool (-Pclrg.gameshots=<dir>[:id,id,...]): plays games with the computer players and saves screenshots
 * at a few moments, for reviewing the artwork. Not a test of tracking.
 */
class GameShots {
    @Test
    fun shots() {
        val arg = System.getProperty("clrg.gameshots")
        org.junit.jupiter.api.Assumptions.assumeTrue(arg != null, "developer tool: run with -Pclrg.gameshots=<dir>[:ids]")
        val dir = File(arg!!.substringBefore(':')).also { it.mkdirs() }
        val ids = arg.substringAfter(':', "").split(',').filter { it.isNotBlank() }.ifEmpty { GameRegistry.ids }
        for (id in ids) {
            val entry = GameRegistry.byId(id) ?: continue
            val d = GameDriver<MiniGame>({ env -> entry.create(env) }, seed = 99)
            val rngs = arrayOf(Rng(1), Rng(2))
            val times = doubleArrayOf(3.0, 9.0, 20.0)
            var next = 0
            d.runUntilOver(200.0) { t ->
                for (k in 0..1) d.game.autoplay(k, d.inputs[k], if (k == 0) 0.9f else 0.6f, rngs[k])
                if (next < times.size && t >= times[next]) {
                    val g = com.clrg.core.render.AwtGfx(1920, 1080)
                    d.game.render(g, d.inputs, t); d.env.particles.render(g); d.env.popups.render(g)
                    g.writePng(File(dir, "${id}_$next.png"))
                    next++
                }
            }
        }
    }
}
