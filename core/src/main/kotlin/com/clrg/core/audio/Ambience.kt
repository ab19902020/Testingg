package com.clrg.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Background sound beds. They are generated live, so they never audibly loop and need no stored audio. */
enum class Ambience {
    NONE,
    /** Indoor crowd murmur with the odd clap, shout and whistle (boxing, darts, table tennis, stadium). */
    ARENA,
    /** Breeze, rustling leaves and birdsong (golf, archery, clay shooting). */
    PARK,
    /** Winter wind and distant sleigh bells (the outdoor Christmas games). */
    WINTER,
    /** A crackling fire (Christmas menus and indoor games). */
    FIRESIDE,
    /** Bowling alley: murmur and distant strikes. */
    LANES,
}

/** Acoustic spaces for the reverb bus that every effect is sent to. [wet] scales the reverb return. */
enum class Room(val size: Float, val damp: Float, val wet: Float, val pre: Float, val width: Float) {
    STUDIO(0.5f, 0.55f, 0.35f, 0.005f, 0.8f),
    LIVING(0.55f, 0.6f, 0.45f, 0.008f, 0.9f),
    HALL(0.8f, 0.45f, 0.6f, 0.02f, 1f),
    ARENA(0.88f, 0.35f, 0.75f, 0.03f, 1f),
    OUTDOOR(0.3f, 0.75f, 0.25f, 0.015f, 1f),
    SNOW(0.4f, 0.85f, 0.2f, 0.01f, 1f),
}

/** Which ambience and room each game uses. */
object Soundscape {
    fun ambience(gameId: String): Ambience = when (gameId) {
        "clays", "archery" -> Ambience.PARK
        "boxing", "darts", "gallery", "goalie_s", "tennis_s", "tabletennis_s" -> Ambience.ARENA
        "bowling", "bowling_s" -> Ambience.LANES
        "tennis", "snowball", "catch", "reindeer", "slalom", "goalie" -> Ambience.WINTER
        "tree", "tabletennis" -> Ambience.FIRESIDE
        else -> Ambience.NONE
    }

    fun room(gameId: String): Room = when (gameId) {
        "clays", "archery", "tennis_s" -> Room.OUTDOOR
        "boxing" -> Room.ARENA
        "darts", "tabletennis_s", "bowling", "bowling_s", "goalie_s" -> Room.HALL
        "gallery", "tree", "tabletennis", "dance" -> Room.LIVING
        "tennis", "snowball", "catch", "reindeer", "slalom", "goalie" -> Room.SNOW
        else -> Room.STUDIO
    }
}

/**
 * Generates one ambience live on the audio thread, one stereo sample per [next] call (read [outL]/[outR]).
 * Allocation-free after construction. Levels are kept low: this sits well under the music and effects.
 */
class AmbienceGen(private val sr: Int, val kind: Ambience, seed: Int = 1) {
    var outL = 0f
        private set
    var outR = 0f
        private set

    private val nl = Noise(101 + seed)
    private val nr = Noise(202 + seed)
    private val ev = Noise(303 + seed)
    private val dt = 1f / sr
    private var t = 0.0
    private var counter = 0

    private val aL = Biquad(); private val aR = Biquad()
    private val bL = Biquad(); private val bR = Biquad()
    private val cL = Biquad(); private val cR = Biquad()
    private val slowL = OnePole().cutoff(1.2f, sr); private val slowR = OnePole().cutoff(1.2f, sr)
    private val gust = OnePole().cutoff(0.15f, sr)
    private val crackBp = Biquad().bandpass(2400f, 0.6f, sr)
    private val popLp = Biquad().lowpass(1800f, 0.7f, sr)
    private var crackEnv = 0f
    private var crackDecay = 0.99f
    private var crackL = 1f
    private var crackR = 1f
    private var gustLevel = 0.6f

