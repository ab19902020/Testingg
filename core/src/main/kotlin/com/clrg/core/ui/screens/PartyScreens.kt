package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.audio.Sfx
import com.clrg.core.games.GameRegistry
import com.clrg.core.math.Rect
import com.clrg.core.party.PlayerProfile
import com.clrg.core.party.Roster
import com.clrg.core.party.Tournament
import com.clrg.core.party.TournamentLength
import com.clrg.core.render.Align
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import com.clrg.core.render.PlayerColors
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui

class TournamentSetupScreen(app: GameApp) : Screen(app) {
    private val chosen = app.roster.players.map { it.id }.toMutableSet()
    private var length = TournamentLength.FULL
    private val modeGames = GameRegistry.forMode(app.mode)
    private val games = modeGames.map { it.info.id }.toMutableSet()

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "New Party Tournament", app = app)
        g.text("Who's playing? (2 - 8)", 520f, 190f, 40f, Colors.WHITE)
        app.roster.players.take(8).forEachIndexed { i, p ->
            val r = Rect(60f + (i % 2) * 470f, 230f + (i / 2) * 110f, 60f + (i % 2) * 470f + 450f, 320f + (i / 2) * 110f)
            val on = p.id in chosen
            if (ui.button(g, "pp${p.id}", r, p.name, if (on) "playing" else "sitting out", PlayerColors.get(p.colorIndex), selected = on, size = 38f)) {
                if (on) { if (chosen.size > 2) chosen.remove(p.id) } else chosen.add(p.id)
            }
        }
        if (ui.button(g, "addp", Rect(60f, 690f, 980f, 770f), "+ Add family member", size = 34f, accent = Colors.ICE)) app.push(NameEntryScreen(app, null))
        g.text("Length", 1420f, 190f, 40f, Colors.WHITE)
        TournamentLength.values().forEachIndexed { i, l ->
            val r = Rect(1040f, 230f + i * 110f, 1800f, 320f + i * 110f)
            if (ui.button(g, "len$i", r, l.label, l.description, selected = length == l, size = 38f)) length = l
        }
        g.text("${app.mode.title} games (tap to include)", 1420f, 600f, 34f, Colors.WHITE)
        modeGames.forEachIndexed { i, e ->
            val r = Rect(1040f + (i % 2) * 385f, 630f + (i / 2) * 64f, 1040f + (i % 2) * 385f + 375f, 686f + (i / 2) * 64f)
            val on = e.info.id in games
            if (ui.button(g, "g$i", r, e.info.title, size = 26f, selected = on, accent = Colors.GOOD)) { if (on) { if (games.size > 1) games.remove(e.info.id) } else games.add(e.info.id) }
        }
        val parts = app.roster.players.filter { it.id in chosen }.map { it.id }
        val preview = Tournament.create(parts, length, modeGames.map { it.info.id }.filter { it in games }, 1)
        g.text("${parts.size} players - ${preview.matches.size} matches - pairs rotate every match", 520f, 820f, 32f, Colors.GOLD, bold = false)
        if (ui.button(g, "start", Rect(60f, 870f, 980f, 990f), "Start tournament", accent = Colors.GOOD, enabled = parts.size >= 2)) {
            app.tournament = Tournament.create(parts, length, modeGames.map { it.info.id }.filter { it in games }, app.platform.nowNanos())
            app.saveTournament()
            app.replace(TournamentHubScreen(app))
        }
    }
}

