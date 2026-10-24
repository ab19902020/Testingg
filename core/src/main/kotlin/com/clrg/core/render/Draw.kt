package com.clrg.core.render

import com.clrg.core.gesture.AvatarPose
import com.clrg.core.math.PI_F
import com.clrg.core.math.Rect
import com.clrg.core.math.TAU
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

enum class Expression { HAPPY, EXCITED, OOPS, FOCUSED }
enum class Prop { NONE, RACKET, PADDLE, SNOWBALL, BOWLING_BALL, MITTENS_BIG, SHIELD }

/** Original, procedurally drawn art used across the collection (no external assets). */
object Draw {
    private val pts = FloatArray(256)

    // ------------------------------------------------------------------ backgrounds
    fun nightSky(g: Gfx, r: Rect, t: Double, stars: Boolean = true) {
        g.gradientRect(r.left, r.top, r.right, r.bottom, Colors.NIGHT_TOP, Colors.NIGHT_BOTTOM)
        if (!stars) return
        for (i in 0 until 60) {
            val x = r.left + hash01(i * 7 + 1) * r.width
            val y = r.top + hash01(i * 13 + 5) * r.height * 0.55f
            val tw = 0.5f + 0.5f * sin((t * (1.0 + hash01(i) * 2.0) + i).toFloat())
            g.fillCircle(x, y, 1.5f + 1.5f * hash01(i * 3), Colors.withAlpha(Colors.WHITE, 0.3f + 0.6f * tw))
        }
    }

    fun snowHills(g: Gfx, r: Rect, baseY: Float, color: Int = 0xFFE3EEFF.toInt(), shade: Int = 0xFFC2D6F2.toInt()) {
        var n = 0
        val steps = 24
        for (i in 0..steps) {
            val x = r.left + r.width * i / steps
            pts[n++] = x; pts[n++] = baseY - 40f - 30f * sin(i * 0.9f) - 18f * sin(i * 2.3f + 1f)
        }
        pts[n++] = r.right; pts[n++] = r.bottom; pts[n++] = r.left; pts[n++] = r.bottom
        g.fillPolygon(pts, n / 2, shade)
        n = 0
        for (i in 0..steps) {
            val x = r.left + r.width * i / steps
            pts[n++] = x; pts[n++] = baseY - 10f * sin(i * 1.3f + 2f) - 6f * sin(i * 3.1f)
        }
        pts[n++] = r.right; pts[n++] = r.bottom; pts[n++] = r.left; pts[n++] = r.bottom
        g.fillPolygon(pts, n / 2, color)
    }

    /** Deterministic falling snow (no state; positions derived from time). */
    fun snowfall(g: Gfx, r: Rect, t: Double, count: Int = 70, alpha: Float = 0.85f) {
        for (i in 0 until count) {
            val speed = 30f + 60f * hash01(i * 5 + 2)
            val size = 2f + 4f * hash01(i * 11 + 3)
            val x0 = hash01(i * 17 + 7) * r.width
            val y = ((hash01(i * 23 + 1) * r.height + t.toFloat() * speed) % r.height)
            val x = r.left + (x0 + 25f * sin((t * 0.8 + i).toFloat())) % r.width
            g.fillCircle(x, r.top + y, size, Colors.withAlpha(Colors.SNOW, alpha * (0.5f + 0.5f * hash01(i))))
        }
    }

    fun stringLights(g: Gfx, x0: Float, x1: Float, y: Float, t: Double, sag: Float = 26f, spacing: Float = 70f) {
        val n = ((x1 - x0) / spacing).toInt().coerceAtLeast(2)
        var k = 0
        for (i in 0..n) {
            val u = i.toFloat() / n
            pts[k++] = x0 + (x1 - x0) * u
            pts[k++] = y + sag * sin(u * PI_F * (n / 3f).coerceAtLeast(1f)).let { it * it }
        }
        g.polyline(pts, n + 1, 3f, 0xFF2A3A2A.toInt())
        val colors = intArrayOf(0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF40C4FF.toInt(), 0xFF69F0AE.toInt(), 0xFFFF80AB.toInt())
        for (i in 0..n) {
            val bx = pts[i * 2]
            val by = pts[i * 2 + 1] + 12f
            val c = colors[i % colors.size]
            val tw = 0.65f + 0.35f * sin((t * 2.2 + i * 1.7).toFloat())
            g.glow(bx, by, 26f, Colors.withAlpha(c, 0.45f * tw))
            g.fillOval(bx - 6f, by - 9f, bx + 6f, by + 9f, Colors.lerp(Colors.darker(c, 0.6f), c, tw))
        }
    }

