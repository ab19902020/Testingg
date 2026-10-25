package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.audio.Ambience
import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Room
import com.clrg.core.audio.Soundscape
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameSession
import com.clrg.core.game.MatchResult
import com.clrg.core.game.Phase
import com.clrg.core.game.PlayerInfo
import com.clrg.core.games.GameRegistry
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.party.TournamentMatch
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.LostReason
import com.clrg.core.tracking.MovementMode
import com.clrg.core.tracking.SlotState
import com.clrg.core.ui.Icon
import com.clrg.core.ui.Icons
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui

/** Hosts one match: instructions, countdown, play, tracking pauses, user pause, finish. */
class GameScreen(app: GameApp, val gameId: String, val match: TournamentMatch?) : Screen(app) {
    override val music: MusicTrack? = null
    override val ambience: Ambience? get() = Soundscape.ambience(gameId)
    override val room: Room? get() = Soundscape.room(gameId)
    override val needsCamera = true
    private val entry = GameRegistry.byId(gameId)!!
    private val seed = app.platform.nowNanos() xor 0x5EEDL
    private val autoRng = arrayOf(Rng(seed + 1), Rng(seed + 2))
    lateinit var session: GameSession
        private set

    override val allowCursors: Boolean get() = !::session.isInitialized || session.phase != Phase.PLAYING

    override fun onEnter() {
        app.objectKind = entry.info.objectKind
        if (::session.isInitialized) { applyControls(); return }
        val level = app.cpuLevel
        val players = (0..1).map { k ->
            val prof = app.activePlayers[k]
            if (level != null && k == 1) {
                PlayerInfo(k, prof.id, prof.name, app.playerColor(k), Hand.RIGHT, MovementMode.STANDARD, cpu = true, cpuSkill = level.skill)
            } else {
                val cal = app.tracker.slots[k].calibration
                PlayerInfo(k, prof.id, prof.name, app.playerColor(k), cal?.dominantHand ?: prof.hand ?: Hand.RIGHT, cal?.mode ?: prof.mode)
            }
        }
        val env = GameEnv(players, app.settings.copy(), seed, app.audio, app.platform::nowNanos, app.timing)
        val game = entry.create(env)
        session = GameSession(game, env)
        session.allowWithoutTracking = app.settings.simulatedInput && app.sim.autoplay
        session.beforeStep = {
            if (app.settings.simulatedInput && app.sim.autoplay) for (k in 0..1) if (!session.cpu[k]) game.autoplay(k, session.inputs[k], 0.85f - 0.25f * k, autoRng[k])
        }
        applyControls()
        // a learned object for this kind of game is used unless the player picks their hands on the start screen
        for (k in 0..1) app.useObject[k] = true
        app.audio.playMusic(MusicTrack.CALM)
    }

    private fun applyControls() {
        val game = session.game
        app.controls.forEach { it.compactSwings = game.compactSwings; it.wantFingers = game.usesFingers }
    }

    override fun onExit() {
        app.controls.forEach { it.compactSwings = false; it.swingBoost = 0f; it.wantFingers = false }
        app.objectKind = null
    }

    override fun onBack(): Boolean {
        when (session.phase) {
            Phase.PLAYING, Phase.COUNTDOWN, Phase.RESUMING -> session.userPause()
            Phase.PAUSED -> session.userResume()
            Phase.INSTRUCTIONS -> app.pop()
            else -> Unit
        }
        return true
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        session.allowWithoutTracking = app.settings.simulatedInput && app.sim.autoplay
        for (k in 0..1) session.scripted[k] = session.allowWithoutTracking && !session.cpu[k]
        if (session.allowWithoutTracking && session.phase == Phase.INSTRUCTIONS) { session.ready[0] = true; session.ready[1] = true }
        session.update(dt.toDouble(), app.controls, app.tracker.slots)
        session.render(g, app.time)
        when (session.phase) {
            Phase.INSTRUCTIONS -> instructions(g, ui)
            Phase.TRACKING_PAUSED -> trackingLost(g, ui)
            Phase.PAUSED -> pauseMenu(g, ui)
            Phase.DONE -> { finish(); return }
            else -> Unit
        }
        if (session.phase == Phase.PLAYING || session.phase == Phase.COUNTDOWN || session.phase == Phase.RESUMING) {
            if (ui.button(g, "pause", Rect(g.width / 2f - 300f, g.height - 96f, g.width / 2f - 160f, g.height - 20f), "", size = 36f, accent = Colors.ICE, icon = Icon.PAUSE)) session.userPause()
            if (app.settings.showCameraInset) PreviewView.draw(g, app, Rect(g.width / 2f - 125f, g.height - 150f, g.width / 2f + 125f, g.height - 12f), labels = false)
        }
    }

