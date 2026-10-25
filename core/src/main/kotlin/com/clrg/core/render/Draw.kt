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
enum class Prop { NONE, RACKET, PADDLE, SNOWBALL, BOWLING_BALL, MITTENS_BIG, SHIELD, GOLF_CLUB, PUTTER }

/**
 * Original, procedurally drawn art used across the collection (no external assets). Lighting convention: a soft key
 * light from the upper left, cool blue shadows on snow, warm glows for lamps and fairy lights.
 */
object Draw {
    private val pts = FloatArray(256)
    private val pts2 = FloatArray(256)

    // Shared palette for lit snow
    const val SNOW_LIT = 0xFFFFFFFF.toInt()
    const val SNOW_SHADE = 0xFFB4C9E6.toInt()
    const val SNOW_DEEP = 0xFF8FA9CF.toInt()

    // ------------------------------------------------------------------ backgrounds

    /** Deep night sky (three-tone gradient with a glow above the horizon) and twinkling, flaring stars. */
    fun nightSky(g: Gfx, r: Rect, t: Double, stars: Boolean = true) {
        val mid = r.top + r.height * 0.55f
        g.gradientRect(r.left, r.top, r.right, mid, 0xFF030A1C.toInt(), 0xFF0D2150.toInt())
        g.gradientRect(r.left, mid - 1f, r.right, r.bottom, 0xFF0D2150.toInt(), 0xFF26507F.toInt())
        if (!stars) return
        for (i in 0 until 90) {
            val x = r.left + hash01(i * 7 + 1) * r.width
            val y = r.top + hash01(i * 13 + 5) * r.height * 0.6f
            val tw = 0.5f + 0.5f * sin((t * (0.8 + hash01(i) * 2.2) + i * 1.3).toFloat())
            val big = hash01(i * 3) > 0.86f
            val rad = if (big) 2.4f else 0.9f + 1.4f * hash01(i * 3)
            if (big) {
                g.glow(x, y, 16f + 8f * tw, Colors.withAlpha(0xFFCFE3FF.toInt(), 0.35f * tw))
                val f = 9f + 7f * tw
                g.line(x - f, y, x + f, y, 1.4f, Colors.withAlpha(Colors.WHITE, 0.45f * tw))
                g.line(x, y - f, x, y + f, 1.4f, Colors.withAlpha(Colors.WHITE, 0.45f * tw))
            }
            g.fillCircle(x, y, rad, Colors.withAlpha(Colors.WHITE, 0.35f + 0.6f * tw))
        }
        // an occasional shooting star
        val cycle = (t % 11.0).toFloat()
        if (cycle < 0.9f) {
            val k = cycle / 0.9f
            val sx = r.left + r.width * (0.15f + 0.5f * hash01((t / 11.0).toInt()))
            val sy = r.top + r.height * 0.08f
            val hx = sx + 420f * k; val hy = sy + 150f * k
            for (j in 0 until 6) {
                val q = j / 6f
                g.line(hx - 70f * (q + 0.16f), hy - 25f * (q + 0.16f), hx - 70f * q, hy - 25f * q, 3f * (1f - q), Colors.withAlpha(Colors.WHITE, (1f - q) * (1f - k) * 0.9f))
            }
        }
    }

    /** Slowly drifting aurora ribbons (green, teal and violet) in the upper sky. */
    fun aurora(g: Gfx, r: Rect, t: Double, strength: Float = 1f) {
        val colors = intArrayOf(0x5A2EF2A0, 0x4A35C8E8, 0x3A9A6BFF)
        val steps = 28
        for (band in 0..2) {
            val base = r.top + r.height * (0.16f + band * 0.07f)
            val amp = r.height * (0.05f + 0.02f * band)
            val height = r.height * (0.13f + 0.03f * band)
            var n = 0
            for (i in 0..steps) {
                val u = i.toFloat() / steps
                val x = r.left + r.width * u
                val w = sin((u * 5.2f + t.toFloat() * (0.11f + band * 0.04f) + band * 1.7f)) * amp + sin(u * 11f + t.toFloat() * 0.07f) * amp * 0.3f
                pts[n++] = x; pts[n++] = base + w - height * (0.6f + 0.4f * sin(u * 3.1f + band + t.toFloat() * 0.05f).let { it * it })
            }
            for (i in steps downTo 0) {
                val u = i.toFloat() / steps
                val x = r.left + r.width * u
                val w = sin((u * 5.2f + t.toFloat() * (0.11f + band * 0.04f) + band * 1.7f)) * amp + sin(u * 11f + t.toFloat() * 0.07f) * amp * 0.3f
                pts[n++] = x; pts[n++] = base + w
            }
            val c = Colors.withAlpha(colors[band], strength)
            g.fillPolygonGradient(pts, n / 2, base - height - amp, base + amp, c and 0x00FFFFFF, c)
        }
    }

    /** A softly glowing moon with a halo and faint craters. */
    fun moon(g: Gfx, x: Float, y: Float, r: Float) {
        g.glow(x, y, r * 4.2f, 0x22FFF2C8)
        g.glow(x, y, r * 2.2f, 0x44FFF2C8)
        g.fillCircleRadial(x, y, r, x - r * 0.35f, y - r * 0.35f, r * 1.5f, 0xFFFFFDF2.toInt(), 0xFFF2DDA8.toInt())
        g.fillCircle(x + r * 0.3f, y + r * 0.15f, r * 0.18f, 0x22B08A50)
        g.fillCircle(x - r * 0.2f, y + r * 0.45f, r * 0.11f, 0x22B08A50)
        g.fillCircle(x + r * 0.05f, y - r * 0.4f, r * 0.09f, 0x1AB08A50)
    }

