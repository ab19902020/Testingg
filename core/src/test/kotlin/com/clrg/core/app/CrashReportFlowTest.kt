package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.party.MemoryStore
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.NullGfx
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.screens.CrashReportScreen
import com.clrg.core.ui.screens.FramingScreen
import com.clrg.core.ui.screens.MainMenuScreen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The report about a previous crash is shown once at startup, can be shared on request and dismissed. */
class CrashReportFlowTest {
    private val report = """
        Christmas Living Room Games 1.0.0 (1)
        Phone: Example Phone X, Android 15 (API 35), chip: Qualcomm SM8650
        When: 2026-10-25 12:00:00
        What happened: the game stopped because of an error in the 'camera' thread

        java.lang.IllegalStateException: CameraDevice was already closed
          at android.hardware.camera2.impl.CameraDeviceImpl.checkIfCameraClosedOrInError(CameraDeviceImpl.java:2373)
          at android.hardware.camera2.impl.CameraDeviceImpl.createCaptureRequest(CameraDeviceImpl.java:858)
          at com.clrg.app.CameraSource${'$'}createSession${'$'}cfg${'$'}1.onConfigured(CameraSource.kt:194)
    """.trimIndent() + "\n" + (1..40).joinToString("\n") { "  at some.long.package.name.ClassNumber$it.methodWithAVeryLongNameThatNeedsWrapping(SourceFile.kt:$it)" }

    private val platform = NullPlatform(MemoryStore()).also { it.crash = report }
    private val app = GameApp(platform, AudioEngine()).also { it.mode = com.clrg.core.games.GameMode.CHRISTMAS }
    private var now = 0L

    private fun frames(n: Int, g: com.clrg.core.render.Gfx = NullGfx()) = repeat(n) { now += 16_666_667L; platform.clock = now; app.frame(g, now) }

    private fun tap(id: String) {
        frames(2)
        val r = assertNotNull(app.ui.boxRect(id), "button '$id' not on screen ${app.current?.javaClass?.simpleName}: ${app.ui.boxIds()}")
        app.submitInput(InputEvent.Touch(InputEvent.Touch.DOWN, r.cx, r.cy))
        app.submitInput(InputEvent.Touch(InputEvent.Touch.UP, r.cx, r.cy))
        frames(2)
    }

    @Test
    fun crashReportIsShownSharedOnRequestAndDismissed() {
        frames(3)
        assertTrue(app.current is CrashReportScreen, "report shown first")
        assertTrue(platform.shared.isEmpty(), "nothing is shared automatically")
        val awt = AwtGfx(1920, 1080)
        frames(1, awt)
        awt.writePng(File("build/screenshots/app_crash_report.png"))
        tap("more") // long reports page through instead of overflowing
        tap("share")
        assertEquals(listOf(report), platform.shared)
        tap("continue")
        assertTrue(app.current is MainMenuScreen)
    }

    @Test
    fun trackingProblemIsShownOnCameraScreens() {
        platform.crash = null
        val app2 = GameApp(platform, AudioEngine())
        platform.problem = "Body tracking could not start: UnsatisfiedLinkError: dlopen failed: library page size mismatch"
        app2.push(FramingScreen(app2, {}))
        val ng = NullGfx()
        repeat(30) { now += 16_666_667L; platform.clock = now; app2.frame(ng, now) } // past the screen fade-in
        val awt = AwtGfx(1920, 1080)
        now += 16_666_667L; platform.clock = now
        app2.frame(awt, now)
        awt.writePng(File("build/screenshots/app_tracking_problem.png"))
        assertTrue(app2.current is FramingScreen)
    }
}
