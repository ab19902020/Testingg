package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.audio.Ambience
import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Room
import com.clrg.core.audio.Sfx
import com.clrg.core.game.CpuLevel
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.gesture.GolfStroke
import com.clrg.core.gesture.GolfSwingDetector
import com.clrg.core.golf.Club
import com.clrg.core.golf.Course
import com.clrg.core.golf.Courses
import com.clrg.core.golf.GolfMatch
import com.clrg.core.golf.GolfPhysics
import com.clrg.core.golf.GolfPlayer
import com.clrg.core.golf.Outcome
import com.clrg.core.golf.Pt
import com.clrg.core.golf.Surface
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Particles
import com.clrg.core.render.PlayerColors
import com.clrg.core.render.Prop
import com.clrg.core.ui.Icon
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One golfer in the line-up: a family member (by profile id) or a computer golfer. */
class GolfEntry(val profileId: Int? = null, val cpu: CpuLevel? = null)

/** Golf: who plays (1-4, in turn order; family members and/or computer golfers) and which course. */
class GolfSetupScreen(app: GameApp) : Screen(app) {
    private val entries = ArrayList<GolfEntry>()
    private var course = 0

    init {
        // start with the people who played last
        for (p in app.activePlayers) if (p.id >= 0 && entries.none { it.profileId == p.id } && app.roster.byId(p.id) != null) entries.add(GolfEntry(profileId = p.id))
        if (entries.isEmpty()) entries.add(GolfEntry(profileId = app.roster.players[0].id))
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, if (app.christmasMode) "Christmas Golf" else "Golf", app = app)
        g.text("Take turns - only the player whose turn it is needs to stand in front of the camera.", g.width / 2f, 165f, 32f, app.theme.dim, bold = false)

        // players, in turn order
        val left = Rect(40f, 205f, g.width * 0.5f - 15f, 905f)
        Draw.panel(g, left, app.theme.panel, app.theme.panelBorder)
        g.text("Who's playing? (1 to 4, in turn order)", left.cx, left.top + 46f, 38f * app.theme.textScale, Colors.GOLD)
        val bw = (left.width - 90f) / 2f
        app.roster.players.take(8).forEachIndexed { i, p ->
            val x = left.left + 30f + (i % 2) * (bw + 30f)
            val y = left.top + 90f + (i / 2) * 104f
            val idx = entries.indexOfFirst { it.profileId == p.id }
            val label = if (idx >= 0) "${idx + 1}. ${p.name}" else p.name
            if (ui.button(g, "golf_p${p.id}", Rect(x, y, x + bw, y + 92f), label, null, PlayerColors.get(p.colorIndex), selected = idx >= 0, size = 36f)) toggle(GolfEntry(profileId = p.id))
        }
        val cy = left.top + 90f + 4 * 104f + 20f
        g.text("Computer golfers", left.cx, cy, 32f, app.theme.dim)
        val cw = (left.width - 100f) / 3f
        CpuLevel.values().forEachIndexed { i, lv ->
            val x = left.left + 30f + i * (cw + 20f)
            val idx = entries.indexOfFirst { it.cpu == lv }
            val label = if (idx >= 0) "${idx + 1}. ${lv.botName}" else lv.botName
            if (ui.button(g, "golf_cpu_${lv.name}", Rect(x, cy + 30f, x + cw, cy + 130f), label, lv.label, PlayerColors.get(lv.colorIndex), selected = idx >= 0, size = 34f)) toggle(GolfEntry(cpu = lv))
        }

        // course
        val right = Rect(g.width * 0.5f + 15f, 205f, g.width - 40f, 905f)
        Draw.panel(g, right, app.theme.panel, app.theme.panelBorder)
        g.text("Course", right.cx, right.top + 46f, 38f * app.theme.textScale, Colors.GOLD)
        Courses.all.forEachIndexed { i, c ->
            val r = Rect(right.left + 30f, right.top + 86f + i * 152f, right.right - 30f, right.top + 224f + i * 152f)
            if (ui.button(g, "golf_course$i", r, "", accent = Colors.GOOD, selected = course == i)) course = i
            val tc = if (course == i) Colors.BLACK else Colors.WHITE
            g.text(c.title(!app.christmasMode), r.left + 30f, r.top + 38f, 38f, tc, Align.LEFT)
            g.text("${c.holes.size} holes  -  ${c.tagline}", r.left + 30f, r.top + 80f, 26f, if (course == i) Colors.BLACK else app.theme.dim, Align.LEFT, bold = false)
            val lens = if (c.holes.size > 3) "${c.holes.sumOf { it.length }} m in all" else c.holes.joinToString("  ") { "${it.length} m" }
            g.text("Par ${c.par}  -  $lens", r.left + 30f, r.top + 116f, 24f, if (course == i) Colors.BLACK else app.theme.dim, Align.LEFT, bold = false)
            miniHole(g, c, Rect(r.right - 130f, r.top + 10f, r.right - 20f, r.bottom - 10f))
        }

        val ok = entries.isNotEmpty()
        if (ui.button(g, "golf_go", Rect(g.width / 2f - 330f, 935f, g.width / 2f + 330f, 1045f), "Tee off!", if (ok) "${entries.size} golfer${if (entries.size > 1) "s" else ""}" else "Pick at least one golfer", accent = Colors.GOOD, enabled = ok, size = 46f)) {
            app.replace(GolfScreen(app, Courses.all[course], entries.toList()))
        }
    }

    private fun toggle(e: GolfEntry) {
        val i = entries.indexOfFirst { it.profileId == e.profileId && it.cpu == e.cpu }
        if (i >= 0) entries.removeAt(i) else if (entries.size < 4) entries.add(e) else app.notify("Up to four golfers")
    }

    private val mini = GolfView().also { it.summer = !app.christmasMode }

    /** Tiny overview of a course's first hole on its card. */
    private fun miniHole(g: Gfx, c: Course, r: Rect) {
        val h = c.holes[0]
        mini.frameHole(r, h, 0f, snap = true)
        mini.drawHole(g, h, app.time, putting = false)
        mini.drawFlag(g, h, app.time)
    }
}

/**
 * A round of golf. Each player plays a whole hole in turn: steps up to the camera, raises a hand, and plays - club
 * and aim with the hand cursor (hold a raised hand over a button), the shot itself with a real swing. Computer
 * golfers take their turns on screen.
 */
class GolfScreen(app: GameApp, private val course: Course, private val lineup: List<GolfEntry>, seed: Long = app.platform.nowNanos()) : Screen(app) {
    private enum class Step { NEW, LOOKING, CONFIRM }