    /** Rolling snow hills: a shaded back ridge and a lit front ridge with a glinting crest. */
    fun snowHills(g: Gfx, r: Rect, baseY: Float, color: Int = 0xFFE9F2FF.toInt(), shade: Int = 0xFFC2D6F2.toInt()) {
        var n = 0
        val steps = 24
        for (i in 0..steps) {
            val x = r.left + r.width * i / steps
            pts[n++] = x; pts[n++] = baseY - 40f - 30f * sin(i * 0.9f) - 18f * sin(i * 2.3f + 1f)
        }
        pts[n++] = r.right; pts[n++] = r.bottom; pts[n++] = r.left; pts[n++] = r.bottom
        g.fillPolygonGradient(pts, n / 2, baseY - 90f, r.bottom, shade, Colors.darker(shade, 0.8f))
        n = 0
        for (i in 0..steps) {
            val x = r.left + r.width * i / steps
            pts[n++] = x; pts[n++] = baseY - 10f * sin(i * 1.3f + 2f) - 6f * sin(i * 3.1f)
        }
        val crest = n / 2
        pts[n++] = r.right; pts[n++] = r.bottom; pts[n++] = r.left; pts[n++] = r.bottom
        g.fillPolygonGradient(pts, n / 2, baseY - 20f, r.bottom, color, Colors.lerp(color, SNOW_SHADE, 0.55f))
        g.polyline(pts, crest, 3f, 0x66FFFFFF)
        // glints in the snow
        for (i in 0 until 14) {
            val gx = r.left + hash01(i * 41 + 3) * r.width
            val gy = baseY + 12f + hash01(i * 17 + 9) * (r.bottom - baseY - 20f).coerceAtLeast(10f)
            g.fillCircle(gx, gy, 1.6f, 0xAAFFFFFF.toInt())
        }
    }