    fun festiveBackdrop(g: Gfx, t: Double, effects: Boolean = true) {
        val r = Rect(0f, 0f, g.width, g.height)
        nightSky(g, r, t)
        g.fillCircle(g.width * 0.86f, 150f, 60f, 0xFFFFF6D5.toInt())
        g.glow(g.width * 0.86f, 150f, 170f, 0x33FFF2B0)
        pineRow(g, 0f, g.width, g.height * 0.80f, 0xFF173F33.toInt(), 120f, 1)
        snowHills(g, r, g.height * 0.86f)
        if (effects) snowfall(g, r, t)
        stringLights(g, 0f, g.width, 8f, t)
    }

    fun pineRow(g: Gfx, x0: Float, x1: Float, baseY: Float, color: Int, size: Float, seed: Int) {
        var x = x0 - size * 0.3f
        var i = seed
        while (x < x1 + size) {
            val s = size * (0.7f + 0.6f * hash01(i * 31 + seed))
            pineTree(g, x, baseY, s, color)
            x += s * 0.65f
            i++
        }
    }

    fun pineTree(g: Gfx, x: Float, baseY: Float, h: Float, color: Int, snowCap: Boolean = true) {
        g.fillRect(x - h * 0.05f, baseY - h * 0.15f, x + h * 0.05f, baseY, 0xFF4E342E.toInt())
        for (tier in 0..2) {
            val tw = h * (0.42f - tier * 0.1f)
            val ty = baseY - h * 0.12f - tier * h * 0.26f
            tri(g, x - tw, ty, x + tw, ty, x, ty - h * 0.42f, color)
            if (snowCap) tri(g, x - tw * 0.35f, ty - h * 0.28f, x + tw * 0.35f, ty - h * 0.28f, x, ty - h * 0.42f, Colors.withAlpha(Colors.SNOW, 0.85f))
        }
    }

    fun tri(g: Gfx, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        pts[0] = x0; pts[1] = y0; pts[2] = x1; pts[3] = y1; pts[4] = x2; pts[5] = y2
        g.fillPolygon(pts, 3, color)
    }

