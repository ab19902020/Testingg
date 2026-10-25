package com.clrg.core.ui

import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Gfx
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The interface icon set. Every icon is drawn in code from simple shapes, so it is crisp at any size. */
enum class Icon {
    PLAY, VERSUS, SOLO, GOLF, TROPHY, PLAYERS, TV, GEAR, HELP, BACK, PAUSE, RESTART, CAMERA, HOME, CHECK, STAR,
    TARGET, GLOVE, BOW, GUN, RACKET, BALL, PIN, DART, SNOWFLAKE, SOUND, HAND, CALIBRATE, GIFT,
}

object Icons {
    /** Draws [icon] centred on ([cx], [cy]), [s] pixels tall, in [color]. */
    fun draw(g: Gfx, icon: Icon, cx: Float, cy: Float, s: Float, color: Int) {
        val w = s * 0.09f
        val dark = Colors.darker(color, 0.55f)
        when (icon) {
            Icon.PLAY -> tri(g, cx - s * 0.28f, cy - s * 0.38f, cx - s * 0.28f, cy + s * 0.38f, cx + s * 0.4f, cy, color)
            Icon.VERSUS -> {
                person(g, cx - s * 0.2f, cy, s * 0.8f, Colors.withAlpha(color, 0.75f))
                person(g, cx + s * 0.2f, cy + s * 0.04f, s * 0.8f, color)
            }
            Icon.SOLO -> {
                person(g, cx - s * 0.08f, cy, s * 0.9f, color)
                // a little computer opponent chip
                val x = cx + s * 0.3f; val y = cy + s * 0.22f; val r = s * 0.17f
                g.fillRoundRect(x - r, y - r, x + r, y + r, r * 0.35f, dark)
                g.strokeRoundRect(x - r, y - r, x + r, y + r, r * 0.35f, w * 0.6f, color)
                g.fillCircle(x - r * 0.38f, y - r * 0.1f, r * 0.18f, color)
                g.fillCircle(x + r * 0.38f, y - r * 0.1f, r * 0.18f, color)
                g.fillRect(x - r * 0.4f, y + r * 0.35f, x + r * 0.4f, y + r * 0.5f, color)
            }
            Icon.GOLF -> {
                g.fillOval(cx - s * 0.42f, cy + s * 0.26f, cx + s * 0.42f, cy + s * 0.46f, Colors.withAlpha(color, 0.45f))
                g.fillOval(cx - s * 0.1f, cy + s * 0.31f, cx + s * 0.1f, cy + s * 0.4f, dark)
                g.line(cx, cy + s * 0.36f, cx, cy - s * 0.44f, w, color)
                tri(g, cx, cy - s * 0.44f, cx, cy - s * 0.12f, cx + s * 0.36f, cy - s * 0.3f, color)
                g.fillCircle(cx - s * 0.28f, cy + s * 0.3f, s * 0.07f, color)
            }
            Icon.TROPHY -> {
                g.arc(cx - s * 0.26f, cy - s * 0.18f, s * 0.14f, PI.toFloat() * 0.5f, PI.toFloat(), w * 0.8f, color)
                g.arc(cx + s * 0.26f, cy - s * 0.18f, s * 0.14f, -PI.toFloat() * 0.5f, PI.toFloat(), w * 0.8f, color)
                g.fillRoundRect(cx - s * 0.27f, cy - s * 0.42f, cx + s * 0.27f, cy - s * 0.02f, s * 0.04f, color)
                g.fillCircle(cx, cy - s * 0.04f, s * 0.27f, color)
                g.fillRect(cx - s * 0.06f, cy + s * 0.15f, cx + s * 0.06f, cy + s * 0.32f, color)
                g.fillRoundRect(cx - s * 0.24f, cy + s * 0.3f, cx + s * 0.24f, cy + s * 0.44f, s * 0.04f, color)
                Draw.star(g, cx, cy - s * 0.12f, s * 0.11f, dark, 0f)
            }
            Icon.PLAYERS -> {
                person(g, cx - s * 0.27f, cy + s * 0.06f, s * 0.62f, Colors.withAlpha(color, 0.7f))
                person(g, cx + s * 0.27f, cy + s * 0.06f, s * 0.62f, Colors.withAlpha(color, 0.7f))
                person(g, cx, cy, s * 0.78f, color)
            }
            Icon.TV -> {
                g.fillRoundRect(cx - s * 0.46f, cy - s * 0.3f, cx + s * 0.46f, cy + s * 0.24f, s * 0.07f, color)
                g.fillRoundRect(cx - s * 0.39f, cy - s * 0.23f, cx + s * 0.39f, cy + s * 0.17f, s * 0.04f, dark)
                g.fillRect(cx - s * 0.05f, cy + s * 0.24f, cx + s * 0.05f, cy + s * 0.36f, color)
                g.fillRoundRect(cx - s * 0.22f, cy + s * 0.34f, cx + s * 0.22f, cy + s * 0.41f, s * 0.03f, color)
                // the phone camera on top, watching
                g.fillRoundRect(cx - s * 0.1f, cy - s * 0.44f, cx + s * 0.1f, cy - s * 0.32f, s * 0.03f, color)
                g.fillCircle(cx, cy - s * 0.38f, s * 0.035f, dark)
            }
            Icon.GEAR -> {
                val ro = s * 0.4f; val ri = s * 0.3f
                for (i in 0 until 8) {
                    val a = i * PI.toFloat() / 4f
                    g.line(cx + cos(a) * ri, cy + sin(a) * ri, cx + cos(a) * ro, cy + sin(a) * ro, s * 0.15f, color)
                }
                g.fillCircle(cx, cy, s * 0.31f, color)
                g.fillCircle(cx, cy, s * 0.13f, dark)
            }
            Icon.HELP -> {
                g.fillCircle(cx, cy, s * 0.44f, color)
                g.text("?", cx, cy + s * 0.02f, s * 0.62f, dark)
            }
            Icon.BACK -> {
                g.line(cx + s * 0.32f, cy, cx - s * 0.26f, cy, w * 1.4f, color)
                g.polyline(floatArrayOf(cx - s * 0.02f, cy - s * 0.28f, cx - s * 0.3f, cy, cx - s * 0.02f, cy + s * 0.28f), 3, w * 1.4f, color)
            }
            Icon.PAUSE -> {
                g.fillRoundRect(cx - s * 0.3f, cy - s * 0.36f, cx - s * 0.08f, cy + s * 0.36f, s * 0.05f, color)
                g.fillRoundRect(cx + s * 0.08f, cy - s * 0.36f, cx + s * 0.3f, cy + s * 0.36f, s * 0.05f, color)
            }
            Icon.RESTART -> {
                g.arc(cx, cy, s * 0.32f, -PI.toFloat() * 0.35f, PI.toFloat() * 1.6f, w * 1.3f, color)
                val a = -PI.toFloat() * 0.35f
                val ex = cx + cos(a) * s * 0.32f; val ey = cy + sin(a) * s * 0.32f
                tri(g, ex - s * 0.16f, ey - s * 0.08f, ex + s * 0.1f, ey - s * 0.16f, ex + s * 0.04f, ey + s * 0.14f, color)
            }
            Icon.CAMERA -> {
                g.fillRoundRect(cx - s * 0.42f, cy - s * 0.24f, cx + s * 0.42f, cy + s * 0.3f, s * 0.08f, color)
                g.fillRoundRect(cx - s * 0.16f, cy - s * 0.34f, cx + s * 0.16f, cy - s * 0.2f, s * 0.04f, color)
                g.fillCircle(cx, cy + s * 0.03f, s * 0.19f, dark)
                g.fillCircle(cx, cy + s * 0.03f, s * 0.1f, color)
            }
            Icon.HOME -> {
                tri(g, cx - s * 0.44f, cy - s * 0.02f, cx + s * 0.44f, cy - s * 0.02f, cx, cy - s * 0.42f, color)
                g.fillRect(cx - s * 0.3f, cy - s * 0.04f, cx + s * 0.3f, cy + s * 0.4f, color)
                g.fillRoundRect(cx - s * 0.09f, cy + s * 0.12f, cx + s * 0.09f, cy + s * 0.4f, s * 0.03f, dark)
            }
            Icon.CHECK -> g.polyline(floatArrayOf(cx - s * 0.34f, cy, cx - s * 0.08f, cy + s * 0.26f, cx + s * 0.36f, cy - s * 0.28f), 3, w * 1.6f, color)
            Icon.STAR -> Draw.star(g, cx, cy, s * 0.45f, color, 0f)
            Icon.TARGET -> {
                g.fillCircle(cx, cy, s * 0.44f, color)
                g.fillCircle(cx, cy, s * 0.32f, dark)
                g.fillCircle(cx, cy, s * 0.2f, color)
                g.fillCircle(cx, cy, s * 0.08f, dark)
            }
            Icon.GLOVE -> {
                g.fillRoundRect(cx - s * 0.3f, cy - s * 0.38f, cx + s * 0.26f, cy + s * 0.16f, s * 0.22f, color)
                g.fillCircle(cx - s * 0.3f, cy - s * 0.02f, s * 0.13f, color)
                g.fillRoundRect(cx - s * 0.22f, cy + s * 0.14f, cx + s * 0.2f, cy + s * 0.42f, s * 0.05f, Colors.withAlpha(color, 0.75f))
                g.line(cx - s * 0.2f, cy + s * 0.2f, cx + s * 0.18f, cy + s * 0.2f, w * 0.5f, dark)
                g.arc(cx - s * 0.02f, cy - s * 0.12f, s * 0.14f, PI.toFloat() * 0.2f, PI.toFloat() * 0.6f, w * 0.5f, dark)
            }
            Icon.BOW -> {
                g.arc(cx + s * 0.2f, cy, s * 0.44f, PI.toFloat() * 0.62f, PI.toFloat() * 0.76f, w * 1.1f, color)
                val a0 = PI.toFloat() * 0.62f; val a1 = a0 + PI.toFloat() * 0.76f
                g.line(cx + s * 0.2f + cos(a0) * s * 0.44f, cy + sin(a0) * s * 0.44f, cx + s * 0.2f + cos(a1) * s * 0.44f, cy + sin(a1) * s * 0.44f, w * 0.35f, color)
                g.line(cx - s * 0.4f, cy, cx + s * 0.42f, cy, w * 0.6f, color)
                tri(g, cx + s * 0.46f, cy, cx + s * 0.32f, cy - s * 0.07f, cx + s * 0.32f, cy + s * 0.07f, color)
            }
            Icon.GUN -> {
                g.fillRoundRect(cx - s * 0.4f, cy - s * 0.24f, cx + s * 0.42f, cy - s * 0.04f, s * 0.05f, color)
                g.fillRoundRect(cx - s * 0.36f, cy - s * 0.1f, cx - s * 0.08f, cy + s * 0.36f, s * 0.07f, color)
                g.arc(cx - s * 0.02f, cy + s * 0.04f, s * 0.1f, 0f, PI.toFloat(), w * 0.6f, color)
            }
            Icon.RACKET -> {
                g.line(cx + s * 0.04f, cy + s * 0.1f, cx + s * 0.34f, cy + s * 0.44f, w * 1.3f, color)
                g.strokeCircle(cx - s * 0.1f, cy - s * 0.12f, s * 0.26f, w * 1.1f, color)
                for (i in -1..1) {
                    g.line(cx - s * 0.1f + i * s * 0.09f, cy - s * 0.34f, cx - s * 0.1f + i * s * 0.09f, cy + s * 0.1f, w * 0.3f, Colors.withAlpha(color, 0.6f))
                    g.line(cx - s * 0.32f, cy - s * 0.12f + i * s * 0.09f, cx + s * 0.12f, cy - s * 0.12f + i * s * 0.09f, w * 0.3f, Colors.withAlpha(color, 0.6f))
                }
            }
            Icon.BALL -> {
                g.fillCircle(cx, cy, s * 0.4f, color)
                g.arc(cx - s * 0.5f, cy, s * 0.36f, -PI.toFloat() * 0.36f, PI.toFloat() * 0.72f, w * 0.6f, dark)
                g.arc(cx + s * 0.5f, cy, s * 0.36f, PI.toFloat() * 0.64f, PI.toFloat() * 0.72f, w * 0.6f, dark)
            }
            Icon.PIN -> {
                g.fillOval(cx - s * 0.12f, cy - s * 0.46f, cx + s * 0.12f, cy - s * 0.12f, color)
                g.fillOval(cx - s * 0.2f, cy - s * 0.14f, cx + s * 0.2f, cy + s * 0.46f, color)
                g.fillRect(cx - s * 0.1f, cy - s * 0.2f, cx + s * 0.1f, cy - s * 0.06f, color)
                g.fillRect(cx - s * 0.12f, cy - s * 0.18f, cx + s * 0.12f, cy - s * 0.13f, dark)
            }
            Icon.DART -> {
                g.save(); g.translate(cx, cy); g.rotate(-PI.toFloat() / 4f)
                g.line(-s * 0.42f, 0f, s * 0.2f, 0f, w * 0.7f, color)
                tri(g, s * 0.2f, -s * 0.06f, s * 0.2f, s * 0.06f, s * 0.46f, 0f, color)
                tri(g, -s * 0.44f, -s * 0.16f, -s * 0.2f, 0f, -s * 0.44f, s * 0.16f, color)
                g.restore()
            }
            Icon.SNOWFLAKE -> for (i in 0 until 6) {
                val a = i * PI.toFloat() / 3f
                val ex = cx + cos(a) * s * 0.42f; val ey = cy + sin(a) * s * 0.42f
                g.line(cx, cy, ex, ey, w * 0.7f, color)
                val mx = cx + cos(a) * s * 0.26f; val my = cy + sin(a) * s * 0.26f
                for (sgn in floatArrayOf(-1f, 1f)) {
                    val b = a + sgn * 0.7f
                    g.line(mx, my, mx + cos(b) * s * 0.12f, my + sin(b) * s * 0.12f, w * 0.55f, color)
                }
            }
            Icon.SOUND -> {
                g.fillRect(cx - s * 0.4f, cy - s * 0.12f, cx - s * 0.24f, cy + s * 0.12f, color)
                tri(g, cx - s * 0.26f, cy - s * 0.12f, cx - s * 0.26f, cy + s * 0.12f, cx, cy - s * 0.32f, color)
                tri(g, cx - s * 0.26f, cy + s * 0.12f, cx, cy + s * 0.32f, cx, cy - s * 0.32f, color)
                g.arc(cx + s * 0.02f, cy, s * 0.18f, -0.8f, 1.6f, w * 0.7f, color)
                g.arc(cx + s * 0.02f, cy, s * 0.34f, -0.8f, 1.6f, w * 0.7f, color)
            }
            Icon.HAND -> {
                g.fillRoundRect(cx - s * 0.22f, cy - s * 0.1f, cx + s * 0.2f, cy + s * 0.42f, s * 0.12f, color)
                for (i in 0 until 4) {
                    val x = cx - s * 0.18f + i * s * 0.115f
                    g.fillRoundRect(x - s * 0.045f, cy - s * 0.42f + (if (i == 0 || i == 3) s * 0.07f else 0f), x + s * 0.045f, cy, s * 0.045f, color)
                }
                g.save(); g.translate(cx - s * 0.24f, cy + s * 0.06f); g.rotate(-0.6f)
                g.fillRoundRect(-s * 0.045f, -s * 0.2f, s * 0.045f, s * 0.05f, s * 0.045f, color)
                g.restore()
            }
            Icon.CALIBRATE -> {
                person(g, cx, cy + s * 0.04f, s * 0.7f, color)
                val c = s * 0.44f; val l = s * 0.14f
                for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
                    g.line(cx + sx * c, cy + sy * c, cx + sx * (c - l), cy + sy * c, w * 0.7f, color)
                    g.line(cx + sx * c, cy + sy * c, cx + sx * c, cy + sy * (c - l), w * 0.7f, color)
                }
            }
            Icon.GIFT -> {
                g.fillRoundRect(cx - s * 0.38f, cy - s * 0.1f, cx + s * 0.38f, cy + s * 0.4f, s * 0.04f, color)
                g.fillRoundRect(cx - s * 0.42f, cy - s * 0.24f, cx + s * 0.42f, cy - s * 0.06f, s * 0.04f, color)
                g.fillRect(cx - s * 0.06f, cy - s * 0.24f, cx + s * 0.06f, cy + s * 0.4f, dark)
                g.strokeCircle(cx - s * 0.12f, cy - s * 0.32f, s * 0.09f, w * 0.6f, color)
                g.strokeCircle(cx + s * 0.12f, cy - s * 0.32f, s * 0.09f, w * 0.6f, color)
            }
        }
    }

    private fun person(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillCircle(cx, cy - s * 0.24f, s * 0.17f, color)
        g.fillRoundRect(cx - s * 0.27f, cy - s * 0.02f, cx + s * 0.27f, cy + s * 0.44f, s * 0.2f, color)
    }

    private val triPts = FloatArray(6)

    private fun tri(g: Gfx, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        triPts[0] = x0; triPts[1] = y0; triPts[2] = x1; triPts[3] = y1; triPts[4] = x2; triPts[5] = y2
        g.fillPolygon(triPts, 3, color)
    }
}
