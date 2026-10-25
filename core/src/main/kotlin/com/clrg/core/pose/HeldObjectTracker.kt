package com.clrg.core.pose

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What colours belong to a player's held object (a stick, a toy racket, a pool noodle): for every colour bin, how
 * likely a pixel of that colour is part of the object rather than the room or the player. Learned once (the player
 * holds the object up), kept in the settings, and used to find the object near the player's hands in every frame.
 */
class ObjectColorModel(val prob: FloatArray, /** Typical length of the object in body units (torso lengths). */ val length: Float) {
    init { require(prob.size == BINS) }

    fun encode(): String {
        val sb = StringBuilder("o1;").append("%.3f".format(java.util.Locale.ROOT, length)).append(';')
        for (p in prob) { val q = (p.coerceIn(0f, 1f) * 255f).toInt(); sb.append(HEX[q shr 4]).append(HEX[q and 15]) }
        return sb.toString()
    }

    companion object {
        /** 18 hues x 4 saturations x 4 brightnesses, plus 8 grey levels for colourless pixels. */
        const val BINS = 18 * 16 + 8
        private const val HEX = "0123456789abcdef"

        fun decode(s: String?): ObjectColorModel? {
            if (s == null) return null
            val parts = s.split(';')
            if (parts.size != 3 || parts[0] != "o1" || parts[2].length != BINS * 2) return null
            val len = parts[1].toFloatOrNull() ?: return null
            val p = FloatArray(BINS) { i -> parts[2].substring(i * 2, i * 2 + 2).toIntOrNull(16)?.div(255f) ?: return null }
            return ObjectColorModel(p, len)
        }

        /** Colour bin of an RGB pixel (0..255 channels). */
        fun bin(r: Float, g: Float, b: Float): Int {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val v = mx / 255f
            val s = if (mx <= 1f) 0f else (mx - mn) / mx
            if (s < 0.2f || v < 0.12f) return 18 * 16 + min(7, (v * 8f).toInt())
            val d = (mx - mn).coerceAtLeast(1e-3f)
            var h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (h < 0f) h += 360f
            val hb = (h / 20f).toInt() % 18
            val sb = ((s - 0.2f) / 0.8f * 4f).toInt().coerceIn(0, 3)
            val vb = ((v - 0.12f) / 0.88f * 4f).toInt().coerceIn(0, 3)
            return hb * 16 + sb * 4 + vb
        }

        /**
         * Bins that are "the same colour" a little differently lit: neighbouring hue and brightness, and with [wide]
         * also neighbouring saturation (a colour washed out by motion blur or glare).
         */
        fun neighbours(bin: Int, out: IntArray, wide: Boolean = false): Int {
            var n = 0
            if (bin >= 18 * 16) {
                val v = bin - 18 * 16
                for (dv in -1..1) { val vv = v + dv; if (vv in 0..7) out[n++] = 18 * 16 + vv }
                return n
            }
            val hb = bin / 16; val sb = (bin / 4) % 4; val vb = bin % 4
            val ds = if (wide) 1 else 0
            for (dh in -1..1) for (dv in -1..1) for (dsat in -ds..ds) {
                val h2 = (hb + dh + 18) % 18; val v2 = vb + dv; val s2 = sb + dsat
                if (v2 !in 0..3 || s2 !in 0..3) continue
                out[n++] = h2 * 16 + s2 * 4 + v2
            }
            return n
        }
    }
}

/** Progress and outcome of learning an object's colours. */
class HeldLearnStatus(
    val player: Int,
    /** 0..1 while learning. */
    val progress: Float,
    val done: Boolean,
    val ok: Boolean,
    val message: String,
    val model: ObjectColorModel?,
)

