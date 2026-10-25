package com.clrg.core.gesture

import com.clrg.core.pose.Lm
import com.clrg.core.tracking.Calibration
import com.clrg.core.tracking.PlayerSlot

/** Hand indices used throughout the gesture system: 0 = player's LEFT hand, 1 = RIGHT hand. */
const val LEFT = 0
const val RIGHT = 1

/**
 * One player's pose in *body units*: origin at the shoulder centre, 1 unit = body scale S (torso length),
 * x to the screen right (mirror view, so the player's own right), y UP.
 */
class BodyFrame {
    var t = 0.0
    var scale = 0f
    var originX = 0f
    var originY = 0f
    val wx = FloatArray(2)
    val wy = FloatArray(2)
    val ex = FloatArray(2)
    val ey = FloatArray(2)
    val sx = FloatArray(2)
    val sy = FloatArray(2)
    /** Wrist visibility 0..1. */
    val wv = FloatArray(2)
    /** Wrist depth relative to the shoulders in S units (negative = closer to the camera). Low precision. */
    val wz = FloatArray(2)
    var noseX = 0f
    var noseY = 0.45f
    var hipX = 0f
    var hipY = -1f
    var hipsVisible = false
    /** Horizontal shift of the shoulder centre from the calibrated neutral position, in calibrated S units. */
    var shift = 0f
    /** Sideways torso tilt (shoulder centre vs hip centre), sine-like, + = leaning to screen right. */
    var tilt = 0f
    /** Vertical shift of the shoulders from the calibrated neutral height, in S units (+ = higher, - = crouching). */
    var rise = 0f

    /**
     * Unsmoothed wrists and elbows of this frame (same units), with their visibility. Smoothing trades a little delay
     * for steadiness, which is right for drawing and pointing; fast-movement detection reads these instead.
     */
    val rwx = FloatArray(2)
    val rwy = FloatArray(2)
    val rwz = FloatArray(2)
    val rwv = FloatArray(2)
    val rex = FloatArray(2)
    val rey = FloatArray(2)
    val rev = FloatArray(2)

    /** Held object (stick / toy racket) in this frame: hand (-1 none), grip point and far end, confidence. */
    var objHand = -1
    var objBaseX = 0f
    var objBaseY = 0f
    var objX = 0f
    var objY = 0f
    var objConf = 0f

    fun copyFrom(o: BodyFrame) {
        t = o.t; scale = o.scale; originX = o.originX; originY = o.originY
        o.wx.copyInto(wx); o.wy.copyInto(wy); o.ex.copyInto(ex); o.ey.copyInto(ey)
        o.sx.copyInto(sx); o.sy.copyInto(sy); o.wv.copyInto(wv); o.wz.copyInto(wz)
        noseX = o.noseX; noseY = o.noseY; hipX = o.hipX; hipY = o.hipY; hipsVisible = o.hipsVisible
        shift = o.shift; tilt = o.tilt; rise = o.rise
        o.rwx.copyInto(rwx); o.rwy.copyInto(rwy); o.rwz.copyInto(rwz); o.rwv.copyInto(rwv)
        o.rex.copyInto(rex); o.rey.copyInto(rey); o.rev.copyInto(rev)
        objHand = o.objHand; objBaseX = o.objBaseX; objBaseY = o.objBaseY; objX = o.objX; objY = o.objY; objConf = o.objConf
    }

