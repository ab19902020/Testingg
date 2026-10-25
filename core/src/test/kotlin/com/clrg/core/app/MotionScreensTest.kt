package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.gesture.RIGHT
import com.clrg.core.math.Rng
import com.clrg.core.party.MemoryStore
import com.clrg.core.pose.HeldLearnStatus
import com.clrg.core.pose.ObjectColorModel
import com.clrg.core.pose.PoseFrame
import com.clrg.core.render.AwtGfx
import com.clrg.core.render.NullGfx
import com.clrg.core.sim.Motions
import com.clrg.core.sim.Puppet
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.screens.HeldObjectScreen
import com.clrg.core.ui.screens.MotionLabScreen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The object-learning screen and the motion test screen, driven like the app: a (simulated) person in front of the
 * camera, camera frames through the tracker, buttons pressed. The camera thread's learning is stood in for by the
 * test platform (the learning itself is tested on images in HeldObjectTrackerTest).
 */
class MotionScreensTest {
    private val store = MemoryStore()
    private val platform = NullPlatform(store)
    private val app = GameApp(platform, AudioEngine()).also { it.mode = com.clrg.core.games.GameMode.CHRISTMAS }
    private val g = NullGfx()
    private var now = 0L
    private val person = Puppet(16f / 9f * 0.3f, 0.35f, 0.25f).apply { trackHint = 7 }
    private val rng = Rng(4)

    init { app.settings.simulatedInput = false; app.applySettings() }

    private fun frames(n: Int, move: (Int) -> Unit = {}) = repeat(n) {
        now += 16_666_667L; platform.clock = now
        move(it)
        if (it % 2 == 0) app.submitPoseFrame(PoseFrame(now, 16f / 9f, listOf(person.pose(0.002f, rng))))
        app.frame(g, now)
    }

    private fun tap(id: String) {
        val r = assertNotNull(app.ui.boxRect(id), "button $id on screen: ${app.ui.boxIds()}")
        app.submitInput(InputEvent.Touch(InputEvent.Touch.DOWN, r.cx, r.cy))
        app.submitInput(InputEvent.Touch(InputEvent.Touch.UP, r.cx, r.cy))
        frames(2)
    }

    private fun shot(name: String) {
        val awt = AwtGfx(1920, 1080)
        now += 16_666_667L; platform.clock = now
        app.submitPoseFrame(PoseFrame(now, 16f / 9f, listOf(person.pose(0.002f, rng))))
        app.frame(awt, now)
        awt.writePng(File("build/screenshots/$name.png"))
    }

    @Test
    fun learningAnObjectAndKeepingIt() {
        frames(60)
        app.push(HeldObjectScreen(app))
        frames(10)
        // the left/right check pops up for a new camera when a hand is raised: done for this camera already
        app.settings.directionChecked.add("0")
        tap("heldLearn")
        Motions.raise(person, RIGHT)
        frames(200) // countdown, then learning is asked for
        assertEquals(1, platform.heldLearnRequests, "learning started for the person in front of the camera")
        // the camera thread reports success; the object is shown being followed
        val model = ObjectColorModel(FloatArray(ObjectColorModel.BINS) { if (it == 47) 1f else 0f }, 0.9f)
        platform.heldStatus = HeldLearnStatus(0, 1f, true, true, "Got it!", model)
        person.heldHand = RIGHT
        frames(20)
        assertNotNull(platform.heldModels[0], "the new object is looked for straight away, before keeping it")
        shot("app_held_learned")
        tap("heldKeep")
        val id = app.activePlayers[0].id
        assertTrue(app.settings.heldObjects.containsKey(id), "kept for ${app.activePlayers[0].name}")
        frames(5)
        assertNotNull(platform.heldModels[0], "and used from now on")
        assertTrue(app.controls[0].hasObject, "the player's object is followed")
    }

    @Test
    fun motionTestShowsSwingsAndTheGrip() {
        frames(60)
        app.settings.directionChecked.add("0")
        app.push(MotionLabScreen(app))
        frames(20)
        // a forehand
        frames(60) { person.setWrist(RIGHT, 0.9f, -0.2f) }
        frames(16) { i -> Motions.swing(person, RIGHT, 0.9f, -0.5f, -0.2f, i / 15f) }
        frames(10)
        shot("app_motion_test")
        Motions.golfAddress(person)
        frames(30)
        shot("app_motion_test_grip")
    }
}
