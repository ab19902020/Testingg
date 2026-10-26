package com.clrg.core.dev

import com.clrg.core.game.MiniGame
import com.clrg.core.games.GameDriver
import com.clrg.core.games.GameRegistry
import com.clrg.core.math.Rng
import com.clrg.core.render.CostGfx
import java.io.File
import kotlin.test.Test

/**
 * Developer tool (-Pclrg.drawcost=<file.csv>): plays every game with computer players and counts the drawing work of
 * each frame (shapes, points, costly fills). A stand-in for frame cost on a phone, to compare versions - not a
 * measurement of frame rate.
 */
class DrawCost {
    data class Cost(val id: String, val meanOps: Double, val p95Ops: Int, val maxOps: Int, val meanHeavy: Double, val maxPoints: Int, val buildOps: Int)

    companion object {
        fun measure(id: String, seconds: Double = 40.0): Cost {
            val entry = GameRegistry.byId(id)!!
            val d = GameDriver<MiniGame>({ env -> entry.create(env) }, seed = 99)
            val rngs = arrayOf(Rng(1), Rng(2))
            val g = CostGfx()
            val ops = ArrayList<Int>(); val heavy = ArrayList<Int>(); var maxPts = 0
            var nextFrame = 0.0
            var t0 = 0.0
            d.runUntilOver(seconds) { t ->
                t0 = t
                for (k in 0..1) d.game.autoplay(k, d.inputs[k], if (k == 0) 0.9f else 0.6f, rngs[k])
                if (t >= nextFrame) {
                    nextFrame += 1.0 / 30.0
                    g.resetFrame()
                    d.game.render(g, d.inputs, t); d.env.particles.render(g); d.env.popups.render(g)
                    ops.add(g.ops); heavy.add(g.heavy); maxPts = maxOf(maxPts, g.points)
                }
            }
            val sorted = ops.sorted()
            return Cost(id, ops.average(), sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)], sorted.last(), heavy.average(), maxPts, g.layerBuildOps)
        }
    }

    @Test
    fun drawCost() {
        val out = System.getProperty("clrg.drawcost")
        org.junit.jupiter.api.Assumptions.assumeTrue(out != null, "developer tool: run with -Pclrg.drawcost=<file.csv>")
        val rows = GameRegistry.ids.map { measure(it) }
        val f = File(out!!)
        f.parentFile?.mkdirs()
        f.writeText("game,mean_ops,p95_ops,max_ops,mean_heavy,max_points,layer_build_ops\n" + rows.joinToString("\n") {
            "${it.id},${"%.0f".format(it.meanOps)},${it.p95Ops},${it.maxOps},${"%.1f".format(it.meanHeavy)},${it.maxPoints},${it.buildOps}"
        } + "\n")
        println(f.readText())
    }
}