    private class Ev {
        var active = false
        var type = 0
        var t = 0f
        var dur = 0f
        var f0 = 0f
        var f1 = 0f
        var gl = 1f
        var gr = 1f
        var amp = 0f
        var ph = 0.0
        var count = 0
        var step = 0f
        var idx = -1
        var f = 0f
        val bp = Biquad()
        val lp = OnePole()
    }

    private val evs = Array(6) { Ev() }
    /** Per-bed trim so every ambience sits at about the same level. */
    private val gain = LEVEL * when (kind) {
        Ambience.ARENA -> 2.2f
        Ambience.PARK -> 2.2f
        Ambience.WINTER -> 3.2f
        Ambience.FIRESIDE -> 1.6f
        Ambience.LANES -> 2.5f
        Ambience.NONE -> 0f
    }
    private var nextEv = 1.5f

    init {
        when (kind) {
            Ambience.ARENA, Ambience.LANES -> {
                aL.bandpass(420f, 0.8f, sr); aR.bandpass(440f, 0.8f, sr)
                bL.bandpass(1100f, 1.2f, sr); bR.bandpass(1050f, 1.2f, sr)
                cL.lowpass(120f, 0.7f, sr); cR.lowpass(120f, 0.7f, sr)
            }
            Ambience.PARK -> {
                aL.bandpass(500f, 0.7f, sr); aR.bandpass(520f, 0.7f, sr)
                bL.bandpass(3500f, 0.9f, sr); bR.bandpass(3700f, 0.9f, sr)
                cL.highpass(120f, 0.7f, sr); cR.highpass(120f, 0.7f, sr)
            }
            Ambience.WINTER -> {
                aL.bandpass(450f, 5f, sr); aR.bandpass(480f, 5f, sr)
                cL.bandpass(320f, 0.7f, sr); cR.bandpass(330f, 0.7f, sr)
            }
            Ambience.FIRESIDE -> {
                cL.bandpass(260f, 0.9f, sr); cR.bandpass(280f, 0.9f, sr)
            }
            Ambience.NONE -> Unit
        }
    }

    fun next() {
        var l = 0f
        var r = 0f
        val tf = t.toFloat()
        when (kind) {
            Ambience.ARENA, Ambience.LANES -> {
                val ml = 0.7f + 0.6f * abs(slowL.lp(nl.white()))
                val mr = 0.7f + 0.6f * abs(slowR.lp(nr.white()))
                val pl = nl.pink(); val pr = nr.pink()
                val murmur = if (kind == Ambience.ARENA) 0.9f else 0.6f
                l = (aL.process(pl) * 1.6f + bL.process(pl) * 0.9f) * ml * murmur + cL.process(nl.brown()) * 0.05f
                r = (aR.process(pr) * 1.6f + bR.process(pr) * 0.9f) * mr * murmur + cR.process(nr.brown()) * 0.05f
            }
            Ambience.PARK -> {
                val g = gustSample(0.35f, 1f)
                if (counter % 64 == 0) {
                    val fc = 320f + 600f * g
                    aL.bandpass(fc, 0.8f, sr); aR.bandpass(fc * 1.05f, 0.8f, sr)
                }
                l = cL.process(aL.process(nl.pink()) * g * 0.9f) + bL.process(nl.white()) * g * g * 0.22f
                r = cR.process(aR.process(nr.pink()) * g * 0.9f) + bR.process(nr.white()) * g * g * 0.22f
            }
            Ambience.WINTER -> {
                val g = gustSample(0.3f, 1f)
                if (counter % 64 == 0) {
                    val fc = 330f + 520f * g + 80f * sin(TAU * 0.11f * tf)
                    aL.bandpass(fc, 5f, sr); aR.bandpass(fc * 1.07f, 5f, sr)
                }
                l = aL.process(nl.white()) * g * 1.4f + cL.process(nl.pink()) * g * 0.5f
                r = aR.process(nr.white()) * g * 1.4f + cR.process(nr.pink()) * g * 0.5f
            }
            Ambience.FIRESIDE -> {
                val flick = 0.6f + 0.4f * abs(slowL.lp(nl.white()) * 3f).coerceAtMost(1f)
                val roar = cL.process(nl.pink()) * flick * 0.7f
                val roarR = cR.process(nr.pink()) * flick * 0.7f
                // crackles: little bursts at random moments, the odd louder pop
                val u = ev.uniform()
                if (u < 9f / sr) startCrackle(ev.range(0.15f, 0.6f), ev.range(0.0006f, 0.002f))
                else if (u < 9.4f / sr) startCrackle(ev.range(0.7f, 1f), ev.range(0.003f, 0.007f))
                val c = crackBp.process(ev.white()) * crackEnv + popLp.process(ev.white()) * crackEnv * crackEnv * 0.8f
                crackEnv *= crackDecay
                l = roar + c * crackL
                r = roarR + c * crackR
            }
            Ambience.NONE -> Unit
        }
        // events: claps, shouts, whistles, birds, bells, distant strikes
        nextEv -= dt
        if (nextEv <= 0f) { scheduleEvent(); }
        for (e in evs) {
            if (!e.active) continue
            val s = eventSample(e)
            l += s * e.gl
            r += s * e.gr
        }
        outL = l * gain
        outR = r * gain
        t += dt
        counter++
    }

