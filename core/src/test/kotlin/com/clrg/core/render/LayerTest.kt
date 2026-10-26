package com.clrg.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cached layers, the blur and grain applied to them, and the light blend modes. */
class LayerTest {
    @Test
    fun aLayerIsDrawnOnceAndReused() {
        val g = AwtGfx(480, 270)
        var drawn = 0
        repeat(5) { g.layer("sky", 0f, 0f, g.width, g.height) { lg -> drawn++; lg.fillRect(0f, 0f, lg.width, lg.height, 0xFF336699.toInt()) } }
        assertEquals(1, drawn, "made once, then reused")
        assertEquals(1, g.layersMade); assertEquals(5, g.layersDrawn)
        g.layer("sky", 0f, 0f, g.width, g.height, version = 2) { lg -> drawn++; lg.fillRect(0f, 0f, lg.width, lg.height, 0xFF336699.toInt()) }
        assertEquals(2, drawn, "a new version is drawn again")
        // the picture matches drawing directly
        val c = g.image.getRGB(240, 135)
        assertEquals(0x336699, c and 0xFFFFFF)
    }

    @Test
    fun layerContentUsesTheSameCoordinatesAsTheScreen() {
        val g = AwtGfx(960, 540) // half resolution: the canvas is still 1920 x 1080 virtual units
        g.layer("box", 400f, 300f, 1400f, 900f) { lg -> lg.fillRect(1000f, 500f, 1100f, 600f, 0xFFFF0000.toInt()) }
        assertEquals(0xFF0000, g.image.getRGB(525, 275) and 0xFFFFFF, "inside the box (1050, 550 virtual)")
        assertEquals(0, g.image.getRGB(450, 200) ushr 24, "outside the box nothing was drawn")
    }

    @Test
    fun blurSoftensEdgesWithoutDarkFringes() {
        val w = 64; val h = 16
        // white bar on transparent: blurred edges stay white (premultiplied), only the alpha fades
        val px = IntArray(w * h) { i -> if (i % w in 24 until 40) 0xFFFFFFFF.toInt() else 0 }
        ImageOps.blur(px, w, h, 4)
        val edge = px[8 * w + 22]
        assertTrue((edge ushr 24) in 10..250, "edge alpha is in between: ${edge ushr 24}")
        assertTrue((edge shr 16 and 0xFF) > 240, "edge colour stays white, no dark fringe: ${Integer.toHexString(edge)}")
        // a hard step becomes a ramp, and the total brightness is kept
        val grey = IntArray(w * h) { i -> if (i % w < 32) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val sumBefore = grey.sumOf { it and 0xFF }
        ImageOps.blur(grey, w, h, 5)
        val sumAfter = grey.sumOf { it and 0xFF }
        assertTrue(kotlin.math.abs(sumAfter - sumBefore) < sumBefore * 0.03, "brightness kept: $sumBefore -> $sumAfter")
        val mid = grey[8 * w + 32] and 0xFF
        assertTrue(mid in 60..200, "the step is a ramp now: $mid")
    }

    @Test
    fun grainVariesPixelsSlightlyAndLeavesTransparencyAlone() {
        val w = 32; val h = 32
        val px = IntArray(w * h) { if (it < 512) 0xFF808080.toInt() else 0 }
        ImageOps.grain(px, w, h, 0.5f)
        val vals = (0 until 512).map { px[it] and 0xFF }
        assertTrue(vals.distinct().size > 5, "grain varies the pixels")
        assertTrue(vals.all { it in 116..140 }, "only slightly: ${vals.minOrNull()}..${vals.maxOrNull()}")
        assertTrue((512 until w * h).all { px[it] == 0 }, "transparent pixels untouched")
    }

    @Test
    fun addBlendBrightensAndMultiplyDarkens() {
        val g = AwtGfx(192, 108)
        g.fillRect(0f, 0f, g.width, g.height, 0xFF404040.toInt())
        g.save(); g.setBlend(Blend.ADD)
        g.fillRect(0f, 0f, 500f, 1080f, 0xFF800000.toInt())
        g.restore()
        g.save(); g.setBlend(Blend.MULTIPLY)
        g.fillRect(1000f, 0f, 1500f, 1080f, 0xFF808080.toInt())
        g.restore()
        g.fillRect(1600f, 0f, 1920f, 1080f, 0xFF00FF00.toInt()) // normal again after restore
        val added = g.image.getRGB(20, 50)
        assertEquals(0x40 + 0x80, added shr 16 and 0xFF, "red adds to the grey")
        assertEquals(0x40, added shr 8 and 0xFF, "green unchanged")
        val mult = g.image.getRGB(120, 50)
        assertTrue((mult and 0xFF) in 0x1E..0x22, "multiply by half: ${mult and 0xFF}")
        assertEquals(0x00FF00, g.image.getRGB(180, 50) and 0xFFFFFF, "restore went back to normal drawing")
    }
}