    // ------------------------------------------------------------------ UI pieces
    fun panel(g: Gfx, r: Rect, fill: Int = Colors.PANEL, border: Int = Colors.PANEL_BORDER, radius: Float = 28f) {
        g.fillRoundRect(r.left + 6f, r.top + 10f, r.right + 6f, r.bottom + 10f, radius, Colors.SHADOW)
        g.fillRoundRect(r.left, r.top, r.right, r.bottom, radius, fill)
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, radius, 3f, border)
    }

    fun progressRing(g: Gfx, cx: Float, cy: Float, r: Float, progress: Float, color: Int, width: Float = 10f, track: Int = 0x55FFFFFF) {
        g.strokeCircle(cx, cy, r, width, track)
        if (progress > 0f) g.arc(cx, cy, r, -PI_F / 2f, TAU * progress.coerceIn(0f, 1f), width, color)
    }

    fun badge(g: Gfx, cx: Float, cy: Float, label: String, color: Int, size: Float = 34f) {
        val w = g.measureText(label, size) + size
        g.fillRoundRect(cx - w / 2, cy - size * 0.75f, cx + w / 2, cy + size * 0.75f, size * 0.75f, color)
        g.text(label, cx, cy, size, Colors.BLACK)
    }

    fun star(g: Gfx, cx: Float, cy: Float, r: Float, color: Int, rot: Float = 0f) {
        for (i in 0 until 10) {
            val a = rot - PI_F / 2f + i * PI_F / 5f
            val rr = if (i % 2 == 0) r else r * 0.45f
            pts[i * 2] = cx + rr * cos(a); pts[i * 2 + 1] = cy + rr * sin(a)
        }
        g.fillPolygon(pts, 10, color)
    }

    // ------------------------------------------------------------------ characters
    /**
     * "Snow buddy" player character, front view: a snow body with a knitted hat and scarf in the player's colour and
     * twig arms that copy the player's real arm angles ([pose]), so movement is mirrored instantly on screen.
     * (x, y) = base on the ground; h = height.
     */
    fun buddy(
        g: Gfx, x: Float, y: Float, h: Float, color: Int, pose: AvatarPose?, expr: Expression = Expression.HAPPY,
        prop: Prop = Prop.NONE, propHand: Int = 1, t: Double = 0.0, shielded: Boolean = false, squash: Float = 0f,
        /** Optional screen-space mitten targets [lx, ly, rx, ry]: arms reach there (IK, cartoon stretch). */
        handTargets: FloatArray? = null, mittenScale: Float = 1f,
    ) {
        g.fillOval(x - h * 0.3f, y - h * 0.05f, x + h * 0.3f, y + h * 0.04f, Colors.SHADOW)
        val bodyC = 0xFFF7FBFF.toInt()
        val shade = 0xFFCADDF2.toInt()
        // lower body
        val lr = h * 0.26f * (1f + squash * 0.2f)
        g.fillCircle(x, y - lr, lr, shade)
        g.fillCircle(x - lr * 0.12f, y - lr * 1.05f, lr * 0.9f, bodyC)
        g.save()
        val tilt = pose?.tilt ?: 0f
        g.translate(x, y - h * 0.42f)
        g.rotate(tilt)
        g.translate(-x, -(y - h * 0.42f))
        val tr = h * 0.2f
        val ty = y - h * 0.6f
        g.fillCircle(x, ty, tr, shade)
        g.fillCircle(x - tr * 0.1f, ty - tr * 0.05f, tr * 0.9f, bodyC)
        // buttons
        g.fillCircle(x, ty - tr * 0.3f, h * 0.018f, 0xFF37474F.toInt())
        g.fillCircle(x, ty + tr * 0.2f, h * 0.018f, 0xFF37474F.toInt())
        // arms
        val sxL = x - tr * 0.85f
        val sxR = x + tr * 0.85f
        val sy = ty - tr * 0.35f
        val ua = h * 0.17f
        val fa = h * 0.16f
        val armW = h * 0.035f
        val twig = 0xFF6D4C41.toInt()
        var mittenX = 0f; var mittenY = 0f; var foreAng = 0f
        for (side in 0..1) {
            val sx = if (side == 0) sxL else sxR
            var up = pose?.upper?.get(side) ?: if (side == 0) -2.0f else -1.14f
            var fo = pose?.fore?.get(side) ?: if (side == 0) -1.9f else -1.24f
            var ex: Float; var ey: Float; var wx: Float; var wy: Float
            if (handTargets != null) {
                // targets are in screen space; undo the body tilt rotation applied to this group
                val tx0 = handTargets[side * 2]; val ty0 = handTargets[side * 2 + 1]
                val px0 = x; val py0 = y - h * 0.42f
                val c = cos(-tilt); val s0 = sin(-tilt)
                val tx = px0 + (tx0 - px0) * c - (ty0 - py0) * s0
                val ty = py0 + (tx0 - px0) * s0 + (ty0 - py0) * c
                val d = len(tx - sx, ty - sy).coerceAtLeast(1f)
                val k = if (d > (ua + fa) * 0.98f) d / ((ua + fa) * 0.98f) else 1f
                val l1 = ua * k; val l2 = fa * k
                val base = kotlin.math.atan2(ty - sy, tx - sx)
                val cosA = ((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d)).coerceIn(-1f, 1f)
                val a = kotlin.math.acos(cosA) * (if (side == 0) -1f else 1f) * (if (ty < sy) -1f else 1f)
                ex = sx + l1 * cos(base + a); ey = sy + l1 * sin(base + a)
                wx = tx; wy = ty
                up = kotlin.math.atan2(-(ey - sy), ex - sx); fo = kotlin.math.atan2(-(wy - ey), wx - ex)
            } else {
                // body-space angle (y up) -> screen (y down)
                ex = sx + ua * cos(up); ey = sy - ua * sin(up)
                wx = ex + fa * cos(fo); wy = ey - fa * sin(fo)
            }
            g.line(sx, sy, ex, ey, armW, twig)
            g.line(ex, ey, wx, wy, armW * 0.85f, twig)
            g.line(ex + (wx - ex) * 0.5f, ey + (wy - ey) * 0.5f, ex + (wx - ex) * 0.5f + h * 0.03f * cos(fo + 0.9f), ey + (wy - ey) * 0.5f - h * 0.03f * sin(fo + 0.9f), armW * 0.5f, twig)
            g.fillCircle(wx, wy, h * 0.045f * mittenScale, color)
            g.fillCircle(wx - h * 0.012f * mittenScale, wy - h * 0.012f * mittenScale, h * 0.02f * mittenScale, Colors.withAlpha(Colors.WHITE, 0.35f))
            if (side == propHand) { mittenX = wx; mittenY = wy; foreAng = fo }
        }
        // head
        val hr = h * 0.15f
        val hy = ty - tr - hr * 0.75f
        g.fillCircle(x, hy, hr, shade)
        g.fillCircle(x - hr * 0.08f, hy - hr * 0.05f, hr * 0.92f, bodyC)
        // scarf
        g.fillRoundRect(x - tr * 0.85f, hy + hr * 0.7f, x + tr * 0.85f, hy + hr * 1.05f, hr * 0.2f, color)
        g.fillRoundRect(x + tr * 0.25f, hy + hr * 0.8f, x + tr * 0.55f, hy + hr * 1.7f, hr * 0.15f, Colors.darker(color, 0.8f))
        // face
        face(g, x, hy, hr, expr, t)
        // hat
        g.fillPie(x, hy - hr * 0.35f, hr * 0.95f, PI_F, PI_F, color)
        g.fillRoundRect(x - hr * 1.0f, hy - hr * 0.48f, x + hr * 1.0f, hy - hr * 0.2f, hr * 0.12f, Colors.lighter(color, 0.45f))
        g.fillCircle(x + hr * 0.15f, hy - hr * 1.38f, hr * 0.26f, Colors.WHITE)
        g.restore()
        if (prop != Prop.NONE) drawProp(g, prop, mittenX, mittenY, foreAng, h, tilt)
        if (shielded) {
            g.glow(x, y - h * 0.55f, h * 0.75f, 0x5540C4FF)
            g.strokeCircle(x, y - h * 0.55f, h * 0.58f, 6f, 0xCC80D8FF.toInt())
        }
    }

    private fun face(g: Gfx, x: Float, hy: Float, hr: Float, expr: Expression, t: Double) {
        val blink = (t % 3.7) < 0.12
        val eyeY = hy - hr * 0.12f
        for (s in intArrayOf(-1, 1)) {
            val ex = x + s * hr * 0.36f
            if (blink) g.line(ex - hr * 0.1f, eyeY, ex + hr * 0.1f, eyeY, hr * 0.06f, 0xFF263238.toInt())
            else {
                val er = if (expr == Expression.EXCITED) hr * 0.13f else hr * 0.11f
                g.fillCircle(ex, eyeY, er, 0xFF263238.toInt())
                g.fillCircle(ex + er * 0.3f, eyeY - er * 0.35f, er * 0.35f, Colors.WHITE)
            }
            g.fillCircle(x + s * hr * 0.55f, hy + hr * 0.25f, hr * 0.12f, 0x66FF6E8A)
        }
        // carrot nose
        tri(g, x - hr * 0.05f, hy + hr * 0.02f, x - hr * 0.05f, hy + hr * 0.2f, x + hr * 0.42f, hy + hr * 0.14f, 0xFFFF8F00.toInt())
        val mouthY = hy + hr * 0.45f
        when (expr) {
            Expression.HAPPY -> g.arc(x, mouthY - hr * 0.15f, hr * 0.28f, 0.35f, PI_F - 0.7f, hr * 0.06f, 0xFF263238.toInt())
            Expression.EXCITED -> { g.fillOval(x - hr * 0.16f, mouthY - hr * 0.1f, x + hr * 0.16f, mouthY + hr * 0.18f, 0xFF4E1A1A.toInt()) }
            Expression.OOPS -> { g.line(x - hr * 0.2f, mouthY, x - hr * 0.05f, mouthY - hr * 0.06f, hr * 0.05f, 0xFF263238.toInt()); g.line(x - hr * 0.05f, mouthY - hr * 0.06f, x + hr * 0.2f, mouthY + hr * 0.04f, hr * 0.05f, 0xFF263238.toInt()) }
            Expression.FOCUSED -> g.line(x - hr * 0.2f, mouthY, x + hr * 0.2f, mouthY, hr * 0.05f, 0xFF263238.toInt())
        }
    }

    private fun drawProp(g: Gfx, prop: Prop, mx: Float, my: Float, foreAng: Float, h: Float, tilt: Float) {
        val dx = cos(foreAng)
        val dy = -sin(foreAng)
        when (prop) {
            Prop.RACKET -> {
                val hx = mx + dx * h * 0.12f; val hy = my + dy * h * 0.12f
                g.line(mx, my, hx, hy, h * 0.025f, 0xFF8D6E63.toInt())
                val cx = mx + dx * h * 0.24f; val cy = my + dy * h * 0.24f
                g.save(); g.translate(cx, cy); g.rotate(-foreAng + PI_F / 2f)
                g.fillOval(-h * 0.08f, -h * 0.12f, h * 0.08f, h * 0.12f, 0x88FFFFFF.toInt())
                g.strokeRoundRect(-h * 0.08f, -h * 0.12f, h * 0.08f, h * 0.12f, h * 0.08f, h * 0.018f, Colors.CANDY)
                g.restore()
            }
            Prop.PADDLE -> {
                val cx = mx + dx * h * 0.1f; val cy = my + dy * h * 0.1f
                g.fillCircle(cx, cy, h * 0.075f, 0xFFD32F2F.toInt())
                g.strokeCircle(cx, cy, h * 0.075f, h * 0.012f, 0xFF5D4037.toInt())
            }
            Prop.SNOWBALL -> g.fillCircle(mx, my - h * 0.04f, h * 0.06f, Colors.SNOW)
            Prop.BOWLING_BALL -> { g.fillCircle(mx, my, h * 0.075f, 0xFF7B1FA2.toInt()); g.fillCircle(mx - h * 0.02f, my - h * 0.025f, h * 0.012f, 0xFF311B92.toInt()) }
            Prop.MITTENS_BIG -> g.fillCircle(mx, my, h * 0.07f, Colors.withAlpha(Colors.WHITE, 0.5f))
            Prop.SHIELD, Prop.NONE -> Unit
        }
    }

    /** Dance instructor elf (original design). */
    fun elf(g: Gfx, x: Float, y: Float, h: Float, pose: AvatarPose, t: Double, bounce: Float) {
        val by = y - bounce * h * 0.03f
        g.fillOval(x - h * 0.22f, y - h * 0.04f, x + h * 0.22f, y + h * 0.03f, Colors.SHADOW)
        // legs and curly shoes
        for (s in intArrayOf(-1, 1)) {
            g.line(x + s * h * 0.07f, by - h * 0.3f, x + s * h * 0.09f, by - h * 0.06f, h * 0.05f, 0xFFD32F2F.toInt())
            g.fillOval(x + s * h * 0.09f - h * 0.06f, by - h * 0.08f, x + s * h * 0.09f + h * 0.08f * s.toFloat().coerceAtLeast(0f) + h * 0.02f, by, 0xFF2E7D32.toInt())
            g.strokeCircle(x + s * h * 0.17f, by - h * 0.07f, h * 0.025f, h * 0.012f, 0xFF2E7D32.toInt())
        }
        // tunic
        tri(g, x - h * 0.2f, by - h * 0.28f, x + h * 0.2f, by - h * 0.28f, x, by - h * 0.68f, 0xFF2E9E4F.toInt())
        g.fillRect(x - h * 0.16f, by - h * 0.36f, x + h * 0.16f, by - h * 0.32f, 0xFF3E2723.toInt())
        g.fillRect(x - h * 0.03f, by - h * 0.37f, x + h * 0.03f, by - h * 0.31f, Colors.GOLD)
        // arms
        val sy = by - h * 0.6f
        for (side in 0..1) {
            val sx = x + (if (side == 0) -1 else 1) * h * 0.08f
            val ex = sx + h * 0.15f * cos(pose.upper[side]); val ey = sy - h * 0.15f * sin(pose.upper[side])
            val wx = ex + h * 0.14f * cos(pose.fore[side]); val wy = ey - h * 0.14f * sin(pose.fore[side])
            g.line(sx, sy, ex, ey, h * 0.045f, 0xFF2E9E4F.toInt())
            g.line(ex, ey, wx, wy, h * 0.04f, 0xFF2E9E4F.toInt())
            g.fillCircle(wx, wy, h * 0.03f, 0xFFFFD7B5.toInt())
        }
        // head
        val hy = by - h * 0.76f
        val hr = h * 0.1f
        for (s in intArrayOf(-1, 1)) tri(g, x + s * hr * 0.8f, hy - hr * 0.2f, x + s * hr * 0.8f, hy + hr * 0.3f, x + s * hr * 1.6f, hy - hr * 0.6f, 0xFFFFCCA6.toInt())
        g.fillCircle(x, hy, hr, 0xFFFFD7B5.toInt())
        g.fillCircle(x - hr * 0.35f, hy - hr * 0.1f, hr * 0.12f, 0xFF263238.toInt())
        g.fillCircle(x + hr * 0.35f, hy - hr * 0.1f, hr * 0.12f, 0xFF263238.toInt())
        g.arc(x, hy + hr * 0.15f, hr * 0.4f, 0.4f, PI_F - 0.8f, hr * 0.1f, 0xFF8D3B3B.toInt())
        g.fillCircle(x - hr * 0.55f, hy + hr * 0.25f, hr * 0.15f, 0x66FF6E8A)
        g.fillCircle(x + hr * 0.55f, hy + hr * 0.25f, hr * 0.15f, 0x66FF6E8A)
        // hat with bell
        val sway = sin(t * 4).toFloat() * hr * 0.4f
        tri(g, x - hr * 1.05f, hy - hr * 0.55f, x + hr * 1.05f, hy - hr * 0.55f, x + hr * 1.6f + sway, hy - hr * 2.2f, 0xFFD32F2F.toInt())
        g.fillRoundRect(x - hr * 1.1f, hy - hr * 0.75f, x + hr * 1.1f, hy - hr * 0.45f, hr * 0.15f, Colors.WHITE)
        g.fillCircle(x + hr * 1.6f + sway, hy - hr * 2.2f, hr * 0.22f, Colors.GOLD)
    }

    fun reindeer(g: Gfx, x: Float, y: Float, h: Float, color: Int, phase: Float, stumble: Float = 0f) {
        val body = 0xFF8D5A3B.toInt()
        val dark = 0xFF5D3A24.toInt()
        val bob = sin(phase * TAU * 2) * h * 0.03f
        g.fillOval(x - h * 0.45f, y - h * 0.06f, x + h * 0.45f, y + h * 0.04f, Colors.SHADOW)
        // legs
        for (i in 0..3) {
            val lx = x + (if (i < 2) -0.25f else 0.22f) * h + (i % 2) * h * 0.06f
            val a = sin(phase * TAU + i * PI_F * 0.5f) * 0.6f
            val kx = lx + sin(a) * h * 0.18f
            val ky = y - h * 0.28f + h * 0.14f
            g.line(lx, y - h * 0.35f + bob, kx, ky, h * 0.06f, if (i % 2 == 0) body else dark)
            g.line(kx, ky, kx + sin(a * 1.3f) * h * 0.1f, y - h * 0.02f, h * 0.05f, dark)
        }
        g.save(); g.translate(x, y - h * 0.45f + bob); g.rotate(stumble * 0.4f)
        g.fillOval(-h * 0.38f, -h * 0.14f, h * 0.36f, h * 0.16f, body)
        // saddle blanket in player colour
        g.fillRoundRect(-h * 0.14f, -h * 0.15f, h * 0.12f, h * 0.06f, h * 0.04f, color)
        // tail
        g.fillCircle(-h * 0.38f, -h * 0.08f, h * 0.05f, Colors.SNOW)
        // neck and head
        g.line(h * 0.26f, -h * 0.05f, h * 0.38f, -h * 0.3f, h * 0.12f, body)
        g.fillOval(h * 0.3f, -h * 0.42f, h * 0.56f, -h * 0.26f, body)
        g.fillCircle(h * 0.56f, -h * 0.34f, h * 0.045f, 0xFFE53935.toInt())
        g.fillCircle(h * 0.41f, -h * 0.37f, h * 0.022f, Colors.BLACK)
        // antlers
        val ant = 0xFFC8A27A.toInt()
        g.line(h * 0.36f, -h * 0.42f, h * 0.3f, -h * 0.62f, h * 0.03f, ant)
        g.line(h * 0.32f, -h * 0.55f, h * 0.22f, -h * 0.6f, h * 0.025f, ant)
        g.line(h * 0.42f, -h * 0.42f, h * 0.46f, -h * 0.64f, h * 0.03f, ant)
        g.line(h * 0.45f, -h * 0.56f, h * 0.54f, -h * 0.62f, h * 0.025f, ant)
        g.restore()
    }

    /** Top-down sleigh with the player's buddy inside. */
    fun sleighTop(g: Gfx, x: Float, y: Float, s: Float, color: Int, angle: Float) {
        g.save(); g.translate(x, y); g.rotate(angle)
        g.fillRoundRect(-s * 0.42f, -s * 0.55f, s * 0.42f, s * 0.6f, s * 0.2f, Colors.SHADOW)
        g.line(-s * 0.38f, -s * 0.6f, -s * 0.38f, s * 0.55f, s * 0.06f, 0xFFB0BEC5.toInt())
        g.line(s * 0.38f, -s * 0.6f, s * 0.38f, s * 0.55f, s * 0.06f, 0xFFB0BEC5.toInt())
        g.fillRoundRect(-s * 0.33f, -s * 0.5f, s * 0.33f, s * 0.5f, s * 0.16f, color)
        g.fillRoundRect(-s * 0.25f, -s * 0.3f, s * 0.25f, s * 0.4f, s * 0.12f, Colors.darker(color, 0.65f))
        g.fillCircle(0f, s * 0.05f, s * 0.2f, Colors.SNOW)
        g.fillPie(0f, s * 0.05f, s * 0.2f, PI_F, PI_F, color)
        g.fillCircle(0f, -s * 0.18f, s * 0.07f, Colors.WHITE)
        g.restore()
    }

    fun present(g: Gfx, cx: Float, cy: Float, size: Float, box: Int, ribbon: Int, rot: Float = 0f) {
        g.save(); g.translate(cx, cy); g.rotate(rot)
        val h = size / 2f
        g.fillRoundRect(-h, -h * 0.8f, h, h, size * 0.08f, box)
        g.fillRect(-h * 1.06f, -h, h * 1.06f, -h * 0.6f, Colors.lighter(box, 0.15f))
        g.fillRect(-h * 0.14f, -h, h * 0.14f, h, ribbon)
        g.fillRect(-h * 1.06f, -h * 0.2f, h * 1.06f, h * 0.06f, ribbon)
        g.fillOval(-h * 0.6f, -h * 1.45f, -h * 0.02f, -h * 0.95f, ribbon)
        g.fillOval(h * 0.02f, -h * 1.45f, h * 0.6f, -h * 0.95f, ribbon)
        g.restore()
    }

    fun coal(g: Gfx, cx: Float, cy: Float, size: Float, rot: Float = 0f) {
        g.save(); g.translate(cx, cy); g.rotate(rot)
        val r = size / 2f
        val n = 9
        for (i in 0 until n) {
            val a = i * TAU / n
            val rr = r * (0.78f + 0.22f * hash01(i * 7 + 3))
            pts[i * 2] = rr * cos(a); pts[i * 2 + 1] = rr * sin(a)
        }
        g.fillPolygon(pts, n, 0xFF212121.toInt())
        g.polyline(pts, n, 4f, 0xFF616161.toInt(), closed = true)
        // unmistakable warning mark
        g.line(-r * 0.35f, -r * 0.35f, r * 0.35f, r * 0.35f, r * 0.16f, 0xFFFF5252.toInt())
        g.line(r * 0.35f, -r * 0.35f, -r * 0.35f, r * 0.35f, r * 0.16f, 0xFFFF5252.toInt())
        g.restore()
    }

    fun ornament(g: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        g.fillRect(cx - r * 0.25f, cy - r * 1.25f, cx + r * 0.25f, cy - r * 0.85f, 0xFFB0BEC5.toInt())
        g.fillCircle(cx, cy, r, color)
        g.fillCircle(cx - r * 0.35f, cy - r * 0.35f, r * 0.28f, Colors.withAlpha(Colors.WHITE, 0.55f))
        g.arc(cx, cy, r * 0.75f, 0.3f, 1.2f, r * 0.12f, Colors.withAlpha(Colors.WHITE, 0.35f))
    }

    fun christmasTree(g: Gfx, cx: Float, baseY: Float, h: Float, lights: Boolean, t: Double) {
        g.fillRect(cx - h * 0.06f, baseY - h * 0.1f, cx + h * 0.06f, baseY, 0xFF5D4037.toInt())
        g.fillRoundRect(cx - h * 0.14f, baseY - h * 0.02f, cx + h * 0.14f, baseY + h * 0.04f, 6f, 0xFFB71C1C.toInt())
        for (tier in 0..3) {
            val w = h * (0.42f - tier * 0.085f)
            val y0 = baseY - h * 0.08f - tier * h * 0.2f
            tri(g, cx - w, y0, cx + w, y0, cx, y0 - h * 0.34f, if (tier % 2 == 0) Colors.PINE else 0xFF34A065.toInt())
        }
        if (lights) for (i in 0 until 14) {
            val u = hash01(i * 13 + 2)
            val v = hash01(i * 29 + 5)
            val ty = baseY - h * 0.12f - v * h * 0.72f
            val halfW = h * 0.4f * (1f - v * 0.9f)
            val lx = cx + (u * 2f - 1f) * halfW * 0.8f
            val c = intArrayOf(0xFFFFD740.toInt(), 0xFFFF5252.toInt(), 0xFF40C4FF.toInt())[i % 3]
            val tw = 0.6f + 0.4f * sin((t * 3 + i).toFloat())
            g.glow(lx, ty, 14f, Colors.withAlpha(c, 0.5f * tw))
            g.fillCircle(lx, ty, 4f, c)
        }
    }

    /** Small mirrored stick figure of the player's tracked pose (feedback that tracking works). */
    fun stickFigure(g: Gfx, xs: FloatArray, ys: FloatArray, vis: FloatArray, map: (Float, Float) -> Pair<Float, Float>, color: Int, width: Float = 5f) {
        for (e in com.clrg.core.pose.Lm.SKELETON_EDGES) {
            if (vis[e[0]] < 0.4f || vis[e[1]] < 0.4f) continue
            val (x0, y0) = map(xs[e[0]], ys[e[0]])
            val (x1, y1) = map(xs[e[1]], ys[e[1]])
            g.line(x0, y0, x1, y1, width, color)
        }
        val n = com.clrg.core.pose.Lm.NOSE
        if (vis[n] > 0.4f) {
            val (hx, hy) = map(xs[n], ys[n])
            g.strokeCircle(hx, hy, width * 3f, width * 0.8f, color)
        }
        for (i in intArrayOf(com.clrg.core.pose.Lm.LEFT_WRIST, com.clrg.core.pose.Lm.RIGHT_WRIST)) {
            if (vis[i] < 0.4f) continue
            val (wx, wy) = map(xs[i], ys[i])
            g.fillCircle(wx, wy, width * 1.6f, color)
        }
    }

    fun hash01(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFFFF) / 16777216f
    }

    fun frac(v: Float): Float = v - floor(v)
    fun len(x: Float, y: Float) = sqrt(x * x + y * y)
}
