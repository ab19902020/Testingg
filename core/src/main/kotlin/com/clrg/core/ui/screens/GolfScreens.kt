package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.audio.MusicTrack
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
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui
import kotlin.math.PI
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
        Common.header(g, ui, "Christmas Golf", app = app)
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
        g.text("Course (3 holes)", right.cx, right.top + 46f, 38f * app.theme.textScale, Colors.GOLD)
        Courses.all.forEachIndexed { i, c ->
            val r = Rect(right.left + 30f, right.top + 90f + i * 196f, right.right - 30f, right.top + 270f + i * 196f)
            if (ui.button(g, "golf_course$i", r, "", accent = Colors.GOOD, selected = course == i)) course = i
            val tc = if (course == i) Colors.BLACK else Colors.WHITE
            g.text(c.name, r.left + 30f, r.top + 50f, 42f, tc, Align.LEFT)
            g.text(c.tagline, r.left + 30f, r.top + 100f, 28f, if (course == i) Colors.BLACK else app.theme.dim, Align.LEFT, bold = false)
            g.text("Par ${c.par}  -  " + c.holes.joinToString("  ") { "${it.length} m" }, r.left + 30f, r.top + 145f, 26f, if (course == i) Colors.BLACK else app.theme.dim, Align.LEFT, bold = false)
            miniHole(g, c, Rect(r.right - 150f, r.top + 12f, r.right - 20f, r.bottom - 12f))
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

    private val mini = GolfView()

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
    private val view = GolfView()
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

    /** True while a camera player (not a computer golfer, not the developer autoplay) is up. */
    private val humanTurn: Boolean get() = !match.player.isCpu && !(app.settings.simulatedInput && app.sim.autoplay)

    override fun onEnter() {
        app.startTurnTracking()
        particles.reduced = app.settings.reducedEffects
    }

    override fun onExit() {
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
        when (match.phase) {
            GolfMatch.Phase.TURN_START -> turnStart(dt)
            GolfMatch.Phase.AIM -> if (humanTurn) humanAim() else cpuAim(dt)
            GolfMatch.Phase.FLIGHT, GolfMatch.Phase.RESULT -> {
                val p = match.plan
                match.update(dt)
                if (p != null && match.phase == GolfMatch.Phase.FLIGHT && !landed && match.animTime >= p.flightSec && p.club != Club.PUTTER) {
                    landed = true
                    app.audio.play(if (p.outcome == Outcome.WATER) Sfx.SPLAT else Sfx.BOUNCE, 0.7f)
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
                    val good = match.strokes[match.current][match.holeIndex] <= match.hole.par
                    app.audio.play(if (good) Sfx.CHEER else Sfx.CATCH)
                    if (good) particles.confetti(0f, 1920f, -20f, 70)
                }
                p?.outcome == Outcome.OUT -> app.audio.play(Sfx.BAD)
                match.message.startsWith("On the green") -> app.audio.play(Sfx.SPARKLE, 0.7f)
            }
        }
        if (to == GolfMatch.Phase.HOLE_DONE) { holeDoneTime = 0f; app.audio.play(Sfx.JINGLE) }
        if (to == GolfMatch.Phase.COURSE_DONE) { app.audio.play(Sfx.WIN); app.audio.playMusic(MusicTrack.VICTORY, restart = true); particles.confetti(0f, 1920f, -20f, 120) }
        if (to == GolfMatch.Phase.TURN_START) { step = Step.NEW; stepTime = 0f }
        if (to == GolfMatch.Phase.AIM) { cpuTime = 0f; cpuShot = null }
    }

    private fun nextHole() { match.continueAfterHole() }

    /** The next golfer steps up. A camera player is picked up and confirms with a raised hand. */
    private fun turnStart(dt: Float) {
        stepTime += dt
        val pl = match.player
        if (!humanTurn) {
            if (stepTime > 1.4f) match.beginTurn()
            return
        }
        val prof = app.roster.byId(pl.profileId)
        if (prof != null) app.activePlayers[0] = prof
        val slot = app.tracker.slots[0]
        when (step) {
            Step.NEW -> {
                if (trackedProfile == pl.profileId && app.calibrated && slot.isPlaying) {
                    // the same person again (e.g. playing alone): carry straight on
                    if (stepTime > 1.2f) match.beginTurn()
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
        app.audio.play(Sfx.HIT, if (club == Club.PUTTER) 0.4f else 0.9f, 0f, if (club == Club.DRIVER) 0.8f else if (club == Club.PUTTER) 1.5f else 1f)
    }

    /** Computer golfer (and developer autoplay): picks a club and aim, then swings after a moment's thought. */
    private fun cpuAim(dt: Float) {
        cpuTime += dt
        if (cpuShot == null) cpuShot = match.cpuShot(match.player.cpuSkill ?: 0.8f, rng)
        if (cpuTime > 1.6f && swingAnim > 5f) swingAnim = 0f // start the swing animation
        if (cpuTime > 1.6f + CPU_IMPACT) { val (p, o) = cpuShot!!; strike(p, o) }
    }

    // ------------------------------------------------------------------ drawing

    private fun draw(g: Gfx, ui: Ui, dt: Float) {
        g.gradientRect(0f, 0f, g.width, g.height, 0xFF0B1E3A.toInt(), 0xFF06122A.toInt())
        val hole = match.hole
        Common.header(g, ui, "Hole ${hole.number} of ${course.holes.size}  -  Par ${hole.par}  -  ${hole.length} m", app = app)
        g.text(course.name, g.width - 30f, 70f, 30f, app.theme.dim, Align.RIGHT, bold = false)
        val mapR = Rect(580f, 140f, g.width - 290f, 1060f)
        frameView(mapR, dt)
        val putting = match.club == Club.PUTTER && match.phase == GolfMatch.Phase.AIM || (match.plan?.club == Club.PUTTER && match.phase != GolfMatch.Phase.AIM && match.phase != GolfMatch.Phase.TURN_START)
        view.drawHole(g, hole, app.time, putting)
        g.save(); g.clipRect(mapR.left, mapR.top, mapR.right, mapR.bottom)
        view.drawFlag(g, hole, app.time)
        drawBallAndGuide(g)
        if (!app.settings.reducedEffects) Draw.snowfall(g, mapR, app.time, 18)
        view.drawWind(g, hole)
        g.restore()
        view.frameBorder(g)
        leftPanel(g, Rect(20f, 140f, 560f, 1060f))
        clubColumn(g, ui, Rect(g.width - 270f, 140f, g.width - 20f, 1060f))
        // result message over the map
        val showMsg = match.phase == GolfMatch.Phase.AIM || match.phase == GolfMatch.Phase.FLIGHT || match.phase == GolfMatch.Phase.RESULT
        if (showMsg && match.message.isNotEmpty() && match.messageTime < 3.2f) {
            val a = (if (match.messageTime > 2.6f) (3.2f - match.messageTime) / 0.6f else 1f).coerceIn(0f, 1f)
            g.save(); g.setAlpha(a)
            val w = g.measureText(match.message, 48f) + 80f
            g.fillRoundRect(mapR.cx - w / 2f, mapR.top + 20f, mapR.cx + w / 2f, mapR.top + 100f, 40f, 0xDD0B2A4F.toInt())
            g.text(match.message, mapR.cx, mapR.top + 60f, 48f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 5f)
            g.restore()
        }
        particles.render(g)
        when {
            match.phase == GolfMatch.Phase.TURN_START && humanTurn -> stepUpOverlay(g, mapR)
            match.phase == GolfMatch.Phase.TURN_START -> banner(g, mapR, "${match.player.name}'s turn", match.player.color)
            match.phase == GolfMatch.Phase.HOLE_DONE -> holeDone(g, ui)
            match.phase == GolfMatch.Phase.COURSE_DONE -> courseDone(g, ui)
        }
        if (showCard > 0f && match.phase == GolfMatch.Phase.AIM) scorecard(g, Rect(g.width / 2f - 640f, 260f, g.width / 2f + 640f, 760f))
        if (match.phase == GolfMatch.Phase.AIM && humanTurn && !app.tracker.slots[0].isPlaying) banner(g, mapR, "Step back in front of the camera", Colors.WARN)
        if (paused) pauseMenu(g, ui)
    }

    private fun frameView(r: Rect, dt: Float) {
        val h = match.hole
        val b = match.ball
        when (match.phase) {
            GolfMatch.Phase.AIM -> {
                if (match.club == Club.PUTTER) view.frame(r, listOf(b, h.pin), 3.5f, 12f, dt)
                else {
                    val reach = match.reachNow()
                    val land = b + GolfPhysics.dir(match.aim) * reach
                    val pts = if (b.dist(h.pin) < reach * 1.3f) listOf(b, land, h.pin) else listOf(b, land)
                    view.frame(r, pts, 28f, 110f, dt)
                }
            }
            GolfMatch.Phase.FLIGHT, GolfMatch.Phase.RESULT -> {
                val p = match.plan
                if (p != null) {
                    if (p.club == Club.PUTTER) view.frame(r, listOf(p.start, p.end, h.pin), 3.5f, 12f, dt)
                    else view.frame(r, listOf(p.start, p.land, p.end), 25f, 110f, dt)
                }
            }
            else -> view.frameHole(r, h, dt)
        }
    }

    private fun drawBallAndGuide(g: Gfx) {
        val color = match.player.color
        when (match.phase) {
            GolfMatch.Phase.AIM -> {
                val det = app.controls[0].golf
                val live = if (humanTurn) det.livePower else 0f
                if (match.club == Club.PUTTER) {
                    val d = if (live > 0f) GolfPhysics.puttDistance(live) else match.distanceToPin()
                    view.drawAim(g, match.ball, match.aim, d, 0.5f, color, live > 0f)
                } else {
                    val reach = match.reachNow(if (live > 0f) live.coerceAtMost(1f) else 1f)
                    val spread = (reach * kotlin.math.sin(match.club.spreadDeg * PI / 360.0)).toFloat()
                    view.drawAim(g, match.ball, match.aim, reach, spread, color, live > 0f)
                }
                view.drawBall(g, match.ball, 0f, color)
            }
            GolfMatch.Phase.FLIGHT -> {
                val p = match.plan ?: return
                val (pos, hgt) = GolfPhysics.positionAt(p, match.animTime)
                view.drawBall(g, pos, hgt, color)
            }
            GolfMatch.Phase.RESULT -> {
                val p = match.plan
                if (p != null && p.outcome != Outcome.HOLED && match.ball != match.hole.pin) view.drawBall(g, if (p.outcome == Outcome.STOPPED) p.end else match.ball, 0f, color)
            }
            else -> Unit
        }
    }

    private fun leftPanel(g: Gfx, r: Rect) {
        Draw.panel(g, r, app.theme.panel, app.theme.panelBorder)
        val pl = match.player
        g.text(pl.name + if (pl.isCpu) " (computer)" else "", r.cx, r.top + 44f, 44f, pl.color, outline = Colors.BLACK, outlineWidth = 4f)
        val strokeNo = match.strokesThisHole + if (match.phase == GolfMatch.Phase.AIM) 1 else 0
        g.text("Stroke ${strokeNo.coerceAtLeast(1)}   -   Round ${GolfMatch.toParText(match.toPar(match.current))}", r.cx, r.top + 92f, 30f, Colors.WHITE, bold = false)
        // the golfer: the player's own tracked body, or the computer's animated swing
        val ax = r.cx; val ay = r.top + 585f; val ah = 380f
        val prop = if (match.club == Club.PUTTER) Prop.PUTTER else Prop.GOLF_CLUB
        if (humanTurn && app.controls[0].hasPose && app.tracker.slots[0].isPlaying) {
            Draw.buddy(g, ax, ay, ah, pl.color, app.controls[0].avatar, Expression.FOCUSED, prop, t = app.time)
        } else {
            val (hx, hy) = scriptedHands(ax, ay, ah)
            Draw.buddy(g, ax, ay, ah, pl.color, null, if (match.phase == GolfMatch.Phase.RESULT) Expression.EXCITED else Expression.FOCUSED, prop, t = app.time, handTargets = floatArrayOf(hx - 8f, hy, hx + 8f, hy))
        }
        // facts
        var y = r.top + 625f
        fun line(t: String, c: Int = Colors.WHITE) { g.text(t, r.cx, y, 30f, c, bold = false); y += 40f }
        if (match.phase == GolfMatch.Phase.AIM || match.phase == GolfMatch.Phase.FLIGHT || match.phase == GolfMatch.Phase.RESULT) {
            line("To the pin: ${GolfMatch.fmt(match.distanceToPin())}")
            line("Lie: ${match.lie.label}", if (match.lie == Surface.SAND || match.lie == Surface.TREES) Colors.WARN else Colors.WHITE)
        }
        // what to do now / the live power meter
        val box = Rect(r.left + 20f, r.bottom - 250f, r.right - 20f, r.bottom - 20f)
        g.fillRoundRect(box.left, box.top, box.right, box.bottom, 20f, 0x66000000)
        if (match.phase != GolfMatch.Phase.AIM) return
        if (!humanTurn) { g.text("${pl.name} is lining up...", box.cx, box.cy, 34f, Colors.WHITE, bold = false); return }
        val det = app.controls[0].golf
        val putt = match.club == Club.PUTTER
        when (det.phase) {
            GolfSwingDetector.Phase.IDLE -> {
                g.text("Hands together, low and still", box.cx, box.top + 50f, 34f, Colors.GOLD)
                g.text(if (putt) "then draw the putter back and stroke through" else "then swing back and through", box.cx, box.top + 95f, 28f, Colors.WHITE, bold = false)
                g.text("Raise a hand to choose a club or aim", box.cx, box.top + 150f, 26f, app.theme.dim, bold = false)
                if (det.addressProgress > 0f) Draw.progressRing(g, box.cx, box.top + 200f, 22f, det.addressProgress, Colors.GOLD, 7f)
            }
            GolfSwingDetector.Phase.ADDRESS -> {
                g.text(if (putt) "Putt!" else "Swing!", box.cx, box.top + 60f, 52f, Colors.GOOD, outline = Colors.BLACK, outlineWidth = 5f)
                g.text(if (putt) "Further back = harder putt" else "Higher backswing = further", box.cx, box.top + 120f, 28f, Colors.WHITE, bold = false)
            }
            GolfSwingDetector.Phase.BACKSWING, GolfSwingDetector.Phase.FOLLOW -> {
                val p = det.livePower
                val bar = Rect(box.left + 30f, box.top + 40f, box.right - 30f, box.top + 90f)
                g.fillRoundRect(bar.left, bar.top, bar.right, bar.bottom, 20f, 0x55FFFFFF)
                val full = bar.left + bar.width / GolfSwingDetector.MAX_POWER
                val fx = bar.left + bar.width * (p / GolfSwingDetector.MAX_POWER).coerceIn(0f, 1f)
                g.fillRoundRect(bar.left, bar.top, fx, bar.bottom, 20f, if (p > 1f) Colors.WARN else Colors.GOOD)
                g.line(full, bar.top - 8f, full, bar.bottom + 8f, 4f, Colors.WHITE)
                val d = if (putt) GolfPhysics.puttDistance(p) else match.reachNow(p.coerceAtMost(1f))
                g.text("about ${GolfMatch.fmt(d)}", box.cx, box.top + 140f, 40f, Colors.WHITE)
                if (p > 1f) g.text("Overswing - less accurate", box.cx, box.top + 190f, 28f, Colors.WARN, bold = false)
            }
        }
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
        g.text("Clubs", r.cx, r.top + 22f, 30f, app.theme.dim)
        Club.values().forEachIndexed { i, c ->
            val b = Rect(r.left, r.top + 50f + i * 106f, r.right, r.top + 144f + i * 106f)
            val dist = if (c == Club.PUTTER) "on the green" else "${(c.carry * GolfPhysics.lieFactor(match.lie, c)).toInt()} m"
            if (ui.button(g, "club_${c.name}", b, c.label, dist, accent = Colors.GOLD, selected = match.club == c, enabled = canChoose, size = 34f)) {
                match.selectClub(c); app.audio.play(Sfx.SELECT, 0.5f)
            }
        }
        // aim: hold a hand on an arrow to keep turning; a tap turns a little
        val ay = r.top + 50f + 6 * 106f + 10f
        val half = (r.width - 12f) / 2f
        val aimL = Rect(r.left, ay, r.left + half, ay + 100f)
        val aimR = Rect(r.right - half, ay, r.right, ay + 100f)
        val fine = match.club == Club.PUTTER
        val stepRad = ((if (fine) 0.5 else 1.0) * PI / 180.0).toFloat()
        if (ui.button(g, "aimL", aimL, "< Aim", accent = Colors.ICE, enabled = canChoose, size = 32f)) match.nudgeAim(-stepRad)
        if (ui.button(g, "aimR", aimR, "Aim >", accent = Colors.ICE, enabled = canChoose, size = 32f)) match.nudgeAim(stepRad)
        if (canChoose) {
            val rate = ((if (fine) 3.0 else 8.0) * PI / 180.0).toFloat() / 60f
            if (ui.cursors.any { it.active && it.hoverId == "aimL" }) match.nudgeAim(-rate)
            if (ui.cursors.any { it.active && it.hoverId == "aimR" }) match.nudgeAim(rate)
        }
        if (ui.button(g, "golf_card", Rect(r.left, ay + 112f, r.right, ay + 200f), "Scorecard", accent = Colors.ICE, size = 32f)) showCard = 5f
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
        g.text("Scorecard - ${course.name}", r.cx, r.top + 50f, 44f, Colors.GOLD)
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
    }
}
