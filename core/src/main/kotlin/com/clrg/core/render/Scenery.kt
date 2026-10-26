package com.clrg.core.render

import com.clrg.core.math.PI_F
import com.clrg.core.math.Rect
import kotlin.math.cos
import kotlin.math.sin

/**
 * Detailed scenery shared by the games, built on cached layers ([Gfx.layer]): each piece is drawn once in full detail
 * (hundreds of shapes, soft depth blur, a fine grain) and then costs about one image per frame. Only things that move
 * (drifting clouds, twinkling stars, a cheering crowd) are drawn live, and those use small cached pictures too.
 *
 * Layer keys must be unique per game and per place on screen; callers pass a [key] prefix.
 */
object Scenery {
    private val pts = FloatArray(512)

    // ------------------------------------------------------------------ daytime (sports)

    /** Clear day sky down to [horizon]: deep blue overhead to a warm haze, the sun with a soft halo. Static. */
    fun daySky(g: Gfx, key: String, l: Float, t: Float, r: Float, horizon: Float, sunX: Float = l + (r - l) * 0.78f) {
        g.layer("$key.sky", l, t, r, horizon + 4f, res = 0.5f, blur = 2f) { lg ->
            val mid = t + (horizon - t) * 0.6f
            lg.gradientRect(l, t, r, mid, 0xFF2266BF.toInt(), 0xFF7DB8EE.toInt())
            lg.gradientRect(l, mid - 1f, r, horizon + 4f, 0xFF7DB8EE.toInt(), 0xFFEAF2F2.toInt())
            val sunY = t + (horizon - t) * 0.22f
            lg.fillRectRadial(l, t, r, horizon + 4f, sunX, sunY, (r - l) * 0.55f, 0x40FFF1C8, 0x00FFF1C8)
            lg.glow(sunX, sunY, 260f, 0x66FFF6D8)
            lg.glow(sunX, sunY, 110f, 0xAAFFFBEA.toInt())
            lg.fillCircle(sunX, sunY, 44f, 0xFFFFFDF4.toInt())
            // thin high cirrus streaks
            for (i in 0 until 9) {
                val cx = l + (r - l) * Draw.hash01(i * 13 + 5)
                val cy = t + (horizon - t) * (0.08f + 0.3f * Draw.hash01(i * 7 + 1))
                val len = 160f + 220f * Draw.hash01(i * 3 + 2)
                lg.fillOval(cx - len, cy - 7f, cx + len, cy + 7f, 0x1FFFFFFF)
                lg.fillOval(cx - len * 0.6f, cy - 3f, cx + len * 0.7f, cy + 4f, 0x26FFFFFF)
            }
        }
    }

    /** Soft, shaded clouds drifting slowly across the sky (each a small cached picture). */
    fun clouds(g: Gfx, l: Float, t: Float, r: Float, horizon: Float, time: Double, count: Int = 6, speed: Float = 1f) {
        val w = r - l
        g.save(); g.clipRect(l, t, r, horizon)
        for (i in 0 until count) {
            val kind = i % 3
            val size = 95f + 35f * kind
            val sp = (5f + i * 2.2f) * speed
            val cx = l + ((i * 0.37f * w + time.toFloat() * sp) % (w + size * 2.4f)) - size * 1.2f
            val cy = t + (horizon - t) * (0.12f + 0.12f * ((i * 2) % 3)) + 30f
            g.save(); g.translate(cx, cy)
            g.layer("cloud.$kind", -size * 1.4f, -size * 1.0f, size * 1.4f, size * 0.7f, res = 0.6f, blur = 2.5f) { lg -> cloud(lg, size, kind) }
            g.restore()
        }
        g.restore()
    }

    private fun cloud(g: Gfx, size: Float, kind: Int) {
        val lit = 0xFFFFFFFF.toInt(); val shade = 0xFFB9CCE0.toInt()
        // flat, shaded underside
        g.fillOval(-size, -size * 0.12f, size, size * 0.3f, 0xFFC8D7E6.toInt())
        // puffs: back ones shaded, front ones lit from the upper left
        val n = 7 + kind
        for (p in 0 until 2) for (i in 0 until n) {
            val u = i / (n - 1f)
            val px = -size * 0.82f + u * size * 1.64f
            val bump = sin(u * PI_F)
            val pr = size * (0.24f + 0.2f * bump) * (0.8f + 0.4f * Draw.hash01(i * 5 + kind))
            val py = -size * (0.05f + 0.32f * bump) + (if (p == 0) -size * 0.04f else size * 0.06f)
            g.fillCircleRadial(px, py, pr, px - pr * 0.4f, py - pr * 0.5f, pr * 1.6f, if (p == 0) Colors.lerp(lit, shade, 0.25f) else lit, shade)
        }
        g.fillOval(-size * 0.9f, size * 0.02f, size * 0.9f, size * 0.32f, 0xCCC5D5E5.toInt())
    }

