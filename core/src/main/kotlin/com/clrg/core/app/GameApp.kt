package com.clrg.core.app

import com.clrg.core.audio.AudioEngine
import com.clrg.core.audio.Sfx
import com.clrg.core.game.CpuLevel
import com.clrg.core.gesture.PlayerControls
import com.clrg.core.party.PlayerProfile
import com.clrg.core.party.Roster
import com.clrg.core.party.Settings
import com.clrg.core.party.Tournament
import com.clrg.core.pose.PoseFrame
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx
import com.clrg.core.render.PlayerColors
import com.clrg.core.sim.SimulatedInput
import com.clrg.core.tracking.PlayerTracker
import com.clrg.core.tracking.SlotState
import com.clrg.core.ui.InputEvent
import com.clrg.core.ui.Key
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Theme
import com.clrg.core.ui.Ui
import com.clrg.core.ui.screens.CrashReportScreen
import com.clrg.core.ui.screens.DevOverlay
import com.clrg.core.ui.screens.MainMenuScreen
import com.clrg.core.ui.screens.PermissionScreen
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * The whole game, platform-independent. The Android layer calls [frame] once per display frame on the render
 * thread, and posts pose frames / input / lifecycle events from other threads through the thread-safe submit
 * methods.
 */
class GameApp(val platform: Platform, val audio: AudioEngine) {
    val store = platform.store
    val settings: Settings = Settings.load(store)
    val roster: Roster = Roster.load(store)
    var tournament: Tournament? = Tournament.load(store)
    val tracker = PlayerTracker(2)
    val controls = arrayOf(PlayerControls(0), PlayerControls(1))
    val theme = Theme(settings.highContrast, settings.largeText)
    val ui = Ui(theme) { audio.play(it, 0.5f) }
    val sim = SimulatedInput()
    val devOverlay = DevOverlay(this)
    /** Camera and display delays: timing games judge movements by when they were made. */
    val timing = com.clrg.core.game.InputTiming()
    /** Makes sure movements go the right way (left/right and up/down) for the camera in use. */
    val directionCheck = DirectionCheck(this)

    /** Who is playing as P1 (left) and P2 (right) right now. */
    val activePlayers = arrayOf(roster.players[0], roster.players[1])

    private val poseQueue = ConcurrentLinkedQueue<PoseFrame>()
    private val inputQueue = ConcurrentLinkedQueue<InputEvent>()
    private val lifecycleQueue = ConcurrentLinkedQueue<String>()
    private val stack = ArrayList<Screen>()
    private var lastNanos = 0L
    private var fade = 0f
    var time = 0.0
        private set
    var renderFps = 0f
        private set
    var frameMs = 0f
        private set
    var poseFramesReceived = 0L
        private set
    var lastPoseLatencyMs = Float.NaN
        private set
    var droppedPoseFrames = 0L
        private set
    /** Set while the TV (external display) that was in use has been disconnected. */
    var displayLost = false
        private set
    private var hadExternalDisplay = false

    val current: Screen? get() = stack.lastOrNull()

    init {
        applySettings()
        stack.add(MainMenuScreen(this))
        if (platform.cameraStatus().state == CameraState.NEEDS_PERMISSION) stack.add(PermissionScreen(this))
        platform.previousCrash()?.let { stack.add(CrashReportScreen(this, it)) }
        hadExternalDisplay = platform.displayStatus().externalConnected
    }

    // ------------------------------------------------------------------ thread-safe submission

    /** Called from the inference thread. Keeps only a short backlog (never a growing queue). */
    fun submitPoseFrame(f: PoseFrame) {
        poseQueue.add(f)
        while (poseQueue.size > 6) { poseQueue.poll(); droppedPoseFrames++ }
    }

    fun submitInput(e: InputEvent) { inputQueue.add(e) }

    /** "pause", "resume", "display" (display configuration changed), "camera" (camera status changed). */
    fun submitLifecycle(event: String) { lifecycleQueue.add(event) }

    // ------------------------------------------------------------------ navigation