    private fun gustSample(lo: Float, hi: Float): Float {
        if (counter % 4800 == 0) gustLevel = ev.range(lo, hi)
        return gust.lp(gustLevel)
    }

    private fun startCrackle(a: Float, decaySec: Float) {
        crackEnv = max(crackEnv, a)
        crackDecay = exp(-1f / (decaySec * sr))
        val p = panGains(ev.range(-0.7f, 0.7f))
        crackL = p.first; crackR = p.second
    }

    private fun free(): Ev? = evs.firstOrNull { !it.active }

    private fun scheduleEvent() {
        when (kind) {
            Ambience.ARENA -> {
                nextEv = ev.range(0.6f, 2.5f)
                val u = ev.uniform()
                when {
                    u < 0.45f -> start(T_CLAPS, ev.range(0.06f, 0.16f))
                    u < 0.8f -> start(T_SHOUT, ev.range(0.04f, 0.1f))
                    else -> start(T_WHISTLE, ev.range(0.02f, 0.05f))
                }
            }
            Ambience.LANES -> {
                nextEv = ev.range(2.5f, 7f)
                if (ev.uniform() < 0.55f) start(T_STRIKE, ev.range(0.08f, 0.2f)) else start(T_SHOUT, ev.range(0.03f, 0.07f))
            }
            Ambience.PARK -> {
                nextEv = ev.range(1.2f, 4.5f)
                start(T_BIRD, ev.range(0.04f, 0.14f))
            }
            Ambience.WINTER -> {
                nextEv = ev.range(5f, 12f)
                start(T_BELLS, ev.range(0.03f, 0.07f))
            }
            else -> nextEv = 5f
        }
    }

    private fun start(type: Int, amp: Float) {
        val e = free() ?: return
        e.active = true; e.type = type; e.t = 0f; e.amp = amp; e.ph = 0.0; e.idx = -1
        val p = panGains(ev.range(-0.85f, 0.85f)); e.gl = p.first; e.gr = p.second
        when (type) {
            T_CLAPS -> {
                e.count = 3 + (ev.uniform() * 6).toInt(); e.step = ev.range(0.18f, 0.28f); e.dur = e.count * e.step
                e.bp.bandpass(ev.range(900f, 2200f), 1.4f, sr)
            }
            T_SHOUT -> {
                e.dur = ev.range(0.25f, 0.6f); e.f0 = if (ev.uniform() < 0.5f) ev.range(110f, 170f) else ev.range(200f, 300f)
                e.f1 = e.f0 * ev.range(0.85f, 1.3f)
                e.bp.bandpass(ev.range(550f, 800f), 3f, sr)
                e.lp.cutoff(1800f, sr)
            }
            T_WHISTLE -> { e.dur = ev.range(0.4f, 0.8f); e.f0 = ev.range(1800f, 2300f); e.f1 = e.f0 * ev.range(1.2f, 1.45f) }
            T_BIRD -> {
                e.count = 3 + (ev.uniform() * 6).toInt(); e.step = ev.range(0.07f, 0.16f); e.dur = e.count * e.step
                e.f0 = ev.range(2600f, 4200f); e.f1 = e.f0 * ev.range(1.15f, 1.6f)
            }
            T_BELLS -> {
                e.count = 6 + (ev.uniform() * 8).toInt(); e.step = ev.range(0.09f, 0.13f); e.dur = e.count * e.step + 0.3f
            }
            T_STRIKE -> { e.dur = 1.6f; e.lp.cutoff(400f, sr); e.bp.bandpass(1200f, 0.8f, sr) }
        }
    }