    /**
     * Mountains, hills and a tree line along [horizon], in two cached layers: the far ranges hazy and softly out of
     * focus, the near hills and trees sharp.
     */
    fun landscape(g: Gfx, key: String, l: Float, r: Float, horizon: Float, seed: Int = 0) {
        val w = r - l
        g.layer("$key.far", l, horizon - 300f, r, horizon + 6f, res = 0.5f, blur = 3f) { lg ->
            for (range in 0..1) {
                val n = ((w / (if (range == 0) 130f else 95f)).toInt()).coerceAtLeast(5)
                var k = 0
                for (i in 0..n) {
                    val x = l + w * i / n
                    val hgt = (if (range == 0) 175f else 115f) + (if (range == 0) 70f else 45f) * sin(i * 1.7f + seed * 0.7f + range * 2f) + 30f * sin(i * 3.3f + seed + range)
                    pts[k++] = x; pts[k++] = horizon - hgt
                }
                val crest = k / 2
                pts[k++] = r; pts[k++] = horizon + 6f; pts[k++] = l; pts[k++] = horizon + 6f
                val top = if (range == 0) 0xFFA9C2DA.toInt() else 0xFF8AA9C4.toInt()
                val bottom = if (range == 0) 0xFFD3E1EC.toInt() else 0xFFB5CAD9.toInt()
                lg.fillPolygonGradient(pts, k / 2, horizon - 300f, horizon, top, bottom)
                // shaded faces away from the sun, snow on the high peaks
                for (i in 1 until crest - 1) {
                    val px = pts[i * 2]; val py = pts[i * 2 + 1]
                    if (py < pts[i * 2 - 1] && py < pts[i * 2 + 3]) {
                        val nx = pts[i * 2 + 2]; val ny = pts[i * 2 + 3]
                        val f = floatArrayOf(px, py, nx, ny, nx - (nx - px) * 0.2f, horizon, px + (nx - px) * 0.15f, horizon)
                        lg.fillPolygonGradient(f, 4, py, horizon, Colors.withAlpha(0xFF52708F.toInt(), if (range == 0) 0.22f else 0.3f), 0x0052708F)
                        if (horizon - py > (if (range == 0) 200f else 150f)) {
                            val sn = floatArrayOf(px, py, px + 30f, py + 34f, px + 10f, py + 28f, px - 8f, py + 40f, px - 28f, py + 30f)
                            lg.fillPolygon(sn, 5, 0xE6F7FAFF.toInt())
                        }
                    }
                }
            }
            // haze over the distance
            lg.gradientRect(l, horizon - 120f, r, horizon + 6f, 0x00EAF2F2, 0x99EAF2F2.toInt())
        }
        g.layer("$key.near", l, horizon - 90f, r, horizon + 14f) { lg ->
            val n = 30
            var k = 0
            for (i in 0..n) {
                val x = l + w * i / n
                val hgt = 38f + 26f * sin(i * 0.9f + seed) + 14f * sin(i * 2.3f + seed * 2)
                pts[k++] = x; pts[k++] = horizon - hgt
            }
            pts[k++] = r; pts[k++] = horizon + 4f; pts[k++] = l; pts[k++] = horizon + 4f
            lg.fillPolygonGradient(pts, k / 2, horizon - 70f, horizon, 0xFF7FA98A.toInt(), 0xFF5C8A68.toInt())
            // tree line: round trees and pines of different sizes and greens, lit from the left, with shadows
            var x = l - 10f
            var i = 0
            while (x < r + 20f) {
                val hk = Draw.hash01(i * 7 + seed * 31)
                val h = 16f + 20f * hk
                val green = Colors.lerp(0xFF2E5B38.toInt(), 0xFF4C7F4A.toInt(), Draw.hash01(i * 11 + seed))
                if (hk > 0.6f) {
                    Draw.tri(lg, x - 11f, horizon + 3f, x + 11f, horizon + 3f, x, horizon - h - 16f, Colors.darker(green, 0.85f))
                    Draw.tri(lg, x - 11f, horizon + 3f, x, horizon + 3f, x, horizon - h - 16f, Colors.lighter(green, 0.1f))
                } else {
                    lg.fillOval(x - 16f, horizon - h - 12f, x + 16f, horizon + 3f, green)
                    lg.fillOval(x - 12f, horizon - h - 11f, x + 6f, horizon - h + 6f, Colors.lighter(green, 0.18f))
                    lg.fillOval(x + 2f, horizon - h - 2f, x + 15f, horizon + 2f, Colors.darker(green, 0.82f))
                }
                x += 13f + 9f * Draw.hash01(i * 3 + 1); i++
            }
            lg.gradientRect(l, horizon - 6f, r, horizon + 14f, 0x00213A27, 0x66213A27)
        }
    }