class TournamentHubScreen(app: GameApp) : Screen(app) {
    private var confirmEnd = false
    private var celebrated = false

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        val t = app.tournament ?: run { app.replace(TournamentSetupScreen(app)); return }
        Common.backdrop(g, app)
        Common.header(g, ui, "Party Tournament", app = app)
        // standings
        val table = Rect(40f, 160f, g.width * 0.48f, 1000f)
        Draw.panel(g, table, app.theme.panel, app.theme.panelBorder)
        g.text("Scoreboard", table.cx, table.top + 50f, 46f, Colors.GOLD)
        val cols = floatArrayOf(table.left + 50f, table.left + 120f, table.right - 400f, table.right - 300f, table.right - 210f, table.right - 120f)
        val headers = listOf("#", "Player", "Won", "Tied", "Lost", "Pts")
        headers.forEachIndexed { i, h -> g.text(h, cols[i], table.top + 120f, 30f, app.theme.dim, if (i == 1) Align.LEFT else Align.CENTER) }
        val leaders = t.leaders()
        t.standings().forEachIndexed { i, s ->
            val p = app.roster.byId(s.playerId)
            val y = table.top + 190f + i * 90f
            if (s.playerId in leaders && s.points > 0) g.fillRoundRect(table.left + 20f, y - 38f, table.right - 20f, y + 38f, 18f, 0x33FFD54F)
            g.text("${i + 1}", cols[0], y, 40f, Colors.WHITE)
            g.fillCircle(cols[1] + 18f, y, 16f, PlayerColors.get(p?.colorIndex ?: 0))
            g.text(p?.name ?: "?", cols[1] + 50f, y, 40f, Colors.WHITE, Align.LEFT)
            g.text("${s.wins}", cols[2], y, 40f, Colors.WHITE)
            g.text("${s.ties}", cols[3], y, 40f, Colors.WHITE)
            g.text("${s.losses}", cols[4], y, 40f, Colors.WHITE)
            g.text("${s.points}", cols[5], y, 44f, Colors.GOLD)
        }
        g.text("Win = ${Tournament.WIN_POINTS} pts, tie = ${Tournament.TIE_POINTS} pt", table.cx, table.bottom - 40f, 28f, app.theme.dim, bold = false)
        val right = Rect(g.width * 0.52f, 160f, g.width - 40f, 1000f)
        Draw.panel(g, right, app.theme.panel, app.theme.panelBorder)
        val m = t.current
        if (m == null) {
            if (!celebrated) { celebrated = true; app.audio.play(Sfx.WIN); app.audio.playMusic(com.clrg.core.audio.MusicTrack.VICTORY, restart = true) }
            val names = leaders.mapNotNull { app.roster.byId(it)?.name }
            g.text(if (names.size == 1) "Champion!" else "Joint champions!", right.cx, right.top + 110f, 72f, Colors.GOLD)
            g.text(names.joinToString(" & "), right.cx, right.top + 220f, 64f, Colors.WHITE)
            Draw.star(g, right.cx, right.top + 380f, 90f, Colors.GOLD, app.time.toFloat() * 0.5f)
            if (ui.button(g, "new", Rect(right.left + 60f, right.bottom - 260f, right.right - 60f, right.bottom - 150f), "New tournament", accent = Colors.GOOD)) {
                app.tournament = null; app.saveTournament(); app.replace(TournamentSetupScreen(app))
            }
            if (ui.button(g, "menu", Rect(right.left + 60f, right.bottom - 130f, right.right - 60f, right.bottom - 30f), "Main menu", accent = Colors.ICE)) app.popToRoot()
            return
        }
        val a = app.roster.byId(m.p1)
        val b = app.roster.byId(m.p2)
        val game = GameRegistry.byId(m.gameId)?.info
        g.text("Match ${t.currentIndex + 1} of ${t.matches.size}", right.cx, right.top + 60f, 40f, app.theme.dim)
        g.text(game?.title ?: m.gameId, right.cx, right.top + 140f, 60f, Colors.GOLD)
        g.text(a?.name ?: "?", right.cx - 220f, right.top + 260f, 60f, PlayerColors.get(a?.colorIndex ?: 0))
        g.text("vs", right.cx, right.top + 260f, 44f, Colors.WHITE)
        g.text(b?.name ?: "?", right.cx + 220f, right.top + 260f, 60f, PlayerColors.get(b?.colorIndex ?: 1))
        g.text("${a?.name} stands on the LEFT, ${b?.name} on the RIGHT", right.cx, right.top + 340f, 32f, Colors.WHITE, bold = false)
        val upNext = t.matches.getOrNull(t.currentIndex + 1)
        if (upNext != null) g.text("Then: ${app.roster.byId(upNext.p1)?.name} vs ${app.roster.byId(upNext.p2)?.name}", right.cx, right.top + 400f, 30f, app.theme.dim, bold = false)
        if (ui.button(g, "play", Rect(right.left + 60f, right.top + 450f, right.right - 60f, right.top + 570f), "Play this match", accent = Colors.GOOD)) {
            if (a != null && b != null) {
                app.setActivePlayers(a, b)
                SetupFlow.start(app) { app.replace(GameScreen(app, m.gameId, m)) }
            }
        }
        if (ui.button(g, "skip", Rect(right.left + 60f, right.top + 600f, right.cx - 10f, right.top + 690f), "Skip match", size = 34f)) { t.skipCurrent(); app.saveTournament() }
        if (!confirmEnd) {
            if (ui.button(g, "end", Rect(right.cx + 10f, right.top + 600f, right.right - 60f, right.top + 690f), "End tournament", size = 34f, accent = Colors.BAD)) confirmEnd = true
        } else {
            if (ui.button(g, "endyes", Rect(right.cx + 10f, right.top + 600f, right.right - 60f, right.top + 690f), "Tap again to end", size = 30f, accent = Colors.BAD, selected = true)) {
                app.tournament = null; app.saveTournament(); app.popToRoot()
            }
        }
        g.text("Scores are saved on this phone automatically.", right.cx, right.bottom - 40f, 26f, app.theme.dim, bold = false)
    }

    override fun onBack(): Boolean { app.popToRoot(); return true }
}

