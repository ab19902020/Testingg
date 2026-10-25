package com.clrg.core.ui

import com.clrg.core.math.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hands-free dwell selection: progress, timing and accidental-activation protection. */
class UiDwellTest {
    private val ui = Ui(Theme()) {}
    private val dt = 1f / 60f
    private val boxes = mapOf("a" to Rect(0f, 0f, 200f, 100f), "b" to Rect(300f, 0f, 500f, 100f), "off" to Rect(600f, 0f, 800f, 100f))
    private val activations = ArrayList<Pair<Double, String>>()

    init { ui.dwellSeconds = 1.5f }

    /** Runs [seconds] of frames; [place] positions the cursors before each frame. */
    private fun run(seconds: Float, cursorsEnabled: Boolean = true, disabled: Set<String> = emptySet(), place: () -> Unit = {}) {
        repeat((seconds / dt).toInt()) {
            place()
            ui.begin(dt, emptyList(), cursorsEnabled)
            ui.activated?.let { activations.add(ui.time to it) }
            for ((id, r) in boxes) if (id != "off") ui.register(id, r, enabled = id !in disabled)
            ui.end()
        }
    }

    private fun point(k: Int, id: String?) {
        val c = ui.cursors[k]
        c.active = id != null
        if (id != null) { val r = boxes.getValue(id); c.x = r.cx; c.y = r.cy }
    }

    @Test
    fun holdingSelectsOnlyAfterTheDwellTime() {
        run(1.4f) { point(0, "a") }
        assertTrue(activations.isEmpty(), "nothing selected before the ring fills")
        assertTrue(ui.dwellOn("a") > 0.85f, "progress is shown while holding: ${ui.dwellOn("a")}")
        run(0.2f) { point(0, "a") }
        assertEquals(listOf("a"), activations.map { it.second })
    }

    @Test
    fun sweepingAcrossButtonsNeverSelects() {
        var t = 0f
        run(6f) { t += dt; point(0, if ((t / 0.7f).toInt() % 2 == 0) "a" else "b") }
        assertTrue(activations.isEmpty(), "a hand passing over buttons must not select: $activations")
    }

    @Test
    fun cursorMustLeaveBeforeTheSameButtonCanFireAgain() {
        run(6f) { point(0, "a") }
        assertEquals(1, activations.size, "keeping the hand on a button selects it once")
        run(0.3f) { point(0, "off") }
        run(1.6f) { point(0, "a") }
        assertEquals(2, activations.size, "leaving and coming back allows a new selection")
    }

    @Test
    fun otherPlayerCannotSelectStraightAfterASelection() {
        // both players dwell; player 1 started a little later
        run(0.2f) { point(0, "a") }
        run(1.4f) { point(0, "a"); point(1, "b") }
        assertEquals(listOf("a"), activations.map { it.second })
        val firstAt = activations[0].first
        run(3f) { point(0, "a"); point(1, "b") }
        val second = activations.getOrNull(1)
        assertTrue(second != null && second.second == "b", "player 2 still gets their selection: $activations")
        assertTrue(second.first - firstAt >= 0.8 + 1.5 - 0.05, "global lock + a full dwell before the next selection: ${second.first - firstAt}")
    }

    @Test
    fun loweringTheHandResetsProgress() {
        run(1.2f) { point(0, "a") }
        run(0.05f) { point(0, null) }
        run(1.2f) { point(0, "a") }
        assertTrue(activations.isEmpty(), "dwell restarts after the hand drops")
        run(0.4f) { point(0, "a") }
        assertEquals(1, activations.size)
    }

    @Test
    fun noSelectionWhenCursorsAreDisabledOrButtonIsDisabled() {
        run(4f, cursorsEnabled = false) { point(0, "a") }
        assertTrue(activations.isEmpty(), "cursors are off during live gameplay")
        run(4f, disabled = setOf("a")) { point(0, "a") }
        assertTrue(activations.isEmpty(), "disabled buttons never fire")
        assertNull(ui.activated)
    }
}