    /** Deterministic falling snow in three depth layers (no state; positions derived from time). */
    fun snowfall(g: Gfx, r: Rect, t: Double, count: Int = 70, alpha: Float = 0.85f) {
        val far = count * 6 / 10
        val mid = count * 3 / 10
        val near = (count / 10).coerceAtLeast(2)
        for (layer in 0..2) {
            val n = when (layer) { 0 -> far; 1 -> mid; else -> near }
            for (i in 0 until n) {
                val id = i * 3 + layer * 1000
                val speed = when (layer) { 0 -> 22f + 18f * hash01(id * 5 + 2); 1 -> 45f + 35f * hash01(id * 5 + 2); else -> 110f + 60f * hash01(id * 5 + 2) }
                val size = when (layer) { 0 -> 1.2f + 1.3f * hash01(id * 11 + 3); 1 -> 2.6f + 2f * hash01(id * 11 + 3); else -> 7f + 6f * hash01(id * 11 + 3) }
                val sway = when (layer) { 0 -> 12f; 1 -> 25f; else -> 45f }
                val x0 = hash01(id * 17 + 7) * r.width
                val y = ((hash01(id * 23 + 1) * r.height + t.toFloat() * speed) % r.height)
                val x = r.left + ((x0 + sway * sin((t * (0.6 + layer * 0.3) + i).toFloat())) % r.width + r.width) % r.width
                val a = alpha * when (layer) { 0 -> 0.45f; 1 -> 0.8f; else -> 0.45f } * (0.6f + 0.4f * hash01(id))
                if (layer == 2) g.glow(x, r.top + y, size * 1.6f, Colors.withAlpha(Colors.SNOW, a))
                else g.fillCircle(x, r.top + y, size, Colors.withAlpha(Colors.SNOW, a))
            }
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
        g.polyline(pts, n + 1, 3f, 0xFF1F2E22.toInt())
        val colors = intArrayOf(0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF40C4FF.toInt(), 0xFF69F0AE.toInt(), 0xFFFF80AB.toInt())
        for (i in 0..n) {
            val bx = pts[i * 2]
            val by = pts[i * 2 + 1] + 12f
            val c = colors[i % colors.size]
            val tw = 0.6f + 0.4f * sin((t * 2.2 + i * 1.7).toFloat())
            g.glow(bx, by, 40f, Colors.withAlpha(c, 0.42f * tw))
            g.fillRect(bx - 3f, by - 14f, bx + 3f, by - 8f, 0xFF37474F.toInt())
            g.fillCircleRadial(bx, by, 8f, bx - 2.5f, by - 3f, 9f, Colors.lighter(c, 0.6f * tw), Colors.lerp(Colors.darker(c, 0.55f), c, tw))
        }
    }

    /** The menu backdrop: aurora night sky, moon, layered pine forest, snow hills, snowfall and fairy lights. */
    fun festiveBackdrop(g: Gfx, t: Double, effects: Boolean = true) {
        val r = Rect(0f, 0f, g.width, g.height)
        nightSky(g, r, t)
        aurora(g, r, t, if (effects) 1f else 0.6f)
        moon(g, g.width * 0.86f, 150f, 58f)
        pineRow(g, -40f, g.width, g.height * 0.76f, 0xFF12302A.toInt(), 95f, 3)
        pineRow(g, 0f, g.width, g.height * 0.81f, 0xFF1B4434.toInt(), 125f, 1)
        snowHills(g, r, g.height * 0.86f)
        if (effects) snowfall(g, r, t)
        stringLights(g, 0f, g.width, 8f, t)
        vignette(g, 0.55f)
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

    /** Layered pine with lit tiers (lighter at the top), snow on each tier and a soft base shadow. */
    fun pineTree(g: Gfx, x: Float, baseY: Float, h: Float, color: Int, snowCap: Boolean = true) {
        g.fillOval(x - h * 0.3f, baseY - h * 0.03f, x + h * 0.3f, baseY + h * 0.04f, 0x33000000)
        g.fillRect(x - h * 0.05f, baseY - h * 0.15f, x + h * 0.05f, baseY, 0xFF4E342E.toInt())
        val top = Colors.lighter(color, 0.18f)
        val bottom = Colors.darker(color, 0.7f)
        for (tier in 0..2) {
            val tw = h * (0.42f - tier * 0.1f)
            val ty = baseY - h * 0.12f - tier * h * 0.26f
            pts[0] = x - tw; pts[1] = ty; pts[2] = x + tw; pts[3] = ty; pts[4] = x; pts[5] = ty - h * 0.42f
            g.fillPolygonGradient(pts, 3, ty - h * 0.42f, ty, top, bottom)
            if (snowCap) {
                tri(g, x - tw * 0.38f, ty - h * 0.27f, x + tw * 0.38f, ty - h * 0.27f, x, ty - h * 0.42f, Colors.withAlpha(Colors.SNOW, 0.9f))
                g.line(x - tw * 0.85f, ty - h * 0.008f, x - tw * 0.5f, ty + h * 0.006f, h * 0.028f, Colors.withAlpha(Colors.SNOW, 0.45f))
            }
        }
    }

    fun tri(g: Gfx, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        pts[0] = x0; pts[1] = y0; pts[2] = x1; pts[3] = y1; pts[4] = x2; pts[5] = y2
        g.fillPolygon(pts, 3, color)
    }

    /** Darkens the screen edges so the eye goes to the middle (cinematic framing). */
    fun vignette(g: Gfx, strength: Float = 0.5f) {
        val w = g.width; val h = g.height
        g.fillRectRadial(0f, 0f, w, h, w / 2f, h * 0.48f, maxOf(w, h) * 0.78f, 0x00000000, Colors.withAlpha(0xFF000814.toInt(), strength))
    }

    /** Soft elliptical contact shadow centred at (cx, cy). */
    fun softShadow(g: Gfx, cx: Float, cy: Float, w: Float, h: Float, alpha: Float = 0.45f) {
        g.save()
        g.translate(cx, cy)
        g.scale(1f, (h / w).coerceAtLeast(0.05f))
        g.glow(0f, 0f, w, Colors.withAlpha(0xFF00081A.toInt(), alpha))
        g.restore()
    }

    /** Pool of light on the ground (lamps, spotlights). */
    fun lightPool(g: Gfx, cx: Float, cy: Float, w: Float, h: Float, color: Int) {
        g.save()
        g.translate(cx, cy)
        g.scale(1f, (h / w).coerceAtLeast(0.05f))
        g.glow(0f, 0f, w, color)
        g.restore()
    }

    /** Stadium floodlight: pole, lamp head with glowing bulbs and a soft light beam down to (aimX, aimY). */
    fun floodlight(g: Gfx, x: Float, baseY: Float, topY: Float, aimX: Float, aimY: Float, spread: Float = 260f) {
        pts[0] = x - 30f; pts[1] = topY + 6f; pts[2] = x + 30f; pts[3] = topY + 6f
        pts[4] = aimX + spread; pts[5] = aimY; pts[6] = aimX - spread; pts[7] = aimY
        g.fillPolygonGradient(pts, 4, topY, aimY, 0x3CFFF6D8, 0x00FFF6D8)
        g.fillRoundRectGradient(x - 6f, topY, x + 6f, baseY, 4f, 0xFF78909C.toInt(), 0xFF263238.toInt())
        g.glow(x, topY - 4f, 110f, 0x66FFF6D8)
        g.fillRoundRectGradient(x - 40f, topY - 26f, x + 40f, topY + 10f, 8f, 0xFF546E7A.toInt(), 0xFF1C262B.toInt())
        for (i in 0..2) {
            val bx = x - 24f + i * 24f
            g.fillCircleRadial(bx, topY - 8f, 9f, bx - 2f, topY - 11f, 12f, Colors.WHITE, 0xFFFFE9A8.toInt())
        }
    }

    /** Coloured stage spotlight beam from (x, topY) widening down to the floor at floorY. */
    fun spotBeam(g: Gfx, x: Float, topY: Float, floorX: Float, floorY: Float, width: Float, color: Int) {
        pts[0] = x - 14f; pts[1] = topY; pts[2] = x + 14f; pts[3] = topY
        pts[4] = floorX + width; pts[5] = floorY; pts[6] = floorX - width; pts[7] = floorY
        g.fillPolygonGradient(pts, 4, topY, floorY, Colors.withAlpha(color, 0.38f), Colors.withAlpha(color, 0.08f))
        lightPool(g, floorX, floorY, width * 1.1f, width * 0.22f, Colors.withAlpha(color, 0.35f))
    }

    /** Small wall wreath with a red bow and twinkling berries. */
    fun wreathSmall(g: Gfx, x: Float, y: Float, r: Float, time: Double) {
        g.strokeCircle(x, y + 2f, r, r * 0.5f, 0x55000000)
        g.strokeCircle(x, y, r, r * 0.5f, 0xFF1B5E20.toInt())
        g.strokeCircle(x, y - 2f, r * 1.05f, r * 0.18f, 0xFF43A047.toInt())
        for (i in 0 until 6) {
            val a = i * 1.047f + 0.3f
            val on = sin(time * 2.5 + i).toFloat() > -0.3f
            g.fillCircle(x + cos(a) * r, y + sin(a) * r, r * 0.13f, if (on) 0xFFFF5252.toInt() else 0xFFB71C1C.toInt())
        }
        tri(g, x, y + r * 0.9f, x - r * 0.55f, y + r * 0.6f, x - r * 0.55f, y + r * 1.2f, 0xFFD32F2F.toInt())
        tri(g, x, y + r * 0.9f, x + r * 0.55f, y + r * 0.6f, x + r * 0.55f, y + r * 1.2f, 0xFFD32F2F.toInt())
        g.fillCircle(x, y + r * 0.9f, r * 0.16f, 0xFFB71C1C.toInt())
    }

    /** Four-pointed twinkle. */
    fun sparkle(g: Gfx, x: Float, y: Float, size: Float, color: Int) {
        g.glow(x, y, size * 1.6f, Colors.withAlpha(color, 0.5f))
        g.line(x - size, y, x + size, y, size * 0.16f, color)
        g.line(x, y - size, x, y + size, size * 0.16f, color)
        g.fillCircle(x, y, size * 0.18f, Colors.WHITE)
    }

    // ------------------------------------------------------------------ UI pieces

    /** Frosted-glass panel: soft drop shadow, gradient body, inner highlight and a crisp border. */
    fun panel(g: Gfx, r: Rect, fill: Int = Colors.PANEL, border: Int = Colors.PANEL_BORDER, radius: Float = 28f) {
        g.fillRoundRect(r.left + 4f, r.top + 14f, r.right + 4f, r.bottom + 18f, radius + 6f, 0x26000000)
        g.fillRoundRect(r.left + 2f, r.top + 8f, r.right + 2f, r.bottom + 9f, radius + 2f, 0x33000000)
        g.fillRoundRectGradient(r.left, r.top, r.right, r.bottom, radius, Colors.lighter(fill, 0.09f), Colors.darker(fill, 0.82f))
        g.fillRoundRectGradient(r.left + 3f, r.top + 3f, r.right - 3f, r.top + minOf(r.height * 0.35f, 120f), radius - 2f, 0x1CFFFFFF, 0x00FFFFFF)
        g.strokeRoundRect(r.left, r.top, r.right, r.bottom, radius, 3f, border)
        g.strokeRoundRect(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f, radius - 4f, 1.5f, 0x14FFFFFF)
    }

    fun progressRing(g: Gfx, cx: Float, cy: Float, r: Float, progress: Float, color: Int, width: Float = 10f, track: Int = 0x55FFFFFF) {
        g.strokeCircle(cx, cy, r, width, track)
        if (progress > 0f) {
            val p = progress.coerceIn(0f, 1f)
            g.arc(cx, cy, r, -PI_F / 2f, TAU * p, width, color)
            val a = -PI_F / 2f + TAU * p
            g.glow(cx + r * cos(a), cy + r * sin(a), width * 2.2f, Colors.withAlpha(color, 0.7f))
        }
    }

    fun badge(g: Gfx, cx: Float, cy: Float, label: String, color: Int, size: Float = 34f) {
        val w = g.measureText(label, size) + size
        val h = size * 0.75f
        g.fillRoundRect(cx - w / 2 + 2f, cy - h + 4f, cx + w / 2 + 2f, cy + h + 4f, h, 0x44000000)
        g.fillRoundRectGradient(cx - w / 2, cy - h, cx + w / 2, cy + h, h, Colors.lighter(color, 0.25f), Colors.darker(color, 0.85f))
        g.fillRoundRectGradient(cx - w / 2 + 3f, cy - h + 2f, cx + w / 2 - 3f, cy, h * 0.8f, 0x55FFFFFF, 0x00FFFFFF)
        g.text(label, cx, cy, size, Colors.BLACK)
    }

    fun star(g: Gfx, cx: Float, cy: Float, r: Float, color: Int, rot: Float = 0f) {
        for (i in 0 until 10) {
            val a = rot - PI_F / 2f + i * PI_F / 5f
            val rr = if (i % 2 == 0) r else r * 0.45f
            pts[i * 2] = cx + rr * cos(a); pts[i * 2 + 1] = cy + rr * sin(a)
        }
        g.fillPolygonGradient(pts, 10, cy - r, cy + r, Colors.lighter(color, 0.45f), Colors.darker(color, 0.8f))
    }

    // ------------------------------------------------------------------ characters

    /** A lit snowball: highlight upper left, cool shadow lower right, faint rim. */
    private fun snowSphere(g: Gfx, cx: Float, cy: Float, r: Float) {
        g.fillCircleRadial(cx, cy, r, cx - r * 0.38f, cy - r * 0.45f, r * 1.75f, SNOW_LIT, SNOW_SHADE)
        g.strokeCircle(cx, cy, r, maxOf(1.5f, r * 0.025f), 0x33335C8C)
    }

    /** Glossy knitted colour ball (mittens, pom-poms). */
    private fun woolBall(g: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        g.fillCircleRadial(cx, cy, r, cx - r * 0.35f, cy - r * 0.4f, r * 1.6f, Colors.lighter(color, 0.35f), Colors.darker(color, 0.62f))
    }

    /**
     * "Snow buddy" player character, front view: lit snowballs, a knitted hat and scarf in the player's colour and
     * twig arms that copy the player's real arm angles ([pose]); the body also rises and crouches and the head tilts
     * with the player, so movement is mirrored instantly on screen. (x, y) = base on the ground; h = height.
     */
    fun buddy(
        g: Gfx, x: Float, y: Float, h: Float, color: Int, pose: AvatarPose?, expr: Expression = Expression.HAPPY,
        prop: Prop = Prop.NONE, propHand: Int = 1, t: Double = 0.0, shielded: Boolean = false, squash: Float = 0f,
        /** Optional screen-space mitten targets [lx, ly, rx, ry]: arms reach there (IK, cartoon stretch). */
        handTargets: FloatArray? = null, mittenScale: Float = 1f,
    ) {
        val rise = pose?.rise ?: 0f
        val crouch = (-rise).coerceIn(0f, 0.9f)
        val lift = (-rise * h * 0.16f).coerceIn(-h * 0.08f, h * 0.12f)
        softShadow(g, x, y, h * (0.34f + crouch * 0.05f), h * 0.07f, 0.5f)
        // lower body (widens a little when crouching)
        val sq = squash + crouch * 0.5f
        val lr = h * 0.26f
        g.save()
        g.translate(x, y)
        g.scale(1f + sq * 0.18f, 1f - sq * 0.12f)
        snowSphere(g, 0f, -lr, lr)
        g.restore()
        g.save()
        val tilt = pose?.tilt ?: 0f
        g.translate(0f, lift)
        g.translate(x, y - h * 0.42f)
        g.rotate(tilt)
        g.translate(-x, -(y - h * 0.42f))
        val tr = h * 0.2f
        val ty = y - h * 0.6f
        snowSphere(g, x, ty, tr)
        // coal buttons
        for (by in floatArrayOf(ty - tr * 0.3f, ty + tr * 0.2f)) {
            g.fillCircle(x, by, h * 0.02f, 0xFF263238.toInt())
            g.fillCircle(x - h * 0.006f, by - h * 0.007f, h * 0.006f, 0x66FFFFFF)
        }
        // arms
        val sxL = x - tr * 0.85f
        val sxR = x + tr * 0.85f
        val sy = ty - tr * 0.35f
        val ua = h * 0.17f
        val fa = h * 0.16f
        val armW = h * 0.035f
        val twig = 0xFF7A5446.toInt()
        val twigDark = 0xFF3E2723.toInt()
        var mittenX = 0f; var mittenY = 0f; var foreAng = 0f
        val bothX = FloatArray(2); val bothY = FloatArray(2)
        for (side in 0..1) {
            val sx = if (side == 0) sxL else sxR
            var up = pose?.upper?.get(side) ?: if (side == 0) -2.0f else -1.14f
            var fo = pose?.fore?.get(side) ?: if (side == 0) -1.9f else -1.24f
            var ex: Float; var ey: Float; var wx: Float; var wy: Float
            if (handTargets != null) {
                // targets are in screen space; undo the body tilt rotation (and lift) applied to this group
                val tx0 = handTargets[side * 2]; val ty0 = handTargets[side * 2 + 1] - lift
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
            g.line(sx, sy, ex, ey, armW * 1.25f, twigDark)
            g.line(ex, ey, wx, wy, armW * 1.05f, twigDark)
            g.line(sx, sy, ex, ey, armW, twig)
            g.line(ex, ey, wx, wy, armW * 0.85f, twig)
            g.line(ex + (wx - ex) * 0.5f, ey + (wy - ey) * 0.5f, ex + (wx - ex) * 0.5f + h * 0.035f * cos(fo + 0.9f), ey + (wy - ey) * 0.5f - h * 0.035f * sin(fo + 0.9f), armW * 0.5f, twig)
            val mr = h * 0.047f * mittenScale
            // white knitted cuff on the wrist side, then the mitten with a thumb
            val fl = len(wx - ex, wy - ey).coerceAtLeast(1e-3f)
            val dx = (wx - ex) / fl; val dy = (wy - ey) / fl
            g.fillCircle(wx - dx * mr * 0.8f, wy - dy * mr * 0.8f, mr * 0.55f, 0xFFF1F5FA.toInt())
            woolBall(g, wx + dy * mr * 0.55f * (if (side == 0) -1f else 1f), wy - dx * mr * 0.55f * (if (side == 0) -1f else 1f), mr * 0.42f, color)
            woolBall(g, wx, wy, mr, color)
            if (side == propHand) { mittenX = wx; mittenY = wy; foreAng = fo }
            bothX[side] = wx; bothY[side] = wy
        }
        // head (tilts with the player's head)
        val hr = h * 0.15f
        val hy = ty - tr - hr * 0.75f
        val neckY = ty - tr * 0.9f
        val headTilt = pose?.head ?: 0f
        // scarf wrap sits on the neck (does not tilt with the head)
        val sc0 = Colors.lighter(color, 0.2f)
        val sc1 = Colors.darker(color, 0.7f)
        g.fillRoundRectGradient(x - tr * 0.86f, hy + hr * 0.68f, x + tr * 0.86f, hy + hr * 1.08f, hr * 0.2f, sc0, sc1)
        g.line(x - tr * 0.8f, hy + hr * 0.8f, x + tr * 0.8f, hy + hr * 0.8f, hr * 0.05f, 0x55FFFFFF)
        // scarf tail swings with movement
        val swing = sin(t.toFloat() * 2.6f) * 0.12f + tilt * 0.8f
        g.save()
        g.translate(x + tr * 0.4f, hy + hr * 0.95f)
        g.rotate(swing)
        g.fillRoundRectGradient(-tr * 0.15f, 0f, tr * 0.15f, hr * 0.85f, hr * 0.12f, sc0, sc1)
        for (i in 0..3) g.line(-tr * 0.12f + i * tr * 0.08f, hr * 0.85f, -tr * 0.12f + i * tr * 0.08f, hr * 1.0f, hr * 0.04f, sc0)
        g.restore()
        g.save()
        g.translate(x, neckY)
        g.rotate(headTilt)
        g.translate(-x, -neckY)
        snowSphere(g, x, hy, hr)
        face(g, x, hy, hr, expr, t)
        // knitted hat: dome, ribbed brim, glossy pom-pom
        val hatTop = Colors.lighter(color, 0.3f)
        val hatBot = Colors.darker(color, 0.72f)
        g.save(); g.clipRect(x - hr * 1.2f, hy - hr * 1.4f, x + hr * 1.2f, hy - hr * 0.36f)
        g.fillCircleRadial(x, hy - hr * 0.35f, hr * 0.93f, x - hr * 0.35f, hy - hr * 0.95f, hr * 1.4f, hatTop, hatBot)
        g.restore()
        g.fillRoundRectGradient(x - hr * 1.02f, hy - hr * 0.5f, x + hr * 1.02f, hy - hr * 0.18f, hr * 0.13f, Colors.lighter(color, 0.55f), Colors.lighter(color, 0.2f))
        for (i in -6..6) g.line(x + i * hr * 0.15f, hy - hr * 0.46f, x + i * hr * 0.15f, hy - hr * 0.22f, hr * 0.035f, Colors.withAlpha(Colors.darker(color, 0.8f), 0.35f))
        val pom = hr * 0.28f
        for (i in 0 until 7) {
            val a = i * TAU / 7
            g.fillCircle(x + hr * 0.15f + cos(a) * pom * 0.55f, hy - hr * 1.38f + sin(a) * pom * 0.55f, pom * 0.55f, 0xFFE3ECF7.toInt())
        }
        g.fillCircleRadial(x + hr * 0.15f, hy - hr * 1.38f, pom * 0.85f, x + hr * 0.05f, hy - hr * 1.5f, pom * 1.3f, Colors.WHITE, 0xFFD5E2F1.toInt())
        g.restore()
        g.restore()
        if (prop == Prop.GOLF_CLUB || prop == Prop.PUTTER) {
            // held in both hands: the shaft carries on from the arms, away from the shoulders
            val hx = (bothX[0] + bothX[1]) / 2f; val hy = (bothY[0] + bothY[1]) / 2f + lift
            golfClub(g, hx, hy, hx - x, hy - (sy + lift), h, prop == Prop.PUTTER)
        } else if (prop != Prop.NONE) drawProp(g, prop, mittenX, mittenY + lift, foreAng, h, tilt)
        if (shielded) {
            val cy = y - h * 0.55f
            g.fillCircleRadial(x, cy, h * 0.62f, x - h * 0.2f, cy - h * 0.25f, h * 0.9f, 0x1880D8FF, 0x5540C4FF)
            g.strokeCircle(x, cy, h * 0.62f, 6f, 0xCC80D8FF.toInt())
            g.arc(x, cy, h * 0.55f, -2.6f, 0.9f, 5f, 0xAAFFFFFF.toInt())
        }
    }

    private fun face(g: Gfx, x: Float, hy: Float, hr: Float, expr: Expression, t: Double) {
        val blink = (t % 3.7) < 0.12
        val eyeY = hy - hr * 0.12f
        val ink = 0xFF1E272C.toInt()
        for (s in intArrayOf(-1, 1)) {
            val ex = x + s * hr * 0.36f
            if (blink) g.line(ex - hr * 0.1f, eyeY, ex + hr * 0.1f, eyeY, hr * 0.06f, ink)
            else {
                val er = if (expr == Expression.EXCITED) hr * 0.14f else hr * 0.115f
                g.fillCircle(ex, eyeY, er, ink)
                g.fillCircle(ex + er * 0.32f, eyeY - er * 0.35f, er * 0.38f, Colors.WHITE)
                g.fillCircle(ex - er * 0.3f, eyeY + er * 0.35f, er * 0.15f, 0xAAFFFFFF.toInt())
            }
            // eyebrows show the mood
            val browY = eyeY - hr * 0.26f
            when (expr) {
                Expression.FOCUSED -> g.line(ex - s * hr * 0.14f, browY - hr * 0.02f, ex + s * hr * 0.12f, browY + hr * 0.04f, hr * 0.05f, ink)
                Expression.OOPS -> g.line(ex - s * hr * 0.14f, browY + hr * 0.04f, ex + s * hr * 0.12f, browY - hr * 0.04f, hr * 0.05f, ink)
                Expression.EXCITED -> g.arc(ex, browY + hr * 0.06f, hr * 0.13f, PI_F + 0.4f, PI_F - 0.8f, hr * 0.045f, ink)
                Expression.HAPPY -> Unit
            }
            g.glow(x + s * hr * 0.56f, hy + hr * 0.26f, hr * 0.2f, 0x88FF6E8A.toInt())
        }
        // carrot nose
        pts2[0] = x - hr * 0.05f; pts2[1] = hy + hr * 0.01f; pts2[2] = x - hr * 0.05f; pts2[3] = hy + hr * 0.21f; pts2[4] = x + hr * 0.46f; pts2[5] = hy + hr * 0.14f
        g.fillPolygonGradient(pts2, 3, hy, hy + hr * 0.21f, 0xFFFFB74D.toInt(), 0xFFE65100.toInt())
        g.line(x + hr * 0.08f, hy + hr * 0.06f, x + hr * 0.1f, hy + hr * 0.12f, hr * 0.02f, 0x66BF360C)
        val mouthY = hy + hr * 0.45f
        when (expr) {
            Expression.HAPPY -> for (i in 0 until 5) {
                val a = 0.5f + i * (PI_F - 1f) / 4f
                g.fillCircle(x + cos(a) * hr * 0.3f, mouthY - hr * 0.2f + sin(a) * hr * 0.24f, hr * 0.045f, ink)
            }
            Expression.EXCITED -> {
                g.fillOval(x - hr * 0.17f, mouthY - hr * 0.1f, x + hr * 0.17f, mouthY + hr * 0.2f, 0xFF4E1A1A.toInt())
                g.fillOval(x - hr * 0.09f, mouthY + hr * 0.05f, x + hr * 0.09f, mouthY + hr * 0.18f, 0xFFE57373.toInt())
            }
            Expression.OOPS -> { g.line(x - hr * 0.2f, mouthY, x - hr * 0.05f, mouthY - hr * 0.06f, hr * 0.05f, ink); g.line(x - hr * 0.05f, mouthY - hr * 0.06f, x + hr * 0.2f, mouthY + hr * 0.04f, hr * 0.05f, ink) }
            Expression.FOCUSED -> g.line(x - hr * 0.18f, mouthY, x + hr * 0.18f, mouthY, hr * 0.05f, ink)
        }
    }

    /** Golf club gripped at ([hx], [hy]) pointing along ([dx], [dy]): shaft, grip and a wood-or-iron head. */
    fun golfClub(g: Gfx, hx: Float, hy: Float, dx: Float, dy: Float, h: Float, putter: Boolean = false) {
        val l = len(dx, dy).coerceAtLeast(1e-3f)
        val ux = dx / l; val uy = dy / l
        val shaft = h * (if (putter) 0.36f else 0.5f)
        val ex = hx + ux * shaft; val ey = hy + uy * shaft
        g.line(hx - ux * h * 0.04f, hy - uy * h * 0.04f, hx + ux * h * 0.06f, hy + uy * h * 0.06f, h * 0.03f, 0xFF263238.toInt()) // grip
        g.line(hx, hy, ex, ey, h * 0.012f, 0xFFB0BEC5.toInt())
        g.line(hx, hy, ex, ey, h * 0.006f, 0xFFECEFF1.toInt())
        // head, set across the end of the shaft
        val px = -uy; val py = ux
        val hw = h * (if (putter) 0.06f else 0.05f)
        val ht = h * (if (putter) 0.018f else 0.03f)
        pts[0] = ex - px * ht; pts[1] = ey - py * ht
        pts[2] = ex + px * hw * 1.6f - px * ht; pts[3] = ey + py * hw * 1.6f - py * ht
        pts[4] = ex + px * hw * 1.6f + ux * ht * 1.8f; pts[5] = ey + py * hw * 1.6f + uy * ht * 1.8f
        pts[6] = ex + ux * ht * 1.8f; pts[7] = ey + uy * ht * 1.8f
        g.fillPolygonGradient(pts, 4, minOf(pts[1], pts[3], pts[5], pts[7]), maxOf(pts[1], pts[3], pts[5], pts[7]), 0xFFCFD8DC.toInt(), 0xFF546E7A.toInt())
        g.polyline(pts, 4, h * 0.004f, 0xFF37474F.toInt(), closed = true)
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
            Prop.SHIELD, Prop.NONE, Prop.GOLF_CLUB, Prop.PUTTER -> Unit
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
        softShadow(g, s * 0.08f, s * 0.1f, s * 0.55f, s * 0.7f, 0.4f)
        // curled steel runners
        for (sx in floatArrayOf(-0.38f, 0.38f)) {
            g.line(s * sx, -s * 0.55f, s * sx, s * 0.55f, s * 0.07f, 0xFF607D8B.toInt())
            g.line(s * sx - s * 0.015f, -s * 0.55f, s * sx - s * 0.015f, s * 0.55f, s * 0.025f, 0xFFECEFF1.toInt())
            g.arc(s * sx, s * 0.62f, s * 0.08f, -PI_F / 2f, PI_F, s * 0.05f, 0xFF607D8B.toInt())
        }
        g.fillRoundRectGradient(-s * 0.34f, -s * 0.5f, s * 0.34f, s * 0.52f, s * 0.16f, Colors.lighter(color, 0.3f), Colors.darker(color, 0.65f))
        g.strokeRoundRect(-s * 0.34f, -s * 0.5f, s * 0.34f, s * 0.52f, s * 0.16f, s * 0.03f, Colors.GOLD)
        g.fillRoundRectGradient(-s * 0.25f, -s * 0.3f, s * 0.25f, s * 0.4f, s * 0.12f, Colors.darker(color, 0.55f), Colors.darker(color, 0.75f))
        // the rider seen from above: shoulders, hat with bobble
        g.fillCircleRadial(0f, s * 0.08f, s * 0.21f, -s * 0.07f, -s * 0.02f, s * 0.3f, Colors.WHITE, 0xFFB4C9E6.toInt())
        g.fillCircleRadial(0f, s * 0.04f, s * 0.15f, -s * 0.05f, -s * 0.03f, s * 0.22f, Colors.lighter(color, 0.3f), Colors.darker(color, 0.65f))
        g.fillCircle(0f, s * 0.04f, s * 0.06f, Colors.WHITE)
        g.fillCircle(0f, -s * 0.2f, s * 0.06f, 0xFFFF8F00.toInt())
        g.restore()
    }

    fun present(g: Gfx, cx: Float, cy: Float, size: Float, box: Int, ribbon: Int, rot: Float = 0f) {
        g.save(); g.translate(cx, cy); g.rotate(rot)
        val h = size / 2f
        g.fillRoundRect(-h + 3f, -h * 0.8f + 6f, h + 3f, h + 6f, size * 0.08f, 0x44000000)
        g.fillRoundRectGradient(-h, -h * 0.8f, h, h, size * 0.08f, Colors.lighter(box, 0.18f), Colors.darker(box, 0.72f))
        g.fillRoundRectGradient(-h * 1.06f, -h, h * 1.06f, -h * 0.6f, size * 0.05f, Colors.lighter(box, 0.35f), box)
        g.fillRoundRectGradient(-h * 0.14f, -h, h * 0.14f, h, size * 0.02f, Colors.lighter(ribbon, 0.3f), Colors.darker(ribbon, 0.75f))
        g.fillRect(-h * 1.06f, -h * 0.2f, h * 1.06f, h * 0.06f, ribbon)
        g.fillRect(-h * 1.06f, -h * 0.2f, h * 1.06f, -h * 0.12f, Colors.withAlpha(Colors.WHITE, 0.3f))
        g.fillOval(-h * 0.62f, -h * 1.47f, -h * 0.02f, -h * 0.93f, Colors.darker(ribbon, 0.8f))
        g.fillOval(h * 0.02f, -h * 1.47f, h * 0.62f, -h * 0.93f, Colors.darker(ribbon, 0.8f))
        g.fillOval(-h * 0.5f, -h * 1.38f, -h * 0.1f, -h * 1.02f, ribbon)
        g.fillOval(h * 0.1f, -h * 1.38f, h * 0.5f, -h * 1.02f, ribbon)
        g.fillCircle(0f, -h * 1.0f, h * 0.14f, Colors.lighter(ribbon, 0.2f))
        g.fillRoundRect(-h * 0.85f, -h * 0.55f, -h * 0.55f, h * 0.7f, h * 0.1f, 0x1FFFFFFF)
        g.restore()
    }

    fun coal(g: Gfx, cx: Float, cy: Float, size: Float, rot: Float = 0f) {
        g.save(); g.translate(cx, cy); g.rotate(rot)
        val r = size / 2f
        g.glow(0f, 0f, r * 1.7f, 0x55FF1744)
        val n = 9
        for (i in 0 until n) {
            val a = i * TAU / n
            val rr = r * (0.78f + 0.22f * hash01(i * 7 + 3))
            pts[i * 2] = rr * cos(a); pts[i * 2 + 1] = rr * sin(a)
        }
        g.fillPolygonGradient(pts, n, -r, r, 0xFF4A4A4A.toInt(), 0xFF0E0E0E.toInt())
        g.polyline(pts, n, 4f, 0xFF757575.toInt(), closed = true)
        g.fillCircle(-r * 0.35f, -r * 0.4f, r * 0.12f, 0x55FFFFFF)
        // unmistakable warning mark
        g.line(-r * 0.35f, -r * 0.35f, r * 0.35f, r * 0.35f, r * 0.2f, 0xFF7F0000.toInt())
        g.line(r * 0.35f, -r * 0.35f, -r * 0.35f, r * 0.35f, r * 0.2f, 0xFF7F0000.toInt())
        g.line(-r * 0.35f, -r * 0.35f, r * 0.35f, r * 0.35f, r * 0.14f, 0xFFFF5252.toInt())
        g.line(r * 0.35f, -r * 0.35f, -r * 0.35f, r * 0.35f, r * 0.14f, 0xFFFF5252.toInt())
        g.restore()
    }

    /** Glass bauble: metal cap, lit sphere, specular highlight and a soft reflection. */
    fun ornament(g: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        g.fillRoundRectGradient(cx - r * 0.27f, cy - r * 1.27f, cx + r * 0.27f, cy - r * 0.83f, r * 0.08f, 0xFFECEFF1.toInt(), 0xFF90A4AE.toInt())
        g.strokeCircle(cx, cy - r * 1.38f, r * 0.13f, r * 0.06f, 0xFFB0BEC5.toInt())
        g.fillCircleRadial(cx, cy, r, cx - r * 0.35f, cy - r * 0.4f, r * 1.55f, Colors.lighter(color, 0.45f), Colors.darker(color, 0.55f))
        g.fillCircle(cx - r * 0.38f, cy - r * 0.4f, r * 0.24f, Colors.withAlpha(Colors.WHITE, 0.75f))
        g.fillCircle(cx - r * 0.18f, cy - r * 0.6f, r * 0.08f, Colors.withAlpha(Colors.WHITE, 0.6f))
        g.arc(cx, cy, r * 0.74f, 0.35f, 1.1f, r * 0.1f, Colors.withAlpha(Colors.WHITE, 0.3f))
    }

    fun christmasTree(g: Gfx, cx: Float, baseY: Float, h: Float, lights: Boolean, t: Double) {
        softShadow(g, cx, baseY + h * 0.03f, h * 0.38f, h * 0.05f, 0.5f)
        g.fillRoundRectGradient(cx - h * 0.06f, baseY - h * 0.1f, cx + h * 0.06f, baseY, 4f, 0xFF795548.toInt(), 0xFF4E342E.toInt())
        g.fillRoundRectGradient(cx - h * 0.15f, baseY - h * 0.03f, cx + h * 0.15f, baseY + h * 0.05f, 8f, 0xFFE53935.toInt(), 0xFF8E1B1B.toInt())
        g.fillRect(cx - h * 0.015f, baseY - h * 0.03f, cx + h * 0.015f, baseY + h * 0.05f, Colors.GOLD)
        for (tier in 0..3) {
            val w = h * (0.42f - tier * 0.085f)
            val y0 = baseY - h * 0.08f - tier * h * 0.2f
            pts[0] = cx - w; pts[1] = y0; pts[2] = cx + w; pts[3] = y0; pts[4] = cx; pts[5] = y0 - h * 0.34f
            val c = if (tier % 2 == 0) Colors.PINE else 0xFF34A065.toInt()
            g.fillPolygonGradient(pts, 3, y0 - h * 0.34f, y0, Colors.lighter(c, 0.2f), Colors.darker(c, 0.62f))
            // garland
            g.arc(cx, y0 - h * 0.34f - w * 0.6f, w * 1.15f, 1.15f, 0.85f, h * 0.012f, 0xCCFFD54F.toInt())
        }
        if (lights) for (i in 0 until 18) {
            val u = hash01(i * 13 + 2)
            val v = hash01(i * 29 + 5)
            val ty = baseY - h * 0.12f - v * h * 0.72f
            val halfW = h * 0.4f * (1f - v * 0.9f)
            val lx = cx + (u * 2f - 1f) * halfW * 0.8f
            val c = intArrayOf(0xFFFFD740.toInt(), 0xFFFF5252.toInt(), 0xFF40C4FF.toInt())[i % 3]
            val tw = 0.6f + 0.4f * sin((t * 3 + i).toFloat())
            g.glow(lx, ty, 20f, Colors.withAlpha(c, 0.55f * tw))
            g.fillCircle(lx, ty, 4.5f, Colors.lighter(c, 0.4f * tw))
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