    /**
     * A crowd in the stands, cheering: three cached pictures of the same people in slightly different poses, shown
     * in turn (a lively crowd for the cost of one image a frame). [shade] darkens it (0 = lit, 1 = black).
     */
    fun crowd(g: Gfx, key: String, l: Float, t: Float, r: Float, b: Float, time: Double, rows: Int = 4, shade: Float = 0.2f,
              blur: Float = 0f, seat: Int = 0xFF2A3346.toInt()) {
        val frame = ((time * 5.0).toLong() % 3).toInt()
        g.layer("$key.crowd$frame", l, t, r, b, res = if (blur > 0f) 0.5f else 0.75f, blur = blur) { lg -> crowdFrame(lg, l, t, r, b, rows, shade, frame, seat) }
    }

    private val CROWD = intArrayOf(0xFFE57373.toInt(), 0xFF64B5F6.toInt(), 0xFFFFD54F.toInt(), 0xFF81C784.toInt(), 0xFFBA68C8.toInt(),
        0xFFFFB74D.toInt(), 0xFFECEFF1.toInt(), 0xFF4DD0E1.toInt(), 0xFFF06292.toInt(), 0xFF90A4AE.toInt())
    private val SKINS = intArrayOf(0xFFF6D2B4.toInt(), 0xFFE0AC85.toInt(), 0xFFC68A62.toInt(), 0xFF8D5A3B.toInt())

    private fun crowdFrame(g: Gfx, l: Float, t: Float, r: Float, b: Float, rows: Int, shade: Float, frame: Int, seat: Int) {
        g.gradientRect(l, t, r, b, Colors.darker(seat, 0.7f), seat)
        val rowH = (b - t) / rows
        for (row in 0 until rows) {
            val depth = row / (rows - 1f).coerceAtLeast(1f) // 0 = back row
            val s = rowH / 46f
            val dark = (shade + (1f - depth) * 0.25f).coerceIn(0f, 0.95f)
            val baseY = t + rowH * (row + 1f)
            // seat backs
            g.fillRect(l, baseY - rowH * 0.28f, r, baseY, Colors.darker(seat, 0.85f - 0.1f * depth))
            var x = l + (row % 2) * 11f * s
            var i = row * 53 + 7
            while (x < r + 12f) {
                val id = i * 31 + row
                val bob = when ((id + frame) % 3) { 0 -> 0f; 1 -> -3f * s; else -> -1.5f * s }
                val arms = Draw.hash01(id * 7) > 0.78f && (id + frame) % 3 == 1
                val shirt = Colors.lerp(CROWD[id % CROWD.size], 0xFF0A0E18.toInt(), dark)
                val skin = Colors.lerp(SKINS[id % SKINS.size], 0xFF0A0E18.toInt(), dark)
                val hy = baseY - rowH * 0.78f + bob
                if (arms) {
                    g.line(x - 8f * s, hy + 9f * s, x - 13f * s, hy - 12f * s, 4.5f * s, shirt)
                    g.line(x + 8f * s, hy + 9f * s, x + 13f * s, hy - 12f * s, 4.5f * s, shirt)
                    g.fillCircle(x - 13f * s, hy - 13f * s, 3.2f * s, skin); g.fillCircle(x + 13f * s, hy - 13f * s, 3.2f * s, skin)
                }
                g.fillRoundRect(x - 10f * s, hy + 5f * s, x + 10f * s, baseY, 6f * s, shirt)
                g.fillCircle(x, hy, 7.5f * s, skin)
                if (Draw.hash01(id * 3) > 0.5f) g.fillOval(x - 7.5f * s, hy - 8f * s, x + 7.5f * s, hy - 1f * s, Colors.lerp(0xFF3E2723.toInt(), 0xFF0A0E18.toInt(), dark))
                if (Draw.hash01(id * 5) > 0.85f) g.fillRect(x - 12f * s, hy + 12f * s, x + 12f * s, hy + 18f * s, Colors.lerp(CROWD[(id + 3) % CROWD.size], 0xFF0A0E18.toInt(), dark)) // scarf / flag
                x += (21f + 5f * Draw.hash01(id)) * s; i++
            }
        }
        // the top rows fade into the dark of the stand
        g.gradientRect(l, t, r, t + (b - t) * 0.45f, Colors.withAlpha(Colors.darker(seat, 0.5f), 0.7f), Colors.withAlpha(Colors.darker(seat, 0.5f), 0f))
    }

