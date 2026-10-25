package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.games.GameRegistry
import com.clrg.core.math.Rect
import com.clrg.core.party.QualityMode
import com.clrg.core.party.Roster
import com.clrg.core.party.Tournament
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui
import kotlin.math.roundToInt

class SettingsScreen(app: GameApp) : Screen(app) {
    private var tab = 0
    private val tabs = listOf("Sound", "Accessibility", "Camera & TV", "Motion", "Timing", "Developer")
    private var confirmReset = false

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "Settings", app = app)
        val tw = (g.width - 120f) / tabs.size
        tabs.forEachIndexed { i, t -> if (ui.button(g, "tab$i", Rect(60f + i * tw, 150f, 50f + (i + 1) * tw, 240f), t, size = 38f, selected = tab == i)) tab = i }
        val s = app.settings
        var y = 280f
        fun row(): Rect { val r = Rect(160f, y, g.width - 160f, y + 96f); y += 112f; return r }
        var changed = false
        fun pct(v: Float) = "${(v * 100).roundToInt()}%"
        when (tab) {
            0 -> {
                when (ui.stepper(g, "music", row(), "Music volume", pct(s.musicVolume))) { -1 -> { s.musicVolume = (s.musicVolume - 0.1f).coerceAtLeast(0f); changed = true }; 1 -> { s.musicVolume = (s.musicVolume + 0.1f).coerceAtMost(1f); changed = true } }
                when (ui.stepper(g, "sfx", row(), "Effects volume", pct(s.sfxVolume))) { -1 -> { s.sfxVolume = (s.sfxVolume - 0.1f).coerceAtLeast(0f); changed = true }; 1 -> { s.sfxVolume = (s.sfxVolume + 0.1f).coerceAtMost(1f); changed = true } }
                if (ui.toggle(g, "captions", row(), "Show captions for sound effects", s.captions)) { s.captions = !s.captions; changed = true }
                ui.paragraph(g, listOf("All music and sound effects are original and generated on the phone."), 160f, y + 20f, 30f, app.theme.dim)
            }
            1 -> {
                if (ui.toggle(g, "hc", row(), "High contrast", s.highContrast)) { s.highContrast = !s.highContrast; changed = true }
                if (ui.toggle(g, "lt", row(), "Larger text", s.largeText)) { s.largeText = !s.largeText; changed = true }
                if (ui.toggle(g, "re", row(), "Reduced effects (less shake, flashing and confetti)", s.reducedEffects)) { s.reducedEffects = !s.reducedEffects; changed = true }
                when (ui.stepper(g, "dwell", row(), "Hands-free select time", "%.1f s".format(s.dwellSeconds))) { -1 -> { s.dwellSeconds = (s.dwellSeconds - 0.25f).coerceAtLeast(0.8f); changed = true }; 1 -> { s.dwellSeconds = (s.dwellSeconds + 0.25f).coerceAtMost(3f); changed = true } }
                when (ui.stepper(g, "sens", row(), "Gesture sensitivity", pct(s.gestureSensitivity))) { -1 -> { s.gestureSensitivity = (s.gestureSensitivity - 0.1f).coerceAtLeast(0.5f); changed = true }; 1 -> { s.gestureSensitivity = (s.gestureSensitivity + 0.1f).coerceAtMost(2f); changed = true } }
                if (ui.toggle(g, "steer", row(), "Sleigh Slalom: steering only (no tuck)", s.steeringOnlySlalom)) { s.steeringOnlySlalom = !s.steeringOnlySlalom; changed = true }
                ui.paragraph(g, listOf("Reduced-movement and seated modes are chosen per player on the Players screen or during calibration."), 160f, y + 10f, 28f, app.theme.dim)
            }
            2 -> {
                val opts = app.platform.cameraOptions()
                val cur = s.cameraId ?: app.platform.cameraStatus().activeId
                val r = row()
                g.text("Camera", r.left, r.cy, 40f, Colors.WHITE, Align.LEFT)
                var x = r.left + 220f
                for (o in opts.take(3)) {
                    if (ui.button(g, "c${o.id}", Rect(x, r.top, x + 420f, r.bottom), "${o.label} ~${o.horizontalFovDeg.toInt()}°", size = 30f, selected = o.id == cur)) app.selectCamera(o.id)
                    x += 440f
                }
                if (ui.toggle(g, "wide", row(), "Wide angle (when the camera supports zooming out)", s.wideAngle)) { s.wideAngle = !s.wideAngle; changed = true }
                val mr = row()
                if (ui.toggle(g, "mirror", Rect(mr.left, mr.top, mr.right - 480f, mr.bottom), "Mirror the picture (this camera)", app.mirrorNow)) app.flipLeftRight()
                if (ui.button(g, "dircheck", Rect(mr.right - 460f, mr.top, mr.right, mr.bottom), "Check left/right", "raise a hand to start", accent = Colors.ICE, size = 32f)) {
                    app.directionCheck.request()
                    app.notify("Raise a hand to start the left/right check")
                }
                val qr = row()
                g.text("Tracking quality", qr.left, qr.cy, 40f, Colors.WHITE, Align.LEFT)
                QualityMode.values().forEachIndexed { i, q ->
                    if (ui.button(g, "q$i", Rect(qr.left + 400f + i * 330f, qr.top, qr.left + 710f + i * 330f, qr.bottom), q.label, size = 32f, selected = s.quality == q)) { s.quality = q; changed = true }
                }
                if (ui.toggle(g, "fps60", row(), "Smoother tracking: 60 fps camera when the phone keeps up", s.highFpsCamera)) { s.highFpsCamera = !s.highFpsCamera; changed = true }
                if (ui.toggle(g, "tv2", row(), "Use the TV as a separate screen when the phone supports it", s.preferTvAsSecondScreen)) { s.preferTvAsSecondScreen = !s.preferTvAsSecondScreen; changed = true }
                if (ui.toggle(g, "inset", row(), "Show a small camera view during games", s.showCameraInset)) { s.showCameraInset = !s.showCameraInset; changed = true }
            }
            3 -> {
                if (ui.button(g, "motionLab", row(), "Motion test - see what the camera sees of you", accent = Colors.GOOD, size = 36f)) app.push(MotionLabScreen(app))
                if (ui.button(g, "heldObj", row(), "Use a real object as your racket / club", accent = Colors.GOOD, size = 36f)) app.push(HeldObjectScreen(app))
                if (ui.toggle(g, "useHeld", row(), "Use learned objects in games", s.useHeldObjects)) { s.useHeldObjects = !s.useHeldObjects; changed = true }
                if (ui.toggle(g, "sharp", row(), "Sharper fast movements (shorter camera exposure)", s.sharpMotion)) { s.sharpMotion = !s.sharpMotion; changed = true }
                when (ui.stepper(g, "sens2", row(), "Gesture sensitivity", pct(s.gestureSensitivity))) { -1 -> { s.gestureSensitivity = (s.gestureSensitivity - 0.1f).coerceAtLeast(0.5f); changed = true }; 1 -> { s.gestureSensitivity = (s.gestureSensitivity + 0.1f).coerceAtMost(2f); changed = true } }
                ui.paragraph(g, listOf(
                    "Sharper fast movements makes swinging arms (and held objects) much less blurred in the camera",
                    "picture, so swings are seen better. The picture may look a little grainier in a dim room; switch",
                    "it off if the picture flickers or goes dark.",
                ), 160f, y + 10f, 28f, app.theme.dim)
            }
            4 -> {
                val r = row()
                val label = "TV + camera delay allowance" + if (s.timingMeasured) " (measured)" else " (typical value)"
                when (ui.stepper(g, "delay", r, label, "${s.displayDelayMs} ms")) {
                    -1 -> { s.displayDelayMs = (s.displayDelayMs - 10).coerceAtLeast(com.clrg.core.party.Settings.MIN_DELAY_MS); s.timingMeasured = false; changed = true }
                    1 -> { s.displayDelayMs = (s.displayDelayMs + 10).coerceAtMost(com.clrg.core.party.Settings.MAX_DELAY_MS); s.timingMeasured = false; changed = true }
                }
                if (ui.button(g, "timingTest", row(), "Run the timing test (about 30 seconds)", accent = Colors.GOOD, size = 36f)) app.push(TimingTestScreen(app))
                ui.paragraph(g, listOf(
                    "Tennis, table tennis, goalkeeping, snowball dodging and dancing judge your moves by when you",
                    "made them. The camera's delay is measured automatically; the TV's can only be measured with",
                    "the timing test. Swings counted late? Raise the allowance. Counted early? Lower it.",
                    "",
                    "For the quickest response: switch on your TV's Game mode (also called PC or low-latency mode;",
                    "the game asks the TV for it automatically where supported), use the cable instead of screen",
                    "casting, keep the room bright, and leave 'Smoother tracking: 60 fps' on.",
                ), 160f, y + 10f, 29f, app.theme.dim)
            }
            5 -> {
                if (ui.toggle(g, "perf", row(), "Performance overlay (frame rate, latency, tracking)", s.devOverlay)) { s.devOverlay = !s.devOverlay; changed = true }
                if (ui.toggle(g, "skel", row(), "Skeleton + tracking debug overlay", s.skeletonOverlay)) { s.skeletonOverlay = !s.skeletonOverlay; changed = true }
                if (ui.toggle(g, "sim", row(), "SIMULATED INPUT (testing without camera - not real tracking)", s.simulatedInput)) {
                    s.simulatedInput = !s.simulatedInput
                    app.resetTracking()
                    changed = true
                }
                val rr = row()
                if (!confirmReset) { if (ui.button(g, "reset", rr, "Reset players, tournament and settings", accent = Colors.BAD, size = 36f)) confirmReset = true }
                else if (ui.button(g, "reset2", rr, "Tap again to erase all saved data", accent = Colors.BAD, selected = true, size = 36f)) {
                    app.store.put(Roster.KEY, null); Tournament.clear(app.store); app.store.put(com.clrg.core.party.Settings.KEY, null)
                    app.tournament = null
                    confirmReset = false
                }
                val d = app.platform.deviceInfo()
                ui.paragraph(g, listOf(
                    "Device: ${d.manufacturer} ${d.model}, Android ${d.androidRelease} (API ${d.sdkInt})",
                    "Chipset: ${d.soc ?: "unknown"} - ${d.note}",
                    "Games in this build: ${GameRegistry.all.size}",
                ), 160f, y + 20f, 30f, app.theme.dim)
            }
        }
        if (changed) app.saveSettings()
    }
}

