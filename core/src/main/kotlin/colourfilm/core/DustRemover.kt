package colourfilm.core

import kotlin.math.abs

/**
 * Removes single-frame dust, dirt and blotches from the luma plane.
 *
 * A speck of dust on one film frame shows up as a small spot that is clearly different from the
 * same place in BOTH the frame before and the frame after, while those two neighbours agree with
 * each other. Such spots are replaced by the average of the neighbours. Because the next frame is
 * needed, the remover delays the stream by one frame: [push] a frame, and when it returns true
 * the cleaned previous frame is in [out].
 *
 * Deliberate limits: only small, compact spots surrounded by an otherwise unchanged picture are
 * touched, so moving people and objects are left alone. Scratches that stay in the same place
 * for many frames are not detected by this method.
 */
class DustRemover(private val width: Int, private val height: Int, private val enabled: Boolean) {
    private val n = width * height
    private val frames = Array(3) { ByteArray(if (enabled) n else 0) }
    private val means = FloatArray(3)
    private var count = 0          // frames pushed so far
    private var head = 0           // index of the most recently pushed frame
    val out = ByteArray(n)
    private val mask = ByteArray(if (enabled) n else 0)
    private val stack = IntArray(if (enabled) MAX_BLOB_PIXELS * 4 + 16 else 0)
    private val blob = IntArray(if (enabled) MAX_BLOB_PIXELS + 1 else 0)
    private val noiseHist = IntArray(64)
    private var changeThresh = THRESH / 2
    private val maxDim = maxOf(6, Math.round(maxOf(width, height) * 0.02f))

    /** Statistics for tests and diagnostics. */
    var spotsRemoved = 0L; private set
    var pixelsRepaired = 0L; private set

    /** Adds a frame. Returns true when a finished frame (the one pushed before this) is in [out]. */
    fun push(y: ByteArray, off: Int, stride: Int): Boolean {
        if (!enabled) {
            for (r in 0 until height) System.arraycopy(y, off + r * stride, out, r * width, width)
            return true
        }
        head = (head + 1) % 3
        val f = frames[head]
        var sum = 0L
        for (r in 0 until height) System.arraycopy(y, off + r * stride, f, r * width, width)
        var i = 0
        while (i < n) { sum += f[i].toInt() and 0xFF; i += 7 }
        means[head] = sum.toFloat() / ((n + 6) / 7)
        count++
        if (count == 1) return false
        val mid = (head + 2) % 3
        if (count == 2) { System.arraycopy(frames[mid], 0, out, 0, n); return true } // first frame: no "before"
        clean(frames[(head + 1) % 3], means[(head + 1) % 3], frames[mid], means[mid], f, means[head])
        return true
    }

    /** After the last [push]: returns true once, with the final frame in [out]. */
    fun flush(): Boolean {
        if (!enabled || count == 0) return false
        System.arraycopy(frames[head], 0, out, 0, n)
        count = 0
        return true
    }

    /** Forget buffered frames (start of a new stream). */
    fun reset() { count = 0 }

