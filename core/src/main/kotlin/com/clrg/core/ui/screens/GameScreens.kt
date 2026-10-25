package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.audio.MusicTrack
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
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui

/** Hosts one match: instructions, countdown, play, tracking pauses, user pause, finish. */
class GameScreen(app: GameApp, val gameId: String, val match: TournamentMatch?) : Screen(app) {
    override val music: MusicTrack? = null
    override val needsCamera = true
    private val entry = GameRegistry.byId(gameId)!!
    private val seed = app.platform.nowNanos() xor 0x5EEDL
    private val autoRng = arrayOf(Rng(seed + 1), Rng(seed + 2))
    lateinit var session: GameSession
        private set

    override val allowCursors: Boolean get() = !::session.isInitialized || session.phase != Phase.PLAYING

    override fun onEnter() {
        if (::session.isInitialized) return
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
        app.controls.forEach { it.compactSwings = game.compactSwings; it.wantFingers = game.usesFingers }
        app.audio.playMusic(MusicTrack.CALM)
    }

    override fun onExit() {
        app.controls.forEach { it.compactSwings = false; it.swingBoost = 0f; it.wantFingers = false }
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
            if (ui.button(g, "pause", Rect(g.width / 2f - 300f, g.height - 96f, g.width / 2f - 160f, g.height - 20f), "II", size = 36f, accent = Colors.ICE)) session.userPause()
            if (app.settings.showCameraInset) PreviewView.draw(g, app, Rect(g.width / 2f - 125f, g.height - 150f, g.width / 2f + 125f, g.height - 12f), labels = false)
        }
    }

    private fun instructions(g: Gfx, ui: Ui) {
        val info = entry.info
        g.fillRect(0f, 0f, g.width, g.height, 0xCC06122A.toInt())
        val r = Rect(60f, 40f, g.width - 60f, g.height - 40f)
        Draw.panel(g, r, app.theme.panel, app.theme.panelBorder)
        g.text(info.title, r.cx, r.top + 70f, 76f * app.theme.textScale, Colors.GOLD)
        g.text(info.goal, r.cx, r.top + 135f, 38f, Colors.WHITE, bold = false)
        val demo = Rect(r.left + 40f, r.top + 180f, r.left + 760f, r.top + 640f)
        g.fillRoundRect(demo.left, demo.top, demo.right, demo.bottom, 20f, 0xFF0E2A4F.toInt())
        g.save(); g.clipRect(demo.left, demo.top, demo.right, demo.bottom)
        session.game.demo(g, demo.inset(20f), app.time)
        g.restore()
        g.text("How to play", demo.cx, demo.bottom + 30f, 26f, app.theme.dim, bold = false)
        var y = r.top + 210f
        for (line in info.howTo) {
            g.text(line, r.left + 800f, y, ui.fit(g, line, 36f * app.theme.textScale, r.right - r.left - 840f, false), Colors.WHITE, Align.LEFT, bold = false)
            y += 66f
        }
        y += 10f
        for ((gesture, action) in info.controls) {
            g.fillRoundRect(r.left + 800f, y - 30f, r.left + 1260f, y + 30f, 20f, 0xFF1C3A66.toInt())
            g.text(gesture, r.left + 1030f, y, ui.fit(g, gesture, 30f, 440f), Colors.GOLD)
            g.text("->  $action", r.left + 1290f, y, 30f, Colors.WHITE, Align.LEFT, bold = false)
            y += 76f
        }
        if (session.env.players.any { it.mode != MovementMode.STANDARD }) {
            g.text(info.gentleNotes, r.left + 800f, y + 10f, ui.fit(g, info.gentleNotes, 28f, r.right - r.left - 840f, false), Colors.ICE, Align.LEFT, bold = false)
        }
        // readiness
        for (k in 0..1) {
            val x = if (k == 0) r.left + 90f else r.cx + 260f
            val c = app.playerColor(k)
            val slot = app.tracker.slots[k]
            val prog = if (session.ready[k]) 1f else app.controls[k].raiseProgress
            Draw.progressRing(g, x, r.bottom - 120f, 50f, prog, if (session.ready[k]) Colors.GOOD else c, 12f)
            g.text(if (session.ready[k]) "OK" else "P${k + 1}", x, r.bottom - 120f, 34f, if (session.ready[k]) Colors.GOOD else c)
            val msg = when {
                session.cpu[k] -> "${app.activePlayers[k].name} (CPU, ${app.cpuLevel?.label ?: ""}) is ready"
                session.ready[k] -> "${app.activePlayers[k].name} is ready"
                !slot.isPlaying -> "${app.activePlayers[k].name}: step into view"
                else -> "${app.activePlayers[k].name}: raise a hand to start"
            }
            val maxW = if (k == 0) r.cx - 230f - (x + 80f) else r.right - 40f - (x + 80f)
            g.text(msg, x + 80f, r.bottom - 120f, ui.fit(g, msg, 34f, maxW, false), Colors.WHITE, Align.LEFT, bold = false)
        }
        if (ui.button(g, "start", Rect(r.cx - 200f, r.bottom - 170f, r.cx + 200f, r.bottom - 70f), "Start", accent = Colors.GOOD)) session.startNow()
        if (ui.button(g, "quitIntro", Rect(r.right - 260f, r.top + 30f, r.right - 30f, r.top + 110f), "Back", size = 34f, accent = Colors.ICE)) app.pop()
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
        g.fillRect(0f, 0f, g.width, g.height, 0xCC000000.toInt())
        ui.title(g, "Paused", 170f, 90f)
        val w = 620f
        var y = 270f
        fun b(id: String, label: String, accent: Int = Colors.GOLD): Boolean { val r = Rect(g.width / 2f - w / 2f, y, g.width / 2f + w / 2f, y + 110f); y += 130f; return ui.button(g, id, r, label, accent = accent) }
        if (b("resume", "Resume", Colors.GOOD)) session.userResume()
        if (b("restart", "Restart match")) app.replace(GameScreen(app, gameId, match))
        if (b("recalibrate", "Recalibrate players")) app.replace(FramingScreen(app, { app.replace(GameScreen(app, gameId, match)) }))
        if (app.canSwitchCamera && b("camSwitch", if (app.currentCamera()?.front == true) "Switch to back camera" else "Switch to front camera", Colors.ICE)) {
            app.switchCamera()
            app.replace(FramingScreen(app, { app.replace(GameScreen(app, gameId, match)) }))
        }
        if (b("quit", if (match != null) "Back to tournament" else "Quit to menu", Colors.BAD)) app.pop()
        if (app.displayLost) g.text("The TV was disconnected", g.width / 2f, y + 10f, 36f, Colors.WARN)
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

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        val info = GameRegistry.byId(gameId)!!.info
        g.text(info.title, g.width / 2f, 70f, 44f, app.theme.dim)
        val head = if (result.winner < 0) "It's a tie!" else "${app.activePlayers[result.winner].name} wins!"
        val hc = if (result.winner < 0) Colors.GOLD else app.playerColor(result.winner)
        g.text(head, g.width / 2f, 170f, 110f * app.theme.textScale, hc, outline = Colors.BLACK, outlineWidth = 10f)
        for (k in 0..1) {
            val x = if (k == 0) g.width * 0.3f else g.width * 0.7f
            val win = result.winner == k
            Draw.buddy(g, x, 720f, 380f, app.playerColor(k), null, if (win || result.winner < 0) com.clrg.core.render.Expression.EXCITED else com.clrg.core.render.Expression.OOPS, t = app.time + k)
            if (win) Draw.star(g, x, 300f, 50f, Colors.GOLD, app.time.toFloat())
            g.text(app.activePlayers[k].name, x, 780f, 50f, app.playerColor(k))
            g.text("${result.scores[k]} ${result.scoreUnit}", x, 845f, 54f, Colors.WHITE)
        }
        var y = 300f
        for (d in result.details) { g.text(d, g.width / 2f, y, 32f, Colors.WHITE, bold = false); y += 52f }
        val by = 930f
        if (match != null) {
            if (ui.button(g, "continue", Rect(g.width / 2f - 320f, by, g.width / 2f + 320f, by + 110f), "Continue tournament", accent = Colors.GOOD)) app.pop()
        } else {
            if (ui.button(g, "rematch", Rect(g.width / 2f - 960f + 100f, by, g.width / 2f - 340f, by + 110f), "Rematch", accent = Colors.GOOD)) {
                app.pop()
                SetupFlow.start(app) { app.replace(GameScreen(app, gameId, null)) }
            }
            if (ui.button(g, "another", Rect(g.width / 2f - 310f, by, g.width / 2f + 310f, by + 110f), "Choose another game")) app.replace(if (app.solo) SoloFlow.gameSelect(app) else GameSelectScreen(app))
            if (ui.button(g, "menu", Rect(g.width / 2f + 340f, by, g.width / 2f + 860f, by + 110f), "Main menu", accent = Colors.ICE)) app.popToRoot()
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
