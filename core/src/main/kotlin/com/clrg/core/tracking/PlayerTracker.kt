package com.clrg.core.tracking

import com.clrg.core.pose.Appearance
import com.clrg.core.pose.PoseFrame
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class Hand { LEFT, RIGHT;
    val opposite: Hand get() = if (this == LEFT) RIGHT else LEFT
}

enum class MovementMode(val label: String) {
    STANDARD("Standard"),
    REDUCED("Reduced movement"),
    SEATED("Seated"),
}

enum class SlotState {
    /** No person assigned (setup, or before acquisition). */
    EMPTY,
    /** Tracked this frame. */
    ACTIVE,
    /** Briefly unseen (occlusion / dropped frame); still counts as playing. */
    MISSING,
    /** Lost for too long or tracking confidence too low: gameplay must pause until reacquired. */
    LOST,
}

enum class LostReason { NONE, NOT_SEEN, LOW_CONFIDENCE }

data class TrackerConfig(
    /** Unseen longer than this -> LOST. */
    val lostAfterSec: Float = 0.45f,
    /** Upper-body confidence below this ... */
    val lowConfidence: Float = 0.3f,
    /** ... for longer than this -> LOST. */
    val lowConfidenceSec: Float = 0.8f,
    /** Candidates below this confidence are ignored entirely. */
    val minConfidence: Float = 0.25f,
    /** A candidate must be stable this long before it is (re)assigned to a slot. */
    val acquireSec: Float = 0.3f,
    val reacquireSec: Float = 0.6f,
    /** Allowed body-scale ratio between a returning player and their calibration. */
    val reacquireScaleMin: Float = 0.7f,
    val reacquireScaleMax: Float = 1.45f,
    /** Clothing signatures further apart than this are treated as a different person. */
    val appearanceRejectDistance: Float = 0.6f,
)

/** Horizontal starting/playing area in frame units. */
data class PlayArea(val x0: Float, val x1: Float) {
    val center: Float get() = (x0 + x1) / 2f
    val width: Float get() = x1 - x0
    fun contains(x: Float, margin: Float = 0f) = x >= x0 - margin && x <= x1 + margin
}

class PlayerSlot(val index: Int) {
    var state = SlotState.EMPTY
        internal set
    var lostReason = LostReason.NONE
        internal set
    /** Latest raw measurements assigned to this player (null when unseen). */
    var metrics: BodyMetrics? = null
        internal set
    val smooth = SmoothedPose()
    /** Smoothed body scale S in frame units. */
    var scale = 0f
        internal set
    var shoulderX = 0f
        internal set
    var shoulderY = 0f
        internal set
    var vx = 0f
        internal set
    var vy = 0f
        internal set
    var confidence = 0f
        internal set
    var appearance: FloatArray? = null
        internal set
    var calibration: Calibration? = null
    var area = PlayArea(0f, 1f)
        internal set
    /** False for a slot that is not a camera player (the computer opponent in solo mode): never acquired or waited for. */
    var enabled = true
        internal set
    var lastSeenNanos = 0L
        internal set
    var missingSec = 0f
        internal set
    internal var lowConfSec = 0f
    internal var lastHint = -1
    /** Seconds the current (re)acquisition candidate has been stable, for progress display. */
    var acquireProgressSec = 0f
        internal set
    internal var pendingX = Float.NaN
    internal var pendingY = 0f
    var lossEvents = 0
        internal set
    var reacquisitions = 0
        internal set
    /** Increments every time new pose data is applied (lets consumers detect fresh data). */
    var updateCount = 0L
        internal set

    val isPlaying: Boolean get() = state == SlotState.ACTIVE || state == SlotState.MISSING
    val needsAttention: Boolean get() = enabled && (state == SlotState.LOST || state == SlotState.EMPTY)

    internal fun clear() {
        state = SlotState.EMPTY
        lostReason = LostReason.NONE
        metrics = null
        smooth.reset()
        missingSec = 0f
        lowConfSec = 0f
        pendingX = Float.NaN
        acquireProgressSec = 0f
        lastHint = -1
    }
}

/**
 * Assigns detected people to player slots.
 *
 * Identity comes from spatial position and temporal continuity (predicted shoulder position, body size, clothing
 * colour, pipeline track hint), never from detection-list order. Before calibration each slot picks up the
 * nearest-to-camera person standing in its starting area. After [lock], a slot only ever follows its own player:
 * spectators are ignored, brief occlusions are bridged, and a lost player is only reacquired by someone in their
 * area with a matching body size (and clothing) who stays put for [TrackerConfig.reacquireSec].
 */