    private fun clean(p: ByteArray, mp: Float, m: ByteArray, mm: Float, nx: ByteArray, mn: Float) {
        System.arraycopy(m, 0, out, 0, n)
        // Brightness offsets between frames (exposure flicker) are removed before comparing.
        val oP = Math.round(mm - mp); val oN = Math.round(mm - mn)
        // Grain level between frames (robust median), so grain is not mistaken for motion.
        noiseHist.fill(0)
        var samples = 0
        var k = 0
        while (k < n) {
            noiseHist[minOf(63, abs((m[k].toInt() and 0xFF) - (p[k].toInt() and 0xFF) - oP))]++; samples++; k += 53
        }
        var acc = 0; var med = 0
        for (b in 0..63) { acc += noiseHist[b]; if (acc * 2 >= samples) { med = b; break } }
        val sigmaD = med * 1.4826f
        changeThresh = maxOf(THRESH / 2, Math.round(3.2f * sigmaD))
        val spotThresh = maxOf(THRESH, Math.round(4.5f * sigmaD))
        var candidates = 0
        for (i in 0 until n) {
            val c = m[i].toInt() and 0xFF
            val dp = c - ((p[i].toInt() and 0xFF) + oP)
            val dn = c - ((nx[i].toInt() and 0xFF) + oN)
            var flag = 0
            if ((dp > spotThresh && dn > spotThresh) || (dp < -spotThresh && dn < -spotThresh)) {
                val dpn = abs(dp - dn) // = |P - N| after offset removal
                if (dpn < 0.6f * minOf(abs(dp), abs(dn))) { flag = 1; candidates++ }
            }
            mask[i] = flag.toByte()
        }
        // Lots of candidates means something else is going on (cut, flash, fast action): do nothing.
        if (candidates == 0 || candidates > n / 60) return

        for (start in 0 until n) {
            if (mask[start].toInt() != 1) continue
            // flood fill (8-connected), giving up when the blob is too big to be dust
            var sp = 0; var size = 0
            var minX = width; var maxX = 0; var minY = height; var maxY = 0
            var tooBig = false
            stack[sp++] = start; mask[start] = 2
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % width; val y = i / width
                if (size < MAX_BLOB_PIXELS) blob[size] = i
                size++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (size > MAX_BLOB_PIXELS || maxX - minX >= maxDim || maxY - minY >= maxDim) tooBig = true
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= width || yy >= height) continue
                    val j = yy * width + xx
                    if (mask[j].toInt() == 1 && sp < stack.size) { mask[j] = 2; stack[sp++] = j }
                }
            }
            if (tooBig || size < 2) continue
            // The surroundings must be unchanged between the frames (rules out moving objects).
            if (!surroundingsStatic(p, oP, m, nx, oN, minX, maxX, minY, maxY)) continue
            spotsRemoved++
            for (k in 0 until size) {
                val i = blob[k]
                val x = i % width; val y = i / width
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= width || yy >= height) continue
                    val j = yy * width + xx
                    val v = ((p[j].toInt() and 0xFF) + oP + (nx[j].toInt() and 0xFF) + oN + 1) / 2
                    out[j] = v.coerceIn(0, 255).toByte()
                    pixelsRepaired++
                }
            }
        }
    }

    private fun surroundingsStatic(
        p: ByteArray, oP: Int, m: ByteArray, nx: ByteArray, oN: Int, minX: Int, maxX: Int, minY: Int, maxY: Int,
    ): Boolean {
        // Look well beyond the spot: a foot or hand is small and "appears for one frame" at a
        // given place, exactly like dust, but it is attached to a moving limb. Dust sits alone.
        val pad = 6 + maxOf(maxX - minX, maxY - minY)
        val x0 = maxOf(0, minX - pad); val x1 = minOf(width - 1, maxX + pad)
        val y0 = maxOf(0, minY - pad); val y1 = minOf(height - 1, maxY + pad)
        var changed = 0; var cnt = 0
        val t = changeThresh
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * width + x
            if (mask[i].toInt() == 2 && x in minX..maxX && y in minY..maxY) continue // the spot itself
            val c = m[i].toInt() and 0xFF
            val pv = (p[i].toInt() and 0xFF) + oP; val nv = (nx[i].toInt() and 0xFF) + oN
            if (abs(c - pv) > t || abs(c - nv) > t || abs(pv - nv) > t) changed++
            cnt++
        }
        return cnt > 0 && changed <= cnt * MAX_CHANGED_NEARBY
    }

    companion object {
        /** Minimum brightness difference (8-bit codes) from both neighbouring frames. */
        const val THRESH = 28
        const val MAX_BLOB_PIXELS = 4000
        /** Largest share of nearby pixels allowed to differ between the frames (grain tolerance). */
        const val MAX_CHANGED_NEARBY = 0.02f
    }
}
