package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.math.Rng
import com.clrg.core.party.MemoryStore
import com.clrg.core.party.Settings
import com.clrg.core.pose.PersonPose
import com.clrg.core.pose.PoseFrame
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.Puppet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phones whose camera delivers the picture mirrored (or upside down) made every movement go backwards. These tests
 * play a phone like that: the person moves their hand to *their* right, towards an arrow on the right of the screen,
 * and the camera picture shows it going the other way. The left/right check must notice and fix it for that camera -
 * and must leave a normal camera alone.
 */
class DirectionCheckTest {
    /** A test phone: the camera's own quirks, and what the app currently asks the camera pipeline to do. */
    private class TestPhone(store: MemoryStore, val deviceMirrored: Boolean, val deviceUpsideDown: Boolean) : NullPlatform(store) {
        var engineMirror = true
        var engineUpsideDown = false
        override fun applySettings(settings: Settings) {
            engineMirror = settings.mirrorFor(settings.cameraId ?: "0")
            engineUpsideDown = settings.upsideDown(settings.cameraId ?: "0")
        }
    }

    private val store = MemoryStore()
    private val rng = Rng(9)
    private val aspect = 16f / 9f
    /** The person, as they really are (x grows to their right, as a mirror would show it). */
    private val person = Puppet(aspect * 0.3f, 0.35f, 0.25f).apply { trackHint = 3 }

    private class Run(val app: GameApp, val phone: TestPhone) {
        var now = 0L
        val g = NullGfx()
    }

    private fun start(mirrored: Boolean, upsideDown: Boolean = false): Run {
        val phone = TestPhone(store, mirrored, upsideDown)
        val app = GameApp(phone, AudioEngine())
        app.settings.simulatedInput = false
        app.applySettings()
        return Run(app, phone)
    }

    /** What the camera pipeline delivers for the person right now, given the phone's quirks and the app's corrections. */
    private fun frame(r: Run): PoseFrame {
        val p = person.pose(0.002f, rng)
        // the person's pose above is the correct (mirror-like) view: it comes out reversed when the app's mirroring
        // does not match the camera's quirk, and turned over when an upside-down picture is not corrected
        val reversed = r.phone.engineMirror == r.phone.deviceMirrored
        val turned = r.phone.engineUpsideDown != r.phone.deviceUpsideDown
        return PoseFrame(r.now, aspect, listOf(transform(p, reversed, turned)))
    }

    /** Left/right reversal (labels swap too, as the pose model sees a mirrored body) and/or a half turn. */
    private fun transform(p: PersonPose, reversed: Boolean, turned: Boolean): PersonPose {
        val n = p.x.size
        val x = p.x.copyOf(); val y = p.y.copyOf(); val z = p.z.copyOf(); val v = p.visibility.copyOf(); val pr = p.presence.copyOf()
        if (reversed) {
            for (i in 0 until n) x[i] = aspect - x[i]
            for ((a, b) in PAIRS) { swap(x, a, b); swap(y, a, b); swap(z, a, b); swap(v, a, b); swap(pr, a, b) }
        }
        if (turned) for (i in 0 until n) { x[i] = aspect - x[i]; y[i] = 1f - y[i] }
        return PersonPose(x, y, z, v, pr, p.score, p.trackHint, p.appearance)
    }

    private fun swap(a: FloatArray, i: Int, j: Int) { val t = a[i]; a[i] = a[j]; a[j] = t }

    private fun frames(r: Run, n: Int, move: (Double) -> Unit = {}) = repeat(n) {
        r.now += 16_666_667L
        (r.app.platform as NullPlatform).clock = r.now
        move(r.now / 1e9)
        if (it % 2 == 0) r.app.submitPoseFrame(frame(r))
        r.app.frame(r.g, r.now)
    }

    /** The person raises their right hand in front of their chest and slides it towards the arrow the game shows. */
    private fun followTheArrows(r: Run) {
        var guard = 0
        while (r.app.directionCheck.active && guard++ < 20) {
            val towardsRight = r.app.directionCheck.stage == DirectionCheck.Stage.RIGHT
            person.setWrist(1, 0.05f, -0.1f)
            frames(r, 30)
            val t0 = r.now / 1e9
            frames(r, 60) { t ->
                val u = ((t - t0) / 0.9).coerceIn(0.0, 1.0).toFloat()
                person.setWrist(1, 0.05f + (if (towardsRight) 0.9f else -0.9f) * u, -0.1f)
            }
        }
    }

    @Test
    fun aMirroredCameraIsNoticedAndFixed() {
        val r = start(mirrored = true)
        frames(r, 90) // walks in
        assertFalse(r.app.directionCheck.active, "nothing to check until someone raises a hand")
        person.setWrist(1, 0.05f, -0.1f)
        frames(r, 20)
        assertTrue(r.app.directionCheck.active, "raising a hand on a new camera starts the left/right check")
        val awt = com.clrg.core.render.AwtGfx(1920, 1080)
        r.now += 16_666_667L; (r.app.platform as NullPlatform).clock = r.now
        r.app.frame(awt, r.now)
        awt.writePng(java.io.File("build/screenshots/app_direction_check.png"))
        followTheArrows(r)
        assertFalse(r.phone.engineMirror, "the picture is no longer mirrored a second time")
        assertTrue("0" in r.app.settings.directionChecked)
        assertEquals(false, Settings.load(store).mirrorFor("0"), "remembered")
        // now the cursor goes the way the hand goes
        person.setWrist(1, 0.0f, 0.2f)
        frames(r, 60)
        val x0 = r.app.ui.cursors.first { it.visible }.x
        person.setWrist(1, 0.8f, 0.2f)
        frames(r, 60)
        assertTrue(r.app.ui.cursors.first { it.visible }.x > x0 + 150f, "hand to the right, cursor to the right")
    }

    @Test
    fun aNormalCameraIsLeftAlone() {
        val r = start(mirrored = false)
        frames(r, 90)
        person.setWrist(1, 0.05f, -0.1f)
        frames(r, 20)
        assertTrue(r.app.directionCheck.active)
        followTheArrows(r)
        assertTrue(r.phone.engineMirror, "still mirrored like a mirror")
        assertTrue("0" in r.app.settings.directionChecked)
        // a later start does not ask again
        val again = start(mirrored = false)
        frames(again, 90)
        person.setWrist(1, 0.05f, -0.1f)
        frames(again, 30)
        assertFalse(again.app.directionCheck.active, "checked once per camera")
    }

    @Test
    fun anUpsideDownPictureIsTurnedTheRightWayUp() {
        val r = start(mirrored = false, upsideDown = true)
        person.relaxArms()
        frames(r, 200)
        assertTrue(r.phone.engineUpsideDown, "the app asked for the picture to be turned")
        assertTrue(r.app.settings.upsideDown("0"))
    }

    companion object {
        /** BlazePose left/right landmark pairs. */
        val PAIRS = listOf(1 to 4, 2 to 5, 3 to 6, 7 to 8, 9 to 10, 11 to 12, 13 to 14, 15 to 16, 17 to 18, 19 to 20, 21 to 22, 23 to 24, 25 to 26, 27 to 28, 29 to 30, 31 to 32)
    }
}