class PlayerTracker(val playerCount: Int = 2, var config: TrackerConfig = TrackerConfig()) {
    val slots = Array(playerCount) { PlayerSlot(it) }
    var locked = false
        private set
    var aspect = 16f / 9f
        private set
    /** People in the last frame that are not assigned to a player (spectators / not yet acquired). */
    val others = ArrayList<BodyMetrics>()
    val lastCandidates = ArrayList<BodyMetrics>()
    private var lastFrameNanos = Long.MIN_VALUE
    var lastFrameSimulated = false
        private set

    /**
     * Solo mode: only slot 0 is a camera player and may stand anywhere in the picture; the other slots are disabled
     * (computer opponents). Switching mode forgets identities and calibrations, because starting areas change.
     */
    var solo = false
        set(value) {
            if (field == value) return
            field = value
            resetAll()
            slots.forEachIndexed { i, s -> s.enabled = !value || i == 0 }
            updateAreas()
        }

    init {
        updateAreas()
    }

    fun lock() {
        locked = true
    }

    /** Back to setup: identities can be re-assigned from starting areas. Calibrations are kept unless [clear]. */
    fun unlock(clear: Boolean = false) {
        locked = false
        if (clear) slots.forEach { it.clear(); it.calibration = null }
    }

    fun resetAll() {
        locked = false
        slots.forEach { it.clear(); it.calibration = null; it.appearance = null }
        lastFrameNanos = Long.MIN_VALUE
    }

    private fun updateAreas() {
        if (solo) {
            slots.forEachIndexed { i, s -> s.area = if (i == 0) PlayArea(0f, aspect) else PlayArea(aspect, aspect) }
            return
        }
        val w = aspect / playerCount
        slots.forEachIndexed { i, s -> s.area = PlayArea(i * w, (i + 1) * w) }
    }