    override val music = MusicTrack.CALM
    override val ambience: Ambience? = Ambience.PARK
    override val room: Room? = Room.OUTDOOR
    override val needsCamera = true
    override val allowCursors: Boolean get() = when {
        paused -> true
        match.phase == GolfMatch.Phase.AIM -> humanTurn && app.controls[0].golf.phase == GolfSwingDetector.Phase.IDLE
        match.phase == GolfMatch.Phase.FLIGHT || match.phase == GolfMatch.Phase.RESULT -> false
        else -> true
    }

    val players: List<GolfPlayer> = lineup.mapIndexed { i, e ->
        if (e.cpu != null) GolfPlayer(e.cpu.botName, PlayerColors.get(e.cpu.colorIndex), -100 - i, cpuSkill = e.cpu.skill)
        else {
            val p = app.roster.byId(e.profileId!!) ?: app.roster.players[0]
            GolfPlayer(p.name, PlayerColors.get(p.colorIndex), p.id, hand = p.hand, mode = p.mode)
        }
    }
    val match = GolfMatch(course, players, seed)
    private val rng = Rng(seed + 7)
    private val view = GolfView().also { it.summer = !app.christmasMode }
    private val particles = Particles()
    private var paused = false
    private var step = Step.NEW
    private var trackedProfile: Int? = null
    private var stepTime = 0f
    private var stableTime = 0f
    private val events = ArrayList<GestureEvent>()
    private val shots = ArrayList<GolfSwingDetector.Shot>()
    private var cpuTime = 0f
    private var cpuShot: Pair<Float, Float>? = null
    private var swingAnim = 9f
    private var lastPhase = match.phase
    private var landed = false
    private var holeDoneTime = 0f
    private var showCard = 0f
    /** Seconds into the opening flyover of the current hole (FLY_SEC or more: none running). */
    private var flyT = 99f
    private var flyHole = -1
    /** "BIRDIE!", "PAR" ... shown big for a moment when a ball drops. */
    private var celebrate: String? = null
    private var celebrateColor = Colors.GOLD
    private var celebrateT = 99f

    /** True while a camera player (not a computer golfer, not the developer autoplay) is up. */
    private val humanTurn: Boolean get() = !match.player.isCpu && !(app.settings.simulatedInput && app.sim.autoplay)

    override fun onEnter() {
        app.objectKind = com.clrg.core.game.ObjectKind.CLUB
        app.startTurnTracking()
        particles.reduced = app.settings.reducedEffects
    }

    override fun onExit() {
        app.objectKind = null
        app.controls.forEach { it.golfStroke = GolfStroke.NONE }
    }