    /**
     * A small looping animation from [frames] cached pictures shown in turn at [fps] (crowds, bobbing spectators):
     * the cost of one image a frame. [draw] gets the frame number.
     */
    fun frames(g: Gfx, key: String, l: Float, t: Float, r: Float, b: Float, time: Double, frames: Int = 3, fps: Double = 5.0,
               res: Float = 0.75f, draw: (Gfx, Int) -> Unit) {
        val f = ((time * fps).toLong() % frames).toInt()
        g.layer("$key.f$f", l, t, r, b, res = res) { lg -> draw(lg, f) }
    }

    // ------------------------------------------------------------------ winter night (Christmas)

    /** Night sky with a dusting of stars and a faint band of the Milky Way (cached); a few bright stars twinkle live. */
    fun nightSky(g: Gfx, key: String, rr: Rect, time: Double, twinkle: Boolean = true) {
        g.layer("$key.night", rr.left, rr.top, rr.right, rr.bottom, res = 0.75f) { lg ->
            val mid = rr.top + rr.height * 0.55f
            lg.gradientRect(rr.left, rr.top, rr.right, mid, 0xFF020818.toInt(), 0xFF0B1E4A.toInt())
            lg.gradientRect(rr.left, mid - 1f, rr.right, rr.bottom, 0xFF0B1E4A.toInt(), 0xFF2A5585.toInt())
            // the Milky Way: a soft diagonal band
            lg.save(); lg.translate(rr.cx, rr.top + rr.height * 0.3f); lg.rotate(-0.35f)
            lg.fillRectRadial(-rr.width, -rr.height * 0.12f, rr.width, rr.height * 0.12f, 0f, 0f, rr.width * 0.6f, 0x223A5FA8, 0x003A5FA8)
            for (i in 0 until 160) {
                val x = (Draw.hash01(i * 19 + 2) - 0.5f) * rr.width * 1.4f
                val y = (Draw.hash01(i * 23 + 9) - 0.5f) * rr.height * 0.16f * (0.4f + Draw.hash01(i))
                lg.fillCircle(x, y, 0.7f + Draw.hash01(i * 5) * 0.8f, 0x66E3ECFF)
            }
            lg.restore()
            for (i in 0 until 260) {
                val x = rr.left + Draw.hash01(i * 7 + 1) * rr.width
                val y = rr.top + Draw.hash01(i * 13 + 5) * rr.height * 0.68f
                val m = Draw.hash01(i * 3)
                val c = if (m > 0.93f) 0xFFFFE8C8.toInt() else if (m > 0.86f) 0xFFC8DCFF.toInt() else Colors.WHITE
                lg.fillCircle(x, y, 0.6f + 1.5f * m * m, Colors.withAlpha(c, 0.3f + 0.6f * Draw.hash01(i * 11)))
            }
            lg.fillRectRadial(rr.left, rr.top + rr.height * 0.5f, rr.right, rr.bottom, rr.cx, rr.bottom, rr.width * 0.7f, 0x334C7FB8, 0x004C7FB8)
        }
        if (!twinkle) return
        g.save(); g.setBlend(Blend.ADD)
        for (i in 0 until 14) {
            val x = rr.left + Draw.hash01(i * 29 + 4) * rr.width
            val y = rr.top + Draw.hash01(i * 31 + 6) * rr.height * 0.55f
            val tw = 0.5f + 0.5f * sin((time * (0.7 + Draw.hash01(i) * 1.6) + i * 1.9).toFloat())
            g.glow(x, y, 14f + 8f * tw, Colors.withAlpha(0xFFBFD6FF.toInt(), 0.35f * tw))
            val f = 7f + 6f * tw
            g.line(x - f, y, x + f, y, 1.3f, Colors.withAlpha(Colors.WHITE, 0.5f * tw))
            g.line(x, y - f, x, y + f, 1.3f, Colors.withAlpha(Colors.WHITE, 0.5f * tw))
        }
        // an occasional shooting star
        val cycle = (time % 13.0).toFloat()
        if (cycle < 0.9f) {
            val k = cycle / 0.9f
            val sx = rr.left + rr.width * (0.15f + 0.5f * Draw.hash01((time / 13.0).toInt()))
            val sy = rr.top + rr.height * 0.08f
            val hx = sx + 420f * k; val hy = sy + 150f * k
            for (j in 0 until 6) {
                val q = j / 6f
                g.line(hx - 70f * (q + 0.16f), hy - 25f * (q + 0.16f), hx - 70f * q, hy - 25f * q, 3f * (1f - q), Colors.withAlpha(Colors.WHITE, (1f - q) * (1f - k) * 0.9f))
            }
        }
        g.restore()
    }

