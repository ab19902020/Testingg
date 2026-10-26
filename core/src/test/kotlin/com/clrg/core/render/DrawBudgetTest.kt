package com.clrg.core.render

import com.clrg.core.dev.DrawCost
import com.clrg.core.games.GameRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against frames getting heavier: every game is played for a while by computer players and each frame's
 * drawing work is counted (see [CostGfx]). Also checks that cached pictures are made once and reused - a picture
 * remade every frame would quietly cost a lot on a phone. A stand-in for frame cost, not a frame-rate measurement.
 */
class DrawBudgetTest {
    @Test
    fun everyGameStaysWithinItsDrawingBudgetAndReusesItsPictures() {
        val problems = ArrayList<String>()
        for (id in GameRegistry.ids) {
            val c = DrawCost.measure(id, seconds = 20.0)
            if (c.p95Ops > MAX_OPS) problems.add("$id: ${c.p95Ops} drawing operations in a busy frame (budget $MAX_OPS)")
            if (c.builds > MAX_BUILDS) problems.add("$id: ${c.builds} cached pictures made in ${c.frames} frames - something is remade every frame")
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    companion object {
        /** Busiest frames (95th percentile) across games: before the cached scenery the heaviest game drew ~3000. */
        const val MAX_OPS = 1300
        /** Pictures made in 20 s of play: scenery, a few animation frames, tree sprites per fog step, new rounds. */
        const val MAX_BUILDS = 60
    }
}
