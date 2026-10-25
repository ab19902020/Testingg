package com.clrg.core.ui.screens

import com.clrg.core.app.CameraState
import com.clrg.core.app.GameApp
import com.clrg.core.game.GameEnv
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInfo
import com.clrg.core.games.GameRegistry
import com.clrg.core.math.Rect
import com.clrg.core.party.PlayerProfile
import com.clrg.core.party.Settings
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.PlayerColors
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui
import kotlin.math.sin

class MainMenuScreen(app: GameApp) : Screen(app) {
    override fun onBack(): Boolean { app.platform.quit(); return true }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        val t = app.time
        // title
        val bob = sin(t * 1.5).toFloat() * 6f
        g.text("Christmas", g.width / 2f, 120f + bob, 92f * app.theme.textScale, Colors.GOLD, outline = 0xFF6D2B00.toInt(), outlineWidth = 10f)
        g.text("Living Room Games", g.width / 2f, 215f + bob, 76f * app.theme.textScale, Colors.WHITE, outline = 0xFF0B1A33.toInt(), outlineWidth = 9f)
        // two buddies waving on either side
        for (k in 0..1) {
            val x = if (k == 0) g.width * 0.1f else g.width * 0.9f
            val p = com.clrg.core.gesture.AvatarPose().apply {
                valid = true
                val wave = sin(t * 4 + k).toFloat()
                upper[k] = 1.2f + 0.2f * wave; fore[k] = 1.8f + 0.4f * wave
                upper[1 - k] = if (k == 0) -1.2f else -1.9f; fore[1 - k] = if (k == 0) -1.3f else -1.8f
            }
            Draw.buddy(g, x, g.height - 170f, 330f, app.playerColor(k), p, Expression.HAPPY, t = t + k)
            g.text(app.activePlayers[k].name, x, g.height - 120f, 34f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 5f)
        }
        val bw = 640f; val bh = 112f; val gap = 22f
        val left = g.width / 2f - bw - gap / 2f
        val right = g.width / 2f + gap / 2f
        var y = 280f
        fun row(): Pair<Rect, Rect> { val a = Rect(left, y, left + bw, y + bh); val b = Rect(right, y, right + bw, y + bh); y += bh + gap; return a to b }
        val (qm, solo) = row()
        if (ui.button(g, "quick", qm, "Quick Match", "Two players, pick a game", accent = Colors.GOLD)) app.push(GameSelectScreen(app))
        if (ui.button(g, "solo", solo, "Play Solo", "You against the computer", accent = Colors.GOLD)) app.push(SoloSetupScreen(app))
        val (golf, pt) = row()
        if (ui.button(g, "golf", golf, "Christmas Golf", "1 to 4 players, taking turns", accent = Colors.GOOD)) app.push(GolfSetupScreen(app))
        if (ui.button(g, "tournament", pt, "Party Tournament", app.tournament?.let { if (it.isComplete) "Last tournament finished" else "Continue: match ${it.currentIndex + 1} of ${it.matches.size}" } ?: "Whole-family scoreboard", accent = Colors.CANDY)) {
            if (app.tournament != null) app.push(TournamentHubScreen(app)) else app.push(TournamentSetupScreen(app))
        }
        val (pl, tv) = row()
        if (ui.button(g, "players", pl, "Players", "${app.roster.players.size} family members", accent = Colors.ICE)) app.push(PlayersScreen(app))
        if (ui.button(g, "setup", tv, "TV & Camera Setup", if (app.calibrated) "Players calibrated" else "Connect, place, calibrate", accent = Colors.GOOD)) SetupFlow.startFull(app)
        val (st, help) = row()
        if (ui.button(g, "settings", st, "Settings", "Sound, accessibility, camera", accent = Colors.ICE)) app.push(SettingsScreen(app))
        if (ui.button(g, "help", help, "How to Play", "Controls for every game", accent = Colors.GOOD)) app.push(HelpScreen(app))
        // hands-free: anyone in front of the camera can steer the menus with a raised hand
        run {
            val hint = when {
                app.ui.cursors.any { it.active } -> "Hold your hand still over a button to choose it"
                app.handCursorShown -> "Raise your hand higher to point"
                else -> "Hands-free: stand in front of the camera and raise a hand, then hold it over a button"
            }
            val hw = g.measureText(hint, 30f, false) + 70f
            g.fillRoundRect(g.width / 2f - hw / 2f, y + 4f, g.width / 2f + hw / 2f, y + 58f, 27f, 0xAA0B2A4F.toInt())
            g.text(hint, g.width / 2f, y + 31f, 30f, Colors.GOLD, bold = false)
            y += 62f
        }
        if (app.canSwitchCamera && ui.button(g, "camSwitch", Rect(30f, 70f, 440f, 160f), app.cameraLabel(), "tap to switch", accent = Colors.ICE, size = 34f)) app.switchCamera()
        val dev = app.platform.deviceInfo()
        if (dev.meetsTarget == false) {
            g.text("Note: this phone is below the recommended Snapdragon 8 Gen 3 class - tracking may be slower.", g.width / 2f, y + 20f, 28f, Colors.WARN, bold = false)
        }
        Common.statusBar(g, app)
    }
}