    /** Snowy hills with a frosted pine forest in two depths (cached, the far one softly blurred). */
    fun winterForest(g: Gfx, key: String, l: Float, r: Float, baseY: Float, bottom: Float, seed: Int = 0) {
        g.layer("$key.forestFar", l, baseY - 260f, r, baseY + 40f, res = 0.5f, blur = 2.5f) { lg ->
            Draw.pineRow(lg, l - 40f, r, baseY - 30f, 0xFF143A36.toInt(), 105f, 3 + seed)
            lg.gradientRect(l, baseY - 120f, r, baseY + 40f, 0x002A5585, 0x882A5585.toInt())
        }
        g.layer("$key.forestNear", l, baseY - 220f, r, bottom) { lg ->
            Draw.pineRow(lg, l, r, baseY + 12f, 0xFF1B4434.toInt(), 135f, 1 + seed)
            Draw.snowHills(lg, Rect(l, 0f, r, bottom), baseY + 60f)
            // moonlit sparkle on the snow
            for (i in 0 until 60) {
                val x = l + Draw.hash01(i * 37 + seed) * (r - l)
                val y = baseY + 70f + Draw.hash01(i * 41 + 3) * (bottom - baseY - 80f).coerceAtLeast(10f)
                lg.fillCircle(x, y, 1.2f + Draw.hash01(i) * 1.4f, 0x99FFFFFF.toInt())
            }
        }
    }

    // ------------------------------------------------------------------ lighting helpers

    /** A light that adds brightness (a lamp, a spark, a muzzle flash): a soft halo and a hot core. */
    fun light(g: Gfx, x: Float, y: Float, r: Float, color: Int, core: Float = 0.25f) {
        g.save(); g.setBlend(Blend.ADD)
        g.glow(x, y, r, color)
        if (core > 0f) g.glow(x, y, r * core, Colors.lighter(color, 0.6f) or 0xFF000000.toInt())
        g.restore()
    }

    /** A soft beam of light from ([x], [y0]) spreading to [spread] wide at [y1] (stage and stadium lights). */
    fun beam(g: Gfx, x: Float, y0: Float, x1: Float, y1: Float, w0: Float, spread: Float, color: Int) {
        val q = floatArrayOf(x - w0 / 2f, y0, x + w0 / 2f, y0, x1 + spread / 2f, y1, x1 - spread / 2f, y1)
        g.save(); g.setBlend(Blend.ADD)
        g.fillPolygonGradient(q, 4, y0, y1, color, color and 0x00FFFFFF)
        g.restore()
    }

    /** Sun / lamp glare: a few soft rings along the line through the screen centre (cheap lens flare). */
    fun flare(g: Gfx, sx: Float, sy: Float, cx: Float, cy: Float, strength: Float = 1f) {
        g.save(); g.setBlend(Blend.ADD)
        val dx = cx - sx; val dy = cy - sy
        val rings = floatArrayOf(0.5f, 0.85f, 1.25f, 1.6f)
        val sizes = floatArrayOf(40f, 22f, 60f, 30f)
        val cols = intArrayOf(0x22FFE0B2, 0x2280D8FF, 0x18B388FF, 0x22FFF59D)
        for (i in rings.indices) g.glow(sx + dx * rings[i], sy + dy * rings[i], sizes[i] * strength, cols[i])
        g.restore()
    }
}
