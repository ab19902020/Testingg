package com.clrg.core.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sound effects. Each one is synthesised once at start-up into a stereo buffer (there are no audio files).
 * [level] is the loudness it is normalised to (loudest 100 ms RMS, dBFS), [send] how much of it goes to the room
 * reverb, [vary] the random pitch spread per play (so repeated hits don't all sound identical) and [duck] how far it
 * dips the music while it plays.
 */
enum class Sfx(val level: Float = -14f, val send: Float = 0.18f, val vary: Float = 0.03f, val duck: Float = 0f) {
    // ---- general purpose
    HIT(-13f, 0.15f, 0.06f), BOUNCE(-17f, 0.12f, 0.08f), SWOOSH(-19f, 0.12f, 0.08f), CATCH(-16f, 0.3f, 0f),
    BAD(-15f, 0.2f, 0.02f), THROW(-18f, 0.12f, 0.08f), SPLAT(-14f, 0.15f, 0.08f), SHIELD(-17f, 0.3f, 0f),
    PINS(-16f, 0.25f, 0.1f), ROLL(-19f, 0.3f, 0.04f), WHISTLE(-14f, 0.25f, 0.01f), BEEP(-16f, 0.25f, 0f),
    GO(-13f, 0.3f, 0f, 0.3f), CHEER(-16f, 0.15f, 0.03f, 0.25f), POINT(-15f, 0.3f, 0f), MISS(-17f, 0.2f, 0.02f),
    SELECT(-20f, 0.2f, 0f), TICK(-25f, 0.1f, 0.01f), SPARKLE(-18f, 0.35f, 0f), JINGLE(-17f, 0.3f, 0.03f),
    GATE(-16f, 0.25f, 0.02f), CRASH(-13f, 0.2f, 0.05f), PICKUP(-16f, 0.25f, 0f), PLACE(-16f, 0.3f, 0.02f),
    SAVE(-14f, 0.2f, 0.05f), WIN(-13f, 0.25f, 0f, 0.7f), BANG(-11f, 0.12f, 0.04f, 0.2f), SHATTER(-14f, 0.15f, 0.06f),
    PUNCH(-13f, 0.2f, 0.07f), BLOCK(-16f, 0.18f, 0.07f), RING(-14f, 0.3f, 0f, 0.3f), TWANG(-15f, 0.15f, 0.04f),
    THUNK(-15f, 0.15f, 0.06f), LAUNCH(-17f, 0.15f, 0.05f),

    // ---- menus
    UI_HOVER(-27f, 0.1f, 0.02f), UI_CONFIRM(-19f, 0.2f, 0f), UI_BACK(-21f, 0.15f, 0f), UI_OPEN(-23f, 0.15f, 0.02f),

    // ---- crowds and results
    CROWD_CHEER(-16f, 0.12f, 0.03f, 0.3f), CROWD_OOH(-18f, 0.12f, 0.04f, 0.15f), CROWD_AW(-18f, 0.12f, 0.04f, 0.15f),
    APPLAUSE(-18f, 0.12f, 0.03f, 0.15f), FANFARE(-13f, 0.25f, 0f, 0.8f), DRUMROLL(-15f, 0.25f, 0f, 0.6f),
    CONFETTI(-17f, 0.2f, 0.03f),

    // ---- golf
    GOLF_DRIVE(-12f, 0.1f, 0.03f), GOLF_IRON(-13f, 0.1f, 0.04f), GOLF_PUTT(-18f, 0.08f, 0.04f), GOLF_CUP(-15f, 0.1f, 0.02f),
    GOLF_LAND(-18f, 0.08f, 0.08f), GOLF_SPLASH(-14f, 0.12f, 0.05f), GOLF_SAND(-16f, 0.08f, 0.06f), GOLF_TREE(-16f, 0.1f, 0.05f),
    GOLF_BIRDIE(-14f, 0.25f, 0f, 0.6f), GOLF_SWING(-19f, 0.08f, 0.06f),

    // ---- shooting
    RELOAD(-18f, 0.1f, 0.03f), EMPTY(-20f, 0.08f, 0.03f), TIN_HIT(-15f, 0.2f, 0.06f), BALLOON_POP(-14f, 0.15f, 0.08f),
    GLASS(-14f, 0.2f, 0.06f), GALLERY_SHOT(-14f, 0.15f, 0.04f),

    // ---- archery and darts
    BOW_DRAW(-22f, 0.08f, 0.04f), ARROW_FLY(-20f, 0.1f, 0.05f), BULLSEYE(-14f, 0.3f, 0f, 0.3f),
    DART_HIT(-15f, 0.15f, 0.06f), DART_MISS(-17f, 0.15f, 0.06f),

    // ---- boxing
    PUNCH_HEAVY(-12f, 0.2f, 0.06f), KNOCKDOWN(-12f, 0.25f, 0.03f, 0.2f),

    // ---- ball sports
    RACKET(-14f, 0.18f, 0.05f), PADDLE(-17f, 0.15f, 0.06f), TABLE(-18f, 0.12f, 0.06f), NET(-18f, 0.12f, 0.05f),
    KICK(-14f, 0.15f, 0.05f), POST(-14f, 0.25f, 0.03f), GOAL_NET(-16f, 0.15f, 0.05f), STRIKE(-12f, 0.3f, 0.02f, 0.2f),

    // ---- winter
    SNOW_CRUNCH(-19f, 0.05f, 0.1f),
}

/** A rendered effect: 16-bit stereo samples ([r] is the same array as [l] for mono effects). */
class SfxData(val sfx: Sfx, val l: ShortArray, val r: ShortArray) {
    val mono: Boolean get() = l === r
    val frames: Int get() = l.size
    /** Bytes of sample memory held. */
    val bytes: Long get() = 2L * l.size * (if (mono) 1 else 2)
    fun left(i: Int): Float = l[i] * SCALE
    fun right(i: Int): Float = r[i] * SCALE

    companion object {
        const val SCALE = 1f / 32767f
    }
}

/**
 * A bank of decaying sine partials computed with two-pole resonators (two multiplies per partial per sample instead
 * of a sine and an exponential). Partials above 0.45 x the sample rate are dropped.
 */
class Resonators(sr: Int, freqs: FloatArray, decays: FloatArray, gains: FloatArray?) {
    private val k = freqs.size
    private val c = DoubleArray(k)
    private val rr = DoubleArray(k)
    private val y1 = DoubleArray(k)
    private val y2 = DoubleArray(k)

    init {
        for (p in 0 until k) {
            if (freqs[p] >= sr * 0.45f) continue
            val w = TAU_D * freqs[p] / sr
            val rad = exp(-1.0 / (decays[p] * sr))
            c[p] = 2 * rad * kotlin.math.cos(w)
            rr[p] = rad * rad
            y1[p] = (gains?.get(p) ?: 1f) * kotlin.math.sin(w)
        }
    }

    fun next(): Float {
        var s = 0.0
        for (p in 0 until k) {
            val y = c[p] * y1[p] - rr[p] * y2[p]
            s += y1[p]
            y2[p] = y1[p]; y1[p] = y
        }
        return s.toFloat()
    }
}

/** Formants (Hz) of the vowels the synthetic crowd can shout. */
enum class Vowel(val f1: Float, val f2: Float, val f3: Float) {
    AH(760f, 1180f, 2500f), EH(560f, 1800f, 2560f), OH(520f, 870f, 2450f), OO(340f, 820f, 2300f), AW(660f, 1000f, 2420f),
}

enum class NoiseColor { WHITE, PINK, BROWN }

enum class Wave { SINE, SQUARE, SAW, TRI }

/**
 * Builds one stereo effect from layers. Each layer is generated, scaled so its peak is the given amplitude, panned
 * and mixed in; then the whole effect can be processed (echo, room, filters) and is loudness-normalised by [finish].
 */
class SfxBuilder(val sr: Int, sec: Float, seed: Int) {
    companion object {
        /**
         * Noise layers are scaled to this many times their nominal amplitude: noise has roughly 2.2x the peak-to-RMS
         * ratio of a tone, so this keeps "amp" meaning about the same loudness for both kinds of layer.
         */
        const val NOISE_CREST = 2.2f
    }

    val n = max(1, (sec * sr).toInt())
    val l = FloatArray(n)
    val r = FloatArray(n)
    val rnd = Noise(seed * 7919 + 13)
    val dt = 1f / sr
    @PublishedApi internal val scratch = FloatArray(n)

    fun src(c: NoiseColor): Float = when (c) {
        NoiseColor.WHITE -> rnd.white()
        NoiseColor.PINK -> rnd.pink()
        NoiseColor.BROWN -> rnd.brown()
    }

    /** Mixes [m] samples of [buf] in at [start], scaled so its peak is [amp], with pan gains [gl]/[gr]. */
    @PublishedApi internal fun mixMono(buf: FloatArray, start: Int, m: Int, amp: Float, gl: Float, gr: Float) {
        var peak = 0f
        for (j in 0 until m) peak = max(peak, kotlin.math.abs(buf[j]))
        if (peak < 1e-9f) return
        val g = amp / peak
        for (j in 0 until m) {
            val i = start + j
            if (i < 0 || i >= n) continue
            l[i] += buf[j] * g * gl
            r[i] += buf[j] * g * gr
        }
    }

    /** Mixes a stereo sub-mix in at [start], scaled so its peak is [amp]. */
    fun mixStereo(tl: FloatArray, tr: FloatArray, start: Int, amp: Float) {
        var peak = 0f
        for (j in tl.indices) peak = max(peak, max(kotlin.math.abs(tl[j]), kotlin.math.abs(tr[j])))
        if (peak < 1e-9f) return
        val g = amp / peak
        for (j in tl.indices) {
            val i = start + j
            if (i < 0 || i >= n) continue
            l[i] += tl[j] * g
            r[i] += tr[j] * g
        }
    }

    /** Adds [sec] seconds of [f](t) starting at [at] seconds, peak-scaled to [amp] and panned. */
    inline fun layer(at: Float, sec: Float, pan: Float, amp: Float, f: (Float) -> Float) {
        val start = (at * sr).toInt()
        val m = min((sec * sr).toInt(), n - start)
        if (m <= 0) return
        for (j in 0 until m) scratch[j] = f(j * dt)
        val g = panGains(pan)
        mixMono(scratch, start, m, amp, g.first, g.second)
    }

    // ------------------------------------------------------------------ sources

    /** Decaying sine with an exponential pitch glide [f0] -> [f1]: kicks, thumps, the body of an impact. */
    fun thump(at: Float, f0: Float, f1: Float, glide: Float, decay: Float, amp: Float, pan: Float = 0f, attack: Float = 0.0008f) {
        var ph = 0.0
        layer(at, attack + decay * 7f, pan, amp) { t ->
            val f = f1 + (f0 - f1) * exp(-t / glide)
            ph += TAU_D * f / sr
            SineTable.sin(ph) * env(t, attack, decay)
        }
    }

    /** A very short high-passed noise tick: the transient at the front of a hit. */
    fun click(at: Float, amp: Float, hp: Float = 2500f, decay: Float = 0.0012f, pan: Float = 0f) {
        val f = Biquad().highpass(hp, 0.7f, sr)
        layer(at, decay * 8f, pan, amp * NOISE_CREST) { t -> f.process(rnd.white()) * exp(-t / decay) }
    }

    /** Filtered noise burst: high-pass [lo] and low-pass [hi] Hz (0 = off), exponential decay. */
    fun noise(at: Float, decay: Float, lo: Float, hi: Float, amp: Float, pan: Float = 0f, attack: Float = 0.0005f,
              col: NoiseColor = NoiseColor.WHITE, sec: Float = attack + decay * 7f) {
        val hp = if (lo > 0f) Biquad().highpass(lo, 0.707f, sr) else null
        val lp1 = if (hi > 0f) Biquad().lowpass(hi, 0.707f, sr) else null
        val lp2 = if (hi > 0f) Biquad().lowpass(hi, 0.707f, sr) else null
        layer(at, sec, pan, amp * NOISE_CREST) { t ->
            var x = src(col)
            if (hp != null) x = hp.process(x)
            if (lp1 != null) x = lp2!!.process(lp1.process(x))
            x * env(t, attack, decay)
        }
    }

    /** Band-pass noise burst centred on [hz]. */
    fun band(at: Float, decay: Float, hz: Float, q: Float, amp: Float, pan: Float = 0f, attack: Float = 0.0005f,
             col: NoiseColor = NoiseColor.WHITE, sec: Float = attack + decay * 7f) {
        val b1 = Biquad().bandpass(hz, q, sr)
        val b2 = Biquad().bandpass(hz, q, sr)
        layer(at, sec, pan, amp * NOISE_CREST) { t -> b2.process(b1.process(src(col))) * env(t, attack, decay) }
    }

    /** Band-pass noise whose centre sweeps [f0] -> [f1] with a smooth swell peaking at [peakAt]: whooshes. */
    fun whoosh(at: Float, sec: Float, f0: Float, f1: Float, q: Float, amp: Float, pan0: Float = 0f, pan1: Float = pan0, peakAt: Float = 0.5f) {
        val start = (at * sr).toInt()
        val m = min((sec * sr).toInt(), n - start)
        if (m <= 0) return
        val b1 = Biquad(); val b2 = Biquad()
        val tl = FloatArray(m); val tr = FloatArray(m)
        var gl = 1f; var gr = 1f
        for (j in 0 until m) {
            val k = j.toFloat() / m
            if (j % 32 == 0) {
                val f = f0 * (f1 / f0).pow(k)
                b1.bandpass(f, q, sr); b2.bandpass(f, q, sr)
                val g = panGains(pan0 + (pan1 - pan0) * k); gl = g.first; gr = g.second
            }
            val e = if (k < peakAt) (k / peakAt).let { it * it } else ((1f - k) / (1f - peakAt)).let { it * sqrt(it) }
            val s = b2.process(b1.process(rnd.white())) * e
            tl[j] = s * gl; tr[j] = s * gr
        }
        mixStereo(tl, tr, start, amp * NOISE_CREST)
    }

    /** Oscillator with an exponential glide [f0] -> [f1], one-pole low-pass, attack and release. */
    fun tone(at: Float, sec: Float, f0: Float, f1: Float, wave: Wave, lpHz: Float, amp: Float, pan: Float = 0f,
             attack: Float = 0.005f, release: Float = 0.08f, vibrato: Float = 0f) {
        var ph = 0.0
        val lp = OnePole().cutoff(lpHz, sr)
        layer(at, sec, pan, amp) { t ->
            val k = t / sec
            val f = f0 * (f1 / f0).pow(k) * (1f + vibrato * sin(TAU * 5.5f * t))
            ph += f / sr; if (ph >= 1.0) ph -= 1.0
            val p = ph.toFloat()
            val w = when (wave) {
                Wave.SINE -> SineTable.sin(TAU_D * p)
                Wave.SQUARE -> if (p < 0.5f) 1f else -1f
                Wave.SAW -> 2f * p - 1f
                Wave.TRI -> 4f * kotlin.math.abs(p - 0.5f) - 1f
            }
            val e = min(1f, t / attack) * min(1f, max(0f, sec - t) / release)
            lp.lp(w) * e
        }
    }

    /** Resonant body: decaying sine partials (wood, metal, glass) struck at [at]. */
    fun modes(at: Float, amp: Float, pan: Float, freqs: FloatArray, decays: FloatArray, gains: FloatArray? = null, attack: Float = 0.0004f) {
        var longest = 0f
        for (d in decays) longest = max(longest, d)
        val bank = Resonators(sr, freqs, decays, gains)
        layer(at, attack + longest * 6.5f, pan, amp) { t -> bank.next() * min(1f, t / attack) }
    }

    /** Two-operator FM tone: carrier [hz], modulator at [ratio], modulation index decaying [idx0] -> [idx1]. */
    fun fm(at: Float, amp: Float, pan: Float, hz: Float, ratio: Float, idx0: Float, idx1: Float, idxDecay: Float,
           attack: Float, decay: Float, sec: Float = attack + decay * 6f) {
        var pc = 0.0; var pm = 0.0
        layer(at, sec, pan, amp) { t ->
            pc += TAU_D * hz / sr; pm += TAU_D * hz * ratio / sr
            val idx = idx1 + (idx0 - idx1) * exp(-t / idxDecay)
            SineTable.sin(pc + idx * SineTable.sin(pm)) * env(t, attack, decay)
        }
    }

    /** FM bell (bright strike, inharmonic shimmer). */
    fun bell(at: Float, midi: Float, amp: Float, pan: Float = 0f, decay: Float = 0.9f) =
        fm(at, amp, pan, midiToHz(midi), 3.5f, 2.6f, 0.15f, 0.2f, 0.002f, decay)

    /** Glassy chime: pure tone with two inharmonic partials. */
    fun glass(at: Float, midi: Float, amp: Float, pan: Float = 0f, decay: Float = 0.6f) {
        val f = midiToHz(midi)
        modes(at, amp, pan, floatArrayOf(f, f * 2.76f, f * 5.4f), floatArrayOf(decay, decay * 0.35f, decay * 0.12f), floatArrayOf(1f, 0.35f, 0.15f), 0.001f)
    }

    /** Marimba-like wooden bar. */
    fun marimba(at: Float, midi: Float, amp: Float, pan: Float = 0f) {
        val f = midiToHz(midi)
        modes(at, amp, pan, floatArrayOf(f, f * 3.93f, f * 9.2f), floatArrayOf(0.22f, 0.05f, 0.015f), floatArrayOf(1f, 0.3f, 0.1f), 0.0008f)
    }

    /** Brass note (FM 1:1, brighter as it gets louder, gentle vibrato). */
    fun brass(at: Float, dur: Float, midi: Float, amp: Float, pan: Float = 0f, detune: Float = 0f) {
        val hz = midiToHz(midi) * (1f + detune)
        var pc = 0.0; var pm = 0.0
        val rel = 0.14f
        layer(at, dur + rel * 6f, pan, amp) { t ->
            val e = when {
                t < 0.035f -> t / 0.035f
                t < dur -> 1f - 0.2f * (t - 0.035f) / dur
                else -> 0.8f * exp(-(t - dur) / rel)
            }
            val vib = 1.0 + 0.006 * SineTable.sin(TAU_D * 5.3 * t) * min(1.0, t / 0.35)
            val step = TAU_D * hz * vib / sr
            pc += step; pm += step
            val idx = 0.5 + 3.0 * e
            SineTable.sin(pc + idx * SineTable.sin(pm)) * e
        }
    }

    /** A section of brass playing [midis] together (each doubled and slightly detuned, spread across the stereo field). */
    fun brassChord(at: Float, dur: Float, midis: FloatArray, amp: Float) {
        for ((k, m) in midis.withIndex()) {
            val pan = if (midis.size == 1) 0f else -0.5f + k / (midis.size - 1f)
            brass(at, dur, m, amp, pan * 0.7f, -0.003f)
            brass(at + 0.004f, dur, m, amp * 0.8f, -pan * 0.5f, 0.0035f)
        }
    }

    /** Kettle drum. */
    fun timpani(at: Float, midi: Float, amp: Float, pan: Float = 0f) {
        val f = midiToHz(midi)
        thump(at, f * 1.5f, f, 0.03f, 0.35f, amp, pan)
        modes(at, amp * 0.45f, pan, floatArrayOf(f * 1.5f, f * 1.99f, f * 2.44f), floatArrayOf(0.4f, 0.3f, 0.2f), floatArrayOf(1f, 0.6f, 0.4f))
        noise(at, 0.05f, 0f, 900f, amp * 0.3f, pan, col = NoiseColor.PINK)
    }

    /** Crash cymbal. */
    fun cymbal(at: Float, amp: Float, decay: Float = 0.9f, pan: Float = 0f) {
        noise(at, decay, 4500f, 0f, amp, pan, attack = 0.001f)
        val fs = FloatArray(10) { rnd.range(3000f, 9500f) }
        val ds = FloatArray(10) { decay * rnd.range(0.3f, 0.8f) }
        modes(at, amp * 0.35f, pan, fs, ds)
        click(at, amp * 0.5f, 3000f)
    }

    /** Snare drum hit. */
    fun snare(at: Float, amp: Float, pan: Float = 0f) {
        thump(at, 230f, 180f, 0.01f, 0.04f, amp * 0.7f, pan)
        band(at, 0.09f, 4200f, 0.7f, amp, pan)
        noise(at, 0.045f, 1800f, 0f, amp * 0.6f, pan)
    }

    /** Karplus-Strong plucked string. */
    fun pluck(at: Float, hz: Float, amp: Float, pan: Float = 0f, decay: Float = 0.4f, bright: Float = 0.5f) {
        val len = max(2, (sr / hz).toInt())
        val ks = FloatArray(len) { rnd.white() }
        val g = exp(-1.0 / (decay * hz)).toFloat()
        var pos = 0
        layer(at, decay * 6f, pan, amp) { _ ->
            val a = ks[pos]
            val b = ks[(pos + 1) % len]
            val v = g * (bright * a + (1f - bright) * 0.5f * (a + b))
            ks[pos] = v
            pos = (pos + 1) % len
            v
        }
    }

    /** Short pitch chirp (bubbles, birds, glitter). */
    fun chirp(at: Float, sec: Float, f0: Float, f1: Float, amp: Float, pan: Float = 0f) {
        var ph = 0.0
        layer(at, sec, pan, amp) { t ->
            val k = t / sec
            ph += TAU_D * f0 * (f1 / f0).pow(k) / sr
            SineTable.sin(ph) * SineTable.sin(PI * k)
        }
    }

    /**
     * Many tiny resonant fragments scattered over [sec] seconds (debris, shards, pins, sand). [tonal] mixes pure sine
     * ringing (1) with filtered noise grains (0). [density](k) shapes when they happen.
     */
    fun grains(at: Float, sec: Float, count: Int, lo: Float, hi: Float, dLo: Float, dHi: Float, amp: Float, panSpread: Float,
               tonal: Float, density: (Float) -> Float = { 1f }) {
        val start = (at * sr).toInt()
        val m = min(((sec + dHi * 6f) * sr).toInt(), n - start)
        if (m <= 0) return
        val tl = FloatArray(m); val tr = FloatArray(m)
        repeat(count) {
            var k: Float
            var guard = 0
            do { k = rnd.uniform(); guard++ } while (rnd.uniform() > density(k) && guard < 50)
            val i0 = (k * sec * sr).toInt()
            val f = lo * (hi / lo).pow(rnd.uniform())
            val d = rnd.range(dLo, dHi)
            val a = rnd.range(0.3f, 1f)
            val (gl, gr) = panGains(rnd.range(-panSpread, panSpread))
            val bp = Biquad().bandpass(f, 3f, sr)
            val len = (d * 6f * sr).toInt()
            val w = TAU_D * f / sr
            val rad = exp(-1.0 / (d * sr))
            val c = 2 * rad * kotlin.math.cos(w)
            val rr = rad * rad
            var y1 = kotlin.math.sin(w); var y2 = 0.0
            var ne = 1f
            val nd = exp(-1f / (d * sr))
            for (j in 0 until len) {
                val i = i0 + j
                if (i >= m) break
                val ramp = min(1f, j / 15f)
                val tone = y1.toFloat()
                val y = c * y1 - rr * y2; y2 = y1; y1 = y
                val s = (tonal * tone + (1f - tonal) * 3f * bp.process(rnd.white()) * ne) * ramp * a
                ne *= nd
                tl[i] += s * gl; tr[i] += s * gr
            }
        }
        mixStereo(tl, tr, start, amp)
    }

    /** One shake of a cluster of sleigh bells. */
    fun jingle(at: Float, amp: Float, pan: Float = 0f, bells: Int = 6, spread: Float = 0.035f) {
        val start = (at * sr).toInt()
        val m = min((0.5f * sr).toInt(), n - start)
        if (m <= 0) return
        val tl = FloatArray(m); val tr = FloatArray(m)
        repeat(bells) {
            val f = rnd.range(2600f, 4400f)
            val (gl, gr) = panGains((pan + rnd.range(-0.35f, 0.35f)).coerceIn(-1f, 1f))
            val parts = floatArrayOf(1f, 1.47f, 2.09f, 2.56f)
            val decs = floatArrayOf(0.16f, 0.11f, 0.07f, 0.045f)
            // the pellet inside rattles: two or three strikes in quick succession
            val hits = 2 + (rnd.uniform() * 2).toInt()
            var t0 = rnd.range(0f, spread)
            val gains = FloatArray(parts.size) { 1f / (it + 1) }
            val freqs = FloatArray(parts.size) { f * parts[it] }
            repeat(hits) { h ->
                val a = rnd.range(0.5f, 1f) * (1f - 0.3f * h)
                val i0 = (t0 * sr).toInt()
                val res = Resonators(sr, freqs, decs, gains)
                for (j in 0 until (0.4f * sr).toInt()) {
                    val i = i0 + j
                    if (i >= m) break
                    val v = res.next() * a * min(1f, j / 15f)
                    tl[i] += v * gl; tr[i] += v * gr
                }
                t0 += rnd.range(0.006f, 0.018f)
            }
        }
        mixStereo(tl, tr, start, amp)
    }

    /**
     * A crowd shouting: [voices] people, each a buzzy voice at a random pitch through three formant filters that
     * morph from vowel [v0] to [v1], with a pitch contour (1 + [rise] sin(pi k) - [fall] k), plus a diffuse
     * noise "bed" ([bed] relative level) for the distant mass of the crowd. [shape](k) is the overall swell.
     */
    fun crowd(at: Float, sec: Float, voices: Int, v0: Vowel, v1: Vowel, amp: Float, rise: Float, fall: Float,
              onsetSpread: Float, bed: Float, shape: (Float) -> Float) {
        val start = (at * sr).toInt()
        val m = min((sec * sr).toInt(), n - start)
        if (m <= 0) return
        val tl = FloatArray(m); val tr = FloatArray(m)
        val shapeT = FloatArray(m / 64 + 2) { shape(min(1f, it * 64f / m)) }
        val b1 = Biquad(); val b2 = Biquad(); val b3 = Biquad()
        repeat(voices) {
            val high = rnd.uniform() < 0.45f
            val f0 = if (high) rnd.range(200f, 330f) else rnd.range(105f, 165f)
            val fs = if (high) rnd.range(1.06f, 1.2f) else rnd.range(0.92f, 1.04f)
            val on = rnd.range(0f, onsetSpread) * sec
            val len = rnd.range(0.55f, 1f) * (sec - on)
            val (gl, gr) = panGains(rnd.range(-0.95f, 0.95f))
            val vibR = rnd.range(4.5f, 7f)
            val vibD = rnd.range(0.008f, 0.025f)
            val att = rnd.range(0.04f, 0.16f)
            val loud = rnd.range(0.45f, 1f)
            val lp = OnePole().cutoff(4500f, sr)
            b1.reset(); b2.reset(); b3.reset()
            var ph = rnd.uniform().toDouble()
            val i0 = (on * sr).toInt()
            val i1 = min(m, i0 + (len * sr).toInt())
            for (i in i0 until i1) {
                val t = (i - i0) * dt
                val k = t / len
                val gk = i.toFloat() / m
                if ((i - i0) % 64 == 0) {
                    b1.bandpass((v0.f1 + (v1.f1 - v0.f1) * gk) * fs, 5f, sr)
                    b2.bandpass((v0.f2 + (v1.f2 - v0.f2) * gk) * fs, 7f, sr)
                    b3.bandpass((v0.f3 + (v1.f3 - v0.f3) * gk) * fs, 9f, sr)
                }
                val pitch = f0 * (1f + rise * SineTable.sin(PI * k) - fall * k) * (1f + vibD * SineTable.sin(TAU_D * vibR * t))
                ph += pitch / sr; if (ph >= 1.0) ph -= 1.0
                val x = lp.lp((2.0 * ph - 1.0).toFloat() * 0.8f + rnd.white() * 0.35f)
                val tail = if (k > 0.7f) max(0f, 1f - (k - 0.7f) / 0.3f) else 1f
                val e = min(1f, t / att) * tail * tail
                val s = (b1.process(x) + 0.55f * b2.process(x) + 0.25f * b3.process(x)) * e * loud * shapeT[i / 64]
                tl[i] += s * gl; tr[i] += s * gr
            }
        }
        if (bed > 0f) {
            var peak = 1e-9f
            for (j in 0 until m) peak = max(peak, max(kotlin.math.abs(tl[j]), kotlin.math.abs(tr[j])))
            val bl = FloatArray(m); val br = FloatArray(m)
            val nl = Noise(start + voices * 31 + 5); val nr = Noise(start + voices * 31 + 9)
            val fl1 = Biquad(); val fl2 = Biquad(); val fr1 = Biquad(); val fr2 = Biquad()
            for (j in 0 until m) {
                val gk = j.toFloat() / m
                if (j % 64 == 0) {
                    val f1 = v0.f1 + (v1.f1 - v0.f1) * gk; val f2 = v0.f2 + (v1.f2 - v0.f2) * gk
                    fl1.bandpass(f1, 2.5f, sr); fr1.bandpass(f1 * 1.04f, 2.5f, sr)
                    fl2.bandpass(f2, 3f, sr); fr2.bandpass(f2 * 0.97f, 3f, sr)
                }
                val sh = shapeT[j / 64]
                val a = nl.pink(); val b = nr.pink()
                bl[j] = (fl1.process(a) + 0.5f * fl2.process(a)) * sh
                br[j] = (fr1.process(b) + 0.5f * fr2.process(b)) * sh
            }
            var bp = 1e-9f
            for (j in 0 until m) bp = max(bp, max(kotlin.math.abs(bl[j]), kotlin.math.abs(br[j])))
            val g = bed * peak / bp
            for (j in 0 until m) { tl[j] += bl[j] * g; tr[j] += br[j] * g }
        }
        mixStereo(tl, tr, start, amp)
    }

    /** Applause: [rate] claps per second at full swell, each a short band-passed noise burst at its own pitch and place. */
    fun applause(at: Float, sec: Float, rate: Float, amp: Float, shape: (Float) -> Float) {
        val start = (at * sr).toInt()
        val m = min(((sec + 0.05f) * sr).toInt(), n - start)
        if (m <= 0) return
        val tl = FloatArray(m); val tr = FloatArray(m)
        var t = 0f
        while (true) {
            val dens = max(0.03f, shape(min(1f, t / sec)))
            t += -ln(max(1e-4f, rnd.uniform())) / (rate * dens)
            if (t >= sec) break
            val a = shape(t / sec) * rnd.range(0.3f, 1f)
            val bp = Biquad().bandpass(rnd.range(800f, 2600f), rnd.range(0.9f, 2f), sr)
            val dec = rnd.range(0.003f, 0.008f)
            val (gl, gr) = panGains(rnd.range(-1f, 1f))
            val i0 = (t * sr).toInt()
            for (j in 0 until (dec * 8f * sr).toInt()) {
                val i = i0 + j
                if (i >= m) break
                val s = bp.process(rnd.white()) * exp(-j * dt / dec) * a
                tl[i] += s * gl; tr[i] += s * gr
            }
        }
        mixStereo(tl, tr, start, amp)
    }

    /** Two-finger whistle from somebody in the crowd. */
    fun fingerWhistle(at: Float, amp: Float, pan: Float) {
        var ph = 0.0
        val f0 = rnd.range(1900f, 2400f)
        layer(at, 0.7f, pan, amp) { t ->
            val k = t / 0.7f
            val f = f0 * (1f + 0.35f * sin(PI.toFloat() * min(1f, k * 1.6f))) * (1f + 0.01f * sin(TAU * 7f * t))
            ph += TAU_D * f / sr
            val e = min(1f, t / 0.03f) * min(1f, (0.7f - t) / 0.12f)
            (sin(ph) * e).toFloat() + rnd.white() * 0.04f * e
        }
    }

    // ------------------------------------------------------------------ processing

    /** Echo off distant surfaces: each bounce [level] quieter and darker ([lpHz]), the right side a little later. */
    fun echo(delay: Float, level: Float, lpHz: Float) {
        val d = (delay * sr).toInt()
        val d2 = (delay * 1.37f * sr).toInt()
        if (d <= 0 || d2 >= n) return
        val fl = OnePole().cutoff(lpHz, sr)
        val fr = OnePole().cutoff(lpHz, sr)
        for (i in d until n) {
            l[i] += level * fl.lp(r[i - d])
            if (i >= d2) r[i] += level * fr.lp(l[i - d2])
        }
    }

    /** Bakes a room reverb into the effect (the tail must fit in the buffer). */
    fun room(size: Float, damp: Float, wet: Float, width: Float = 1f, pre: Float = 0.01f) {
        val rv = Reverb(sr)
        rv.configure(size, damp, width, pre, sr)
        for (i in 0 until n) {
            rv.process((l[i] + r[i]) * 0.5f)
            l[i] += rv.outL * wet
            r[i] += rv.outR * wet
        }
    }

    fun lowpass(hz: Float) {
        val a = Biquad().lowpass(hz, 0.707f, sr); val b = Biquad().lowpass(hz, 0.707f, sr)
        for (i in 0 until n) { l[i] = a.process(l[i]); r[i] = b.process(r[i]) }
    }

    fun highpass(hz: Float) {
        val a = Biquad().highpass(hz, 0.707f, sr); val b = Biquad().highpass(hz, 0.707f, sr)
        for (i in 0 until n) { l[i] = a.process(l[i]); r[i] = b.process(r[i]) }
    }

    /**
     * Loudness-normalises to [levelDb] (loudest 100 ms RMS), keeps the peak under 0.95, removes DC rumble, trims the
     * silent tail and shares one array when both channels are identical.
     */
    fun finish(sfx: Sfx, levelDb: Float = sfx.level): SfxData {
        highpass(25f)
        // loudness is judged on a copy weighted like a small speaker hears it (bass rolled off below ~150 Hz)
        val wl = FloatArray(n); val wr = FloatArray(n)
        val h1 = Biquad().highpass(150f, 0.6f, sr); val h2 = Biquad().highpass(150f, 0.6f, sr)
        for (i in 0 until n) { wl[i] = h1.process(l[i]); wr[i] = h2.process(r[i]) }
        val win = max(1, sr / 10)
        var best = 1e-12
        var acc = 0.0
        for (i in 0 until n) {
            acc += 0.5 * (wl[i] * wl[i] + wr[i] * wr[i])
            if (i >= win) { val o = i - win; acc -= 0.5 * (wl[o] * wl[o] + wr[o] * wr[o]) }
            best = max(best, acc / min(i + 1, win))
        }
        val rms = sqrt(best).toFloat()
        var peak = 1e-9f
        for (i in 0 until n) peak = max(peak, max(kotlin.math.abs(l[i]), kotlin.math.abs(r[i])))
        val g = min(10f.pow(levelDb / 20f) / max(rms, 1e-9f), 0.95f / peak)
        var end = n
        val floor = 2e-4f / g
        while (end > 1 && kotlin.math.abs(l[end - 1]) < floor && kotlin.math.abs(r[end - 1]) < floor) end--
        val fade = min(end, sr / 200)
        val outL = ShortArray(end); val outR = ShortArray(end)
        var same = true
        for (i in 0 until end) {
            val f = if (i >= end - fade) (end - i).toFloat() / fade else 1f
            outL[i] = (l[i] * g * f * 32767f).toInt().coerceIn(-32767, 32767).toShort()
            outR[i] = (r[i] * g * f * 32767f).toInt().coerceIn(-32767, 32767).toShort()
            if (outL[i] != outR[i]) same = false
        }
        return SfxData(sfx, outL, if (same) outL else outR)
    }
}

/** The recipes: how each effect is built from layers. */
object SfxSynth {
    fun render(sfx: Sfx, sr: Int): SfxData {
        fun b(sec: Float) = SfxBuilder(sr, sec, sfx.ordinal + 1)
        fun fl(vararg v: Float) = v
        val x: SfxBuilder = when (sfx) {
            // ---------------------------------------------------------- general purpose
            Sfx.HIT -> b(0.4f).apply {
                thump(0f, 260f, 130f, 0.012f, 0.03f, 0.6f)
                click(0f, 0.6f)
                noise(0f, 0.035f, 700f, 6000f, 0.9f)
                band(0f, 0.03f, 900f, 1f, 0.7f)
                modes(0f, 0.35f, 0f, fl(1250f, 2780f), fl(0.03f, 0.018f), fl(1f, 0.6f))
            }
            Sfx.BOUNCE -> b(0.25f).apply {
                thump(0f, 260f, 140f, 0.01f, 0.025f, 0.8f)
                band(0f, 0.012f, 900f, 1f, 0.6f)
                noise(0f, 0.01f, 0f, 2500f, 0.35f, col = NoiseColor.PINK)
            }
            Sfx.SWOOSH -> b(0.32f).apply {
                whoosh(0f, 0.24f, 350f, 1900f, 1.3f, 1f, -0.3f, 0.3f, 0.55f)
                whoosh(0.02f, 0.2f, 800f, 3500f, 2.5f, 0.35f, -0.2f, 0.4f, 0.6f)
            }
            Sfx.CATCH -> b(1.3f).apply {
                thump(0f, 300f, 160f, 0.01f, 0.03f, 0.45f)
                bell(0f, 79f, 0.6f, -0.2f)
                bell(0.05f, 84f, 0.65f, 0f)
                bell(0.1f, 88f, 0.8f, 0.2f, 0.8f)
                glass(0.1f, 100f, 0.22f, 0.4f)
                noise(0.1f, 0.12f, 6500f, 0f, 0.12f, attack = 0.02f)
            }
            Sfx.BAD -> b(0.7f).apply {
                thump(0f, 320f, 110f, 0.06f, 0.12f, 1f)
                tone(0f, 0.42f, 233f, 150f, Wave.SQUARE, 1100f, 0.6f, attack = 0.004f, release = 0.15f)
                marimba(0f, 47f, 0.4f)
            }
            Sfx.THROW -> b(0.36f).apply {
                whoosh(0f, 0.27f, 450f, 1500f, 1.1f, 1f, -0.2f, 0.25f, 0.5f)
                noise(0f, 0.05f, 1500f, 5000f, 0.2f, attack = 0.02f)
            }
            Sfx.SPLAT -> b(0.5f).apply {
                thump(0f, 180f, 80f, 0.015f, 0.04f, 0.6f)
                noise(0f, 0.07f, 200f, 2500f, 1f, col = NoiseColor.PINK)
                band(0f, 0.05f, 1000f, 0.9f, 0.6f)
                grains(0.005f, 0.12f, 28, 1500f, 6500f, 0.002f, 0.006f, 0.6f, 0.6f, 0f)
                noise(0f, 0.15f, 0f, 900f, 0.4f, col = NoiseColor.BROWN)
            }
            Sfx.SHIELD -> b(1.0f).apply {
                val notes = fl(76f, 79f, 83f, 86f, 88f)
                for ((k, m) in notes.withIndex()) glass(k * 0.035f, m, 0.5f, -0.4f + 0.2f * k, 0.45f)
                whoosh(0f, 0.35f, 400f, 4000f, 3f, 0.4f, -0.3f, 0.3f, 0.7f)
            }
            Sfx.PINS -> b(0.5f).apply {
                val f = rnd.range(650f, 800f)
                modes(0f, 1f, 0f, fl(f, f * 2.31f, f * 3.9f), fl(0.05f, 0.03f, 0.015f), fl(1f, 0.6f, 0.3f))
                val t2 = rnd.range(0.04f, 0.09f)
                modes(t2, 0.6f, 0f, fl(f * 0.8f, f * 1.9f), fl(0.04f, 0.02f), fl(1f, 0.5f))
                grains(0.02f, 0.25f, 6, 800f, 2500f, 0.01f, 0.03f, 0.4f, 0.5f, 0.8f)
                thump(0.06f, 160f, 90f, 0.01f, 0.04f, 0.3f)
            }
            Sfx.ROLL -> b(1.8f).apply {
                thump(0f, 120f, 60f, 0.02f, 0.08f, 0.7f)
                modes(0f, 0.3f, 0f, fl(210f, 480f), fl(0.06f, 0.03f))
                val lp1 = Biquad().lowpass(420f, 0.8f, sr); val lp2 = Biquad().lowpass(420f, 0.8f, sr)
                val hp = Biquad().highpass(90f, 0.7f, sr)
                layer(0f, 1.7f, 0f, 1f) { t ->
                    val e = min(1f, t / 0.12f) * min(1f, (1.7f - t) / 0.5f)
                    val am = 1f + 0.18f * sin(TAU * 11f * t) + 0.1f * sin(TAU * 3.3f * t)
                    hp.process(lp2.process(lp1.process(rnd.white()))) * e * am
                }
            }
            Sfx.WHISTLE -> b(0.55f).apply {
                var ph = 0.0
                layer(0f, 0.5f, 0f, 1f) { t ->
                    val e = min(1f, t / 0.015f) * (if (t > 0.38f) exp(-(t - 0.38f) / 0.03f) else 1f)
                    val trill = 0.55f + 0.45f * sin(TAU * 31f * t + 0.3f * sin(TAU * 5f * t))
                    ph += TAU_D * (2850f + 90f * sin(TAU * 31f * t)) / sr
                    ((sin(ph) + 0.15 * sin(2 * ph)) * trill * e).toFloat()
                }
                band(0f, 0.4f, 2900f, 4f, 0.18f, attack = 0.015f, sec = 0.45f)
            }
            Sfx.BEEP -> b(0.45f).apply {
                tone(0f, 0.17f, 880f, 880f, Wave.SINE, 8000f, 1f, attack = 0.002f, release = 0.1f)
                tone(0f, 0.12f, 1760f, 1760f, Wave.SINE, 8000f, 0.25f, attack = 0.002f, release = 0.08f)
                fm(0f, 0.3f, 0f, 880f, 2f, 1f, 0f, 0.05f, 0.002f, 0.15f)
                click(0f, 0.2f)
            }
            Sfx.GO -> b(1.5f).apply {
                brassChord(0f, 0.32f, fl(67f, 72f, 76f, 79f), 0.7f)
                timpani(0f, 43f, 0.8f)
                cymbal(0f, 0.55f, 0.6f)
                tone(0f, 0.3f, 1760f, 1760f, Wave.SINE, 8000f, 0.15f, attack = 0.002f, release = 0.2f)
            }
            Sfx.CHEER -> b(2.3f).apply {
                val sh = { k: Float -> min(1f, k / 0.12f) * (1f - k).pow(0.7f) }
                crowd(0f, 1.9f, 26, Vowel.EH, Vowel.AH, 1f, 0.18f, 0.1f, 0.25f, 0.35f, sh)
                applause(0.1f, 1.9f, 220f, 0.55f, sh)
                fingerWhistle(0.3f, 0.22f, 0.5f)
                room(0.7f, 0.4f, 0.18f)
            }
            Sfx.POINT -> b(1.4f).apply {
                bell(0f, 84f, 0.8f, -0.15f, 0.8f)
                bell(0.08f, 91f, 0.7f, 0.15f, 0.9f)
                glass(0.08f, 103f, 0.2f, 0.3f)
            }
            Sfx.MISS -> b(0.6f).apply {
                tone(0f, 0.15f, 392f, 380f, Wave.SQUARE, 1100f, 0.8f, attack = 0.008f, release = 0.05f)
                tone(0.15f, 0.36f, 294f, 262f, Wave.SQUARE, 900f, 0.8f, attack = 0.008f, release = 0.2f, vibrato = 0.012f)
            }
            Sfx.SELECT -> b(0.8f).apply {
                glass(0f, 88f, 0.8f, -0.1f, 0.35f)
                glass(0.05f, 95f, 0.7f, 0.1f, 0.4f)
                click(0f, 0.25f, 3000f)
            }
            Sfx.TICK -> b(0.07f).apply {
                modes(0f, 1f, 0f, fl(2300f, 5100f), fl(0.006f, 0.003f), fl(1f, 0.5f))
                click(0f, 0.4f, 4000f)
            }
            Sfx.SPARKLE -> b(1.1f).apply {
                val scale = fl(88f, 91f, 93f, 95f, 98f, 100f, 103f)
                for (k in 0 until 9) {
                    glass(k * 0.05f + rnd.range(0f, 0.02f), scale[(rnd.uniform() * scale.size).toInt().coerceAtMost(scale.size - 1)],
                        rnd.range(0.3f, 0.6f), rnd.range(-0.8f, 0.8f), 0.25f)
                }
                noise(0f, 0.25f, 7000f, 0f, 0.15f, attack = 0.05f)
            }
            Sfx.JINGLE -> b(0.9f).apply {
                val ts = fl(0f, 0.1f, 0.2f, 0.3f, 0.42f)
                val amps = fl(1f, 0.8f, 0.9f, 0.75f, 0.85f)
                for (k in ts.indices) jingle(ts[k], amps[k], if (k % 2 == 0) -0.3f else 0.3f)
            }
            Sfx.GATE -> b(0.8f).apply {
                glass(0f, 86f, 0.8f, 0f, 0.5f)
                glass(0f, 93f, 0.3f, 0.2f, 0.4f)
                whoosh(0f, 0.25f, 600f, 2500f, 1.5f, 0.5f, -0.6f, 0.6f)
            }
            Sfx.CRASH -> b(1.0f).apply {
                thump(0f, 140f, 60f, 0.03f, 0.1f, 0.7f)
                band(0f, 0.07f, 700f, 1f, 0.8f, col = NoiseColor.PINK)
                noise(0f, 0.22f, 60f, 700f, 0.9f, attack = 0.003f, col = NoiseColor.BROWN)
                noise(0f, 0.08f, 1500f, 0f, 0.5f)
                grains(0.02f, 0.45f, 40, 1200f, 6000f, 0.003f, 0.012f, 0.5f, 0.9f, 0f) { k -> (1f - k) * (1f - k) }
                modes(0f, 0.4f, 0f, fl(190f, 420f, 860f), fl(0.08f, 0.05f, 0.03f))
            }
            Sfx.PICKUP -> b(0.8f).apply {
                pluck(0f, 523f, 0.7f, -0.1f, 0.25f, 0.6f)
                pluck(0.05f, 784f, 0.7f, 0.1f, 0.25f, 0.6f)
                glass(0.1f, 96f, 0.45f, 0.2f, 0.4f)
                noise(0.08f, 0.1f, 7000f, 0f, 0.1f, attack = 0.02f)
            }
            Sfx.PLACE -> b(1.4f).apply {
                modes(0f, 0.8f, 0f, fl(2050f, 5150f, 8800f), fl(0.5f, 0.2f, 0.08f), fl(1f, 0.5f, 0.25f))
                bell(0f, 79f, 0.4f, -0.2f, 0.8f)
                bell(0.06f, 84f, 0.5f, 0.2f, 0.9f)
                thump(0f, 400f, 250f, 0.01f, 0.02f, 0.3f)
            }
            Sfx.SAVE -> b(0.45f).apply {
                thump(0f, 200f, 95f, 0.012f, 0.04f, 0.7f)
                band(0f, 0.035f, 1600f, 0.8f, 1f)
                band(0f, 0.03f, 600f, 1f, 0.6f)
                click(0f, 0.5f)
                noise(0f, 0.05f, 0f, 600f, 0.4f, col = NoiseColor.BROWN)
            }
            Sfx.WIN -> b(3.4f).apply {
                val mel = fl(72f, 76f, 79f, 84f, 79f, 84f, 88f)
                val at = fl(0f, 0.15f, 0.3f, 0.45f, 0.8f, 0.95f, 1.1f)
                for (k in mel.indices) bell(at[k], mel[k], if (k == mel.size - 1) 0.9f else 0.7f, (k % 3 - 1) * 0.3f, if (k == mel.size - 1) 1.6f else 0.8f)
                for (k in 0 until 8) jingle(0.5f + k * 0.12f, 0.45f, if (k % 2 == 0) -0.5f else 0.5f, 5)
                timpani(0f, 48f, 0.6f); timpani(0.45f, 43f, 0.6f); timpani(1.1f, 36f, 0.8f)
                cymbal(1.1f, 0.5f, 1.1f)
                for (m in fl(60f, 64f, 67f, 72f)) tone(0.4f, 2.4f, midiToHz(m), midiToHz(m), Wave.SAW, 1500f, 0.18f, (m - 66f) / 12f, attack = 0.5f, release = 0.9f)
                room(0.8f, 0.4f, 0.22f)
            }
            Sfx.BANG -> b(1.7f).apply {
                click(0f, 1f, 1500f, 0.0008f)
                noise(0f, 0.01f, 0f, 7000f, 1f)
                noise(0f, 0.05f, 0f, 1400f, 0.85f, col = NoiseColor.PINK)
                band(0f, 0.03f, 1500f, 0.8f, 0.9f)
                thump(0f, 110f, 50f, 0.015f, 0.08f, 0.7f)
                noise(0.03f, 0.5f, 60f, 600f, 0.3f, attack = 0.06f, col = NoiseColor.BROWN)
                echo(0.34f, 0.22f, 1800f)
            }
            Sfx.SHATTER -> b(0.65f).apply {
                click(0f, 0.7f)
                noise(0f, 0.012f, 1800f, 0f, 0.8f)
                grains(0f, 0.3f, 45, 1800f, 7500f, 0.002f, 0.01f, 0.9f, 0.9f, 0.35f) { k -> (1f - k) * (1f - k) }
                noise(0f, 0.06f, 0f, 500f, 0.4f, col = NoiseColor.BROWN)
            }
            Sfx.PUNCH -> b(0.45f).apply {
                thump(0f, 180f, 85f, 0.015f, 0.05f, 0.75f)
                band(0f, 0.03f, 1700f, 0.8f, 1f)
                band(0f, 0.03f, 520f, 1f, 0.7f, col = NoiseColor.PINK)
                click(0f, 0.6f, 3500f, 0.0008f)
            }
            Sfx.BLOCK -> b(0.3f).apply {
                thump(0f, 220f, 120f, 0.01f, 0.025f, 0.6f)
                noise(0f, 0.025f, 0f, 1200f, 0.7f, col = NoiseColor.PINK)
                band(0f, 0.025f, 1100f, 1.2f, 0.9f)
            }
            Sfx.RING -> b(2.8f).apply {
                val f = 690f
                for (t in fl(0f, 0.26f, 0.52f)) {
                    modes(t, 1f, 0f, fl(f, f * 2f, f * 2.76f, f * 3.92f, f * 5.4f), fl(1.6f, 1.1f, 0.7f, 0.45f, 0.25f), fl(1f, 0.55f, 0.7f, 0.35f, 0.25f))
                    click(t, 0.35f, 3000f)
                }
            }
            Sfx.TWANG -> b(0.7f).apply {
                pluck(0f, 98f, 1f, 0f, 0.25f, 0.4f)
                thump(0f, 130f, 70f, 0.01f, 0.05f, 0.6f)
                band(0f, 0.01f, 2500f, 1f, 0.4f)
                whoosh(0.01f, 0.2f, 2000f, 900f, 2f, 0.3f)
            }
            Sfx.THUNK -> b(0.6f).apply {
                thump(0f, 240f, 130f, 0.01f, 0.025f, 0.7f)
                band(0f, 0.025f, 800f, 1f, 0.9f, col = NoiseColor.PINK)
                noise(0f, 0.025f, 0f, 1500f, 0.5f, col = NoiseColor.PINK)
                click(0f, 0.5f, 2000f)
                tone(0.005f, 0.45f, 150f, 140f, Wave.SQUARE, 900f, 0.3f, attack = 0.002f, release = 0.4f)
            }
            Sfx.LAUNCH -> b(0.65f).apply {
                modes(0f, 0.7f, 0f, fl(2300f, 4100f, 5900f), fl(0.02f, 0.012f, 0.008f))
                thump(0f, 95f, 50f, 0.01f, 0.05f, 0.8f)
                pluck(0f, 196f, 0.4f, 0f, 0.15f, 0.7f)
                whoosh(0.02f, 0.45f, 500f, 1100f, 1.3f, 0.6f, 0f, 0f, 0.3f)
            }

            // ---------------------------------------------------------- menus
            Sfx.UI_HOVER -> b(0.12f).apply {
                modes(0f, 1f, 0f, fl(1320f, 2650f), fl(0.02f, 0.008f), fl(1f, 0.25f))
                click(0f, 0.2f, 4000f)
            }
            Sfx.UI_CONFIRM -> b(0.9f).apply {
                glass(0f, 84f, 0.8f, -0.1f, 0.35f)
                glass(0.06f, 91f, 0.9f, 0.1f, 0.5f)
                thump(0f, 220f, 130f, 0.008f, 0.03f, 0.35f)
                noise(0.06f, 0.15f, 6000f, 0f, 0.1f, attack = 0.02f)
            }
            Sfx.UI_BACK -> b(0.5f).apply {
                marimba(0f, 79f, 0.8f, 0.1f)
                marimba(0.07f, 72f, 0.8f, -0.1f)
            }
            Sfx.UI_OPEN -> b(0.5f).apply {
                whoosh(0f, 0.2f, 700f, 3200f, 1.6f, 1f, -0.2f, 0.2f, 0.6f)
                glass(0.12f, 96f, 0.3f, 0f, 0.3f)
            }

            // ---------------------------------------------------------- crowds and results
            Sfx.CROWD_CHEER -> b(3.2f).apply {
                val sh = { k: Float -> min(1f, k / 0.1f) * (1f - k).pow(0.6f) }
                crowd(0f, 2.7f, 40, Vowel.EH, Vowel.AH, 1f, 0.2f, 0.12f, 0.2f, 0.5f, sh)
                applause(0.05f, 2.6f, 320f, 0.65f, sh)
                fingerWhistle(0.25f, 0.2f, -0.6f)
                fingerWhistle(0.6f, 0.16f, 0.7f)
                room(0.85f, 0.35f, 0.25f)
            }
            Sfx.CROWD_OOH -> b(1.7f).apply {
                crowd(0f, 1.35f, 30, Vowel.OO, Vowel.OH, 1f, 0.22f, 0.05f, 0.12f, 0.3f) { k -> sin(PI.toFloat() * k).pow(0.7f) }
                room(0.8f, 0.4f, 0.2f)
            }
            Sfx.CROWD_AW -> b(1.6f).apply {
                crowd(0f, 1.25f, 30, Vowel.AH, Vowel.AW, 1f, 0.05f, 0.25f, 0.1f, 0.3f) { k -> min(1f, k * 8f) * (1f - k).pow(0.6f) }
                room(0.8f, 0.4f, 0.2f)
            }
            Sfx.APPLAUSE -> b(2.7f).apply {
                applause(0f, 2.3f, 300f, 1f) { k -> min(1f, k * 6f) * (1f - k).pow(0.8f) }
                room(0.75f, 0.4f, 0.2f)
            }
            Sfx.FANFARE -> b(3.6f).apply {
                brass(0f, 0.11f, 67f, 0.7f); brass(0.15f, 0.11f, 67f, 0.7f); brass(0.3f, 0.11f, 67f, 0.75f)
                brassChord(0.45f, 0.85f, fl(60f, 64f, 67f, 72f), 0.6f)
                brass(0.45f, 0.85f, 76f, 0.8f)
                brass(1.45f, 0.11f, 74f, 0.7f); brass(1.6f, 0.11f, 76f, 0.75f)
                brassChord(1.75f, 1.1f, fl(60f, 67f, 72f, 76f, 79f), 0.65f)
                for (k in 0 until 9) timpani(k * 0.05f, 43f, 0.15f + 0.04f * k)
                timpani(0.45f, 48f, 0.8f); timpani(1.75f, 36f, 0.9f)
                cymbal(0.45f, 0.45f, 0.9f, -0.3f); cymbal(1.75f, 0.6f, 1.3f, 0.3f)
                room(0.8f, 0.4f, 0.22f)
            }
            Sfx.DRUMROLL -> b(2.5f).apply {
                var t = 0f
                var k = 0
                while (t < 1.3f) {
                    snare(t, (0.3f + 0.7f * t / 1.3f) * rnd.range(0.8f, 1f), if (k % 2 == 0) -0.15f else 0.15f)
                    t += 1f / 26f; k++
                }
                cymbal(1.3f, 1f, 1.1f)
                thump(1.3f, 110f, 50f, 0.02f, 0.1f, 0.9f)
                room(0.7f, 0.4f, 0.15f)
            }
            Sfx.CONFETTI -> b(0.95f).apply {
                noise(0f, 0.006f, 300f, 0f, 1f)
                thump(0f, 400f, 120f, 0.004f, 0.015f, 0.6f)
                grains(0.02f, 0.75f, 120, 3000f, 9000f, 0.001f, 0.004f, 0.4f, 1f, 0f) { k -> (1f - k).pow(1.5f) }
            }

            // ---------------------------------------------------------- golf
            Sfx.GOLF_DRIVE -> b(0.95f).apply {
                click(0f, 0.8f, 3000f)
                modes(0f, 1f, 0f, fl(3150f, 4870f, 6420f, 8100f), fl(0.045f, 0.03f, 0.02f, 0.012f), fl(1f, 0.7f, 0.5f, 0.3f))
                thump(0f, 260f, 140f, 0.006f, 0.018f, 0.55f)
                whoosh(0f, 0.32f, 1200f, 450f, 1f, 0.35f, 0f, 0.4f, 0.2f)
                echo(0.19f, 0.12f, 2500f)
            }
            Sfx.GOLF_IRON -> b(0.85f).apply {
                click(0f, 0.9f, 2500f, 0.001f)
                modes(0f, 0.8f, 0f, fl(2350f, 3850f, 5550f), fl(0.022f, 0.014f, 0.01f), fl(1f, 0.6f, 0.4f))
                noise(0.002f, 0.05f, 150f, 1800f, 0.6f, attack = 0.002f, col = NoiseColor.BROWN)
                grains(0.01f, 0.12f, 14, 1500f, 5000f, 0.002f, 0.006f, 0.25f, 0.4f, 0f)
                whoosh(0f, 0.28f, 1100f, 450f, 1f, 0.3f, 0f, 0.3f, 0.2f)
                echo(0.18f, 0.1f, 2200f)
            }
            Sfx.GOLF_PUTT -> b(0.25f).apply {
                modes(0f, 1f, 0f, fl(1380f, 2950f, 4600f), fl(0.025f, 0.012f, 0.006f), fl(1f, 0.45f, 0.2f))
                click(0f, 0.3f, 2000f)
                thump(0f, 380f, 300f, 0.005f, 0.012f, 0.3f)
            }
            Sfx.GOLF_CUP -> b(0.65f).apply {
                // the ball rattles round the plastic cup and settles
                val ts = fl(0f, 0.075f, 0.135f, 0.18f, 0.215f, 0.24f, 0.26f)
                val amps = fl(1f, 0.75f, 0.6f, 0.45f, 0.35f, 0.25f, 0.15f)
                for (k in ts.indices) {
                    val q = rnd.range(0.97f, 1.03f)
                    modes(ts[k], amps[k], rnd.range(-0.1f, 0.1f), fl(1050f * q, 2430f * q, 3720f * q), fl(0.035f, 0.02f, 0.012f), fl(1f, 0.6f, 0.35f))
                }
                thump(0.28f, 260f, 170f, 0.01f, 0.03f, 0.3f)
            }
            Sfx.GOLF_LAND -> b(0.3f).apply {
                thump(0f, 160f, 90f, 0.012f, 0.035f, 0.7f)
                band(0f, 0.02f, 600f, 1f, 0.7f, col = NoiseColor.PINK)
                noise(0f, 0.035f, 0f, 700f, 0.6f, col = NoiseColor.BROWN)
                band(0.003f, 0.03f, 3200f, 1.2f, 0.2f)
            }
            Sfx.GOLF_SPLASH -> b(1.0f).apply {
                thump(0f, 420f, 160f, 0.01f, 0.025f, 0.5f)
                val b1 = Biquad(); val b2 = Biquad()
                var j = 0
                layer(0f, 0.6f, 0f, 1f) { t ->
                    if (j++ % 32 == 0) { val f = 1800f * (500f / 1800f).pow(min(1f, t / 0.3f)); b1.bandpass(f, 0.8f, sr); b2.bandpass(f, 0.8f, sr) }
                    b2.process(b1.process(rnd.white())) * env(t, 0.004f, 0.18f)
                }
                noise(0f, 0.12f, 4000f, 0f, 0.35f, attack = 0.005f)
                repeat(14) {
                    val f0 = rnd.range(350f, 700f)
                    chirp(rnd.range(0.05f, 0.6f), rnd.range(0.015f, 0.05f), f0, f0 * rnd.range(1.6f, 2.6f), rnd.range(0.15f, 0.4f), rnd.range(-0.5f, 0.5f))
                }
                grains(0.15f, 0.5f, 18, 1500f, 4000f, 0.004f, 0.012f, 0.25f, 0.7f, 1f)
            }
            Sfx.GOLF_SAND -> b(0.55f).apply {
                thump(0f, 110f, 70f, 0.01f, 0.05f, 0.7f)
                val lp = Biquad().lowpass(3500f, 0.7f, sr)
                layer(0f, 0.4f, 0f, 1f) { t ->
                    val g = if (rnd.uniform() < 0.25f) 1f else 0.15f
                    lp.process(rnd.white() * g) * env(t, 0.002f, 0.12f)
                }
                noise(0.02f, 0.25f, 2000f, 7000f, 0.25f, attack = 0.01f)
            }
            Sfx.GOLF_TREE -> b(1.0f).apply {
                for ((k, t) in fl(0f, 0.06f, 0.17f).withIndex()) {
                    val f = rnd.range(450f, 700f)
                    modes(t, 1f - 0.3f * k, rnd.range(-0.3f, 0.3f), fl(f, f * 2.6f), fl(0.05f, 0.02f))
                }
                val bp = Biquad().bandpass(4000f, 0.8f, sr)
                val am = OnePole().cutoff(30f, sr)
                layer(0f, 0.75f, 0f, 0.8f) { t -> bp.process(rnd.white()) * (0.4f + 4f * kotlin.math.abs(am.lp(rnd.white()))) * env(t, 0.02f, 0.3f) }
                thump(0.45f, 200f, 110f, 0.012f, 0.03f, 0.3f)
            }
            Sfx.GOLF_BIRDIE -> b(2.4f).apply {
                brass(0f, 0.09f, 67f, 0.7f); brass(0.12f, 0.09f, 72f, 0.7f); brass(0.24f, 0.09f, 76f, 0.7f)
                brassChord(0.36f, 0.6f, fl(67f, 72f, 76f, 79f), 0.6f)
                timpani(0.36f, 43f, 0.7f)
                cymbal(0.36f, 0.4f, 0.8f)
                applause(0.3f, 1.6f, 200f, 0.5f) { k -> min(1f, k * 5f) * (1f - k) }
                room(0.6f, 0.5f, 0.15f)
            }
            Sfx.GOLF_SWING -> b(0.4f).apply {
                whoosh(0f, 0.32f, 260f, 1600f, 1.4f, 1f, -0.2f, 0.3f, 0.7f)
            }

            // ---------------------------------------------------------- shooting
            Sfx.RELOAD -> b(0.4f).apply {
                modes(0f, 0.9f, 0.1f, fl(1950f, 3350f, 5150f), fl(0.015f, 0.01f, 0.006f))
                click(0f, 0.4f, 3000f)
                thump(0f, 380f, 240f, 0.005f, 0.012f, 0.3f)
                band(0.05f, 0.03f, 2500f, 1.5f, 0.25f)
                modes(0.13f, 1f, -0.1f, fl(1650f, 2900f, 4700f), fl(0.018f, 0.01f, 0.006f))
                thump(0.13f, 300f, 170f, 0.006f, 0.015f, 0.45f)
            }
            Sfx.EMPTY -> b(0.08f).apply {
                modes(0f, 1f, 0f, fl(3400f, 6100f), fl(0.006f, 0.003f), fl(1f, 0.5f))
                thump(0f, 500f, 300f, 0.003f, 0.008f, 0.3f)
            }
            Sfx.TIN_HIT -> b(1.6f).apply {
                click(0f, 0.6f, 3000f)
                modes(0f, 1f, 0f, fl(1250f, 2790f, 4420f, 6610f, 8800f), fl(0.28f, 0.2f, 0.12f, 0.07f, 0.04f), fl(1f, 0.8f, 0.6f, 0.35f, 0.2f))
                modes(0.07f, 0.3f, 0.2f, fl(1310f, 2900f), fl(0.08f, 0.05f))
            }
            Sfx.BALLOON_POP -> b(0.25f).apply {
                noise(0f, 0.012f, 500f, 0f, 1f)
                thump(0f, 300f, 150f, 0.004f, 0.015f, 0.3f)
                band(0.002f, 0.02f, 750f, 1.5f, 0.6f)
            }
            Sfx.GLASS -> b(1.0f).apply {
                noise(0f, 0.01f, 2500f, 0f, 0.8f)
                grains(0f, 0.55f, 40, 2500f, 9500f, 0.02f, 0.15f, 0.9f, 0.9f, 0.9f) { k -> (1f - k).pow(1.5f) }
                modes(0f, 0.5f, 0f, fl(1850f, 4100f), fl(0.08f, 0.04f))
            }
            Sfx.GALLERY_SHOT -> b(0.45f).apply {
                noise(0f, 0.02f, 900f, 0f, 1f)
                band(0f, 0.02f, 1300f, 1f, 0.6f)
                thump(0f, 260f, 140f, 0.006f, 0.02f, 0.35f)
                modes(0.025f, 0.35f, 0f, fl(3100f, 5300f), fl(0.01f, 0.006f))
                echo(0.12f, 0.08f, 3000f)
            }

            // ---------------------------------------------------------- archery and darts
            Sfx.BOW_DRAW -> b(0.85f).apply {
                val b1 = Biquad().bandpass(950f, 6f, sr); val b2 = Biquad().bandpass(2100f, 8f, sr)
                var next = 0f
                layer(0f, 0.8f, 0f, 1f) { t ->
                    var imp = 0f
                    if (t >= next) { imp = rnd.range(0.4f, 1f); next = t + 1f / (35f + 35f * t / 0.8f) * rnd.range(0.7f, 1.3f) }
                    (b1.process(imp) + 0.6f * b2.process(imp)) * min(1f, t / 0.1f) * min(1f, (0.8f - t) / 0.1f)
                }
                band(0f, 0.6f, 3000f, 2f, 0.1f, attack = 0.3f, sec = 0.8f)
            }
            Sfx.ARROW_FLY -> b(0.55f).apply {
                val b1 = Biquad(); val b2 = Biquad()
                var j = 0
                layer(0f, 0.5f, 0f, 1f) { t ->
                    val k = t / 0.5f
                    if (j++ % 32 == 0) { val f = 2600f * (1100f / 2600f).pow(k); b1.bandpass(f, 1.5f, sr); b2.bandpass(f, 1.5f, sr) }
                    val am = 0.5f + 0.5f * sin(TAU * 72f * t)
                    b2.process(b1.process(rnd.white())) * am * min(1f, k / 0.06f) * (1f - k)
                }
            }
            Sfx.BULLSEYE -> b(1.7f).apply {
                thump(0f, 190f, 100f, 0.01f, 0.03f, 0.5f)
                bell(0f, 84f, 0.8f, -0.1f, 1.1f)
                bell(0f, 91f, 0.6f, 0.1f, 1.1f)
                glass(0.05f, 103f, 0.35f, 0.3f, 0.6f)
                noise(0.05f, 0.25f, 7000f, 0f, 0.12f, attack = 0.04f)
            }
            Sfx.DART_HIT -> b(0.3f).apply {
                click(0f, 0.7f, 2500f, 0.0008f)
                thump(0f, 520f, 300f, 0.004f, 0.01f, 0.6f)
                band(0f, 0.009f, 2600f, 1.2f, 0.6f)
                var ph = 0.0
                layer(0.004f, 0.25f, 0f, 0.15f) { t -> ph += TAU_D * 150 / sr; (sin(ph) * exp(-t / 0.04f)).toFloat() }
            }
            Sfx.DART_MISS -> b(0.55f).apply {
                modes(0f, 0.8f, 0f, fl(2900f, 5100f, 7300f), fl(0.03f, 0.015f, 0.01f))
                modes(0.08f, 0.6f, 0.1f, fl(700f, 1650f), fl(0.03f, 0.015f))
                modes(0.16f, 0.3f, 0.15f, fl(760f, 1700f), fl(0.025f, 0.012f))
            }

            // ---------------------------------------------------------- boxing
            Sfx.PUNCH_HEAVY -> b(0.65f).apply {
                thump(0f, 150f, 65f, 0.02f, 0.08f, 0.8f)
                band(0f, 0.04f, 1400f, 0.7f, 1f)
                click(0f, 0.6f, 3000f)
                noise(0f, 0.06f, 0f, 600f, 0.5f, col = NoiseColor.PINK)
                band(0.003f, 0.05f, 650f, 1f, 0.8f)
                room(0.4f, 0.5f, 0.08f)
            }
            Sfx.KNOCKDOWN -> b(1.3f).apply {
                thump(0f, 110f, 50f, 0.03f, 0.15f, 0.8f)
                band(0f, 0.06f, 900f, 0.9f, 0.9f, col = NoiseColor.PINK)
                noise(0f, 0.08f, 80f, 1600f, 0.7f, col = NoiseColor.PINK)
                modes(0.02f, 0.25f, 0f, fl(310f, 655f, 1240f), fl(0.35f, 0.25f, 0.15f))
                noise(0f, 0.3f, 30f, 200f, 0.5f, col = NoiseColor.BROWN)
                room(0.6f, 0.45f, 0.12f)
            }

            // ---------------------------------------------------------- ball sports
            Sfx.RACKET -> b(0.35f).apply {
                click(0f, 0.5f, 3000f)
                modes(0f, 1f, 0f, fl(520f, 1240f, 2080f, 3100f), fl(0.035f, 0.025f, 0.015f, 0.01f), fl(1f, 0.7f, 0.45f, 0.25f))
                thump(0f, 260f, 150f, 0.006f, 0.016f, 0.6f)
                band(0f, 0.008f, 4500f, 1f, 0.25f)
            }
            Sfx.PADDLE -> b(0.12f).apply {
                click(0f, 0.6f, 3000f)
                modes(0f, 1f, 0f, fl(2150f, 4350f, 6200f), fl(0.014f, 0.008f, 0.005f))
                thump(0f, 700f, 450f, 0.003f, 0.008f, 0.3f)
            }
            Sfx.TABLE -> b(0.15f).apply {
                modes(0f, 1f, 0f, fl(1480f, 3050f), fl(0.018f, 0.009f))
                thump(0f, 420f, 300f, 0.004f, 0.015f, 0.6f)
                click(0f, 0.3f, 2500f)
            }
            Sfx.NET -> b(0.45f).apply {
                band(0f, 0.07f, 1200f, 0.9f, 0.8f, attack = 0.004f)
                noise(0f, 0.12f, 2000f, 6000f, 0.35f, attack = 0.01f)
                thump(0f, 160f, 100f, 0.01f, 0.03f, 0.5f)
            }
            Sfx.KICK -> b(0.45f).apply {
                thump(0f, 170f, 85f, 0.012f, 0.04f, 0.8f)
                band(0f, 0.03f, 1000f, 0.9f, 1f)
                click(0f, 0.4f, 2200f)
                whoosh(0.01f, 0.3f, 900f, 400f, 1f, 0.25f)
            }
            Sfx.POST -> b(2.0f).apply {
                click(0f, 0.6f)
                modes(0f, 1f, 0f, fl(520f, 1430f, 2660f, 3920f, 5400f), fl(0.7f, 0.45f, 0.3f, 0.18f, 0.1f), fl(1f, 0.8f, 0.5f, 0.35f, 0.2f))
                thump(0f, 180f, 100f, 0.01f, 0.03f, 0.6f)
            }
            Sfx.GOAL_NET -> b(0.65f).apply {
                val bp = Biquad().bandpass(1800f, 0.7f, sr)
                val am = OnePole().cutoff(25f, sr)
                layer(0f, 0.6f, 0f, 0.7f) { t -> bp.process(rnd.white()) * (0.3f + 5f * kotlin.math.abs(am.lp(rnd.white()))) * env(t, 0.01f, 0.25f) }
                noise(0f, 0.15f, 3000f, 0f, 0.3f, attack = 0.02f)
                thump(0f, 120f, 80f, 0.01f, 0.03f, 0.35f)
            }
            Sfx.STRIKE -> b(1.7f).apply {
                thump(0f, 140f, 60f, 0.02f, 0.08f, 1f)
                grains(0f, 0.6f, 55, 600f, 3200f, 0.02f, 0.07f, 1f, 1f, 0.85f) { k -> (1f - k).pow(1.3f) }
                grains(0.25f, 0.9f, 25, 500f, 1600f, 0.02f, 0.05f, 0.5f, 1f, 0.8f)
                noise(0f, 0.3f, 30f, 300f, 0.4f, col = NoiseColor.BROWN)
                room(0.7f, 0.4f, 0.15f)
            }

            // ---------------------------------------------------------- winter
            Sfx.SNOW_CRUNCH -> b(0.25f).apply {
                val lp = Biquad().lowpass(4500f, 0.7f, sr)
                layer(0f, 0.2f, 0f, 1f) { t ->
                    val g = if (rnd.uniform() < 0.18f) rnd.uniform() else 0.1f
                    lp.process(rnd.white() * g) * env(t, 0.02f, 0.06f)
                }
                thump(0f, 140f, 90f, 0.01f, 0.03f, 0.25f)
            }
        }
        return x.finish(sfx)
    }
}