/** Plain-language camera explanation shown before the system permission prompt. */
class PermissionScreen(app: GameApp) : Screen(app) {
    override val allowCursors = false

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        val st = app.platform.cameraStatus().state
        if (st == CameraState.RUNNING || st == CameraState.OPENING) { app.pop(); return }
        val r = Rect(g.width / 2f - 820f, 150f, g.width / 2f + 820f, 900f)
        Draw.panel(g, r, app.theme.panel, app.theme.panelBorder)
        ui.title(g, "Your body is the controller", 240f, 70f)
        ui.paragraph(g, listOf(
            "This game uses the phone's camera to see how you and the other player move.",
            "Everything is worked out on this phone, live. Nothing is recorded, saved or uploaded,",
            "and no video ever leaves the device. You can turn the permission off at any time",
            "in Android settings.",
        ), r.left + 80f, 360f, 40f)
        if (st == CameraState.DENIED) {
            g.text("Camera access was turned off. Open settings to allow it, then come back.", g.width / 2f, 620f, 36f, Colors.WARN)
            if (ui.button(g, "settings", Rect(g.width / 2f - 470f, 700f, g.width / 2f - 20f, 820f), "Open settings")) app.platform.openAppSettings()
        } else {
            if (ui.button(g, "allow", Rect(g.width / 2f - 470f, 700f, g.width / 2f - 20f, 820f), "Allow camera", accent = Colors.GOOD)) app.platform.requestCameraPermission()
        }
        if (ui.button(g, "later", Rect(g.width / 2f + 20f, 700f, g.width / 2f + 470f, 820f), "Not now", accent = Colors.ICE)) app.pop()
    }
}

/** Grid of the ten games with live instruction animations. */
class GameSelectScreen(app: GameApp, private val onPick: ((String) -> Unit)? = null) : Screen(app) {
    private val demoGames: List<MiniGame> by lazy {
        val env = GameEnv(
            listOf(PlayerInfo(0, 0, "P1", app.playerColor(0), Hand.RIGHT, MovementMode.STANDARD), PlayerInfo(1, 0, "P2", app.playerColor(1), Hand.RIGHT, MovementMode.STANDARD)),
            Settings(), 1,
        )
        GameRegistry.all.map { it.create(env) }
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "Choose a game", app = app)
        val cols = 5
        val margin = 40f
        val cw = (g.width - margin * 2 - (cols - 1) * 24f) / cols
        val ch = 400f
        GameRegistry.all.forEachIndexed { i, e ->
            val c = i % cols; val rr = i / cols
            val x = margin + c * (cw + 24f)
            val y = 170f + rr * (ch + 30f)
            val r = Rect(x, y, x + cw, y + ch)
            val act = ui.button(g, "game_${e.info.id}", r, "", accent = Colors.GOLD)
            val art = Rect(r.left + 16f, r.top + 16f, r.right - 16f, r.top + 250f)
            g.save(); g.clipRect(art.left, art.top, art.right, art.bottom)
            g.fillRoundRect(art.left, art.top, art.right, art.bottom, 18f, 0xFF0E2A4F.toInt())
            demoGames[i].demo(g, art.inset(10f), app.time)
            g.restore()
            g.text("${i + 1}. ${e.info.title}", r.cx, r.top + 290f, ui.fit(g, "${i + 1}. ${e.info.title}", 36f * app.theme.textScale, cw - 30f), Colors.WHITE)
            g.text(e.info.tagline, r.cx, r.top + 340f, ui.fit(g, e.info.tagline, 26f, cw - 30f, false), app.theme.dim, bold = false)
            g.text(e.info.goal, r.cx, r.top + 374f, ui.fit(g, e.info.goal, 22f, cw - 30f, false), Colors.GOLD, bold = false)
            if (act) {
                if (onPick != null) onPick.invoke(e.info.id) else app.push(PlayerPickScreen(app, e.info.id))
            }
        }
        Common.statusBar(g, app)
    }
}