    private fun instructions(g: Gfx, ui: Ui) {
        val info = entry.info
        val accent = if (app.christmasMode) Colors.CANDY else 0xFF40C4FF.toInt()
        g.fillRect(0f, 0f, g.width, g.height, 0xD006122A.toInt())
        val r = Rect(50f, 30f, g.width - 50f, g.height - 30f)
        g.fillRoundRect(r.left + 6f, r.top + 14f, r.right + 6f, r.bottom + 16f, 34f, 0x55000000)
        g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, 34f, 0xF2132B52.toInt(), 0xF20A1630.toInt())
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, 34f, 3f, Colors.withAlpha(accent, 0.6f))
        // title band
        g.fillRoundRectGradient(r.left, r.top, r.right, r.top + 150f, 34f, Colors.withAlpha(accent, 0.28f), 0x00000000)
        g.text(info.title, r.left + 60f, r.top + 62f, 70f * app.theme.textScale, Colors.GOLD, Align.LEFT, outline = 0xAA000000.toInt(), outlineWidth = 6f)
        g.text(info.goal, r.left + 62f, r.top + 120f, 34f, Colors.WHITE, Align.LEFT, bold = false)
        var cx = r.left + 70f + g.measureText(info.title, 70f * app.theme.textScale) + 30f
        for (t in GameRegistry.tags(gameId)) { cx += ui.chip(g, cx, r.top + 62f, t, accent, 24f) + 10f }
        if (ui.button(g, "quitIntro", Rect(r.right - 250f, r.top + 30f, r.right - 30f, r.top + 110f), "Back", size = 34f, accent = Colors.ICE, icon = Icon.BACK)) app.pop()
        // left: the live preview, and the "play with" choice
        val hasObj = info.objectKind != null
        val demo = Rect(r.left + 40f, r.top + 175f, r.left + 820f, r.top + if (hasObj) 590f else 760f)
        g.fillRoundRect(demo.left - 4f, demo.top - 4f, demo.right + 4f, demo.bottom + 4f, 24f, Colors.withAlpha(accent, 0.5f))
        g.fillRoundRect(demo.left, demo.top, demo.right, demo.bottom, 20f, 0xFF0E2A4F.toInt())
        g.save(); g.clipRect(demo.left, demo.top, demo.right, demo.bottom)
        session.game.demo(g, demo.inset(20f), app.time)
        g.restore()
        val wl = "Watch how to play"
        val ww = g.measureText(wl, 24f, false) + 70f
        g.fillRoundRect(demo.left + 16f, demo.top + 16f, demo.left + 16f + ww, demo.top + 58f, 21f, 0xAA000000.toInt())
        Icons.draw(g, Icon.PLAY, demo.left + 42f, demo.top + 37f, 22f, Colors.GOLD)
        g.text(wl, demo.left + 16f + ww / 2f + 14f, demo.top + 37f, 24f, Colors.WHITE, bold = false)
        info.objectKind?.let { kind -> objectChoice(g, ui, Rect(demo.left, demo.bottom + 26f, demo.right, r.bottom - 175f), kind) }
        // right: steps and controls, scaled down to fit when a game has a lot to explain
        val tx = r.left + 880f
        val tw = r.right - 60f - tx
        val bottomLimit = r.bottom - 200f
        val gentle = session.env.players.any { it.mode != MovementMode.STANDARD }
        var f = 1f
        var stepLines: List<List<String>> = emptyList()
        for (attempt in 0 until 6) {
            stepLines = info.howTo.map { Common.wrap(g, it, 30f * f * app.theme.textScale, tw - 70f, false).take(3) }
            val need = 50f * f + stepLines.sumOf { (38f * f * it.size + 16f * f).toDouble() }.toFloat() + 56f * f + info.controls.size * 64f * f + (if (gentle) 70f * f else 0f)
            if (r.top + 200f + need <= bottomLimit || f <= 0.66f) break
            f -= 0.07f
        }
        g.text("How to play", tx, r.top + 200f, 34f * f, accent, Align.LEFT)
        var y = r.top + 200f + 50f * f
        stepLines.forEachIndexed { i, lines ->
            g.fillCircle(tx + 20f, y, 20f * f, Colors.withAlpha(accent, 0.85f))
            g.text("${i + 1}", tx + 20f, y, 24f * f, Colors.BLACK)
            for ((j, l) in lines.withIndex()) g.text(l, tx + 58f, y + j * 38f * f, 30f * f * app.theme.textScale, Colors.WHITE, Align.LEFT, bold = false)
            y += 38f * f * lines.size + 16f * f
        }
        y += 8f * f
        g.text("Moves", tx, y, 34f * f, accent, Align.LEFT)
        y += 48f * f
        val gw = minOf(470f, tw * 0.52f)
        val rh = 28f * f
        for ((gesture, action) in info.controls) {
            g.fillRoundRectGradient(tx, y - rh, tx + gw, y + rh, rh, 0xFF24497F.toInt(), 0xFF16305A.toInt())
            g.strokeRoundRect(tx, y - rh, tx + gw, y + rh, rh, 2f, Colors.withAlpha(Colors.GOLD, 0.5f))
            g.text(gesture, tx + gw / 2f, y, ui.fit(g, gesture, 28f * f, gw - 30f), Colors.GOLD)
            Icons.draw(g, Icon.PLAY, tx + gw + 30f, y, 22f * f, Colors.withAlpha(Colors.WHITE, 0.7f))
            g.text(action, tx + gw + 56f, y, ui.fit(g, action, 28f * f, tw - gw - 60f, false), Colors.WHITE, Align.LEFT, bold = false)
            y += 64f * f
        }
        if (gentle) {
            for ((j, l) in Common.wrap(g, info.gentleNotes, 26f * f, tw, false).take(2).withIndex()) g.text(l, tx, y + 4f + j * 32f * f, 26f * f, Colors.ICE, Align.LEFT, bold = false)
        }
        // readiness: a card per player, and Start in the middle
        val by = r.bottom - 110f
        for (k in 0..1) {
            val c = app.playerColor(k)
            val card = if (k == 0) Rect(r.left + 40f, by - 60f, r.cx - 230f, by + 60f) else Rect(r.cx + 230f, by - 60f, r.right - 40f, by + 60f)
            val ready = session.ready[k]
            g.fillRoundRect(card.left, card.top, card.right, card.bottom, 30f, if (ready) Colors.withAlpha(Colors.GOOD, 0.18f) else 0x66000000)
            g.strokeRoundRect(card.left, card.top, card.right, card.bottom, 30f, 3f, Colors.withAlpha(if (ready) Colors.GOOD else c, 0.8f))
            val slot = app.tracker.slots[k]
            val prog = if (ready) 1f else app.controls[k].raiseProgress
            val rx = card.left + 66f
            Draw.progressRing(g, rx, card.cy, 42f, prog, if (ready) Colors.GOOD else c, 11f)
            if (ready) Icons.draw(g, Icon.CHECK, rx, card.cy, 44f, Colors.GOOD) else Icons.draw(g, Icon.HAND, rx, card.cy, 40f, c)
            val msg = when {
                session.cpu[k] -> "${app.activePlayers[k].name} (CPU, ${app.cpuLevel?.label ?: ""}) is ready"
                ready -> "${app.activePlayers[k].name} is ready"
                !slot.isPlaying -> "${app.activePlayers[k].name}: step into view"
                else -> "${app.activePlayers[k].name}: raise a hand to start"
            }
            g.text(msg, rx + 66f, card.cy, ui.fit(g, msg, 32f, card.right - rx - 90f, false), Colors.WHITE, Align.LEFT, bold = false)
        }
        if (ui.button(g, "start", Rect(r.cx - 200f, by - 58f, r.cx + 200f, by + 58f), "Start", accent = Colors.GOOD, size = 50f, icon = Icon.PLAY)) session.startNow()
    }

    /** "Play with": each camera player picks their hands or their learned object, or teaches one now. */
    private fun objectChoice(g: Gfx, ui: Ui, r: Rect, kind: com.clrg.core.game.ObjectKind) {
        val gun = kind == com.clrg.core.game.ObjectKind.GUN
        g.text("Play with:", r.left + 10f, r.top + 16f, 30f, Colors.GOLD, Align.LEFT)
        val rows = (0..1).filter { !session.cpu[it] }
        val rowH = ((r.height - 40f) / rows.size.coerceAtLeast(1)).coerceAtMost(96f)
        rows.forEachIndexed { i, k ->
            val y = r.top + 40f + i * rowH
            val c = app.playerColor(k)
            g.text(app.activePlayers[k].name, r.left + 10f, y + rowH / 2f - 4f, 28f, c, Align.LEFT)
            val has = app.hasHeldObject(k, kind)
            val bw = (r.width - 170f) / 2f
            val b1 = Rect(r.left + 160f, y, r.left + 160f + bw - 8f, y + rowH - 12f)
            val b2 = Rect(b1.right + 16f, y, r.right, y + rowH - 12f)
            val handsLabel = if (gun) "Finger gun" else "My arm"
            if (ui.button(g, "objHands$k", b1, handsLabel, size = 28f, selected = !has || !app.useObject[k], accent = c)) app.useObject[k] = false
            if (has) {
                if (ui.button(g, "objUse$k", b2, "My ${kind.label}", size = 28f, selected = app.useObject[k], accent = c)) app.useObject[k] = true
            } else if (ui.button(g, "objTeach$k", b2, "Teach my ${kind.label}", size = 28f, accent = Colors.GOOD)) {
                app.push(HeldObjectScreen(app, kind, k).also { it.returnWhenKept = true })
            }
        }
    }

    private fun trackingLost(g: Gfx, ui: Ui) {
        g.fillRect(0f, 0f, g.width, g.height, 0xCC000000.toInt())
        ui.title(g, "Paused - we lost track of someone", 90f, 60f)
        var y = 170f
        for (k in session.missing) {
            val s = app.tracker.slots[k]
            val side = if (k == 0) "LEFT" else "RIGHT"
            val why = if (s.lostReason == LostReason.LOW_CONFIDENCE) "the camera can't see you clearly" else "you're out of view"
            val msg = if (app.solo) "${app.activePlayers[k].name}: $why - please step back into the picture"
                else "${app.activePlayers[k].name} (P${k + 1}): $why - please step back into the $side area"
            g.text(msg, g.width / 2f, y, ui.fit(g, msg, 40f, g.width - 220f), app.playerColor(k))
            if (s.acquireProgressSec > 0f) Draw.progressRing(g, g.width / 2f + 820f, y, 22f, s.acquireProgressSec / app.tracker.config.reacquireSec, Colors.GOOD, 6f)
            y += 64f
        }
        PreviewView.draw(g, app, Rect(g.width / 2f - 620f, y + 10f, g.width / 2f + 620f, g.height - 150f))
        g.text("The game continues with a countdown when everyone is back.", g.width / 2f, g.height - 115f, 32f, Colors.TEXT_DIM, bold = false)
        if (ui.button(g, "recal", Rect(60f, g.height - 100f, 480f, g.height - 20f), "Recalibrate", size = 32f)) {
            app.replace(FramingScreen(app, { app.replace(GameScreen(app, gameId, match)) }))
        }
        if (ui.button(g, "quitLost", Rect(g.width - 480f, g.height - 100f, g.width - 60f, g.height - 20f), "Quit match", size = 32f, accent = Colors.BAD)) app.pop()
    }

    private fun pauseMenu(g: Gfx, ui: Ui) {
        g.fillRect(0f, 0f, g.width, g.height, 0xC0000814.toInt())
        val w = 700f
        val items = if (app.canSwitchCamera) 5 else 4
        val ph = 210f + items * 122f
        val p = Rect(g.width / 2f - w / 2f - 50f, (g.height - ph) / 2f - 20f, g.width / 2f + w / 2f + 50f, (g.height + ph) / 2f - 20f)
        g.fillRoundRect(p.left + 8f, p.top + 16f, p.right + 8f, p.bottom + 18f, 36f, 0x66000000)
        g.fillRoundRectGradient(p.left, p.top, p.right, p.bottom, 36f, 0xF2183563.toInt(), 0xF20B1A36.toInt())
        g.strokeRoundRect(p.left, p.top, p.right, p.bottom, 36f, 3f, 0x885D8BC9.toInt())
        Icons.draw(g, Icon.PAUSE, g.width / 2f - 150f, p.top + 80f, 56f, Colors.GOLD)
        g.text("Paused", g.width / 2f + 30f, p.top + 80f, 76f * app.theme.textScale, Colors.WHITE)
        g.text("${entry.info.title}  -  ${session.game.status() ?: "match on hold"}", g.width / 2f, p.top + 145f, 28f, app.theme.dim, bold = false)
        var y = p.top + 190f
        fun b(id: String, label: String, icon: Icon, accent: Int = Colors.GOLD): Boolean { val r = Rect(g.width / 2f - w / 2f, y, g.width / 2f + w / 2f, y + 104f); y += 122f; return ui.button(g, id, r, label, accent = accent, icon = icon, size = 42f) }
        if (b("resume", "Resume", Icon.PLAY, Colors.GOOD)) session.userResume()
        if (b("restart", "Restart match", Icon.RESTART)) app.replace(GameScreen(app, gameId, match))
        if (b("recalibrate", "Recalibrate players", Icon.CALIBRATE)) app.replace(FramingScreen(app, { app.replace(GameScreen(app, gameId, match)) }))
        if (app.canSwitchCamera && b("camSwitch", if (app.currentCamera()?.front == true) "Switch to back camera" else "Switch to front camera", Icon.CAMERA, Colors.ICE)) {
            app.switchCamera()
            app.replace(FramingScreen(app, { app.replace(GameScreen(app, gameId, match)) }))
        }
        if (b("quit", if (match != null) "Back to tournament" else "Quit to menu", if (match != null) Icon.TROPHY else Icon.HOME, Colors.BAD)) app.pop()
        if (app.displayLost) g.text("The TV was disconnected", g.width / 2f, p.bottom + 40f, 36f, Colors.WARN)
    }

    private fun finish() {
        val result = session.game.result()
        val m = match
        val t = app.tournament
        if (m != null && t != null && t.current === m) {
            val winnerId = if (result.winner < 0) 0 else app.activePlayers[result.winner].id
            val p1IsLeft = app.activePlayers[0].id == m.p1
            val s1 = if (p1IsLeft) result.scores[0] else result.scores[1]
            val s2 = if (p1IsLeft) result.scores[1] else result.scores[0]
            t.record(winnerId, s1, s2)
            app.saveTournament()
        }
        app.replace(ResultsScreen(app, result, gameId, m))
    }
}

