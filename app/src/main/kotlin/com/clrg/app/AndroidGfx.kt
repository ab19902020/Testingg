package com.clrg.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.clrg.core.render.Align
import com.clrg.core.render.Gfx
import com.clrg.core.render.ImageRef

/** Camera preview bitmap handed to the renderer. */
class AndroidImage(val bitmap: Bitmap) : ImageRef {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/**
 * [Gfx] on a (hardware-accelerated) android.graphics.Canvas. The caller scales the canvas so that the virtual
 * height is 1080 units. Paints, paths and shaders are reused so drawing does not allocate per frame.
 */
class AndroidGfx : Gfx {
    private lateinit var canvas: Canvas
    override var width = 1920f
        private set
    override var height = 1080f
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val bold: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL) ?: Typeface.DEFAULT_BOLD
    private val regular: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) ?: Typeface.DEFAULT
    private var alpha = 1f
    private val alphaStack = FloatArray(64)
    private var depth = 0
    private val glowCache = object : LinkedHashMap<Int, RadialGradient>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RadialGradient>?) = size > 96
    }
    private val gradCache = object : LinkedHashMap<Long, LinearGradient>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LinearGradient>?) = size > 96
    }

    fun begin(c: Canvas, virtualWidth: Float, virtualHeight: Float) {
        canvas = c
        width = virtualWidth
        height = virtualHeight
        alpha = 1f
        depth = 0
    }

    private fun col(c: Int): Int {
        if (alpha >= 0.999f) return c
        val a = (((c ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (c and 0xFFFFFF)
    }

    override fun save() {
        canvas.save()
        if (depth < alphaStack.size) alphaStack[depth] = alpha
        depth++
    }

    override fun restore() {
        canvas.restore()
        depth--
        if (depth in 0 until alphaStack.size) alpha = alphaStack[depth]
    }

    override fun translate(dx: Float, dy: Float) = canvas.translate(dx, dy)
    override fun scale(sx: Float, sy: Float) = canvas.scale(sx, sy)
    override fun rotate(radians: Float) = canvas.rotate(Math.toDegrees(radians.toDouble()).toFloat())
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) { canvas.clipRect(l, t, r, b) }
    override fun setAlpha(a: Float) { alpha = a.coerceIn(0f, 1f) }
    override fun getAlpha(): Float = alpha

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = col(color); canvas.drawRect(l, t, r, b, fill)
    }

    override fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        fill.color = col(color); canvas.drawRoundRect(l, t, r, b, radius, radius, fill)
    }

    override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, color: Int) {
        stroke.color = col(color); stroke.strokeWidth = width; canvas.drawRoundRect(l, t, r, b, radius, radius, stroke)
    }

    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = col(color); canvas.drawCircle(cx, cy, r, fill)
    }

    override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) {
        stroke.color = col(color); stroke.strokeWidth = width; canvas.drawCircle(cx, cy, r, stroke)
    }

    override fun fillOval(l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = col(color); canvas.drawOval(l, t, r, b, fill)
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) {
        stroke.color = col(color); stroke.strokeWidth = width; canvas.drawLine(x0, y0, x1, y1, stroke)
    }

    private fun buildPath(pts: FloatArray, n: Int, close: Boolean) {
        path.rewind()
        if (n <= 0) return
        path.moveTo(pts[0], pts[1])
        for (i in 1 until n) path.lineTo(pts[i * 2], pts[i * 2 + 1])
        if (close) path.close()
    }

    override fun fillPolygon(pts: FloatArray, n: Int, color: Int) {
        buildPath(pts, n, true); fill.color = col(color); canvas.drawPath(path, fill)
    }

    override fun polyline(pts: FloatArray, n: Int, width: Float, color: Int, closed: Boolean) {
        buildPath(pts, n, closed); stroke.color = col(color); stroke.strokeWidth = width; canvas.drawPath(path, stroke)
    }

    override fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, width: Float, color: Int) {
        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.color = col(color); stroke.strokeWidth = width
        canvas.drawArc(rect, Math.toDegrees(start.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), false, stroke)
    }

    override fun fillPie(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int) {
        rect.set(cx - r, cy - r, cx + r, cy + r)
        fill.color = col(color)
        canvas.drawArc(rect, Math.toDegrees(start.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), true, fill)
    }

    override fun gradientRect(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        if (r <= l || b <= t) return
        val key = (top.toLong() shl 32) or (bottom.toLong() and 0xFFFFFFFFL)
        val sh = gradCache.getOrPut(key) { LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP) }
        shaderPaint.shader = sh
        shaderPaint.alpha = (alpha * 255).toInt()
        canvas.save()
        canvas.translate(l, t)
        canvas.scale(r - l, b - t)
        canvas.drawRect(0f, 0f, 1f, 1f, shaderPaint)
        canvas.restore()
        shaderPaint.shader = null
    }

    override fun glow(cx: Float, cy: Float, r: Float, color: Int) {
        if (r <= 0.5f) return
        val sh = glowCache.getOrPut(color) { RadialGradient(0f, 0f, 1f, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP) }
        shaderPaint.shader = sh
        shaderPaint.alpha = (alpha * 255).toInt()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, shaderPaint)
        canvas.restore()
        shaderPaint.shader = null
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align, bold: Boolean, outline: Int, outlineWidth: Float) {
        if (s.isEmpty() || size <= 0.5f) return
        textPaint.typeface = if (bold) this.bold else regular
        textPaint.textSize = size
        val w = textPaint.measureText(s)
        val bx = when (align) { Align.LEFT -> x; Align.CENTER -> x - w / 2f; Align.RIGHT -> x - w }
        val fm = textPaint.fontMetrics
        val by = y - (fm.ascent + fm.descent) / 2f
        if (outlineWidth > 0f && outline != 0) {
            textStroke.typeface = textPaint.typeface
            textStroke.textSize = size
            textStroke.strokeWidth = outlineWidth
            textStroke.color = col(outline)
            canvas.drawText(s, bx, by, textStroke)
        }
        textPaint.color = col(color)
        canvas.drawText(s, bx, by, textPaint)
    }

    override fun measureText(s: String, size: Float, bold: Boolean): Float {
        textPaint.typeface = if (bold) this.bold else regular
        textPaint.textSize = size
        return textPaint.measureText(s)
    }

    override fun image(img: ImageRef, l: Float, t: Float, r: Float, b: Float, mirror: Boolean) {
        val bmp = (img as? AndroidImage)?.bitmap ?: return
        if (bmp.isRecycled) return
        rect.set(l, t, r, b)
        bitmapPaint.alpha = (alpha * 255).toInt()
        if (mirror) {
            canvas.save()
            canvas.scale(-1f, 1f, (l + r) / 2f, (t + b) / 2f)
            canvas.drawBitmap(bmp, null, rect, bitmapPaint)
            canvas.restore()
        } else canvas.drawBitmap(bmp, null, rect, bitmapPaint)
    }
}