/** Choose who plays on the left (P1) and right (P2). */
class PlayerPickScreen(app: GameApp, private val gameId: String) : Screen(app) {
    // after solo play, Player 2 is the computer: start from family members only
    private var p1 = app.activePlayers[0].id.takeIf { it >= 0 } ?: app.roster.players[0].id
    private var p2 = app.activePlayers[1].id.takeIf { it >= 0 && it != p1 } ?: app.roster.players.first { it.id != p1 }.id

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        val info = GameRegistry.byId(gameId)!!.info
        Common.header(g, ui, info.title, app = app)
        g.text("Who is playing?", g.width / 2f, 190f, 48f, Colors.WHITE)
        for (side in 0..1) {
            val x0 = if (side == 0) g.width / 2f - 860f else g.width / 2f + 60f
            g.text(if (side == 0) "Player 1 - stands on the LEFT" else "Player 2 - stands on the RIGHT", x0 + 400f, 260f, 38f, if (side == 0) PlayerColors.get(0) else PlayerColors.get(1))
            app.roster.players.take(8).forEachIndexed { i, p ->
                val r = Rect(x0 + (i % 2) * 410f, 310f + (i / 2) * 120f, x0 + (i % 2) * 410f + 390f, 410f + (i / 2) * 120f)
                val chosen = if (side == 0) p.id == p1 else p.id == p2
                val other = if (side == 0) p.id == p2 else p.id == p1
                if (ui.button(g, "p${side}_${p.id}", r, p.name, if (other) "already playing" else null, PlayerColors.get(p.colorIndex), enabled = !other, selected = chosen, size = 40f)) {
                    if (side == 0) p1 = p.id else p2 = p.id
                }
            }
        }
        if (ui.button(g, "play", Rect(g.width / 2f - 300f, 860f, g.width / 2f + 300f, 980f), "Let's play!", accent = Colors.GOOD, size = 54f)) {
            val a = app.roster.byId(p1) ?: return
            val b = app.roster.byId(p2) ?: return
            startQuickMatch(app, gameId, a, b)
        }
        // on your own: Player 1 against the computer (the level chosen last time in Play Solo)
        val level = com.clrg.core.game.CpuLevel.fromName(app.settings.cpuLevel)
        if (ui.button(g, "justme", Rect(g.width / 2f + 340f, 870f, g.width / 2f + 860f, 970f), "Just me", "vs computer (${level.label})", accent = Colors.ICE, size = 40f)) {
            val a = app.roster.byId(p1) ?: return
            app.startSolo(a, level)
            SetupFlow.start(app) { app.replace(GameScreen(app, gameId, null)) }
        }
    }

    companion object {
        fun startQuickMatch(app: GameApp, gameId: String, a: PlayerProfile, b: PlayerProfile) {
            app.setActivePlayers(a, b)
            SetupFlow.start(app) { app.replace(GameScreen(app, gameId, null)) }
        }
    }
}

/** Shared paragraph drawing helper for info panels. */
internal fun infoPanel(g: Gfx, app: GameApp, r: Rect, title: String, lines: List<String>, size: Float = 36f) {
    Draw.panel(g, r, app.theme.panel, app.theme.panelBorder)
    g.text(title, r.cx, r.top + 50f, 44f * app.theme.textScale, Colors.GOLD)
    var y = r.top + 120f
    for (l in lines) { g.text(l, r.left + 40f, y, size * app.theme.textScale, Colors.WHITE, Align.LEFT, bold = false); y += size * app.theme.textScale * 1.5f }
}
