package com.clrg.core.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

const val TAU = (2.0 * PI).toFloat()
const val PI_F = PI.toFloat()

fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
fun clamp01(v: Float): Float = clamp(v, 0f, 1f)
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
fun invLerp(a: Float, b: Float, v: Float): Float = if (a == b) 0f else (v - a) / (b - a)
fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3f - 2f * t)
}
fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}
fun easeOutCubic(t: Float): Float { val u = 1f - clamp01(t); return 1f - u * u * u }
fun easeInOutSine(t: Float): Float = (-(kotlin.math.cos(PI_F * clamp01(t)) - 1f) / 2f)

fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
    val dx = x1 - x0
    val dy = y1 - y0
    return sqrt(dx * dx + dy * dy)
}

/** Wraps an angle in radians into [-PI, PI). */
fun normalizeRadians(a: Float): Float = (a - TAU * floor((a + PI_F) / TAU))

/** Smallest absolute difference between two angles in radians. */
fun angleDiff(a: Float, b: Float): Float = abs(normalizeRadians(a - b))

/** Exponential smoothing factor for a time constant, frame-rate independent. */
fun expSmoothing(dt: Float, timeConstant: Float): Float =
    if (timeConstant <= 0f) 1f else (1f - exp(-(dt / timeConstant).toDouble())).toFloat()

fun approach(current: Float, target: Float, maxDelta: Float): Float =
    if (current < target) minOf(current + maxDelta, target) else maxOf(current - maxDelta, target)

/** Simple mutable 2D vector used by game simulation code. */
class Vec2(var x: Float = 0f, var y: Float = 0f) {
    fun set(nx: Float, ny: Float): Vec2 { x = nx; y = ny; return this }
    fun set(o: Vec2): Vec2 { x = o.x; y = o.y; return this }
    fun len(): Float = sqrt(x * x + y * y)
    fun copy(): Vec2 = Vec2(x, y)
    override fun toString(): String = "(%.3f, %.3f)".format(x, y)
}

/** Axis-aligned rectangle in virtual screen units. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
    fun inset(d: Float): Rect = Rect(left + d, top + d, right - d, bottom - d)
    fun inset(dx: Float, dy: Float): Rect = Rect(left + dx, top + dy, right - dx, bottom - dy)
    fun offset(dx: Float, dy: Float): Rect = Rect(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun ofCenter(cx: Float, cy: Float, w: Float, h: Float) = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        fun ofSize(x: Float, y: Float, w: Float, h: Float) = Rect(x, y, x + w, y + h)
    }
}
