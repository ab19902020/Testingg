package com.clrg.core.render

import com.clrg.core.math.Rng
import com.clrg.core.math.clamp01
import com.clrg.core.math.easeOutBack
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight particle bursts for hits, catches and celebrations. Capped so effects never obscure play; the
 * "reduced effects" accessibility setting lowers the count and disables screen flashes.
 */
class Particles(private val maxParticles: Int = 400) {
    enum class Kind { SPARK, CONFETTI, PUFF, STAR, RING }

    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var maxLife: Float,
                    var size: Float, var color: Int, var kind: Kind, var rot: Float, var vr: Float, var gravity: Float)

    private val list = ArrayList<P>()
    private val rng = Rng(42)
    var reduced = false

    fun clear() = list.clear()
    val count: Int get() = list.size

    fun burst(x: Float, y: Float, color: Int, n: Int = 24, speed: Float = 420f, kind: Kind = Kind.SPARK, size: Float = 9f, gravity: Float = 600f) {
        val count = if (reduced) n / 3 else n
        repeat(count) {
            if (list.size >= maxParticles) list.removeAt(0)
            val a = rng.range(0f, 6.2832f)
            val s = speed * rng.range(0.35f, 1f)
            val life = rng.range(0.5f, 1.1f)
            list.add(P(x, y, cos(a) * s, sin(a) * s - speed * 0.3f, life, life, size * rng.range(0.6f, 1.3f), color, kind, rng.range(0f, 6f), rng.range(-8f, 8f), gravity))
        }
    }

    /** Expanding impact ring (hits, catches, saves). */
    fun ring(x: Float, y: Float, color: Int, size: Float = 90f) {
        if (list.size >= maxParticles) list.removeAt(0)
        list.add(P(x, y, 0f, 0f, 0.45f, 0.45f, size, color, Kind.RING, 0f, 0f, 0f))
    }

    fun confetti(x0: Float, x1: Float, y: Float, n: Int = 80) {
        val colors = PlayerColors.all
        val count = if (reduced) n / 4 else n
        repeat(count) {
            if (list.size >= maxParticles) list.removeAt(0)
            val life = rng.range(1.6f, 2.8f)
            list.add(P(rng.range(x0, x1), y - rng.range(0f, 80f), rng.range(-60f, 60f), rng.range(40f, 180f), life, life,
                rng.range(10f, 18f), colors[rng.nextInt(colors.size)], Kind.CONFETTI, rng.range(0f, 6f), rng.range(-6f, 6f), 120f))
        }
    }

    fun update(dt: Float) {
        val it = list.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.vy += p.gravity * dt
            p.vx *= (1f - 0.8f * dt)
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vr * dt
        }
    }

    fun render(g: Gfx) {
        for (p in list) {
            val a = clamp01(p.life / p.maxLife * 1.5f)
            val c = Colors.withAlpha(p.color, a)
            when (p.kind) {
                Kind.SPARK -> {
                    if (!reduced) g.glow(p.x, p.y, p.size * 2.6f, Colors.withAlpha(p.color, a * 0.45f))
                    g.fillCircle(p.x, p.y, p.size * (0.4f + 0.6f * a), Colors.lerp(c, Colors.withAlpha(Colors.WHITE, a), 0.35f))
                }
                Kind.PUFF -> g.glow(p.x, p.y, p.size * (2.4f - 0.8f * a), Colors.withAlpha(p.color, a * 0.85f))
                Kind.STAR -> {
                    if (!reduced) g.glow(p.x, p.y, p.size * 2.2f, Colors.withAlpha(p.color, a * 0.4f))
                    Draw.star(g, p.x, p.y, p.size, c, p.rot)
                }
                Kind.CONFETTI -> {
                    g.save(); g.translate(p.x, p.y); g.rotate(p.rot)
                    val flip = kotlin.math.abs(sin(p.rot * 2))
                    g.fillRect(-p.size / 2, -p.size / 4 * (0.4f + 0.6f * flip), p.size / 2, p.size / 4, Colors.lerp(Colors.darker(c, 0.7f), c, flip))
                    g.restore()
                }
                Kind.RING -> {
                    val k = 1f - p.life / p.maxLife
                    val rr = p.size * (0.25f + 0.75f * com.clrg.core.math.easeOutCubic(k))
                    g.strokeCircle(p.x, p.y, rr, 10f * (1f - k) + 2f, Colors.withAlpha(p.color, (1f - k) * 0.9f))
                    if (!reduced) g.glow(p.x, p.y, rr * 1.1f, Colors.withAlpha(p.color, (1f - k) * 0.25f))
                }
            }
        }
    }
}

/** Floating text popups ("+10", "SAVE!", "OUT!"): the visual equivalent of every important sound cue. */
class Popups {
    private class Pop(val text: String, val x: Float, val y: Float, val color: Int, val size: Float, var t: Float, val dur: Float, val rise: Float)
    private val list = ArrayList<Pop>()

    fun add(text: String, x: Float, y: Float, color: Int = Colors.WHITE, size: Float = 56f, dur: Float = 1.0f, rise: Float = 90f) {
        if (list.size > 24) list.removeAt(0)
        list.add(Pop(text, x, y, color, size, 0f, dur, rise))
    }

    fun clear() = list.clear()

    fun update(dt: Float) {
        val it = list.iterator()
        while (it.hasNext()) { val p = it.next(); p.t += dt; if (p.t >= p.dur) it.remove() }
    }

    fun render(g: Gfx) {
        for (p in list) {
            val k = p.t / p.dur
            val pop = easeOutBack(clamp01(k * 4f))
            val a = if (k > 0.7f) (1f - k) / 0.3f else 1f
            g.save()
            g.setAlpha(g.getAlpha() * a)
            g.text(p.text, p.x, p.y - p.rise * k, p.size * (0.6f + 0.4f * pop), p.color, outline = 0xCC000000.toInt(), outlineWidth = p.size * 0.12f)
            g.restore()
        }
    }
}

/** Short screen-space shake, disabled when reduced effects is on. */
class Shake {
    private var amount = 0f
    private var t = 0f
    var reduced = false
    fun kick(a: Float) { if (!reduced) amount = maxOf(amount, a) }
    fun update(dt: Float) { t += dt; amount = maxOf(0f, amount - dt * 60f) }
    val dx: Float get() = if (amount <= 0f) 0f else sin(t * 61f) * amount
    val dy: Float get() = if (amount <= 0f) 0f else cos(t * 47f) * amount
}