class HelpScreen(app: GameApp) : Screen(app) {
    private var tab = 0
    private var game = 0
    private val tabs = listOf("Game controls", "Golf", "TV & camera", "Privacy", "About")

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "How to Play", app = app)
        val tw = (g.width - 120f) / tabs.size
        tabs.forEachIndexed { i, t -> if (ui.button(g, "tab$i", Rect(60f + i * tw, 150f, 50f + (i + 1) * tw, 240f), t, size = 38f, selected = tab == i)) tab = i }
        val body = Rect(60f, 270f, g.width - 60f, g.height - 40f)
        when (tab) {
            0 -> {
                GameRegistry.all.forEachIndexed { i, e ->
                    if (ui.button(g, "hg$i", Rect(60f, 270f + i * 76f, 560f, 336f + i * 76f), e.info.title, size = 28f, selected = game == i)) game = i
                }
                val info = GameRegistry.all[game].info
                val r = Rect(600f, 270f, g.width - 60f, g.height - 40f)
                infoPanel(g, app, r, info.title, info.howTo + listOf("") + info.controls.map { "${it.first}  ->  ${it.second}" } + listOf("", info.gentleNotes), 32f)
            }
            1 -> infoPanel(g, app, body, "Christmas Golf (1 to 4 players, taking turns)", listOf(
                "Take turns: only the golfer whose turn it is stands in front of the camera. Raise a hand to start.",
                "Choose a club or turn your aim: raise a hand and hold it over the button (hold on an arrow to keep turning).",
                "Grip: put your hands together as if holding a club (the mittens on screen join up), low and still.",
                "Swing: then swing back and through like a real golf swing - your snowman copies it.",
                "     The higher your backswing, the further the ball goes. Swing back down quickly.",
                "     Bring your hands back through where they started for a straight shot.",
                "Putt: grip, draw your hands back sideways (further = harder) and stroke through.",
                "The camera follows the ball down the fairway; the small map shows the whole hole.",
                "Drive with the Driver, hit the approach with an iron or the wedge, then putt on the green.",
                "Watch the wind and the slope arrows on the green. Water or out of bounds: one penalty stroke.",
                "Three courses of three holes each. Fewest strokes wins.",
            ), 31f)
            2 -> infoPanel(g, app, body, "Setting up", listOf(
                "TV: connect a USB-C to HDMI adapter (the phone must support USB-C video output),",
                "     or cast the screen (works, but adds delay). The game is drawn for any screen shape.",
                "Phone: landscape, steady on a stand near the TV, camera towards the players, chest height.",
                "Space: stand 2-3 m away, side by side, an arm's length apart. Player 1 left, Player 2 right.",
                "Light: make the room bright; avoid strong light behind you.",
                "Calibration measures each player's size and comfortable reach so everybody plays fairly.",
                "Hands-free menus: lift a hand to chest height to point, and hold it over a button to choose.",
                "     Small, relaxed movements are enough - the right hand points from your right side, the left from your left.",
                "A real object (toy racket, stick, pool noodle) can be your racket or club: Settings > Motion.",
                "Motion test (Settings > Motion) shows what the camera sees of you - handy when something isn't picked up.",
                "If someone steps away the game pauses and waits for them - spectators can't take over.",
                "On your own? Play Solo: you against a computer player (Easy, Medium or Hard). Stand in the middle.",
            ), 32f)
            3 -> infoPanel(g, app, body, "Privacy", listOf(
                "The camera picture is processed on this phone, frame by frame, to find body positions.",
                "No video or photos are recorded, stored or uploaded - not even temporarily to disk.",
                "The app has no internet permission at all. Player names and scores stay on this phone.",
                "Clothing colours are used only in memory to tell the two players apart, and are forgotten",
                "when the app closes. If you keep a learned held object, only its colours (a list of numbers,",
                "not a picture) are saved. You can switch the camera permission off in Android settings.",
            ), 34f)
            4 -> infoPanel(g, app, body, "About", listOf(
                "Christmas Living Room Games - an original family party game.",
                "Pose tracking: MediaPipe BlazePose models (Apache 2.0) run with TensorFlow Lite (Apache 2.0).",
                "All characters, artwork, music and sound effects are original and generated in code.",
                "See LICENSES.md in the source project for full licence information.",
                "Recommended: Snapdragon 8 Gen 3 class phone or better, with USB-C video output for wired TV.",
            ), 34f)
        }
    }
}