class ResultsScreen(app: GameApp, val result: MatchResult, private val gameId: String, private val match: TournamentMatch?) : Screen(app) {
    override val music = MusicTrack.VICTORY
    override val ambience: Ambience? = null
    override val room: Room? = null

    private var age = 0f

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        age += dt
        Common.backdrop(g, app)
        val info = GameRegistry.byId(gameId)!!.info
        val tie = result.winner < 0
        val head = if (tie) "It's a tie!" else "${app.activePlayers[result.winner].name} wins!"
        val hc = if (tie) Colors.GOLD else app.playerColor(result.winner)
        g.text(info.title.uppercase(), g.width / 2f, 62f, 34f, app.theme.dim)
        val pop = (age / 0.35f).coerceAtMost(1f).let { 0.6f + 0.4f * it + 0.08f * kotlin.math.sin(it * 3.14f) }
        g.save(); g.translate(g.width / 2f, 150f); g.scale(pop); g.translate(-g.width / 2f, -150f)
        g.glow(g.width / 2f, 150f, 420f, Colors.withAlpha(hc, 0.25f))
        g.text(head, g.width / 2f, 150f, 104f * app.theme.textScale, hc, outline = Colors.BLACK, outlineWidth = 10f)
        g.restore()
        // podium on the left, match stats on the right
        val baseY = 880f
        for (k in 0..1) {
            val x = if (k == 0) 380f else 760f
            val win = result.winner == k
            val bh = if (tie) 170f else if (win) 230f else 130f
            val rise = (age / 0.6f).coerceAtMost(1f)
            val top = baseY - bh * rise
            val col = if (win || tie) Colors.GOLD else 0xFFB0BEC5.toInt()
            g.fillRoundRect(x - 165f + 6f, top + 10f, x + 165f + 6f, baseY + 10f, 16f, 0x55000000)
            g.fillRoundRectGradient(x - 165f, top, x + 165f, baseY, 16f, Colors.lighter(col, 0.2f), Colors.darker(col, 0.6f))
            g.fillRoundRectGradient(x - 165f, top, x + 165f, top + 26f, 16f, Colors.lighter(col, 0.5f), Colors.lighter(col, 0.1f))
            g.text(if (tie) "=" else if (win) "1" else "2", x, top + (baseY - top) * 0.45f + 10f, minOf(110f, bh * 0.6f), Colors.withAlpha(Colors.BLACK, 0.35f))
            Draw.person(g, !app.christmasMode, x, top, 330f, app.playerColor(k), null,
                if (win || tie) com.clrg.core.render.Expression.EXCITED else com.clrg.core.render.Expression.OOPS, t = app.time + k)
            if (win) {
                Icons.draw(g, Icon.TROPHY, x, top - 420f + 6f * kotlin.math.sin(app.time.toFloat() * 3f), 110f, Colors.GOLD)
                Draw.star(g, x - 90f, top - 380f, 18f, Colors.GOLD, app.time.toFloat())
                Draw.star(g, x + 92f, top - 360f, 14f, Colors.GOLD, -app.time.toFloat())
            }
            g.text(app.activePlayers[k].name, x, baseY + 44f, 40f, app.playerColor(k), outline = Colors.BLACK, outlineWidth = 5f)
            g.text("${result.scores[k]} ${result.scoreUnit}", x, baseY + 92f, 40f, Colors.WHITE)
        }
        val sp = Rect(1020f, 250f, g.width - 80f, 880f)
        g.fillRoundRectGradient(sp.left, sp.top, sp.right, sp.bottom, 30f, 0xCC16305A.toInt(), 0xCC0A1630.toInt())
        g.strokeRoundRect(sp.left, sp.top, sp.right, sp.bottom, 30f, 3f, 0x885D8BC9.toInt())
        g.text("Match stats", sp.left + 40f, sp.top + 50f, 36f, Colors.GOLD, Align.LEFT)
        var y = sp.top + 110f
        for (d in result.details.take(8)) {
            g.fillCircle(sp.left + 52f, y, 8f, Colors.GOLD)
            g.text(d, sp.left + 76f, y, ui.fit(g, d, 32f, sp.width - 110f, false), Colors.WHITE, Align.LEFT, bold = false)
            y += 60f
        }
        val by = 960f
        if (match != null) {
            if (ui.button(g, "continue", Rect(g.width / 2f - 340f, by, g.width / 2f + 340f, by + 100f), "Continue tournament", accent = Colors.GOOD, icon = Icon.TROPHY)) app.pop()
        } else {
            if (ui.button(g, "rematch", Rect(80f, by, 620f, by + 100f), "Rematch", accent = Colors.GOOD, icon = Icon.RESTART)) {
                app.pop()
                SetupFlow.start(app) { app.replace(GameScreen(app, gameId, null)) }
            }
            if (ui.button(g, "another", Rect(g.width / 2f - 320f, by, g.width / 2f + 320f, by + 100f), "Choose another game", icon = Icon.VERSUS)) app.replace(if (app.solo) SoloFlow.gameSelect(app) else GameSelectScreen(app))
            if (ui.button(g, "menu", Rect(g.width - 620f, by, g.width - 80f, by + 100f), "Main menu", accent = Colors.ICE, icon = Icon.HOME)) app.popToRoot()
        }
    }

    override fun onBack(): Boolean { if (match != null) app.pop() else app.popToRoot(); return true }
}

/** Shows a slot's state compactly (used by several screens). */
internal fun slotLabel(s: com.clrg.core.tracking.PlayerSlot): String = when (s.state) {
    SlotState.ACTIVE -> "tracked"
    SlotState.MISSING -> "briefly hidden"
    SlotState.LOST -> "lost"
    SlotState.EMPTY -> "not assigned"
}