    companion object {
        /** Builds a body frame from a tracked slot's smoothed landmarks. */
        fun fill(out: BodyFrame, slot: PlayerSlot, tSec: Double, cal: Calibration?): Boolean {
            val p = slot.smooth
            if (!p.valid) return false
            val s = if (slot.scale > 1e-4f) slot.scale else return false
            val ox = (p.x[Lm.LEFT_SHOULDER] + p.x[Lm.RIGHT_SHOULDER]) / 2f
            val oy = (p.y[Lm.LEFT_SHOULDER] + p.y[Lm.RIGHT_SHOULDER]) / 2f
            out.t = tSec
            out.scale = s
            out.originX = ox
            out.originY = oy
            val wIdx = intArrayOf(Lm.LEFT_WRIST, Lm.RIGHT_WRIST)
            val eIdx = intArrayOf(Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW)
            val sIdx = intArrayOf(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER)
            val pose = slot.metrics?.pose
            for (h in 0..1) {
                out.wx[h] = (p.x[wIdx[h]] - ox) / s
                out.wy[h] = (oy - p.y[wIdx[h]]) / s
                out.ex[h] = (p.x[eIdx[h]] - ox) / s
                out.ey[h] = (oy - p.y[eIdx[h]]) / s
                out.sx[h] = (p.x[sIdx[h]] - ox) / s
                out.sy[h] = (oy - p.y[sIdx[h]]) / s
                out.wv[h] = p.vis[wIdx[h]]
                out.wz[h] = if (pose != null) (pose.z[wIdx[h]] - (pose.z[Lm.LEFT_SHOULDER] + pose.z[Lm.RIGHT_SHOULDER]) / 2f) / s else 0f
            }
            // unsmoothed joints, relative to the (smoothed) shoulder centre
            val raw = slot.metrics?.pose
            for (h in 0..1) {
                if (raw != null) {
                    out.rwx[h] = (raw.x[wIdx[h]] - ox) / s
                    out.rwy[h] = (oy - raw.y[wIdx[h]]) / s
                    out.rwv[h] = raw.vis(wIdx[h])
                    out.rex[h] = (raw.x[eIdx[h]] - ox) / s
                    out.rey[h] = (oy - raw.y[eIdx[h]]) / s
                    out.rev[h] = raw.vis(eIdx[h])
                } else {
                    out.rwx[h] = out.wx[h]; out.rwy[h] = out.wy[h]; out.rwv[h] = out.wv[h]
                    out.rex[h] = out.ex[h]; out.rey[h] = out.ey[h]; out.rev[h] = p.vis[eIdx[h]]
                }
                out.rwz[h] = out.wz[h]
            }
            val held = raw?.held
            if (held != null && held.confidence > 0f) {
                out.objHand = held.hand
                out.objBaseX = (held.baseX - ox) / s; out.objBaseY = (oy - held.baseY) / s
                out.objX = (held.tipX - ox) / s; out.objY = (oy - held.tipY) / s
                out.objConf = held.confidence
            } else {
                out.objHand = -1; out.objConf = 0f
            }
            out.noseX = (p.x[Lm.NOSE] - ox) / s
            out.noseY = (oy - p.y[Lm.NOSE]) / s
            out.hipsVisible = p.vis[Lm.LEFT_HIP] > 0.5f && p.vis[Lm.RIGHT_HIP] > 0.5f
            if (out.hipsVisible) {
                out.hipX = ((p.x[Lm.LEFT_HIP] + p.x[Lm.RIGHT_HIP]) / 2f - ox) / s
                out.hipY = (oy - (p.y[Lm.LEFT_HIP] + p.y[Lm.RIGHT_HIP]) / 2f) / s
                out.tilt = -out.hipX // shoulders right of hips = leaning right
            } else {
                out.hipX = 0f; out.hipY = -1f; out.tilt = 0f
            }
            out.shift = if (cal != null) (ox - cal.neutralShoulderX) / cal.bodyScale else 0f
            out.rise = if (cal != null) (cal.neutralShoulderY - oy) / cal.bodyScale else 0f
            return true
        }
    }
}

/** Short history of body frames (about 1.5 s) for windowed gesture detection. */
class BodyHistory(capacity: Int = 96) {
    private val buf = Array(capacity) { BodyFrame() }
    private var head = -1
    var size = 0
        private set

    fun push(f: BodyFrame) {
        head = (head + 1) % buf.size
        buf[head].copyFrom(f)
        if (size < buf.size) size++
    }

    fun clear() { size = 0; head = -1 }

    /** k = 0 is the latest frame. */
    fun get(k: Int): BodyFrame = buf[((head - k) % buf.size + buf.size) % buf.size]

    val latest: BodyFrame? get() = if (size == 0) null else get(0)

    /** The newest frame at least [age] seconds older than the latest (or the oldest available). */
    fun atAge(age: Double): BodyFrame? {
        if (size == 0) return null
        val t = get(0).t
        for (k in 1 until size) if (t - get(k).t >= age) return get(k)
        return if (size > 1) get(size - 1) else null
    }
}