/**
 * Finds a held object in the camera picture, next to a player's hands, from its learned colours.
 *
 * Learning: the player holds the object straight up in one hand, beside them. For a moment the tracker looks along
 * a range of upward directions from the hand for the narrow strip whose colour is most uniform and most unlike
 * what is either side of it - that strip is the object - and gathers its colours against the colours of the room,
 * the player's clothes, skin and the rest of the picture around the hand.
 *
 * Tracking (every frame, ~1-2 ms): around each hand of a player with a model, the picture is sampled on a fine grid;
 * pixels with the object's colours that connect to the hand form the object, and its long axis gives the direction
 * and its far end the tip. Too short, too round or too wide a patch is rejected (a wall or a jumper of the same
 * colour is not a stick).
 *
 * Thread use: [models], [tracks] and [requestLearn] are set from the game; [process] runs on the camera thread.
 * Works best with a brightly coloured object that stands out from the room; a brown stick against brown furniture is
 * hard to tell apart by colour alone.
 */
class HeldObjectTracker {
    /** Colour model per player (null: that player holds nothing). */
    @Volatile var models: Array<ObjectColorModel?> = arrayOfNulls(2)
    /** Pipeline track id of each player (-1: not known). */
    @Volatile var tracks: IntArray = intArrayOf(-1, -1)
    @Volatile var status: HeldLearnStatus? = null
        private set
    @Volatile private var request: IntArray? = null // [trackId, player]

    /** Starts learning the object held up by the person with pipeline track [trackId], for [player]. */
    fun requestLearn(trackId: Int, player: Int) {
        request = intArrayOf(trackId, player)
        status = HeldLearnStatus(player, 0f, false, false, "Hold your object straight up beside you, and keep still", null)
    }

    fun cancelLearn() { request = null; status = null; frames = 0; attempts = 0 }

    // ---- learning state (camera thread)
    private val fgHist = FloatArray(ObjectColorModel.BINS)
    private val bgHist = FloatArray(ObjectColorModel.BINS)
    private val lengths = ArrayList<Float>()
    private var frames = 0
    private var attempts = 0
    private var learnPlayer = -1
    private var fgN = 0f
    private var bgN = 0f
    private val fgSamples = ArrayList<Int>()
    private val bgSamples = ArrayList<Int>()

    private val tmp = FloatArray(3)
    private val nb = IntArray(27)
    private val lastHand = intArrayOf(-1, -1)

    /** Runs learning (if asked for) and tracking for every player with a model. Sets [RawPose.held]. */
    fun process(src: PixelSource, toBuffer: Affine, poses: List<RawPose>) {
        request?.let { req -> learnStep(src, toBuffer, poses, req) }
        val ms = models
        val tr = tracks
        for (p in 0..1) {
            val m = ms[p] ?: continue
            val pose = poses.firstOrNull { it.trackId == tr[p] } ?: continue
            pose.held = track(src, toBuffer, pose.landmarks, m, p)
        }
    }

    private fun bodyScale(lm: RawLandmarks): Float {
        val sx = (lm.x[Lm.LEFT_SHOULDER] + lm.x[Lm.RIGHT_SHOULDER]) / 2f; val sy = (lm.y[Lm.LEFT_SHOULDER] + lm.y[Lm.RIGHT_SHOULDER]) / 2f
        val hipsOk = lm.visibility[Lm.LEFT_HIP] > 0.5f && lm.visibility[Lm.RIGHT_HIP] > 0.5f
        val sw = hypot(lm.x[Lm.LEFT_SHOULDER] - lm.x[Lm.RIGHT_SHOULDER], lm.y[Lm.LEFT_SHOULDER] - lm.y[Lm.RIGHT_SHOULDER])
        return if (hipsOk) hypot((lm.x[Lm.LEFT_HIP] + lm.x[Lm.RIGHT_HIP]) / 2f - sx, (lm.y[Lm.LEFT_HIP] + lm.y[Lm.RIGHT_HIP]) / 2f - sy) else sw * 1.35f
    }

