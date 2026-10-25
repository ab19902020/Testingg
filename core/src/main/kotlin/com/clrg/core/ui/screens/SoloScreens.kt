package com.clrg.core.ui.screens

import com.clrg.core.app.GameApp
import com.clrg.core.game.CpuLevel
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.math.Rect
import com.clrg.core.party.PlayerProfile
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.PlayerColors
import com.clrg.core.ui.Screen
import com.clrg.core.ui.Ui
import kotlin.math.sin

/** Starting solo matches: one person at the camera against a computer player. */
object SoloFlow {
    /** Game picker that starts a solo match against the current CPU level. */
    fun gameSelect(app: GameApp) = GameSelectScreen(app) { id -> SetupFlow.start(app) { app.replace(GameScreen(app, id, null)) } }

    fun start(app: GameApp, human: PlayerProfile, level: CpuLevel) {
        app.startSolo(human, level)
        app.push(gameSelect(app))
    }
}

/** "Play Solo": who is playing and how good the computer opponent is. */
class SoloSetupScreen(app: GameApp) : Screen(app) {
    private var humanId = (app.activePlayers[0].takeIf { it.id >= 0 } ?: app.roster.players[0]).id
    private var level = app.cpuLevel ?: CpuLevel.fromName(app.settings.cpuLevel)

    override fun frame(g: Gfx, ui: Ui, dt: Float) {
        Common.backdrop(g, app)
        Common.header(g, ui, "Play Solo", app = app)
        g.text("You against a computer player - every game, one person at the camera.", g.width / 2f, 165f, 34f, app.theme.dim, bold = false)

        // who is playing
        val left = Rect(40f, 210f, g.width * 0.42f, 900f)
        Draw.panel(g, left, app.theme.panel, app.theme.panelBorder)
        g.text("Who's playing?", left.cx, left.top + 50f, 44f * app.theme.textScale, Colors.GOLD)
        val bw = (left.width - 90f) / 2f
        app.roster.players.take(8).forEachIndexed { i, p ->
            val x = left.left + 30f + (i % 2) * (bw + 30f)
            val y = left.top + 100f + (i / 2) * 135f
            if (ui.button(g, "solo_p${p.id}", Rect(x, y, x + bw, y + 115f), p.name, null, PlayerColors.get(p.colorIndex), selected = p.id == humanId, size = 40f)) humanId = p.id
        }

        // opponent difficulty
        val right = Rect(g.width * 0.42f + 30f, 210f, g.width - 40f, 900f)
        Draw.panel(g, right, app.theme.panel, app.theme.panelBorder)
        g.text("Your opponent", right.cx, right.top + 50f, 44f * app.theme.textScale, Colors.GOLD)
        val cw = (right.width - 80f) / 3f
        CpuLevel.values().forEachIndexed { i, lv ->
            val r = Rect(right.left + 20f + i * (cw + 20f), right.top + 100f, right.left + 20f + i * (cw + 20f) + cw, right.bottom - 30f)
            val sel = lv == level
            if (ui.button(g, "level_${lv.name}", r, "", accent = levelColor(lv), selected = sel)) level = lv
            val wave = sin(app.time * 3 + i).toFloat()
            val pose = AvatarPose().apply {
                valid = true
                upper[0] = -1.9f; fore[0] = -1.8f
                if (sel) { upper[1] = 1.1f + 0.2f * wave; fore[1] = 1.7f + 0.3f * wave } else { upper[1] = -1.3f; fore[1] = -1.4f }
            }
            Draw.buddy(g, r.cx, r.top + 330f, 260f, levelColor(lv), pose, if (sel) Expression.EXCITED else Expression.HAPPY, t = app.time + i)
            val textC = if (sel) Colors.BLACK else Colors.WHITE
            g.text(lv.botName, r.cx, r.top + 385f, 42f, textC)
            g.text(lv.label, r.cx, r.top + 440f, 36f, if (sel) Colors.BLACK else levelColor(lv))
            val words = lv.blurb.split(" ")
            val half = (words.size + 1) / 2
            g.text(words.take(half).joinToString(" "), r.cx, r.top + 500f, ui.fit(g, words.take(half).joinToString(" "), 26f, cw - 30f, false), textC, bold = false)
            g.text(words.drop(half).joinToString(" "), r.cx, r.top + 535f, ui.fit(g, words.drop(half).joinToString(" "), 26f, cw - 30f, false), textC, bold = false)
        }

        if (ui.button(g, "solo_go", Rect(g.width / 2f - 330f, 935f, g.width / 2f + 330f, 1045f), "Choose a game", accent = Colors.GOOD, size = 48f)) {
            val human = app.roster.byId(humanId) ?: app.roster.players[0]
            SoloFlow.start(app, human, level)
        }
    }

    private fun levelColor(lv: CpuLevel) = PlayerColors.get(lv.colorIndex)
}