class PlayersScreen(app: GameApp) : Screen(app) {
    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "Players", app = app)
        val players = app.roster.players
        players.take(8).forEachIndexed { i, p ->
            val y = 160f + i * 104f
            val c = PlayerColors.get(p.colorIndex)
            if (ui.button(g, "col${p.id}", Rect(60f, y, 200f, y + 90f), "", accent = c)) { p.colorIndex = (p.colorIndex + 1) % PlayerColors.all.size; app.saveRoster() }
            g.fillCircle(130f, y + 45f, 32f, c)
            if (ui.button(g, "name${p.id}", Rect(220f, y, 760f, y + 90f), p.name, "rename", size = 38f)) app.push(NameEntryScreen(app, p))
            val handLabel = when (p.hand) { null -> "Hand: ask"; Hand.LEFT -> "Left-handed"; Hand.RIGHT -> "Right-handed" }
            if (ui.button(g, "hand${p.id}", Rect(780f, y, 1180f, y + 90f), handLabel, size = 32f)) {
                p.hand = when (p.hand) { null -> Hand.RIGHT; Hand.RIGHT -> Hand.LEFT; Hand.LEFT -> null }; app.saveRoster()
            }
            if (ui.button(g, "mode${p.id}", Rect(1200f, y, 1640f, y + 90f), p.mode.label, size = 32f)) {
                p.mode = MovementMode.values()[(p.mode.ordinal + 1) % MovementMode.values().size]; app.saveRoster()
            }
            if (ui.button(g, "del${p.id}", Rect(1660f, y, g.width - 60f, y + 90f), "Remove", size = 30f, enabled = players.size > 2 && app.activePlayers.none { it.id == p.id }, accent = Colors.BAD)) {
                app.roster.remove(p.id); app.saveRoster()
            }
        }
        if (players.size < 8 && ui.button(g, "add", Rect(60f, 160f + players.size * 104f, 760f, 250f + players.size * 104f), "+ Add family member", size = 36f, accent = Colors.GOOD)) app.push(NameEntryScreen(app, null))
        g.text("Tap a colour to change it. Hand and movement mode are remembered for calibration.", g.width / 2f, g.height - 40f, 28f, app.theme.dim, bold = false)
    }
}

/** On-screen keyboard for names (touch or hands-free dwell). */
class NameEntryScreen(app: GameApp, private val profile: PlayerProfile?) : Screen(app) {
    private var text = profile?.name ?: ""

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, if (profile == null) "New player" else "Rename", app = app)
        g.fillRoundRect(g.width / 2f - 500f, 160f, g.width / 2f + 500f, 270f, 24f, 0xFF0E2240.toInt())
        g.text(text + if ((app.time * 2).toInt() % 2 == 0) "_" else " ", g.width / 2f, 215f, 64f, Colors.WHITE)
        val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
        rows.forEachIndexed { ri, row ->
            val kw = 150f
            val x0 = g.width / 2f - row.length * kw / 2f
            row.forEachIndexed { i, ch ->
                val r = Rect(x0 + i * kw + 6f, 300f + ri * 130f, x0 + (i + 1) * kw - 6f, 420f + ri * 130f)
                if (ui.button(g, "k$ch", r, ch.toString(), size = 50f) && text.length < PlayerProfile.MAX_NAME) text += if (text.isEmpty()) ch else ch.lowercaseChar()
            }
        }
        if (ui.button(g, "space", Rect(g.width / 2f - 600f, 700f, g.width / 2f - 10f, 800f), "Space", size = 40f) && text.length < PlayerProfile.MAX_NAME) text += " "
        if (ui.button(g, "del", Rect(g.width / 2f + 10f, 700f, g.width / 2f + 600f, 800f), "Delete", size = 40f) && text.isNotEmpty()) text = text.dropLast(1)
        val sug = Roster.SUGGESTED_NAMES.filter { s -> app.roster.players.none { it.name == s } }.take(6)
        sug.forEachIndexed { i, s ->
            val r = Rect(60f + i * ((g.width - 120f) / 6f), 830f, 50f + (i + 1) * ((g.width - 120f) / 6f), 910f)
            if (ui.button(g, "sug$i", r, s, size = 34f, accent = Colors.ICE)) text = s
        }
        if (ui.button(g, "done", Rect(g.width / 2f - 300f, 950f, g.width / 2f + 300f, 1060f), "Done", accent = Colors.GOOD, enabled = text.isNotBlank())) {
            val name = text.trim().take(PlayerProfile.MAX_NAME)
            if (profile == null) app.roster.add(name) else profile.name = name
            app.saveRoster()
            app.pop()
        }
    }
}