    /** Grip point of hand [h] (0 left, 1 right): between the wrist and the knuckles. */
    private fun grip(lm: RawLandmarks, h: Int, out: FloatArray): Boolean {
        val w = if (h == 0) Lm.LEFT_WRIST else Lm.RIGHT_WRIST
        if (lm.visibility[w] < 0.3f) return false
        val idx = if (h == 0) Lm.LEFT_INDEX else Lm.RIGHT_INDEX
        val pk = if (h == 0) 17 else 18
        if (lm.visibility[idx] > 0.3f && lm.visibility[pk] > 0.3f) {
            out[0] = (lm.x[w] + lm.x[idx] + lm.x[pk]) / 3f; out[1] = (lm.y[w] + lm.y[idx] + lm.y[pk]) / 3f
        } else { out[0] = lm.x[w]; out[1] = lm.y[w] }
        return true
    }

    private fun binAt(src: PixelSource, toBuffer: Affine, ux: Float, uy: Float): Int {
        val bx = toBuffer.mapX(ux, uy); val by = toBuffer.mapY(ux, uy)
        if (bx < 0f || by < 0f || bx >= src.width || by >= src.height) return -1
        src.readPixel(bx.toInt(), by.toInt(), tmp, 0)
        return ObjectColorModel.bin(tmp[0], tmp[1], tmp[2])
    }

    // ------------------------------------------------------------------------------------------------ learning

