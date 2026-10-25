package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.party.MemoryStore
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.NullGfx
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Renders the hand pointer half-way through choosing a main-menu button (screenshot for the docs). */
class PointerLookTest {
    @Test
    fun pointerHalfWayThroughAChoice() {
        val store = MemoryStore()
        val platform = NullPlatform(store)
        val app = GameApp(platform, AudioEngine())
        app.settings.simulatedInput = true
        app.applySettings()
        app.sim.controlsVisible = false
        val g = NullGfx()
        var now = 0L
        fun frames(n: Int) = repeat(n) { now += 16_666_667L; platform.clock = now; app.frame(g, now) }
        frames(90)
        val r = assertNotNull(app.ui.boxRect("golf"))
        app.sim.pointAt(0, r.cx, r.cy, g.width, g.height)
        frames(70) // arrive, then part of the hold
        assertTrue(app.ui.dwellOn("golf") > 0.1f, "holding over Golf: ${app.ui.dwellOn("golf")}")
        val awt = AwtGfx(1920, 1080)
        now += 16_666_667L; platform.clock = now
        app.frame(awt, now)
        awt.writePng(File("build/screenshots/app_pointer.png"))
    }
}