    private fun eventSample(e: Ev): Float {
        val t = e.t
        e.t += dt
        if (t >= e.dur) { e.active = false; return 0f }
        val k = t / e.dur
        return when (e.type) {
            T_CLAPS -> {
                val i = (t / e.step).toInt()
                val tc = t - i * e.step
                e.bp.process(ev.white()) * exp(-tc / 0.005f) * e.amp * 6f
            }
            T_SHOUT -> {
                val f = e.f0 + (e.f1 - e.f0) * sin(PI.toFloat() * k)
                e.ph += f / sr; if (e.ph >= 1.0) e.ph -= 1.0
                val src = e.lp.lp((2.0 * e.ph - 1.0).toFloat())
                e.bp.process(src) * sin(PI.toFloat() * k).pow(0.6f) * e.amp * 4f
            }
            T_WHISTLE -> {
                val f = e.f0 * (e.f1 / e.f0).pow(sin(PI.toFloat() * min(1f, k * 1.5f)))
                e.ph += TAU_D * f / sr
                (sin(e.ph) * sin(PI * k)).toFloat() * e.amp
            }
            T_BIRD -> {
                val i = (t / e.step).toInt()
                val kc = (t - i * e.step) / (e.step * 0.6f)
                if (kc >= 1f) 0f else {
                    val up = i % 2 == 0
                    val f = if (up) e.f0 + (e.f1 - e.f0) * kc else e.f1 - (e.f1 - e.f0) * kc * 0.7f
                    e.ph += TAU_D * f * (1.0 + 0.03 * sin(TAU_D * 90 * t)) / sr
                    (sin(e.ph) * sin(PI * kc)).toFloat() * e.amp
                }
            }
            T_BELLS -> {
                val i = (t / e.step).toInt()
                if (i != e.idx) { e.idx = i; e.f = ev.range(2600f, 4200f) }
                if (i >= e.count) 0f else {
                    val tc = t - i * e.step
                    (sin(TAU_D * e.f * tc) + 0.5 * sin(TAU_D * e.f * 1.47 * tc)).toFloat() * exp(-tc / 0.07f) * e.amp * min(1f, (e.dur - t) / 0.3f)
                }
            }
            T_STRIKE -> {
                if (t < 0.9f) e.lp.lp(ev.pink()) * min(1f, t / 0.3f) * e.amp * 1.5f
                else {
                    val tc = t - 0.9f
                    val g = if (ev.uniform() < 0.3f) 1f else 0.2f
                    e.bp.process(ev.white() * g) * exp(-tc / 0.22f) * e.amp * 3f
                }
            }
            else -> 0f
        }
    }

    companion object {
        private const val T_CLAPS = 1
        private const val T_SHOUT = 2
        private const val T_WHISTLE = 3
        private const val T_BIRD = 4
        private const val T_BELLS = 5
        private const val T_STRIKE = 6
        /** Overall level of every bed (calibrated so beds sit around -32 dBFS RMS at full ambience volume). */
        private const val LEVEL = 0.12f
    }
}
