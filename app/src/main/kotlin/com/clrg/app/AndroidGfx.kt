package com.clrg.app

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.clrg.core.render.Align
import com.clrg.core.render.Blend
import com.clrg.core.render.Gfx
import com.clrg.core.render.ImageOps
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
class AndroidGfx(
    /** A layer being made: nested layers draw directly into it. */
    private val inLayer: Boolean = false,
) : Gfx {
    private lateinit var canvas: Canvas
    /** Device pixels per virtual unit (for sizing cached layers). */
    private var pixelScale = 1f
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
    private var blend = Blend.NORMAL
    private val blendStack = arrayOfNulls<Blend>(64)
    private var depth = 0
    private val glowCache = object : LinkedHashMap<Int, RadialGradient>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RadialGradient>?) = size > 96
    }
    private val gradCache = object : LinkedHashMap<Long, LinearGradient>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LinearGradient>?) = size > 96
    }
    private val radialCache = object : LinkedHashMap<Long, RadialGradient>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RadialGradient>?) = size > 160
    }
    private val shaderMatrix = Matrix()

    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

    /** Unit radial gradient (centre 0,0, radius 1) placed at (gx, gy) with radius gr. */
    private fun radial(gx: Float, gy: Float, gr: Float, inner: Int, outer: Int): RadialGradient {
        val sh = radialCache.getOrPut(key(inner, outer)) { RadialGradient(0f, 0f, 1f, inner, outer, Shader.TileMode.CLAMP) }
        shaderMatrix.setScale(gr.coerceAtLeast(0.01f), gr.coerceAtLeast(0.01f))
        shaderMatrix.postTranslate(gx, gy)
        sh.setLocalMatrix(shaderMatrix)
        return sh
    }

    /** Unit vertical gradient (y 0..1) placed between y0 and y1. */
    private fun vertical(y0: Float, y1: Float, top: Int, bottom: Int): LinearGradient {
        val sh = gradCache.getOrPut(key(top, bottom)) { LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP) }
        shaderMatrix.setScale(1f, (y1 - y0).coerceAtLeast(0.01f))
        shaderMatrix.postTranslate(0f, y0)
        sh.setLocalMatrix(shaderMatrix)
        return sh
    }

    private inline fun withShader(sh: Shader, draw: (Paint) -> Unit) {
        shaderPaint.shader = sh
        shaderPaint.alpha = (alpha * 255).toInt()
        draw(shaderPaint)
        shaderPaint.shader = null
    }

    override fun fillCircleRadial(cx: Float, cy: Float, r: Float, gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) {
        if (r <= 0f) return
        withShader(radial(gx, gy, gr, inner, outer)) { canvas.drawCircle(cx, cy, r, it) }
    }

    override fun fillRectRadial(l: Float, t: Float, r: Float, b: Float, gx: Float, gy: Float, gr: Float, inner: Int, outer: Int) {
        if (r <= l || b <= t) return
        withShader(radial(gx, gy, gr, inner, outer)) { canvas.drawRect(l, t, r, b, it) }
    }

    override fun fillRoundRectGradient(l: Float, t: Float, r: Float, b: Float, radius: Float, top: Int, bottom: Int) {
        if (r <= l || b <= t) return
        withShader(vertical(t, b, top, bottom)) { canvas.drawRoundRect(l, t, r, b, radius, radius, it) }
    }

    private val linCache = object : LinkedHashMap<Long, LinearGradient>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LinearGradient>?) = size > 96
    }
    private val matVals = FloatArray(9)

    override fun fillPolygonLinear(pts: FloatArray, n: Int, x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int) {
        buildPath(pts, n, true)
        val sh = linCache.getOrPut(key(c0, c1)) { LinearGradient(0f, 0f, 1f, 0f, c0, c1, Shader.TileMode.CLAMP) }
        var dx = x1 - x0; var dy = y1 - y0
        if (dx * dx + dy * dy < 1e-4f) dx = 0.01f
        // maps the unit gradient (u along 0..1) onto the line from (x0, y0) to (x1, y1)
        matVals[0] = dx; matVals[1] = -dy; matVals[2] = x0
        matVals[3] = dy; matVals[4] = dx; matVals[5] = y0
        matVals[6] = 0f; matVals[7] = 0f; matVals[8] = 1f
        shaderMatrix.setValues(matVals)
        sh.setLocalMatrix(shaderMatrix)
        withShader(sh) { canvas.drawPath(path, it) }
    }

    override fun fillPolygonGradient(pts: FloatArray, n: Int, y0: Float, y1: Float, top: Int, bottom: Int) {
        buildPath(pts, n, true)
        withShader(vertical(y0, y1, top, bottom)) { canvas.drawPath(path, it) }
    }

    fun begin(c: Canvas, virtualWidth: Float, virtualHeight: Float, scale: Float = 1f) {
        canvas = c
        width = virtualWidth
        height = virtualHeight
        pixelScale = scale
        alpha = 1f
        depth = 0
        if (blend != Blend.NORMAL) applyBlend(Blend.NORMAL)
    }

    // ---- blend modes

    private fun androidMode(b: Blend): BlendMode? = when (b) {
        Blend.NORMAL -> null
        Blend.ADD -> BlendMode.PLUS
        Blend.SCREEN -> BlendMode.SCREEN
        Blend.MULTIPLY -> BlendMode.MULTIPLY
    }

    private fun applyBlend(b: Blend) {
        blend = b
        val m = androidMode(b)
        for (p in arrayOf(fill, stroke, shaderPaint, textPaint, textStroke, bitmapPaint)) p.blendMode = m
    }

    override fun setBlend(mode: Blend) { if (mode != blend) applyBlend(mode) }

    // ---- cached layers

    private class Layer(val bmp: Bitmap, val version: Int, val w: Int, val h: Int) { val bytes = w.toLong() * h * 4 }
    private val layers = LinkedHashMap<String, Layer>(16, 0.75f, true)
    private var layerBytes = 0L

    override fun layer(key: String, l: Float, t: Float, r: Float, b: Float, version: Int, res: Float, blur: Float,
                       grain: Float, draw: (Gfx) -> Unit) {
        if (r <= l || b <= t) return
        if (inLayer) { super.layer(key, l, t, r, b, version, res, blur, grain, draw); return }
        // never sharper than the virtual canvas (1920 x 1080 on a TV): plenty for backdrops, and bounded memory
        val s = (minOf(pixelScale, 1f) * res).coerceIn(0.1f, 1f)
        val pw = kotlin.math.ceil((r - l) * s).toInt().coerceAtLeast(1)
        val ph = kotlin.math.ceil((b - t) * s).toInt().coerceAtLeast(1)
        var lay = layers[key]
        if (lay == null || lay.version != version || lay.w != pw || lay.h != ph) {
            lay?.let { layers.remove(key); layerBytes -= it.bytes }
            lay = makeLayer(l, t, r, b, pw, ph, s, version, blur, grain, draw)
            if (lay == null) { super.layer(key, l, t, r, b, version, res, blur, grain, draw); return }
            layers[key] = lay
            layerBytes += lay.bytes
            val it = layers.entries.iterator()
            while (layerBytes > LAYER_BUDGET && layers.size > 1 && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
                layerBytes -= e.value.bytes
                it.remove()
            }
        }
        rect.set(l, t, r, b)
        bitmapPaint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(lay.bmp, null, rect, bitmapPaint)
    }

    private fun makeLayer(l: Float, t: Float, r: Float, b: Float, pw: Int, ph: Int, s: Float, version: Int, blur: Float,
                          grain: Float, draw: (Gfx) -> Unit): Layer? {
        val bmp = try { Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { return null }
        val c = Canvas(bmp)
        c.scale(s, s)
        c.translate(-l, -t)
        val sub = AndroidGfx(inLayer = true)
        sub.begin(c, width, height, s)
        try { draw(sub) } catch (e: Throwable) { return null }
        if (blur > 0f || grain > 0f) {
            val px = IntArray(pw * ph)
            bmp.getPixels(px, 0, pw, 0, 0, pw, ph)
            if (blur > 0f) ImageOps.blur(px, pw, ph, (blur * s).toInt().coerceAtLeast(1))
            if (grain > 0f) ImageOps.grain(px, pw, ph, grain)
            bmp.setPixels(px, 0, pw, 0, 0, pw, ph)
        }
        bmp.prepareToDraw()
        return Layer(bmp, version, pw, ph)
    }

    private fun col(c: Int): Int {
        if (alpha >= 0.999f) return c
        val a = (((c ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (c and 0xFFFFFF)
    }

    override fun save() {
        canvas.save()
        if (depth < alphaStack.size) { alphaStack[depth] = alpha; blendStack[depth] = blend }
        depth++
    }

    override fun restore() {
        canvas.restore()
        depth--
        if (depth in 0 until alphaStack.size) {
            alpha = alphaStack[depth]
            val b = blendStack[depth] ?: Blend.NORMAL
            if (b != blend) applyBlend(b)
        }
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
        withShader(vertical(t, b, top, bottom)) { canvas.drawRect(l, t, r, b, it) }
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

    companion object {
        /** Memory for cached layers (bytes): about four full-screen pictures. */
        const val LAYER_BUDGET = 36L * 1024 * 1024
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
