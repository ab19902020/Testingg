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
import com.clrg.core.ui.Icon
import com.clrg.core.ui.Icons
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui
import kotlin.math.sin

class MainMenuScreen(app: GameApp) : Screen(app) {
    override fun onBack(): Boolean { app.platform.quit(); return true }

    /** Little live previews of the current mode's games, shown in turn on the Quick Match card. */
    private val featured = HashMap<com.clrg.core.games.GameMode, List<Pair<String, MiniGame>>>()

    private fun featuredFor(mode: com.clrg.core.games.GameMode): List<Pair<String, MiniGame>> = featured.getOrPut(mode) {
        GameRegistry.forMode(mode).map { e ->
            val env = GameEnv(
                listOf(PlayerInfo(0, 0, "P1", app.playerColor(0), Hand.RIGHT, MovementMode.STANDARD), PlayerInfo(1, 0, "P2", app.playerColor(1), Hand.RIGHT, MovementMode.STANDARD)),
                Settings(), 1,
            )
            e.info.title to e.create(env)
        }
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        val t = app.time
        val xmas = app.christmasMode
        val accent = if (xmas) Colors.CANDY else 0xFF40C4FF.toInt()
        // logo and title
        Common.logo(g, 118f, 96f, 58f, xmas, t)
        g.text("Living Room Games", 200f, 78f, 64f * app.theme.textScale, Colors.WHITE, Align.LEFT, outline = 0xFF0B1A33.toInt(), outlineWidth = 8f)
        g.text(if (xmas) "Christmas" else "Sports & Arcade", 204f, 136f, 32f * app.theme.textScale, if (xmas) Colors.GOLD else 0xFF80D8FF.toInt(), Align.LEFT)
        // top right: camera and the mode switch
        var right = g.width - 40f
        if (app.canSwitchCamera) {
            if (ui.button(g, "camSwitch", Rect(right - 270f, 44f, right, 136f), app.cameraLabel(), "tap to switch", accent = Colors.ICE, size = 30f, icon = Icon.CAMERA)) app.switchCamera()
            right -= 290f
        }
        Common.modeSwitch(g, ui, app, Rect(right - 640f, 44f, right, 136f))
        // hero cards
        val count = GameRegistry.forMode(app.mode).size
        val top = 190f; val heroH = 400f
        val demos = featuredFor(app.mode)
        val slot = ((t / 6.0).toInt()) % demos.size
        val fadeK = ((t % 6.0) / 6.0).toFloat()
        if (ui.tile(g, "quick", Rect(80f, top, 820f, top + heroH), "Quick Match", "Two players, $count games", Colors.GOLD,
                art = { gg, r -> featuredArt(gg, r, demos, slot, fadeK) }, titleSize = 52f, badge = "2 PLAYERS")) app.push(GameSelectScreen(app))
        if (ui.tile(g, "solo", Rect(850f, top, 1350f, top + heroH), "Play Solo", "You against the computer", accent, icon = Icon.SOLO, titleSize = 48f)) app.push(SoloSetupScreen(app))
        if (ui.tile(g, "golf", Rect(1380f, top, g.width - 80f, top + heroH), if (xmas) "Christmas Golf" else "Golf", "1 to 4 players, taking turns", Colors.GOOD,
                art = { gg, r -> golfArt(gg, r, xmas, t) }, titleSize = 48f, badge = "3D")) app.push(GolfSetupScreen(app))
        // the rest of the console
        val y2 = top + heroH + 30f; val h2 = 236f
        val n = 5; val gap = 24f
        val w2 = (g.width - 160f - gap * (n - 1)) / n
        fun r2(i: Int) = Rect(80f + i * (w2 + gap), y2, 80f + i * (w2 + gap) + w2, y2 + h2)
        val tourSub = app.tournament?.let { if (it.isComplete) "Last one finished" else "Match ${it.currentIndex + 1} of ${it.matches.size}" } ?: "Family scoreboard"
        if (ui.tile(g, "tournament", r2(0), "Tournament", tourSub, Colors.CANDY, icon = Icon.TROPHY, titleSize = 34f)) {
            if (app.tournament != null) app.push(TournamentHubScreen(app)) else app.push(TournamentSetupScreen(app))
        }
        if (ui.tile(g, "players", r2(1), "Players", "${app.roster.players.size} family members", Colors.ICE, icon = Icon.PLAYERS, titleSize = 34f)) app.push(PlayersScreen(app))
        if (ui.tile(g, "setup", r2(2), "TV & Camera", if (app.calibrated) "Players calibrated" else "Connect, place, calibrate", Colors.GOOD, icon = Icon.TV, titleSize = 34f)) SetupFlow.startFull(app)
        if (ui.tile(g, "settings", r2(3), "Settings", "Sound, motion, camera", 0xFFB388FF.toInt(), icon = Icon.GEAR, titleSize = 34f)) app.push(SettingsScreen(app))
        if (ui.tile(g, "help", r2(4), "How to Play", "Controls for every game", Colors.WARM, icon = Icon.HELP, titleSize = 34f)) app.push(HelpScreen(app))
        // hands-free: anyone in front of the camera can steer the menus with a raised hand
        var y = y2 + h2 + 34f
        run {
            val hint = when {
                app.ui.cursors.any { it.active && it.hoverId != null } -> "Keep your hand there until the ring fills"
                app.ui.cursors.any { it.active } -> "Move your hand over a card and hold it there - small movements are enough"
                app.handCursorShown -> "Lift your hand to chest height to point"
                else -> "Hands-free: stand in front of the camera and lift a hand to chest height to point"
            }
            val hw = g.measureText(hint, 30f, false) + 110f
            g.fillRoundRect(g.width / 2f - hw / 2f, y, g.width / 2f + hw / 2f, y + 56f, 28f, 0xAA0B2A4F.toInt())
            Icons.draw(g, Icon.HAND, g.width / 2f - hw / 2f + 40f, y + 28f, 34f, Colors.GOLD)
            g.text(hint, g.width / 2f + 22f, y + 28f, 30f, Colors.GOLD, bold = false)
            y += 66f
        }
        val dev = app.platform.deviceInfo()
        if (dev.meetsTarget == false) {
            g.text("Note: this phone is below the recommended Snapdragon 8 Gen 3 class - tracking may be slower.", g.width / 2f, y + 8f, 26f, Colors.WARN, bold = false)
        }
        Common.statusBar(g, app)
    }