    fun update(frame: PoseFrame) {
        val dt = if (lastFrameNanos == Long.MIN_VALUE) 0f else ((frame.timestampNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.5f)
        lastFrameNanos = frame.timestampNanos
        lastFrameSimulated = frame.simulated
        if (abs(frame.aspect - aspect) > 1e-3f) { aspect = frame.aspect; updateAreas() }

        val cands = frame.persons.mapNotNull { BodyMetrics.of(it) }.filter { it.confidence >= config.minConfidence }
        lastCandidates.clear(); lastCandidates.addAll(cands)
        val used = BooleanArray(cands.size)

        // --- 1) Continuity matching for players currently being tracked ---------------------------------
        val tracked = slots.filter { it.isPlaying }
        val assignment = bestAssignment(tracked, cands, frame.timestampNanos)
        for ((k, slot) in tracked.withIndex()) {
            val ci = assignment[k]
            if (ci >= 0) {
                used[ci] = true
                apply(slot, recalibrated(cands[ci], slot), frame.timestampNanos, dt)
            } else {
                slot.missingSec += dt
                slot.metrics = null
                slot.state = SlotState.MISSING
                if (slot.missingSec > config.lostAfterSec) markLost(slot, LostReason.NOT_SEEN)
            }
        }

        // --- 2) Acquisition (setup) / guarded reacquisition (locked) --------------------------------------
        for (slot in slots) {
            if (slot.isPlaying || !slot.enabled) continue
            val pick = if (locked && slot.calibration != null) pickReacquire(slot, cands, used) else pickAcquire(slot, cands, used)
            if (pick < 0) {
                slot.pendingX = Float.NaN
                slot.acquireProgressSec = 0f
                continue
            }
            val c = cands[pick]
            val continuous = !slot.pendingX.isNaN() && hypot(c.shoulderX - slot.pendingX, c.shoulderY - slot.pendingY) < 0.7f * c.scale
            slot.acquireProgressSec = if (continuous) slot.acquireProgressSec + dt else 0f
            slot.pendingX = c.shoulderX
            slot.pendingY = c.shoulderY
            val need = if (locked && slot.calibration != null) config.reacquireSec else config.acquireSec
            if (slot.acquireProgressSec >= need) {
                used[pick] = true
                slot.smooth.reset()
                slot.missingSec = 0f
                slot.lowConfSec = 0f
                slot.scale = 0f
                if (slot.state == SlotState.LOST) slot.reacquisitions++
                apply(slot, recalibrated(c, slot), frame.timestampNanos, 0f)
                slot.pendingX = Float.NaN
                slot.acquireProgressSec = 0f
            } else {
                used[pick] = true // reserve so the other slot cannot take the same person this frame
            }
        }

        // --- 3) Setup mode: release players who walked out of their area ----------------------------------
        if (!locked) {
            for (slot in slots) {
                val m = slot.metrics ?: continue
                if (!slot.area.contains(m.shoulderX, margin = 0.12f * slot.area.width)) {
                    slot.clear()
                }
            }
        }

        others.clear()
        for (i in cands.indices) if (!used[i]) others.add(cands[i])
    }

    private fun recalibrated(m: BodyMetrics, slot: PlayerSlot): BodyMetrics {
        val cal = slot.calibration ?: return m
        if (m.hipsVisible) return m
        return BodyMetrics.of(m.pose, cal.torsoRatio) ?: m
    }

    private fun apply(slot: PlayerSlot, m: BodyMetrics, nanos: Long, dt: Float) {
        val first = slot.state == SlotState.EMPTY || slot.state == SlotState.LOST || slot.scale <= 0f
        val sinceSeen = if (slot.lastSeenNanos == 0L || first) dt else ((nanos - slot.lastSeenNanos) / 1e9f).coerceIn(0f, 0.5f)
        if (!first && sinceSeen > 0f) {
            val nvx = (m.shoulderX - slot.shoulderX) / sinceSeen
            val nvy = (m.shoulderY - slot.shoulderY) / sinceSeen
            slot.vx += 0.5f * (nvx - slot.vx)
            slot.vy += 0.5f * (nvy - slot.vy)
        } else {
            slot.vx = 0f; slot.vy = 0f
        }
        slot.shoulderX = m.shoulderX
        slot.shoulderY = m.shoulderY
        slot.scale = if (first) m.scale else slot.scale + min(1f, sinceSeen / 0.4f) * (m.scale - slot.scale)
        slot.confidence = if (first) m.confidence else slot.confidence + min(1f, sinceSeen / 0.25f) * (m.confidence - slot.confidence)
        slot.smooth.update(m.pose, if (first) 0f else sinceSeen)
        slot.metrics = m
        slot.lastSeenNanos = nanos
        slot.missingSec = 0f
        slot.lastHint = m.pose.trackHint
        slot.state = SlotState.ACTIVE
        slot.lostReason = LostReason.NONE
        slot.updateCount++
        m.pose.appearance?.let { a ->
            val cur = slot.appearance
            if (cur == null || first) slot.appearance = a.copyOf()
            else if (m.confidence > 0.6f) for (i in cur.indices) cur[i] += 0.05f * (a[i] - cur[i])
        }
        if (slot.confidence < config.lowConfidence) {
            slot.lowConfSec += sinceSeen
            if (slot.lowConfSec > config.lowConfidenceSec) markLost(slot, LostReason.LOW_CONFIDENCE)
        } else {
            slot.lowConfSec = 0f
        }
    }

    private fun markLost(slot: PlayerSlot, reason: LostReason) {
        if (slot.state != SlotState.LOST) slot.lossEvents++
        slot.state = if (locked || slot.calibration != null) SlotState.LOST else SlotState.EMPTY
        slot.lostReason = reason
        slot.metrics = null
        slot.lowConfSec = 0f
        slot.pendingX = Float.NaN
        slot.acquireProgressSec = 0f
    }

    /** Cost of explaining candidate [c] as continuation of [slot]; +INF when gated out. */
    private fun continuityCost(slot: PlayerSlot, c: BodyMetrics, nanos: Long): Float {
        val since = ((nanos - slot.lastSeenNanos) / 1e9f).coerceIn(0f, 1f)
        val px = slot.shoulderX + slot.vx * min(since, 0.25f)
        val py = slot.shoulderY + slot.vy * min(since, 0.25f)
        val s = max(slot.scale, 1e-3f)
        val d = hypot(c.shoulderX - px, c.shoulderY - py) / s
        val gate = min(2.5f, 0.8f + 4f * since)
        if (d > gate) return Float.POSITIVE_INFINITY
        val ratio = c.scale / s
        if (ratio < 0.65f || ratio > 1.55f) return Float.POSITIVE_INFINITY
        var cost = d + 1.5f * abs(ln(ratio))
        val a = slot.appearance
        val b = c.pose.appearance
        if (a != null && b != null) {
            val ad = Appearance.distance(a, b)
            if (locked && ad > config.appearanceRejectDistance && slot.state == SlotState.MISSING) return Float.POSITIVE_INFINITY
            cost += 0.8f * ad
        }
        if (c.pose.trackHint == slot.lastHint && slot.lastHint >= 0) cost -= 0.25f
        return cost
    }

    /** Exhaustive optimal assignment (few slots, few candidates): maximise matches, then minimise cost. */
    private fun bestAssignment(tracked: List<PlayerSlot>, cands: List<BodyMetrics>, nanos: Long): IntArray {
        val n = tracked.size
        val result = IntArray(n) { -1 }
        if (n == 0 || cands.isEmpty()) return result
        val cost = Array(n) { i -> FloatArray(cands.size) { j -> continuityCost(tracked[i], cands[j], nanos) } }
        val cur = IntArray(n) { -1 }
        var bestScore = Float.NEGATIVE_INFINITY
        val taken = BooleanArray(cands.size)
        fun rec(i: Int, matched: Int, total: Float) {
            if (i == n) {
                var score = matched * 1000f - total
                // keep left-to-right order of players (they stand side by side and never cross)
                if (n >= 2 && cur[0] >= 0 && cur[1] >= 0) {
                    val before = tracked[0].shoulderX - tracked[1].shoulderX
                    val after = cands[cur[0]].shoulderX - cands[cur[1]].shoulderX
                    if (before * after < 0f) score -= 1.0f
                }
                if (score > bestScore) { bestScore = score; cur.copyInto(result) }
                return
            }
            cur[i] = -1
            rec(i + 1, matched, total)
            for (j in cands.indices) {
                if (taken[j] || cost[i][j] == Float.POSITIVE_INFINITY) continue
                taken[j] = true; cur[i] = j
                rec(i + 1, matched + 1, total + cost[i][j])
                taken[j] = false; cur[i] = -1
            }
        }
        rec(0, 0, 0f)
        return result
    }

    /**
     * Setup: the closest (largest) person standing in this slot's starting area. Size is only compared with
     * other people in the *same* area (so a child next to an adult is fine); someone much smaller than the
     * nearest person in the area is treated as further back (a spectator).
     */
    private fun pickAcquire(slot: PlayerSlot, cands: List<BodyMetrics>, used: BooleanArray): Int {
        var maxScale = 0f
        for (c in cands) if (slot.area.contains(c.shoulderX) && c.scale > maxScale) maxScale = c.scale
        var best = -1
        for (i in cands.indices) {
            if (used[i]) continue
            val c = cands[i]
            if (!slot.area.contains(c.shoulderX)) continue
            if (c.scale < MIN_PLAYER_SCALE || c.scale < 0.6f * maxScale) continue
            if (c.confidence < 0.4f) continue
            if (best < 0 || c.scale > cands[best].scale) best = i
        }
        return best
    }

    /** Locked: only someone in the player's area (or near their last position) with matching size/clothing. */
    private fun pickReacquire(slot: PlayerSlot, cands: List<BodyMetrics>, used: BooleanArray): Int {
        val cal = slot.calibration!!
        var best = -1
        var bestCost = Float.POSITIVE_INFINITY
        for (i in cands.indices) {
            if (used[i]) continue
            val c = cands[i]
            if (c.confidence < 0.4f) continue
            val nearLast = hypot(c.shoulderX - slot.shoulderX, c.shoulderY - slot.shoulderY) < 1.5f * cal.bodyScale
            if (!slot.area.contains(c.shoulderX, margin = 0.15f * slot.area.width) && !nearLast) continue
            val ratio = c.scale / cal.bodyScale
            if (ratio < config.reacquireScaleMin || ratio > config.reacquireScaleMax) continue
            var cost = abs(ln(ratio)) * 2f + abs(c.shoulderX - slot.area.center) / cal.bodyScale * 0.2f
            val a = slot.appearance
            val b = c.pose.appearance
            if (a != null && b != null) {
                val ad = Appearance.distance(a, b)
                if (ad > config.appearanceRejectDistance) continue
                cost += ad
            }
            // prefer whoever is already being followed as the pending candidate
            if (!slot.pendingX.isNaN() && hypot(c.shoulderX - slot.pendingX, c.shoulderY - slot.pendingY) < 0.7f * c.scale) cost -= 0.5f
            if (cost < bestCost) { bestCost = cost; best = i }
        }
        return best
    }

    companion object {
        /** Smallest torso (fraction of frame height) accepted as a player: roughly an adult at 5 m. */
        const val MIN_PLAYER_SCALE = 0.06f
    }

    /** True when every camera player slot is being tracked. */
    val allPlaying: Boolean get() = slots.all { !it.enabled || it.isPlaying }
}