    override fun onBack(): Boolean { paused = !paused; return true }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        val ctl = app.controls[0]
        ctl.golfStroke = if (!paused && match.phase == GolfMatch.Phase.AIM && humanTurn) (if (match.club == Club.PUTTER) GolfStroke.PUTT else GolfStroke.FULL) else GolfStroke.NONE
        events.clear(); ctl.drainEvents(events)
        shots.clear(); ctl.drainGolfShots(shots)
        if (!paused) advance(dt)
        particles.update(dt)
        draw(g, ui, dt)
    }

    // ------------------------------------------------------------------ game flow

    private fun advance(dt: Float) {
        swingAnim += dt
        showCard = (showCard - dt).coerceAtLeast(0f)
        celebrateT += dt
        // a new hole opens with a flyover from the green back to the tee
        if (match.phase == GolfMatch.Phase.TURN_START && flyHole != match.holeIndex && match.current == 0 && match.strokesThisHole == 0) {
            flyHole = match.holeIndex; flyT = 0f
        }
        if (flyT < FLY_SEC) flyT += dt
        when (match.phase) {
            GolfMatch.Phase.TURN_START -> turnStart(dt)
            GolfMatch.Phase.AIM -> if (humanTurn) humanAim() else cpuAim(dt)
            GolfMatch.Phase.FLIGHT, GolfMatch.Phase.RESULT -> {
                val p = match.plan
                match.update(dt)
                if (p != null && match.phase == GolfMatch.Phase.FLIGHT && !landed && match.animTime >= p.flightSec && p.club != Club.PUTTER) {
                    landed = true
                    extras.landX = p.land.x; extras.landY = p.land.y; extras.landAge = 0f
                    if (p.hitTree) app.audio.play(Sfx.GOLF_TREE, 0.8f)
                    when (match.hole.surface(p.land)) {
                        Surface.WATER -> { app.audio.play(Sfx.GOLF_SPLASH, 0.9f); app.audio.play(Sfx.CROWD_AW, 0.3f) }
                        Surface.SAND -> app.audio.play(Sfx.GOLF_SAND, 0.8f)
                        Surface.TREES -> if (!p.hitTree) app.audio.play(Sfx.GOLF_TREE, 0.8f)
                        else -> app.audio.play(Sfx.GOLF_LAND, 0.8f)
                    }
                }
            }
            GolfMatch.Phase.HOLE_DONE -> { holeDoneTime += dt; if (holeDoneTime > 14f) nextHole() }
            GolfMatch.Phase.COURSE_DONE -> Unit
        }
        if (match.phase != lastPhase) onPhaseChange(lastPhase, match.phase)
        lastPhase = match.phase
    }

    private fun onPhaseChange(from: GolfMatch.Phase, to: GolfMatch.Phase) {
        if (to == GolfMatch.Phase.RESULT && from == GolfMatch.Phase.FLIGHT) {
            val p = match.plan
            when {
                p?.outcome == Outcome.HOLED || match.message.contains("HOLE IN ONE") || match.message.startsWith("Tapped in") || match.strokes[match.current][match.holeIndex] > 0 -> {
                    val toPar = match.strokes[match.current][match.holeIndex] - match.hole.par
                    val good = toPar <= 0
                    val strokes = match.strokes[match.current][match.holeIndex]
                    celebrate = when {
                        strokes == 1 -> "HOLE IN ONE!"
                        toPar <= -3 -> "ALBATROSS!"
                        toPar == -2 -> "EAGLE!"
                        toPar == -1 -> "BIRDIE!"
                        toPar == 0 -> "PAR"
                        toPar == 1 -> "BOGEY"
                        toPar == 2 -> "DOUBLE BOGEY"
                        else -> "+$toPar"
                    }
                    celebrateColor = when { toPar < 0 -> Colors.GOLD; toPar == 0 -> Colors.WHITE; else -> Colors.ICE }
                    celebrateT = 0f
                    app.audio.play(Sfx.GOLF_CUP, 0.9f)
                    when {
                        toPar <= -2 || match.message.contains("HOLE IN ONE") -> { app.audio.play(Sfx.GOLF_BIRDIE); app.audio.play(Sfx.CROWD_CHEER, 0.8f) }
                        toPar == -1 -> app.audio.play(Sfx.GOLF_BIRDIE)
                        toPar == 0 -> app.audio.play(Sfx.APPLAUSE, 0.6f)
                    }
                    if (good) particles.confetti(0f, 1920f, -20f, 70)
                }
                p?.outcome == Outcome.OUT -> { app.audio.play(Sfx.BAD, 0.7f); app.audio.play(Sfx.CROWD_AW, 0.35f) }
                match.message.startsWith("On the green") -> app.audio.play(Sfx.APPLAUSE, 0.35f)
            }
        }
        if (to == GolfMatch.Phase.HOLE_DONE) { holeDoneTime = 0f; app.audio.play(if (app.christmasMode) Sfx.JINGLE else Sfx.UI_OPEN) }
        if (to == GolfMatch.Phase.COURSE_DONE) { app.audio.play(if (app.christmasMode) Sfx.WIN else Sfx.FANFARE); app.audio.play(Sfx.APPLAUSE, 0.8f); app.audio.playMusic(MusicTrack.VICTORY, restart = true); particles.confetti(0f, 1920f, -20f, 120) }
        if (to == GolfMatch.Phase.TURN_START) { step = Step.NEW; stepTime = 0f }
        if (to == GolfMatch.Phase.AIM) { cpuTime = 0f; cpuShot = null; extras.landX = Float.NaN; flyT = FLY_SEC }
    }

    private fun nextHole() { match.continueAfterHole() }

    /** The next golfer steps up. A camera player is picked up and confirms with a raised hand. */
    private fun turnStart(dt: Float) {
        stepTime += dt
        val pl = match.player
        if (!humanTurn) {
            if (stepTime > 1.4f && flyT >= FLY_SEC) match.beginTurn()
            return
        }
        val prof = app.roster.byId(pl.profileId)
        if (prof != null) app.activePlayers[0] = prof
        val slot = app.tracker.slots[0]
        when (step) {
            Step.NEW -> {
                if (trackedProfile == pl.profileId && app.calibrated && slot.isPlaying) {
                    // the same person again (e.g. playing alone): carry straight on
                    if (stepTime > 1.2f && flyT >= FLY_SEC) match.beginTurn()
                    return
                }
                trackedProfile = null
                app.nextTurnPerson()
                step = Step.LOOKING; stableTime = 0f
            }
            Step.LOOKING -> {
                stableTime = if (slot.isPlaying) stableTime + dt else 0f
                if (stableTime > 0.8f && app.calibrateTurnPerson(prof)) { step = Step.CONFIRM; app.audio.play(Sfx.SELECT, 0.6f) }
            }
            Step.CONFIRM -> {
                if (!slot.isPlaying) { app.nextTurnPerson(); step = Step.LOOKING; stableTime = 0f; return }
                if (events.any { it is GestureEvent.RaiseHold }) {
                    trackedProfile = pl.profileId
                    app.audio.play(Sfx.GO, 0.7f)
                    app.controls[0].discardEvents()
                    match.beginTurn()
                }
            }
        }
    }

    private fun humanAim() {
        if (!app.tracker.slots[0].isPlaying) return
        val s = shots.lastOrNull() ?: return
        val wanted = if (match.club == Club.PUTTER) GolfStroke.PUTT else GolfStroke.FULL
        if (s.stroke != wanted) return
        strike(s.power, s.offset)
    }

    private fun strike(power: Float, offset: Float) {
        val club = match.club
        match.shoot(power, offset) ?: return
        landed = false
        swingAnim = 0f
        when (club) {
            Club.PUTTER -> app.audio.play(Sfx.GOLF_PUTT, 0.5f + 0.5f * power.coerceIn(0f, 1f))
            Club.DRIVER, Club.WOOD -> app.audio.play(Sfx.GOLF_DRIVE, 0.95f, 0f, if (club == Club.WOOD) 1.05f else 1f)
            else -> app.audio.play(Sfx.GOLF_IRON, 0.9f, 0f, if (club == Club.WEDGE) 1.08f else 1f)
        }
    }

    /** Computer golfer (and developer autoplay): picks a club and aim, then swings after a moment's thought. */
    private fun cpuAim(dt: Float) {
        cpuTime += dt
        if (cpuShot == null) cpuShot = match.cpuShot(match.player.cpuSkill ?: 0.8f, rng)
        if (cpuTime > 1.6f && swingAnim > 5f) swingAnim = 0f // start the swing animation
        if (cpuTime > 1.6f + CPU_IMPACT) { val (p, o) = cpuShot!!; strike(p, o) }
    }

    // ------------------------------------------------------------------ drawing

    private val scene = GolfScene3D().also { it.summer = !app.christmasMode }
    private val extras = GolfScene3D.Extras()
    private var shownAngle = 0f

    /** The middle of the screen between the side panels (banners and overlays go here). */
    private fun centre(g: Gfx) = Rect(520f, 140f, g.width - 290f, 1060f)

    private fun draw(g: Gfx, ui: Ui, dt: Float) {
        val hole = match.hole
        updateCamera(dt)
        fillExtras(dt)
        scene.draw(g, Rect(0f, 0f, g.width, g.height), hole, app.time, extras)
        Draw.vignette(g, if (app.settings.reducedEffects) 0.15f else 0.28f)
        val flying = flyT < FLY_SEC && match.phase == GolfMatch.Phase.TURN_START
        val mapR = Rect(560f, 140f, g.width - 300f, g.height - 40f)
        if (flying) holeIntro(g, flyT / FLY_SEC)
        else {
            if (ui.button(g, "back", Rect(24f, 24f, 116f, 140f), "", accent = Colors.ICE, icon = Icon.PAUSE, size = 34f)) paused = !paused
            holeCard(g, Rect(132f, 24f, 560f, 140f))
            playerCard(g, Rect(24f, 156f, 560f, 372f))
            if (match.phase == GolfMatch.Phase.AIM && humanTurn) {
                glass(g, Rect(24f, 390f, 520f, g.height - 24f))
                cameraInset(g, Rect(34f, 400f, 510f, 650f))
                swingPanel(g, Rect(34f, 660f, 510f, g.height - 34f))
            } else if (match.phase == GolfMatch.Phase.AIM) {
                val t = "${match.player.name} is lining up..."
                val w = g.measureText(t, 32f, false) + 60f
                g.fillRoundRect(24f, g.height - 100f, 24f + w, g.height - 34f, 33f, 0xCC0B1A36.toInt())
                g.text(t, 24f + w / 2f, g.height - 67f, 32f, Colors.WHITE, bold = false)
            }
            miniMap(g, Rect(g.width - 276f, 24f, g.width - 24f, 330f), dt)
            clubColumn(g, ui, Rect(g.width - 276f, 346f, g.width - 24f, g.height - 24f))
        }
        // result message at the top of the view
        val showMsg = match.phase == GolfMatch.Phase.AIM || match.phase == GolfMatch.Phase.FLIGHT || match.phase == GolfMatch.Phase.RESULT
        if (showMsg && match.message.isNotEmpty() && match.messageTime < 3.2f) {
            val a = (if (match.messageTime > 2.6f) (3.2f - match.messageTime) / 0.6f else 1f).coerceIn(0f, 1f)
            g.save(); g.setAlpha(a)
            val w = minOf(g.measureText(match.message, 46f) + 80f, 1040f)
            g.fillRoundRect(g.width / 2f - w / 2f, 34f, g.width / 2f + w / 2f, 112f, 39f, 0xDD0B2A4F.toInt())
            g.strokeRoundRect(g.width / 2f - w / 2f, 34f, g.width / 2f + w / 2f, 112f, 39f, 3f, Colors.withAlpha(match.player.color, 0.8f))
            g.text(match.message, g.width / 2f, 73f, ui.fit(g, match.message, 46f, w - 60f), Colors.GOLD, outline = Colors.BLACK, outlineWidth = 4f)
            g.restore()
        }
        celebration(g)
        particles.render(g)
        when {
            match.phase == GolfMatch.Phase.TURN_START && flying -> Unit
            match.phase == GolfMatch.Phase.TURN_START && humanTurn -> stepUpOverlay(g, mapR)
            match.phase == GolfMatch.Phase.TURN_START -> banner(g, mapR, "${match.player.name}'s turn", match.player.color)
            match.phase == GolfMatch.Phase.HOLE_DONE -> holeDone(g, ui)
            match.phase == GolfMatch.Phase.COURSE_DONE -> courseDone(g, ui)
        }
        if (showCard > 0f && match.phase == GolfMatch.Phase.AIM) scorecard(g, Rect(g.width / 2f - 640f, 260f, g.width / 2f + 640f, 760f))
        if (match.phase == GolfMatch.Phase.AIM && humanTurn && !app.tracker.slots[0].isPlaying) banner(g, mapR, "Step back in front of the camera", Colors.WARN)
        if (paused) pauseMenu(g, ui)
    }

    /** Frosted panel behind HUD cards. */
    private fun glass(g: Gfx, r: Rect, accent: Int = 0x885D8BC9.toInt()) {
        g.fillRoundRect(r.left + 4f, r.top + 8f, r.right + 4f, r.bottom + 8f, 26f, 0x44000000)
        g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, 26f, 0xD0173260.toInt(), 0xD00A1730.toInt())
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, 26f, 2.5f, accent)
    }

    /** Hole number, par and length. */
    private fun holeCard(g: Gfx, r: Rect) {
        val h = match.hole
        glass(g, r)
        g.text("HOLE ${h.number}", r.left + 26f, r.top + 44f, 46f, Colors.GOLD, Align.LEFT)
        g.text("of ${course.holes.size}", r.left + 36f + g.measureText("HOLE ${h.number}", 46f), r.top + 50f, 26f, app.theme.dim, Align.LEFT, bold = false)
        val chip = "PAR ${h.par}"
        val cw = g.measureText(chip, 30f) + 36f
        g.fillRoundRect(r.right - cw - 20f, r.top + 22f, r.right - 20f, r.top + 66f, 22f, Colors.GOLD)
        g.text(chip, r.right - 20f - cw / 2f, r.top + 44f, 30f, Colors.BLACK)
        g.text("${h.length} m  -  ${course.title(!app.christmasMode)}", r.left + 26f, r.top + 92f, 28f, Colors.WHITE, Align.LEFT, bold = false)
    }

    /** Opening card while the camera flies the hole. */
    private fun holeIntro(g: Gfx, k: Float) {
        val h = match.hole
        val a = when { k < 0.12f -> k / 0.12f; k > 0.88f -> (1f - k) / 0.12f; else -> 1f }.coerceIn(0f, 1f)
        g.save(); g.setAlpha(a)
        val r = Rect(g.width / 2f - 560f, g.height - 350f, g.width / 2f + 560f, g.height - 50f)
        glass(g, r, Colors.withAlpha(Colors.GOLD, 0.8f))
        g.text("HOLE ${h.number}", r.cx, r.top + 70f, 92f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 6f)
        g.text("Par ${h.par}  -  ${h.length} m  -  ${course.title(!app.christmasMode)}", r.cx, r.top + 140f, 40f, Colors.WHITE)
        val notes = ArrayList<String>()
        if (h.water.isNotEmpty()) notes.add("Water")
        if (h.bunkers.isNotEmpty()) notes.add("${h.bunkers.size} bunker${if (h.bunkers.size > 1) "s" else ""}")
        if (h.trees.isNotEmpty()) notes.add("Trees")
        val dogleg = kotlin.math.abs(h.pin.x) > h.length * 0.15f
        if (dogleg) notes.add(if (h.pin.x > 0) "Dogleg right" else "Dogleg left")
        if (h.wind.len() > 1.5f) notes.add("Windy")
        var x = r.cx - (notes.sumOf { (g.measureText(it, 26f, false) + 40f).toDouble() }.toFloat() + 12f * (notes.size - 1)) / 2f
        for (n in notes) x += app.ui.chip(g, x, r.top + 200f, n, Colors.ICE, 26f) + 12f
        if (humanTurn) {
            val t = when (step) {
                Step.CONFIRM -> "${match.player.name}: raise a hand to start"
                else -> "${match.player.name}: step up to the camera"
            }
            g.text(t, r.cx, r.bottom - 26f, 28f, match.player.color, bold = false)
        }
        g.restore()
    }

    /** "BIRDIE!" and friends, popping up in the middle when a ball drops. */
    private fun celebration(g: Gfx) {
        val text = celebrate ?: return
        val t = celebrateT
        if (t > 2.6f) return
        val pop = if (t < 0.35f) com.clrg.core.math.easeOutBack((t / 0.35f).coerceIn(0f, 1f)) else 1f
        val a = if (t > 2.1f) (2.6f - t) / 0.5f else 1f
        val big = text.endsWith("!")
        val size = if (big) 120f else 84f
        g.save(); g.setAlpha(a.coerceIn(0f, 1f))
        g.translate(g.width / 2f, 330f); g.scale(pop.coerceAtLeast(0.01f)); g.translate(-g.width / 2f, -330f)
        val w = g.measureText(text, size) + 120f
        if (big) g.glow(g.width / 2f, 330f, w * 0.7f, Colors.withAlpha(celebrateColor, 0.35f))
        g.fillRoundRect(g.width / 2f - w / 2f, 330f - size * 0.75f, g.width / 2f + w / 2f, 330f + size * 0.75f, size * 0.75f, 0xCC0B1A36.toInt())
        g.text(text, g.width / 2f, 330f, size, celebrateColor, outline = Colors.BLACK, outlineWidth = size * 0.08f)
        if (big) for (i in 0 until 6) {
            val ang = app.time.toFloat() * 1.5f + i * 1.047f
            Draw.star(g, g.width / 2f + kotlin.math.cos(ang) * (w * 0.55f), 330f + kotlin.math.sin(ang) * size * 0.9f, 18f, Colors.GOLD, ang)
        }
        g.restore()
    }

    /** Where the 3D camera wants to be for what is happening now. */
    private fun updateCamera(dt: Float) {
        val h = match.hole
        val left = match.player.hand == com.clrg.core.tracking.Hand.LEFT
        var rate = 3.2f
        when (match.phase) {
            GolfMatch.Phase.AIM -> {
                if (match.club == Club.PUTTER) scene.puttView(match.ball, match.aim, h.pin, left) else scene.aimView(match.ball, match.aim, left)
                rate = 4f
            }
            GolfMatch.Phase.FLIGHT, GolfMatch.Phase.RESULT -> {
                val p = match.plan
                if (p == null) scene.overview(h, app.time)
                else if (p.club == Club.PUTTER) scene.puttView(p.start, match.aim, h.pin, left)
                else if (match.phase == GolfMatch.Phase.FLIGHT) {
                    val (pos, hgt) = GolfPhysics.positionAt(p, match.animTime)
                    scene.flightView(p.start, match.aim, left, pos.x, pos.y, hgt, GolfPhysics.aimAt(p.start, p.land), match.animTime)
                } else {
                    // settle behind where the ball finished
                    val end = if (p.outcome == Outcome.STOPPED) p.end else p.land
                    scene.chaseView(end.x, end.y, 0f, GolfPhysics.aimAt(p.start, p.land), 1f)
                    rate = 1.6f
                }
            }
            else -> {
                if (flyT < FLY_SEC) scene.flyover(h, flyT / FLY_SEC) else scene.overview(h, app.time)
                rate = 1.4f
            }
        }
        scene.update(dt, rate)
    }

    private fun fillExtras(dt: Float) {
        val x = extras
        val pl = match.player
        x.ballColor = pl.color
        x.golferColor = pl.color
        x.tracerColor = pl.color
        x.landAge += dt
        x.leftHanded = pl.hand == com.clrg.core.tracking.Hand.LEFT
        x.club = match.club
        x.showAim = false; x.golfer = false; x.putting = false
        x.ballX = Float.NaN
        val p = match.plan
        when (match.phase) {
            GolfMatch.Phase.AIM -> {
                x.ballX = match.ball.x; x.ballY = match.ball.y; x.ballH = 0f
                x.golferX = match.ball.x; x.golferY = match.ball.y
                x.golfer = true
                x.aim = match.aim
                x.showAim = true
                val live = if (humanTurn) app.controls[0].golf.livePower else 0f
                x.aimStrong = live > 0f
                x.aimColor = pl.color
                if (match.club == Club.PUTTER) {
                    x.putting = true
                    x.aimDist = if (live > 0f) GolfPhysics.puttDistance(live) else match.distanceToPin()
                    x.ringR = 0.5f; x.apex = 0f
                } else {
                    val reach = match.reachNow(if (live > 0f) live.coerceAtMost(1f) else 1f)
                    x.aimDist = reach
                    x.ringR = (reach * sin(match.club.spreadDeg * PI / 360.0)).toFloat()
                    x.apex = maxOf(4f, reach * match.club.loft)
                }
                x.trailN = 0
            }
            GolfMatch.Phase.FLIGHT -> if (p != null) {
                val (pos, hgt) = GolfPhysics.positionAt(p, match.animTime)
                x.ballX = pos.x; x.ballY = pos.y; x.ballH = hgt
                x.putting = p.club == Club.PUTTER
                x.golferX = p.start.x; x.golferY = p.start.y
                x.golfer = match.animTime < 1.8f
                x.aim = match.aim
                if (x.trailN >= x.trail.size / 3) { System.arraycopy(x.trail, 3, x.trail, 0, x.trail.size - 3); x.trailN-- }
                x.trail[x.trailN * 3] = pos.x; x.trail[x.trailN * 3 + 1] = pos.y; x.trail[x.trailN * 3 + 2] = hgt
                x.trailN++
            }
            GolfMatch.Phase.RESULT -> if (p != null && p.outcome != Outcome.HOLED && match.ball != match.hole.pin) {
                val end = if (p.outcome == Outcome.STOPPED) p.end else match.ball
                x.ballX = end.x; x.ballY = end.y; x.ballH = 0f
                x.putting = p.club == Club.PUTTER
            }
            else -> x.trailN = 0
        }
        // the golfer copies the player's swing (or the computer's animation)
        val target = if (humanTurn && app.controls[0].hasPose && !app.controls[0].golf.handsX.isNaN())
            GolfScene3D.swingAngleFromHands(app.controls[0].golf.handsX, app.controls[0].golf.handsY, x.leftHanded)
        else scriptedAngle()
        shownAngle += (target - shownAngle) * (1f - kotlin.math.exp(-dt * 18f))
        x.swingAngle = shownAngle
    }

    /** Swing angle of the computer golfer's animation: address, backswing, strike and follow-through. */
    private fun scriptedAngle(): Float {
        val t = swingAnim
        val putt = match.club == Club.PUTTER
        val top = if (putt) 0.45f else 2.0f
        val fin = if (putt) -0.4f else -2.1f
        return when {
            t < 0.6f -> top * sin(t / 0.6f * PI.toFloat() / 2f)
            t < 0.75f -> top
            t < CPU_IMPACT -> top * (1f - ((t - 0.75f) / (CPU_IMPACT - 0.75f)).let { it * it })
            t < CPU_IMPACT + 0.35f -> fin * sin((t - CPU_IMPACT) / 0.35f * PI.toFloat() / 2f)
            t < 3f -> fin
            else -> 0f
        }
    }

    /** Whose turn, the stroke, the score, distance to the pin, the lie and the wind. */
    private fun playerCard(g: Gfx, r: Rect) {
        val pl = match.player
        glass(g, r, Colors.withAlpha(pl.color, 0.8f))
        g.fillRoundRect(r.left, r.top, r.left + 14f, r.bottom, 7f, pl.color)
        g.text(pl.name + if (pl.isCpu) " (computer)" else "", r.left + 34f, r.top + 40f, ui0fit(g, pl.name, 42f, r.width - 200f), pl.color, Align.LEFT, outline = Colors.BLACK, outlineWidth = 3f)
        val strokeNo = (match.strokesThisHole + if (match.phase == GolfMatch.Phase.AIM) 1 else 0).coerceAtLeast(1)
        g.text("Stroke $strokeNo   -   Round ${GolfMatch.toParText(match.toPar(match.current))}", r.left + 34f, r.top + 84f, 28f, Colors.WHITE, Align.LEFT, bold = false)
        val playing = match.phase == GolfMatch.Phase.AIM || match.phase == GolfMatch.Phase.FLIGHT || match.phase == GolfMatch.Phase.RESULT
        if (playing) {
            g.text(GolfMatch.fmt(match.distanceToPin()), r.left + 34f, r.top + 146f, 58f, Colors.WHITE, Align.LEFT)
            g.text("to the pin", r.left + 44f + g.measureText(GolfMatch.fmt(match.distanceToPin()), 58f), r.top + 154f, 26f, app.theme.dim, Align.LEFT, bold = false)
            val lieCol = when (match.lie) { Surface.SAND, Surface.TREES -> Colors.WARN; Surface.GREEN, Surface.FAIRWAY, Surface.TEE -> Colors.GOOD; else -> Colors.ICE }
            g.fillCircle(r.left + 44f, r.top + 192f, 9f, lieCol)
            g.text("Lie: ${match.lie.label}", r.left + 62f, r.top + 192f, 28f, Colors.WHITE, Align.LEFT, bold = false)
        }
        // wind, as it blows across the view
        val w = match.hole.wind
        val m = w.len()
        val ang = atan2(w.x, w.y) - scene.cam.yaw
        val ax = r.right - 74f; val ay = r.top + 120f
        g.fillCircle(ax, ay, 52f, 0x66000000)
        g.strokeCircle(ax, ay, 52f, 2f, 0x669AD9FF)
        val dx = sin(ang) * 36f; val dy = -cos(ang) * 36f
        g.line(ax - dx, ay - dy, ax + dx, ay + dy, 7f, Colors.ICE)
        val hd = atan2(dy, dx)
        g.line(ax + dx, ay + dy, ax + dx - cos(hd - 0.5f) * 18f, ay + dy - sin(hd - 0.5f) * 18f, 7f, Colors.ICE)
        g.line(ax + dx, ay + dy, ax + dx - cos(hd + 0.5f) * 18f, ay + dy - sin(hd + 0.5f) * 18f, 7f, Colors.ICE)
        g.text("%.1f m/s".format(m), ax, ay + 76f, 24f, Colors.ICE, bold = false)
    }

    private fun ui0fit(g: Gfx, s: String, size: Float, maxW: Float): Float {
        val w = g.measureText(s, size)
        return if (w <= maxW || w <= 0f) size else size * maxW / w
    }

    /** The camera's picture of the player with the hands marked: green when they hold the "club" together. */
    private fun cameraInset(g: Gfx, r: Rect) {
        g.fillRoundRect(r.left, r.top, r.right, r.bottom, 18f, 0x66000000)
        if (!humanTurn || match.phase == GolfMatch.Phase.TURN_START) {
            g.text(if (match.player.isCpu) "${match.player.name} is playing" else "Your turn is coming", r.cx, r.cy, 30f, app.theme.dim, bold = false)
            return
        }
        val inner = Rect(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom - 10f)
        PreviewView.draw(g, app, inner, showAreas = false, labels = false)
        val slot = app.tracker.slots[0]
        val sm = slot.smooth
        if (!slot.isPlaying || !sm.valid) return
        val aspect = app.tracker.aspect
        var w = inner.width; var hgt = w / aspect
        if (hgt > inner.height) { hgt = inner.height; w = hgt * aspect }
        val x0 = inner.cx - w / 2f; val y0 = inner.cy - hgt / 2f
        fun mx(fx: Float) = x0 + fx / aspect * w
        fun my(fy: Float) = y0 + fy * hgt
        val lw = com.clrg.core.pose.Lm.LEFT_WRIST; val rw = com.clrg.core.pose.Lm.RIGHT_WRIST
        val det = app.controls[0].golf
        val ok = det.grip
        val c = if (ok) Colors.GOOD else Colors.WARN
        val ax = mx(sm.x[lw]); val ay = my(sm.y[lw]); val bx = mx(sm.x[rw]); val by = my(sm.y[rw])
        if (!ok) g.line(ax, ay, bx, by, 4f, Colors.withAlpha(c, 0.8f))
        val rr = (slot.scale * hgt * 0.25f).coerceIn(10f, 60f)
        g.strokeCircle((ax + bx) / 2f, (ay + by) / 2f, rr + if (ok) 0f else kotlin.math.hypot(ax - bx, ay - by) / 2f, 5f, c)
        g.text(if (ok) "Grip OK" else "Hands together", inner.cx, inner.bottom - 22f, 26f, c, outline = Colors.BLACK, outlineWidth = 4f)
    }

    /** What to do now: the grip guide, then the address, then the live power of the swing. */
    private fun swingPanel(g: Gfx, r: Rect) {
        if (match.phase != GolfMatch.Phase.AIM) return
        if (!humanTurn) { g.text("${match.player.name} is lining up...", r.cx, r.cy, 34f, Colors.WHITE, bold = false); return }
        val det = app.controls[0].golf
        val putt = match.club == Club.PUTTER
        when (det.phase) {
            GolfSwingDetector.Phase.IDLE -> {
                // two mittens that come together as the player's hands do
                val gap = if (det.handsGap.isNaN()) 1.2f else det.handsGap.coerceIn(0f, 1.4f)
                val cx = r.cx; val cy = r.top + 85f
                val sep = 18f + gap * 110f
                val col = if (det.grip) Colors.GOOD else Colors.WARN
                for (s in intArrayOf(-1, 1)) {
                    val mx = cx + s * sep / 2f
                    g.fillRoundRectGradient(mx - 22f, cy - 30f, mx + 22f, cy + 26f, 20f, Colors.lighter(col, 0.3f), Colors.darker(col, 0.7f))
                    g.fillCircle(mx - s * 24f, cy, 11f, col)
                }
                if (det.grip) g.line(cx, cy - 60f, cx, cy + 60f, 6f, 0xFFB0BEC5.toInt())
                if (!det.grip) {
                    g.text("Hands together", r.cx, r.top + 160f, 42f, Colors.GOLD)
                    g.text("as if holding a club, low in front", r.cx, r.top + 205f, 30f, Colors.WHITE, bold = false)
                } else {
                    g.text("Good grip - hold still", r.cx, r.top + 160f, 42f, Colors.GOOD)
                    g.text(if (putt) "then rock the putter back and through" else "then swing back and through", r.cx, r.top + 205f, 28f, Colors.WHITE, bold = false)
                    if (det.addressProgress > 0f) Draw.progressRing(g, r.cx, r.top + 260f, 24f, det.addressProgress, Colors.GOLD, 8f)
                }
                g.text("Raise one hand to pick a club or aim", r.cx, r.bottom - 30f, 26f, app.theme.dim, bold = false)
            }
            GolfSwingDetector.Phase.ADDRESS -> {
                g.text(if (putt) "Putt!" else "Swing!", r.cx, r.top + 90f, 76f, Colors.GOOD, outline = Colors.BLACK, outlineWidth = 6f)
                g.text(if (putt) "Further back = harder putt" else "Higher backswing = further", r.cx, r.top + 175f, 32f, Colors.WHITE, bold = false)
                g.text("Keep your hands together", r.cx, r.top + 225f, 28f, app.theme.dim, bold = false)
            }
            GolfSwingDetector.Phase.BACKSWING, GolfSwingDetector.Phase.FOLLOW -> {
                val p = det.livePower
                val bar = Rect(r.left + 30f, r.top + 60f, r.right - 30f, r.top + 120f)
                g.fillRoundRect(bar.left, bar.top, bar.right, bar.bottom, 24f, 0x55FFFFFF)
                val full = bar.left + bar.width / GolfSwingDetector.MAX_POWER
                val fx = bar.left + bar.width * (p / GolfSwingDetector.MAX_POWER).coerceIn(0f, 1f)
                g.fillRoundRect(bar.left, bar.top, fx, bar.bottom, 24f, if (p > 1f) Colors.WARN else Colors.GOOD)
                g.line(full, bar.top - 8f, full, bar.bottom + 8f, 4f, Colors.WHITE)
                val d = if (putt) GolfPhysics.puttDistance(p) else match.reachNow(p.coerceAtMost(1f))
                g.text("about ${GolfMatch.fmt(d)}", r.cx, r.top + 175f, 46f, Colors.WHITE)
                if (p > 1f) g.text("Overswing - less accurate", r.cx, r.top + 230f, 28f, Colors.WARN, bold = false)
            }
        }
    }

    /** The whole hole from above, with the ball and where it is aimed. */
    private fun miniMap(g: Gfx, r: Rect, dt: Float) {
        glass(g, Rect(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f))
        val h = match.hole
        view.frameHole(r, h, dt)
        view.drawHole(g, h, app.time, false)
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        view.drawFlag(g, h, app.time)
        if (!extras.ballX.isNaN()) {
            if (match.phase == GolfMatch.Phase.AIM) view.drawAim(g, match.ball, match.aim, extras.aimDist, extras.ringR, match.player.color, extras.aimStrong)
            view.drawBall(g, Pt(extras.ballX, extras.ballY), 0f, match.player.color)
        }
        g.restore()
        view.frameBorder(g)
    }

    /** Hands of the animated golfer (computer turns): address, backswing, strike and follow-through. */
    private fun scriptedHands(x: Float, y: Float, h: Float): Pair<Float, Float> {
        val addr = x to y - h * 0.34f
        val t = swingAnim
        val putt = match.club == Club.PUTTER
        if (putt) {
            val k = when { t < 0.5f -> sin(t / 0.5f * PI.toFloat() / 2f); t < CPU_IMPACT -> 1f - 2f * ((t - 0.5f) / (CPU_IMPACT - 0.5f)); t < 1.6f -> -1f; else -> 0f }
            return addr.first + k * h * 0.12f to addr.second
        }
        val top = x + h * 0.26f to y - h * 0.86f
        val fin = x - h * 0.26f to y - h * 0.8f
        fun mix(a: Pair<Float, Float>, b: Pair<Float, Float>, u: Float) = a.first + (b.first - a.first) * u to a.second + (b.second - a.second) * u
        return when {
            t < 0.6f -> mix(addr, top, sin(t / 0.6f * PI.toFloat() / 2f))
            t < 0.75f -> top
            t < CPU_IMPACT -> mix(top, addr, ((t - 0.75f) / (CPU_IMPACT - 0.75f)).let { it * it })
            t < CPU_IMPACT + 0.35f -> mix(addr, fin, sin((t - CPU_IMPACT) / 0.35f * PI.toFloat() / 2f))
            t < 3f -> fin
            else -> addr
        }
    }

    private fun clubColumn(g: Gfx, ui: Ui, r: Rect) {
        val canChoose = match.phase == GolfMatch.Phase.AIM && humanTurn
        val rowH = 76f
        Club.values().forEachIndexed { i, c ->
            val b = Rect(r.left, r.top + i * rowH, r.right, r.top + i * rowH + rowH - 8f)
            val dist = if (c == Club.PUTTER) "green" else "${(c.carry * GolfPhysics.lieFactor(match.lie, c)).toInt()} m"
            if (ui.button(g, "club_${c.name}", b, c.label, dist, accent = Colors.GOLD, selected = match.club == c, enabled = canChoose, size = 32f)) {
                match.selectClub(c); app.audio.play(Sfx.SELECT, 0.5f)
            }
        }
        // aim: hold a hand on an arrow to keep turning; a tap turns a little
        val ay = r.top + 6 * rowH + 6f
        val half = (r.width - 10f) / 2f
        val aimL = Rect(r.left, ay, r.left + half, ay + 82f)
        val aimR = Rect(r.right - half, ay, r.right, ay + 82f)
        val fine = match.club == Club.PUTTER
        val stepRad = ((if (fine) 0.5 else 1.0) * PI / 180.0).toFloat()
        if (ui.button(g, "aimL", aimL, "", accent = Colors.ICE, enabled = canChoose, size = 28f, icon = Icon.BACK)) match.nudgeAim(-stepRad)
        if (ui.button(g, "aimR", aimR, "", accent = Colors.ICE, enabled = canChoose, size = 28f, icon = Icon.PLAY)) match.nudgeAim(stepRad)
        if (canChoose) {
            val rate = ((if (fine) 3.0 else 8.0) * PI / 180.0).toFloat() / 60f
            if (ui.cursors.any { it.active && it.hoverId == "aimL" }) match.nudgeAim(-rate)
            if (ui.cursors.any { it.active && it.hoverId == "aimR" }) match.nudgeAim(rate)
        }
        if (ui.button(g, "golf_card", Rect(r.left, ay + 92f, r.right, ay + 166f), "Scorecard", accent = Colors.ICE, size = 28f, icon = Icon.TROPHY)) showCard = 5f
    }

    private fun banner(g: Gfx, r: Rect, text: String, color: Int) {
        val w = g.measureText(text, 60f) + 100f
        g.fillRoundRect(r.cx - w / 2f, r.cy - 70f, r.cx + w / 2f, r.cy + 70f, 50f, 0xDD0B2A4F.toInt())
        g.text(text, r.cx, r.cy, 60f, color, outline = Colors.BLACK, outlineWidth = 6f)
    }

    private fun stepUpOverlay(g: Gfx, r: Rect) {
        val pl = match.player
        val p = Rect(r.left + 40f, r.top + 40f, r.right - 40f, r.bottom - 40f)
        Draw.panel(g, p, app.theme.panel, app.theme.panelBorder)
        g.text("${pl.name}'s turn!", p.cx, p.top + 70f, 72f, pl.color, outline = Colors.BLACK, outlineWidth = 6f)
        val multi = players.count { !it.isCpu } > 1
        g.text(if (multi) "Step up in front of the camera - everyone else, stand back" else "Stand in front of the camera", p.cx, p.top + 140f, 34f, Colors.WHITE, bold = false)
        PreviewView.draw(g, app, Rect(p.left + 60f, p.top + 180f, p.right - 60f, p.bottom - 170f), showAreas = false, labels = false)
        val msg = when (step) {
            Step.NEW -> "Get ready..."
            Step.LOOKING -> if (app.tracker.slots[0].isPlaying) "Got you - stand still a moment" else "Looking for you..."
            Step.CONFIRM -> "Raise a hand and hold it to start your turn"
        }
        g.text(msg, p.cx, p.bottom - 110f, 44f, if (step == Step.CONFIRM) Colors.GOLD else Colors.WHITE, outline = Colors.BLACK, outlineWidth = 5f)
        if (step == Step.CONFIRM) Draw.progressRing(g, p.cx, p.bottom - 45f, 28f, app.controls[0].raiseProgress, pl.color, 9f)
    }

    private fun holeDone(g: Gfx, ui: Ui) {
        g.fillRect(0f, 0f, g.width, g.height, 0x99000000.toInt())
        val r = Rect(g.width / 2f - 700f, 180f, g.width / 2f + 700f, 780f)
        scorecard(g, r)
        val last = match.holeIndex + 1 >= course.holes.size
        if (ui.button(g, "golf_next", Rect(g.width / 2f - 330f, 820f, g.width / 2f + 330f, 940f), if (last) "Final scores" else "Next hole", "or wait a moment", accent = Colors.GOOD, size = 46f)) nextHole()
    }

    private fun courseDone(g: Gfx, ui: Ui) {
        g.fillRect(0f, 0f, g.width, g.height, 0xAA000000.toInt())
        val lead = match.leaders()
        val title = if (players.size == 1) "Round complete: ${match.total(0)} strokes (${GolfMatch.toParText(match.toPar(0))})"
        else if (lead.size == 1) "${players[lead[0]].name} wins!" else "It's a tie!"
        g.text(title, g.width / 2f, 150f, 80f, if (lead.size == 1) players[lead[0]].color else Colors.GOLD, outline = Colors.BLACK, outlineWidth = 8f)
        scorecard(g, Rect(g.width / 2f - 700f, 220f, g.width / 2f + 700f, 780f))
        if (ui.button(g, "golf_again", Rect(g.width / 2f - 960f + 120f, 830f, g.width / 2f - 340f, 950f), "Play again", "Same course", accent = Colors.GOOD, size = 42f)) app.replace(GolfScreen(app, course, lineup))
        if (ui.button(g, "golf_other", Rect(g.width / 2f - 300f, 830f, g.width / 2f + 300f, 950f), "Another course", accent = Colors.ICE, size = 42f)) app.replace(GolfSetupScreen(app))
        if (ui.button(g, "golf_menu", Rect(g.width / 2f + 340f, 830f, g.width / 2f + 960f - 120f, 950f), "Main menu", accent = Colors.ICE, size = 42f)) { app.leaveSolo(); app.popToRoot() }
    }

    private fun pauseMenu(g: Gfx, ui: Ui) {
        g.fillRect(0f, 0f, g.width, g.height, 0xBB000000.toInt())
        ui.title(g, "Paused", 300f, 90f)
        if (ui.button(g, "golf_resume", Rect.ofCenter(g.width / 2f, 470f, 620f, 110f), "Resume", accent = Colors.GOOD)) paused = false
        if (ui.button(g, "golf_card2", Rect.ofCenter(g.width / 2f, 600f, 620f, 110f), "Scorecard", accent = Colors.ICE)) { paused = false; showCard = 5f }
        if (ui.button(g, "golf_quit", Rect.ofCenter(g.width / 2f, 730f, 620f, 110f), "Quit golf", accent = Colors.BAD)) { app.leaveSolo(); app.popToRoot() }
    }

    /** Players x holes, totals and score to par. */
    private fun scorecard(g: Gfx, r: Rect) {
        Draw.panel(g, r, app.theme.panel, app.theme.panelBorder)
        g.text("Scorecard - ${course.title(!app.christmasMode)}", r.cx, r.top + 50f, 44f, Colors.GOLD)
        val cols = course.holes.size + 2
        val nameW = r.width * 0.34f
        val cw = (r.width - nameW - 40f) / cols
        var y = r.top + 120f
        fun cell(i: Int) = r.left + 20f + nameW + cw * (i + 0.5f)
        g.text("Hole", r.left + 40f, y, 32f, app.theme.dim, Align.LEFT)
        course.holes.forEachIndexed { i, h -> g.text("${h.number}", cell(i), y, 32f, if (i == match.holeIndex && match.phase != GolfMatch.Phase.COURSE_DONE) Colors.GOLD else app.theme.dim) }
        g.text("Total", cell(cols - 2), y, 32f, app.theme.dim)
        g.text("To par", cell(cols - 1), y, 32f, app.theme.dim)
        y += 52f
        g.text("Par", r.left + 40f, y, 32f, Colors.WHITE, Align.LEFT, bold = false)
        course.holes.forEachIndexed { i, h -> g.text("${h.par}", cell(i), y, 32f, Colors.WHITE, bold = false) }
        g.text("${course.par}", cell(cols - 2), y, 32f, Colors.WHITE, bold = false)
        y += 22f
        g.line(r.left + 30f, y, r.right - 30f, y, 2f, 0x55FFFFFF)
        y += 46f
        val rowH = minOf(70f, (r.bottom - y - 20f) / players.size)
        for ((i, p) in players.withIndex()) {
            val lead = match.leaders().contains(i) && match.total(i) > 0
            g.text((if (lead) "* " else "") + p.name, r.left + 40f, y, 38f, p.color, Align.LEFT)
            course.holes.forEachIndexed { h, hole ->
                val s = match.strokes[i][h]
                val c = when { s == 0 -> app.theme.dim; s < hole.par -> Colors.GOOD; s > hole.par -> Colors.WARN; else -> Colors.WHITE }
                g.text(if (s == 0) "-" else "$s", cell(h), y, 38f, c)
            }
            g.text("${match.total(i)}", cell(cols - 2), y, 38f, Colors.WHITE)
            g.text(GolfMatch.toParText(match.toPar(i)), cell(cols - 1), y, 38f, Colors.GOLD)
            y += rowH
        }
    }

    companion object {
        /** Seconds from the start of the computer's swing animation to impact. */
        const val CPU_IMPACT = 1.0f
        /** Length of a hole's opening flyover (s). */
        const val FLY_SEC = 4.5f
    }
}