    /** The Quick Match card art: each game's live preview in turn, crossfading. */
    private fun featuredArt(g: Gfx, r: Rect, demos: List<Pair<String, MiniGame>>, slot: Int, k: Float) {
        g.fillRoundRect(r.left, r.top, r.right, r.bottom, 20f, 0xFF0E2A4F.toInt())
        val (title, game) = demos[slot]
        val fade = when { k < 0.08f -> k / 0.08f; k > 0.92f -> (1f - k) / 0.08f; else -> 1f }
        g.save(); g.setAlpha(g.getAlpha() * fade)
        game.demo(g, r.inset(14f), app.time)
        g.restore()
        val label = "Now showing: $title"
        val w = g.measureText(label, 24f, false) + 36f
        g.fillRoundRect(r.left + 14f, r.top + 14f, r.left + 14f + w, r.top + 56f, 21f, 0xAA000000.toInt())
        g.text(label, r.left + 14f + w / 2f, r.top + 35f, 24f, Colors.WHITE, bold = false)
    }

    /** The golf card art: a little hole with a waving flag and a ball flying at it. */
    private fun golfArt(g: Gfx, r: Rect, xmas: Boolean, t: Double) {
        g.gradientRect(r.left, r.top, r.right, r.bottom, if (xmas) 0xFF0D1B3A.toInt() else 0xFF5EB6FF.toInt(), if (xmas) 0xFF2B4C7E.toInt() else 0xFFCDEBFF.toInt())
        val hz = r.top + r.height * 0.55f
        // distant hills and trees
        val hill = FloatArray(2 * 14)
        for (i in 0 until 12) { hill[i * 2] = r.left + r.width * i / 11f; hill[i * 2 + 1] = hz - 30f - 22f * sin(i * 1.3f + 0.5f) }
        hill[24] = r.right; hill[25] = r.bottom; hill[26] = r.left; hill[27] = r.bottom
        g.fillPolygon(hill, 14, if (xmas) 0xFF9FB8D8.toInt() else 0xFF5FA463.toInt())
        // fairway with mown stripes, in perspective
        val fw = floatArrayOf(r.cx - 40f, hz, r.cx + 40f, hz, r.right + 60f, r.bottom, r.left - 60f, r.bottom)
        g.fillPolygon(fw, 4, if (xmas) 0xFFE8F1FB.toInt() else 0xFF7CC66B.toInt())
        for (i in 0 until 6) {
            val y0 = hz + (r.bottom - hz) * (i / 6f) * (i / 6f)
            val y1 = hz + (r.bottom - hz) * ((i + 0.5f) / 6f) * ((i + 0.5f) / 6f)
            g.fillRect(r.left, y0, r.right, y1, if (xmas) 0x10000000 else 0x14FFFFFF)
        }
        // green, hole and flag
        val gx = r.cx + r.width * 0.12f; val gy = hz + 26f
        g.fillOval(gx - 90f, gy - 18f, gx + 90f, gy + 22f, if (xmas) 0xFFC9DDF0.toInt() else 0xFF5DBB4C.toInt())
        g.fillOval(gx - 9f, gy - 3f, gx + 9f, gy + 5f, 0xFF1B2A1B.toInt())
        g.line(gx, gy, gx, gy - 92f, 4f, Colors.WHITE)
        val wave = sin(t.toFloat() * 4f) * 8f
        g.fillPolygon(floatArrayOf(gx, gy - 92f, gx + 54f, gy - 80f + wave * 0.5f, gx, gy - 64f), 3, Colors.CANDY)
        // a ball arcing towards the flag on a loop
        val k = ((t % 3.0) / 3.0).toFloat()
        val bx = r.left + r.width * 0.18f + (gx - r.left - r.width * 0.18f) * k
        val by = r.bottom - 30f + (gy - r.bottom + 30f) * k - sin(k * Math.PI.toFloat()) * r.height * 0.42f
        for (j in 1..6) {
            val kk = (k - j * 0.035f).coerceAtLeast(0f)
            val tx = r.left + r.width * 0.18f + (gx - r.left - r.width * 0.18f) * kk
            val ty = r.bottom - 30f + (gy - r.bottom + 30f) * kk - sin(kk * Math.PI.toFloat()) * r.height * 0.42f
            g.fillCircle(tx, ty, 5f - j * 0.6f, Colors.withAlpha(Colors.WHITE, 0.5f - j * 0.07f))
        }
        g.fillCircle(bx, by, 8f, Colors.WHITE)
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

/** Cards for the current mode's games, each with a live preview and tags. The mode can be switched here too. */
class GameSelectScreen(app: GameApp, private val onPick: ((String) -> Unit)? = null) : Screen(app) {
    private val demoCache = HashMap<com.clrg.core.games.GameMode, List<MiniGame>>()

    private fun demos(mode: com.clrg.core.games.GameMode): List<MiniGame> = demoCache.getOrPut(mode) {
        GameRegistry.forMode(mode).map { e ->
            val env = GameEnv(
                listOf(PlayerInfo(0, 0, "P1", app.playerColor(0), Hand.RIGHT, MovementMode.STANDARD), PlayerInfo(1, 0, "P2", app.playerColor(1), Hand.RIGHT, MovementMode.STANDARD)),
                Settings(), 1,
            )
            e.create(env)
        }
    }

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "Choose a game", app = app)
        Common.modeSwitch(g, ui, app, Rect(g.width - 680f, 30f, g.width - 40f, 116f))
        val entries = GameRegistry.forMode(app.mode)
        val games = demos(app.mode)
        val cols = 5
        val rows = (entries.size + cols - 1) / cols
        val margin = 50f
        val gap = 26f
        val cw = (g.width - margin * 2 - (cols - 1) * gap) / cols
        val top = 165f
        val avail = g.height - top - 80f
        val ch = minOf(430f, (avail - (rows - 1) * gap) / rows)
        val accent = if (app.christmasMode) Colors.CANDY else 0xFF40C4FF.toInt()
        entries.forEachIndexed { i, e ->
            val c = i % cols; val rr = i / cols
            // a short last row is centred
            val inRow = if (rr == rows - 1) entries.size - rr * cols else cols
            val x0 = margin + (cols - inRow) * (cw + gap) / 2f
            val x = x0 + c * (cw + gap)
            val y = top + rr * (ch + gap)
            val r = Rect(x, y, x + cw, y + ch)
            val act = ui.tile(g, "game_${e.info.id}", r, e.info.title, e.info.tagline, if (i % 3 == 0) Colors.GOLD else accent,
                art = { gg, ar ->
                    gg.fillRoundRect(ar.left, ar.top, ar.right, ar.bottom, 18f, 0xFF0E2A4F.toInt())
                    games[i].demo(gg, ar.inset(8f), app.time)
                }, tags = GameRegistry.tags(e.info.id), titleSize = 34f)
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
    // wrap long lines to the panel, and shrink a little if it would still overflow
    var sz = size * app.theme.textScale
    var wrapped: List<String> = emptyList()
    for (attempt in 0 until 6) {
        wrapped = lines.flatMap { l ->
            val indent = l.takeWhile { it == ' ' }
            if (l.isBlank()) listOf("") else Common.wrap(g, l.trim(), sz, r.width - 80f - (if (indent.isNotEmpty()) 40f else 0f), false).mapIndexed { i, w -> if (indent.isNotEmpty() || i > 0) "    $w" else w }
        }
        if (r.top + 120f + wrapped.size * sz * 1.45f <= r.bottom - 20f || sz < size * 0.7f) break
        sz *= 0.92f
    }
    var y = r.top + 120f
    for (l in wrapped) { g.text(l, r.left + 40f, y, sz, Colors.WHITE, Align.LEFT, bold = false); y += sz * 1.45f }
}