    private fun learnStep(src: PixelSource, toBuffer: Affine, poses: List<RawPose>, req: IntArray) {
        if (learnPlayer != req[1] || (frames == 0 && attempts == 0)) {
            fgHist.fill(0f); bgHist.fill(0f); lengths.clear(); fgSamples.clear(); bgSamples.clear(); fgN = 0f; bgN = 0f
            frames = 0; learnPlayer = req[1]
        }
        if (++attempts > MAX_ATTEMPTS) {
            // about four seconds without a clear look at it: say so rather than wait for ever
            request = null; frames = 0; attempts = 0
            status = HeldLearnStatus(req[1], 1f, true, false, "Couldn't make out the object. A brightly coloured one held straight up, away from things of the same colour, works best", null)
            return
        }
        val pose = poses.firstOrNull { it.trackId == req[0] } ?: poses.maxByOrNull { bodyScale(it.landmarks) }
        if (pose == null) { status = HeldLearnStatus(req[1], 0f, false, false, "Stand in front of the camera", null); return }
        val lm = pose.landmarks
        val s = bodyScale(lm)
        // the raised hand holds the object
        val g = FloatArray(2)
        val sy = (lm.y[Lm.LEFT_SHOULDER] + lm.y[Lm.RIGHT_SHOULDER]) / 2f
        var hand = -1
        var best = Float.MAX_VALUE
        for (h in 0..1) if (grip(lm, h, g) && g[1] < best) { best = g[1]; hand = h }
        if (hand < 0 || best > sy + 0.35f * s) {
            status = HeldLearnStatus(req[1], frames / LEARN_FRAMES.toFloat(), false, false, "Hold your object straight up, hand at shoulder height", null)
            return
        }
        grip(lm, hand, g)
        // find the strip pointing up from the hand that looks most like an object
        var bestScore = -1f; var bestAng = 0f
        val fgBins = IntArray(36); val flBins = IntArray(48)
        for (k in -10..10) {
            val ang = k * 5f * (PI / 180.0).toFloat()
            val dx = sin(ang); val dy = -cos(ang)
            val score = stripScore(src, toBuffer, g, dx, dy, s, fgBins, flBins)
            if (score > bestScore) { bestScore = score; bestAng = ang }
        }
        if (bestScore < 0.25f) {
            frames = max(0, frames - 1)
            status = HeldLearnStatus(req[1], frames / LEARN_FRAMES.toFloat(), false, false, "I can't make out the object yet - hold it straight up, away from your body", null)
            return
        }
        val dx = sin(bestAng); val dy = -cos(bestAng)
        stripScore(src, toBuffer, g, dx, dy, s, fgBins, flBins)
        val len = objectLength(src, toBuffer, g, dx, dy, s, fgBins)
        // only what lies along the object itself (the strip may reach past its end)
        for ((i, b) in fgBins.withIndex()) if (b >= 0 && 0.22f + (i / 3) * 0.065f <= len - 0.02f) { addSpread(fgHist, b, 1f, wide = true); fgN++; fgSamples.add(b) }
        for (b in flBins) if (b >= 0) { addSpread(bgHist, b, 1f); bgN++; bgSamples.add(b) }
        // the rest of the picture around the hand, and the player themselves (clothes, skin)
        val rng = java.util.Random(frames * 7919L)
        repeat(80) {
            val a = rng.nextFloat() * 2f * PI.toFloat()
            if (abs(angleDiff(a, kotlin.math.atan2(dy, dx))) < 0.3f) return@repeat
            val rr = (0.3f + rng.nextFloat() * 1.4f) * s
            val b = binAt(src, toBuffer, g[0] + cos(a) * rr, g[1] + sin(a) * rr)
            if (b >= 0) { addSpread(bgHist, b, 1f); bgN++; bgSamples.add(b) }
        }
        for (i in BODY_POINTS) {
            if (lm.visibility[i] < 0.4f) continue
            for (j in 0 until 3) {
                val b = binAt(src, toBuffer, lm.x[i] + (rng.nextFloat() - 0.5f) * 0.08f * s, lm.y[i] + (rng.nextFloat() - 0.5f) * 0.08f * s)
                if (b >= 0) { addSpread(bgHist, b, 1f); bgN++; bgSamples.add(b) }
            }
        }
        lengths.add(len)
        frames++
        if (frames < LEARN_FRAMES) {
            status = HeldLearnStatus(req[1], frames / LEARN_FRAMES.toFloat(), false, false, "Learning your object - keep still", null)
            return
        }
        finishLearning(req[1])
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    /**
     * How much the strip from the hand along (dx, dy) looks like one object: its own colours agree with each other
     * and differ from the strips either side. Fills the sampled bins.
     */
    private fun stripScore(src: PixelSource, toBuffer: Affine, g: FloatArray, dx: Float, dy: Float, s: Float, fg: IntArray, fl: IntArray): Float {
        val px = -dy; val py = dx
        var i = 0; var j = 0
        for (step in 0 until 12) {
            val t = (0.22f + step * 0.065f) * s
            for (lat in floatArrayOf(-0.022f, 0f, 0.022f)) fg[i++] = binAt(src, toBuffer, g[0] + dx * t + px * lat * s, g[1] + dy * t + py * lat * s)
            for (lat in floatArrayOf(-0.24f, -0.16f, 0.16f, 0.24f)) fl[j++] = binAt(src, toBuffer, g[0] + dx * t + px * lat * s, g[1] + dy * t + py * lat * s)
        }
        // the strip's main colour (with its lighting neighbours)
        val count = HashMap<Int, Int>()
        for (b in fg) if (b >= 0) count[b] = (count[b] ?: 0) + 1
        val mode = count.maxByOrNull { it.value }?.key ?: return 0f
        val n = ObjectColorModel.neighbours(mode, nb)
        fun like(b: Int): Boolean { if (b < 0) return false; for (k in 0 until n) if (nb[k] == b) return true; return false }
        val inside = fg.count { like(it) } / fg.size.toFloat()
        val outside = fl.count { like(it) } / fl.size.toFloat()
        return inside * (1f - outside)
    }

    /** Length (body units) of the object along (dx, dy): how far its colour carries on from the hand. */
    private fun objectLength(src: PixelSource, toBuffer: Affine, g: FloatArray, dx: Float, dy: Float, s: Float, fg: IntArray): Float {
        val count = HashMap<Int, Int>()
        for (b in fg) if (b >= 0) count[b] = (count[b] ?: 0) + 1
        val mode = count.maxByOrNull { it.value }?.key ?: return 0.5f
        val n = ObjectColorModel.neighbours(mode, nb)
        var last = 0.2f
        var misses = 0
        var t = 0.15f
        while (t < 2.0f) {
            val b = binAt(src, toBuffer, g[0] + dx * t * s, g[1] + dy * t * s)
            var hit = false
            for (k in 0 until n) if (nb[k] == b) hit = true
            if (hit) { last = t; misses = 0 } else if (++misses > 3) break
            t += 0.03f
        }
        return last
    }

    private fun addSpread(h: FloatArray, b: Int, w: Float, wide: Boolean = false) {
        h[b] += w
        val n = ObjectColorModel.neighbours(b, nb, wide)
        for (k in 0 until n) if (nb[k] != b) h[nb[k]] += w * (if (wide) 0.3f else 0.15f)
    }

    private fun finishLearning(player: Int) {
        val prob = FloatArray(ObjectColorModel.BINS)
        val ratio = if (bgN > 0f) fgN / bgN else 1f
        for (i in prob.indices) {
            val f = fgHist[i]; val b = bgHist[i] * ratio
            prob[i] = if (f + b <= 0.5f) 0f else f / (f + b + 0.5f)
        }
        val inside = if (fgSamples.isEmpty()) 0f else fgSamples.map { prob[it] }.average().toFloat()
        val outside = if (bgSamples.isEmpty()) 1f else bgSamples.map { prob[it] }.average().toFloat()
        val len = lengths.sorted().let { it[it.size / 2] }.coerceIn(0.3f, 1.8f)
        request = null
        frames = 0
        attempts = 0
        val ok = inside > 0.55f && inside - outside > 0.35f
        status = if (ok) HeldLearnStatus(player, 1f, true, true, "Got it! Move it about - the line should follow it", ObjectColorModel(prob, len))
        else HeldLearnStatus(player, 1f, true, false, "Couldn't tell it apart from the room. A brightly coloured object works best (a toy bat, a pool noodle)", null)
    }

    // ------------------------------------------------------------------------------------------------ tracking

    /** Why the last search found nothing (diagnostics). */
    var lastReject = ""
        private set
    private val mask = BooleanArray(G * G)
    private val seen = BooleanArray(G * G)
    private val queue = IntArray(G * G)
    private val gp = FloatArray(2)

    private fun track(src: PixelSource, toBuffer: Affine, lm: RawLandmarks, m: ObjectColorModel, player: Int): HeldObservation? {
        val s = bodyScale(lm)
        if (s < 4f) return null
        lastReject = ""
        val first = if (lastHand[player] >= 0) lastHand[player] else 1
        var best: HeldObservation? = null
        for (h in intArrayOf(first, 1 - first)) {
            if (!grip(lm, h, gp)) continue
            val o = find(src, toBuffer, gp[0], gp[1], s, m, h)
            if (o != null && (best == null || o.confidence > best.confidence)) best = o
            if (best != null && best.confidence > 0.6f) break
        }
        lastHand[player] = best?.hand ?: lastHand[player]
        return best
    }

    /** The object attached to the hand at (gx, gy), from model [m]; null when it is not seen well enough. */
    fun find(src: PixelSource, toBuffer: Affine, gx: Float, gy: Float, s: Float, m: ObjectColorModel, hand: Int): HeldObservation? {
        val r = ((m.length * 1.3f + 0.2f) * s).coerceAtMost(2.0f * s)
        val cell = 2f * r / G
        val x0 = gx - r; val y0 = gy - r
        for (j in 0 until G) for (i in 0 until G) {
            val k = j * G + i
            seen[k] = false
            val cx = x0 + (i + 0.5f) * cell; val cy = y0 + (j + 0.5f) * cell
            val dxr = cx - gx; val dyr = cy - gy
            if (dxr * dxr + dyr * dyr > r * r) { mask[k] = false; continue }
            val b = binAt(src, toBuffer, cx, cy)
            mask[k] = b >= 0 && m.prob[b] >= 0.5f
        }
        // grow from the cells at the hand
        val seedR = 0.2f * s
        var qn = 0
        for (j in 0 until G) for (i in 0 until G) {
            val k = j * G + i
            if (!mask[k]) continue
            val cx = x0 + (i + 0.5f) * cell - gx; val cy = y0 + (j + 0.5f) * cell - gy
            if (cx * cx + cy * cy <= seedR * seedR) { seen[k] = true; queue[qn++] = k }
        }
        if (qn == 0) { lastReject += "[hand $hand: no object colour at the hand] "; return null }
        var head = 0
        while (head < qn) {
            val k = queue[head++]
            val i = k % G; val j = k / G
            for (dj in -2..2) for (di in -2..2) {
                if (di == 0 && dj == 0) continue
                if ((abs(di) == 2 || abs(dj) == 2) && di != 0 && dj != 0) continue // reach over a one-cell gap straight across only
                val ii = i + di; val jj = j + dj
                if (ii < 0 || jj < 0 || ii >= G || jj >= G) continue
                val kk = jj * G + ii
                if (seen[kk] || !mask[kk]) continue
                seen[kk] = true; queue[qn++] = kk
            }
        }
        if (qn < 6) { lastReject += "[hand $hand: too small ($qn)] "; return null }
        // long axis of the patch
        var mx = 0.0; var my = 0.0
        for (q in 0 until qn) { val k = queue[q]; mx += x0 + (k % G + 0.5f) * cell; my += y0 + (k / G + 0.5f) * cell }
        mx /= qn; my /= qn
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in 0 until qn) {
            val k = queue[q]
            val dx = x0 + (k % G + 0.5f) * cell - mx; val dy = y0 + (k / G + 0.5f) * cell - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= qn; syy /= qn; sxy /= qn
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc; val l2 = max(1e-6, tr / 2 - disc)
        // eigenvector of the larger eigenvalue (two equivalent forms; use the better-conditioned one)
        var ax = (l1 - syy).toFloat(); var ay = sxy.toFloat()
        val bx = sxy.toFloat(); val by = (l1 - sxx).toFloat()
        if (abs(bx) + abs(by) > abs(ax) + abs(ay)) { ax = bx; ay = by }
        if (abs(ax) + abs(ay) < 1e-9f) { ax = 1f; ay = 0f }
        val al = hypot(ax, ay); ax /= al; ay /= al
        if ((mx - gx) * ax + (my - gy) * ay < 0) { ax = -ax; ay = -ay }
        val elong = sqrt(l1 / l2).toFloat()
        val width = sqrt(l2).toFloat() * 2f
        // far end: the 95th percentile of the patch along the axis from the hand
        val proj = FloatArray(qn)
        for (q in 0 until qn) { val k = queue[q]; proj[q] = (x0 + (k % G + 0.5f) * cell - gx) * ax + (y0 + (k / G + 0.5f) * cell - gy) * ay }
        proj.sort()
        val reach = proj[(qn * 0.95f).toInt().coerceAtMost(qn - 1)]
        val expected = m.length * s
        // a fast swing smears the object into a wedge from the hand: less elongated, but still long and narrow-ish
        if (reach < 0.3f * expected || elong < 1.9f || (elong < 2.5f && reach < 0.6f * expected) || width > 0.3f * s) {
            lastReject += "[hand $hand: reach %.0f/%.0f elong %.1f width %.0f/%.0f cells $qn] ".format(reach, expected, elong, width, 0.3f * s)
            return null
        }
        val conf = ((elong - 1.9f) / 4f).coerceIn(0.15f, 1f) * (reach / expected).coerceIn(0.3f, 1f)
        return HeldObservation(hand, gx, gy, gx + ax * reach, gy + ay * reach, conf)
    }

    companion object {
        /** Frames of holding still needed to learn an object (~0.8 s at 30 fps). */
        const val LEARN_FRAMES = 24
        /** Frames tried before giving up on learning (~4 s at 30 fps). */
        const val MAX_ATTEMPTS = 120
        /** Search grid size across the area around a hand. */
        private const val G = 64
        private val BODY_POINTS = intArrayOf(Lm.NOSE, Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW, Lm.LEFT_HIP, Lm.RIGHT_HIP, 9, 10)
    }
}