    fun push(s: Screen) {
        current?.onExit()
        stack.add(s)
        s.onEnter()
        fade = 1f
        ui.focusId = null
        s.music?.let { audio.playMusic(it) }
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1).onExit()
        current?.let { it.onEnter(); it.music?.let { m -> audio.playMusic(m) } }
        fade = 1f
        ui.focusId = null
    }

    fun replace(s: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1).onExit()
        stack.add(s)
        s.onEnter()
        fade = 1f
        ui.focusId = null
        s.music?.let { audio.playMusic(it) }
    }

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.size - 1).onExit()
        current?.let { it.onEnter(); it.music?.let { m -> audio.playMusic(m) } }
        fade = 1f
    }

    fun <T : Screen> findScreen(cls: Class<T>): T? = stack.lastOrNull { cls.isInstance(it) }?.let { cls.cast(it) }

    // ------------------------------------------------------------------ settings & players

    fun applySettings() {
        audio.musicVolume = settings.musicVolume
        audio.sfxVolume = settings.sfxVolume
        theme.highContrast = settings.highContrast
        theme.largeText = settings.largeText
        ui.dwellSeconds = settings.dwellSeconds
        controls.forEach { it.sensitivity = settings.gestureSensitivity }
        timing.displayDelay = settings.displayDelayMs / 1000.0
        platform.applySettings(settings)
        platform.setSimulatedInput(settings.simulatedInput)
    }

    fun saveSettings() {
        settings.save(store)
        applySettings()
    }

    fun saveRoster() = roster.save(store)

    fun saveTournament() {
        val t = tournament
        if (t == null) Tournament.clear(store) else t.save(store)
    }

    fun playerColor(i: Int) = PlayerColors.get(activePlayers[i].colorIndex)

    // ------------------------------------------------------------------ camera choice

    /** The camera in use: the one chosen in settings, else the one the platform opened by default. */
    fun currentCamera(): CameraOption? {
        val opts = platform.cameraOptions()
        val id = settings.cameraId ?: platform.cameraStatus().activeId
        return opts.firstOrNull { it.id == id } ?: opts.firstOrNull { !it.front } ?: opts.firstOrNull()
    }

    /** True when the phone has both a front and a back camera to switch between. */
    val canSwitchCamera: Boolean get() = platform.cameraOptions().let { o -> o.any { it.front } && o.any { !it.front } }

    /** Short label for the camera in use ("Front camera" / "Back camera"). */
    fun cameraLabel(): String = currentCamera()?.let { if (it.front) "Front camera" else "Back camera" } ?: "Camera"

    /**
     * Front <-> back camera. The picture changes completely (different position, angle and field of view), so player
     * identities and calibrations are reset and the next match starts with framing again.
     */
    fun switchCamera(): Boolean {
        val opts = platform.cameraOptions()
        val cur = currentCamera()
        val target = (if (cur?.front == true) opts.filter { !it.front }.maxByOrNull { it.horizontalFovDeg } else opts.firstOrNull { it.front }) ?: return false
        selectCamera(target.id)
        return true
    }

    /** Uses camera [id] from now on (resets identities and calibrations if it is a different camera). */
    fun selectCamera(id: String) {
        val opt = platform.cameraOptions().firstOrNull { it.id == id } ?: return
        if (currentCamera()?.id == id && settings.cameraId == id) return
        settings.cameraId = id
        saveSettings()
        resetTracking()
        notify("Switched to the ${opt.label.lowercase()}")
    }

    private var noticeText: String? = null
    private var noticeTime = 0f

    /** Shows a short message at the top of the screen for a few seconds. */
    fun notify(text: String) { noticeText = text; noticeTime = 0f }

    /** Solo mode: Player 2 is a computer opponent at this level; null when two people play at the camera. */
    var cpuLevel: CpuLevel? = null
        private set
    val solo: Boolean get() = cpuLevel != null

    /**
     * One person plays against the computer. The camera player may stand anywhere in the picture; switching from
     * two-player mode needs a fresh framing/calibration (handled by the tracker).
     */
    fun startSolo(human: PlayerProfile, level: CpuLevel) {
        val sameHuman = solo && activePlayers[0].id == human.id
        cpuLevel = level
        settings.cpuLevel = level.name
        settings.save(store)
        activePlayers[0] = human
        activePlayers[1] = cpuProfile(level, human)
        if (!tracker.solo) { tracker.solo = true; controls.forEach { it.calibration = null; it.reset() } }
        else if (!sameHuman) resetTracking()
        sim.solo = true
    }

    /**
     * Turn-based play (golf): one person at the camera at a time, standing anywhere in the picture. The others wait
     * their turn out of the way. Uses the single-player tracking of solo mode, without a computer opponent slot.
     */
    fun startTurnTracking() {
        cpuLevel = null
        if (!tracker.solo) { tracker.solo = true; controls.forEach { it.calibration = null; it.reset() } }
        sim.solo = true
    }

    /** The next person steps up: forget the last one, so whoever stands in front of the camera now is picked up. */
    fun nextTurnPerson() {
        tracker.resetAll()
        controls.forEach { it.calibration = null; it.reset() }
    }

    /**
     * Quick calibration of the person just picked up for their turn (body size and shoulder position from how they
     * stand now; typical reach), then locks onto them so people walking past cannot take over. False until someone
     * is being followed.
     */
    fun calibrateTurnPerson(profile: PlayerProfile?): Boolean {
        val slot = tracker.slots[0]
        val m = slot.metrics ?: return false
        if (!slot.isPlaying) return false
        val cal = com.clrg.core.tracking.Calibration.fromNeutral(m, profile?.hand ?: com.clrg.core.tracking.Hand.RIGHT, profile?.mode ?: com.clrg.core.tracking.MovementMode.STANDARD)
        slot.calibration = cal
        controls[0].calibration = cal
        tracker.lock()
        return true
    }

    /** Swaps left and right for the camera in use (when movements go the wrong way on screen). */
    fun flipLeftRight() {
        val id = currentCamera()?.id ?: return
        settings.mirrorByCamera[id] = !settings.mirrorFor(id)
        settings.directionChecked.add(id)
        saveSettings()
        resetTracking()
        notify("Flipped left and right")
    }

    /** Whether the camera in use shows the picture like a mirror. */
    val mirrorNow: Boolean get() = settings.mirrorFor(currentCamera()?.id)

    /** Back to two people at the camera (quick match, tournament, full set-up). */
    fun leaveSolo() {
        if (!solo && !tracker.solo) return
        cpuLevel = null
        tracker.solo = false
        controls.forEach { it.calibration = null; it.reset() }
        sim.solo = false
        if (activePlayers[1].id < 0) activePlayers[1] = roster.players.firstOrNull { it.id != activePlayers[0].id } ?: roster.players[1]
    }

    /** The computer player's (unsaved) profile, in a colour different from the human's. */
    private fun cpuProfile(level: CpuLevel, human: PlayerProfile): PlayerProfile {
        val color = (listOf(level.colorIndex) + listOf(3, 4, 7, 5)).first { it != human.colorIndex }
        return PlayerProfile(-1 - level.ordinal, level.botName, color)
    }

    /** New people at the controls: forget identities and calibrations. */
    fun setActivePlayers(p1: PlayerProfile, p2: PlayerProfile) {
        leaveSolo()
        val changed = activePlayers[0].id != p1.id || activePlayers[1].id != p2.id
        activePlayers[0] = p1
        activePlayers[1] = p2
        if (changed) resetTracking()
    }

    fun resetTracking() {
        tracker.resetAll()
        controls.forEach { it.calibration = null; it.reset() }
    }

    /** Every camera player calibrated, locked and currently tracked. */
    val playersReady: Boolean get() = calibrated && tracker.allPlaying

    val calibrated: Boolean get() = tracker.locked && tracker.slots.all { !it.enabled || it.calibration != null }

    // ------------------------------------------------------------------ frame

    fun frame(g: Gfx, nowNanos: Long = platform.nowNanos()) {
        val dt = if (lastNanos == 0L) 1f / 60f else ((nowNanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        if (lastNanos != 0L) {
            val inst = (nowNanos - lastNanos) / 1e6f
            frameMs += 0.1f * (inst - frameMs)
            renderFps = if (frameMs > 0f) 1000f / frameMs else 0f
        }
        lastNanos = nowNanos
        time += dt

        handleLifecycle()

        // 1) tracking: every pending pose frame goes through the tracker and the gesture detectors
        platform.setExpectedPeople(if (tracker.solo) 1 else 2)
        timing.poseNow = nowNanos / 1e9
        if (settings.simulatedInput) {
            // simulated poses have their own clock and no camera or TV: judged as they arrive
            timing.enabled = false
            poseQueue.clear()
            for (f in sim.update(dt)) feedPose(f)
        } else {
            timing.enabled = true
            while (true) {
                val f = poseQueue.poll() ?: break
                feedPose(f)
                directionCheck.observe(tracker.lastCandidates, f.timestampNanos)
                lastPoseLatencyMs = (nowNanos - f.timestampNanos) / 1e6f
                timing.observe((nowNanos - f.timestampNanos) / 1e9)
            }
        }

        updateHeldObjects()
        updateHandTracking()
        // 2) latency compensation for what players see, then hands-free cursors from calibrated, tracked players
        if (!settings.simulatedInput) for (c in controls) c.predict(nowNanos / 1e9)
        updateCursors(g, dt)

        // 3) input + screens
        val events = ArrayList<InputEvent>()
        while (true) events.add(inputQueue.poll() ?: break)
        val scr = current ?: return
        // hands-free menus from the moment anyone is in front of the camera (no set-up needed first); the first
        // time on a camera, a quick left/right check makes sure the cursor goes the way the hand goes
        directionCheck.update(dt, scr.allowCursors && !displayLost)
        val cursorsOn = scr.allowCursors && !directionCheck.active
        // no pointer on screen while pointing does nothing (live play, flights in golf, the left/right check)
        if (!cursorsOn) for (c in ui.cursors) { c.visible = false; c.active = false }
        ui.begin(dt, events, cursorsOn)
        if (ui.backPressed && scr.onBack()) { /* handled */ }
        g.fillRect(0f, 0f, g.width, g.height, Colors.NIGHT_TOP)
        scr.frame(g, ui, dt)
        drawGlobalOverlays(g, scr)
        if (settings.simulatedInput) { sim.drawControls(g, ui); sim.banner(g) }
        if (settings.devOverlay) devOverlay.draw(g)
        ui.drawCursors(g)
        ui.end()
        if (fade > 0f) {
            g.fillRect(0f, 0f, g.width, g.height, Colors.withAlpha(Colors.NIGHT_TOP, fade))
            fade = max(0f, fade - dt * 4f)
        }
    }

    private fun feedPose(f: PoseFrame) {
        poseFramesReceived++
        tracker.update(f)
        val t = f.timestampNanos / 1e9
        for (k in 0..1) {
            val slot = tracker.slots[k]
            if (slot.calibration != null && controls[k].calibration !== slot.calibration) controls[k].calibration = slot.calibration
            if (slot.state == SlotState.LOST || slot.state == SlotState.EMPTY) controls[k].reset()
            controls[k].update(slot, t)
        }
    }

    /** True when someone in front of the camera has a hand cursor on screen right now. */
    val handCursorShown: Boolean get() = ui.cursors.any { it.visible }

    // ------------------------------------------------------------------ held objects

    private val heldCache = HashMap<String, com.clrg.core.pose.ObjectColorModel?>()
    private var heldSentModels: Array<com.clrg.core.pose.ObjectColorModel?> = arrayOfNulls(2)
    private var heldSentTracks = intArrayOf(-2, -2)

    /** A just-learned object being tried out (slot, model) before the player decides to keep it. */
    var previewHeld: Pair<Int, com.clrg.core.pose.ObjectColorModel>? = null

    /** The learned object of the player in slot [k] (when held objects are on), else null. */
    fun heldModelFor(k: Int): com.clrg.core.pose.ObjectColorModel? {
        previewHeld?.let { if (it.first == k) return it.second }
        if (!settings.useHeldObjects) return null
        val enc = settings.heldObjects[activePlayers[k].id] ?: return null
        return heldCache.getOrPut(enc) { com.clrg.core.pose.ObjectColorModel.decode(enc) }
    }

    /** Tells the camera thread whose object to look for, and where those players are (only when it changes). */
    private fun updateHeldObjects() {
        val models = Array(2) { k -> if (tracker.slots[k].enabled) heldModelFor(k) else null }
        val tracks = IntArray(2) { k -> tracker.slots[k].trackHint }
        if (!models.contentEquals(heldSentModels) || !tracks.contentEquals(heldSentTracks)) {
            heldSentModels = models; heldSentTracks = tracks
            platform.setHeldObjects(models, tracks)
        }
    }

    private var handSentTracks = intArrayOf(-2, -2)
    private var handSentMasks = intArrayOf(-1, -1)

    /** Finger tracking for the players whose game asks for it (finger guns), on the hands in use. */
    private fun updateHandTracking() {
        val masks = IntArray(2) { k -> if (tracker.slots[k].enabled && tracker.slots[k].isPlaying) controls[k].fingerMask else 0 }
        val tracks = IntArray(2) { k -> if (masks[k] != 0) tracker.slots[k].trackHint else -1 }
        if (!masks.contentEquals(handSentMasks) || !tracks.contentEquals(handSentTracks)) {
            handSentMasks = masks; handSentTracks = tracks
            platform.setHandTracking(tracks, masks)
        }
    }

    /** One steady pointer per player (see [HandPointer]). */
    val pointers = arrayOf(com.clrg.core.ui.HandPointer(), com.clrg.core.ui.HandPointer())

    /**
     * Hand cursors for everyone the camera is following - calibrated players, or anyone standing in front of the
     * camera before set-up (with typical arm reach until they calibrate). A cursor acts while its hand is lifted to
     * the chest, and selects by holding over a button.
     */
    private fun updateCursors(g: Gfx, dt: Float) {
        for (k in 0..1) {
            val c = ui.cursors[k]
            val slot = tracker.slots[k]
            val p = pointers[k]
            c.color = playerColor(k)
            p.update(controls[k], slot.enabled && slot.isPlaying, g.width, g.height, dt)
            c.visible = p.visible && !p.x.isNaN()
            c.active = c.visible && p.active
            if (c.visible) { c.x = p.x; c.y = p.y }
        }
    }

    private fun handleLifecycle() {
        while (true) {
            when (lifecycleQueue.poll() ?: break) {
                "pause" -> {
                    findScreen(com.clrg.core.ui.screens.GameScreen::class.java)?.session?.userPause()
                    audio.playMusic(null)
                }
                "resume" -> current?.music?.let { audio.playMusic(it, restart = true) }
                "display" -> {
                    val d = platform.displayStatus()
                    if (hadExternalDisplay && !d.externalConnected) {
                        displayLost = true
                        findScreen(com.clrg.core.ui.screens.GameScreen::class.java)?.session?.userPause()
                        audio.play(Sfx.BAD)
                    }
                    if (d.externalConnected) displayLost = false
                    hadExternalDisplay = d.externalConnected
                }
                "camera" -> Unit
            }
        }
    }

    fun dismissDisplayLost() { displayLost = false }

    private fun drawGlobalOverlays(g: Gfx, scr: Screen) {
        val cam = platform.cameraStatus()
        if (!settings.simulatedInput && cam.state != CameraState.RUNNING && scr.needsCamera) {
            val msg = when (cam.state) {
                CameraState.NEEDS_PERMISSION, CameraState.DENIED -> "Camera permission is needed to play"
                CameraState.OPENING -> "Starting the camera..."
                CameraState.INTERRUPTED -> "Camera in use by another app - reconnecting..."
                CameraState.ERROR -> "Camera problem: ${cam.message} - retrying"
                CameraState.NO_CAMERA -> "No usable camera found"
                else -> "Camera stopped"
            }
            val w = g.measureText(msg, 40f) + 80f
            g.fillRoundRect(g.width / 2 - w / 2, g.height - 140f, g.width / 2 + w / 2, g.height - 60f, 30f, 0xEE7A1F1F.toInt())
            g.text(msg, g.width / 2, g.height - 100f, 40f, Colors.WHITE)
        } else if (!settings.simulatedInput && scr.needsCamera) {
            platform.trackingProblem()?.let { problem ->
                val msg = problem.take(110)
                val size = 32f
                val w = minOf(g.width - 40f, g.measureText(msg, size) + 80f)
                g.fillRoundRect(g.width / 2 - w / 2, g.height - 140f, g.width / 2 + w / 2, g.height - 60f, 30f, 0xEE7A1F1F.toInt())
                g.text(msg, g.width / 2, g.height - 100f, ui.fit(g, msg, size, w - 60f), Colors.WHITE)
            }
        }
        noticeText?.let { msg ->
            noticeTime += 1f / 60f
            if (noticeTime > 2.8f) { noticeText = null; return@let }
            val a = (if (noticeTime < 0.25f) noticeTime / 0.25f else if (noticeTime > 2.4f) (2.8f - noticeTime) / 0.4f else 1f).coerceIn(0f, 1f)
            val w = g.measureText(msg, 38f) + 90f
            g.save(); g.setAlpha(a)
            g.fillRoundRect(g.width / 2 - w / 2, 70f, g.width / 2 + w / 2, 140f, 35f, 0xEE0B2A4F.toInt())
            g.strokeRoundRect(g.width / 2 - w / 2, 70f, g.width / 2 + w / 2, 140f, 35f, 3f, Colors.ICE)
            g.text(msg, g.width / 2, 105f, 38f, Colors.WHITE)
            g.restore()
        }
        directionCheck.draw(g)
        if (displayLost) {
            g.fillRect(0f, 0f, g.width, g.height, 0xCC000000.toInt())
            ui.title(g, "TV disconnected", g.height / 2 - 140f)
            ui.paragraph(g, listOf("The game is paused. Reconnect the HDMI / USB-C cable,", "or carry on playing on the phone screen."), g.width / 2, g.height / 2 - 40f, 42f, align = com.clrg.core.render.Align.CENTER)
            if (ui.button(g, "displayOk", com.clrg.core.math.Rect.ofCenter(g.width / 2, g.height / 2 + 160f, 560f, 110f), "Continue")) dismissDisplayLost()
        }
    }

    // ------------------------------------------------------------------ input helpers for the platform

    fun key(k: Key) = submitInput(InputEvent.KeyPress(k))
}
