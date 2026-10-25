package com.clrg.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A small DSP toolkit for the built-in synthesiser: noise sources, filters, a stereo reverb and a limiter.
 * Everything here is allocation-free once constructed, so it can run on the audio thread.
 */

const val TAU = 6.2831855f
const val TAU_D = 2.0 * PI

/** Table sine (4096 points, linearly interpolated): much faster than kotlin.math.sin for synthesis loops. */
object SineTable {
    private const val N = 4096
    private val t = FloatArray(N + 1) { kotlin.math.sin(TAU_D * it / N).toFloat() }

    /** sin([rad]) for any angle. */
    fun sin(rad: Double): Float {
        var x = rad / TAU_D
        x -= kotlin.math.floor(x)
        val f = x * N
        val i = f.toInt()
        return t[i] + (t[i + 1] - t[i]) * (f - i).toFloat()
    }

    fun sin(rad: Float): Float = sin(rad.toDouble())
}

/** Attack (linear, [a] s) then exponential decay (time constant [d] s). */
fun env(t: Float, a: Float, d: Float): Float = if (t < a) t / a else exp(-(t - a) / d)

/** Fast deterministic noise: white (xorshift), pink (Kellet filter) and brown (leaky integrator). */
class Noise(seed: Int = 1) {
    private var s = if (seed == 0) 0x2545F491 else seed
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var b3 = 0f
    private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
    private var br = 0f

    /** Uniform white noise in -1..1. */
    fun white(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return s * 4.656613e-10f
    }

    /** Uniform 0..1. */
    fun uniform(): Float = 0.5f + 0.5f * white()

    /** Uniform in [a, b). */
    fun range(a: Float, b: Float): Float = a + (b - a) * uniform()

    /** Pink (1/f) noise, roughly -1..1. */
    fun pink(): Float {
        val w = white()
        b0 = 0.99886f * b0 + w * 0.0555179f
        b1 = 0.99332f * b1 + w * 0.0750759f
        b2 = 0.96900f * b2 + w * 0.1538520f
        b3 = 0.86650f * b3 + w * 0.3104856f
        b4 = 0.55000f * b4 + w * 0.5329522f
        b5 = -0.7616f * b5 - w * 0.0168980f
        val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
        b6 = w * 0.115926f
        return p * 0.11f
    }

    /** Brown (1/f^2) noise, roughly -1..1: deep rumbles, wind, surf. */
    fun brown(): Float {
        br = (br + 0.04f * white()) * 0.996f
        return (br * 3.2f).coerceIn(-1f, 1f)
    }
}

/** One-pole low-pass (smoothing) filter. */
class OnePole {
    var a = 1f
    var y = 0f
    fun cutoff(hz: Float, sr: Int): OnePole { a = (1.0 - exp(-2.0 * PI * hz / sr)).toFloat(); return this }
    fun lp(x: Float): Float { y += a * (x - y); return y }
    fun hp(x: Float): Float { y += a * (x - y); return x - y }
}

/** RBJ-cookbook biquad. Coefficient setters can be called per sample for sweeps (offline) or per block (live). */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double): Biquad {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
        return this
    }

    private fun w0(hz: Float, sr: Int) = 2.0 * PI * hz.coerceIn(10f, sr * 0.45f) / sr

    fun lowpass(hz: Float, q: Float, sr: Int): Biquad {
        val w = w0(hz, sr); val c = cos(w); val al = sin(w) / (2 * q)
        return set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun highpass(hz: Float, q: Float, sr: Int): Biquad {
        val w = w0(hz, sr); val c = cos(w); val al = sin(w) / (2 * q)
        return set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    /** Band-pass with 0 dB peak gain. */
    fun bandpass(hz: Float, q: Float, sr: Int): Biquad {
        val w = w0(hz, sr); val c = cos(w); val al = sin(w) / (2 * q)
        return set(al, 0.0, -al, 1 + al, -2 * c, 1 - al)
    }

    /** Peaking EQ: boost or cut [db] around [hz]. */
    fun peak(hz: Float, q: Float, db: Float, sr: Int): Biquad {
        val a = 10.0.pow(db / 40.0); val w = w0(hz, sr); val c = cos(w); val al = sin(w) / (2 * q)
        return set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }
}

/**
 * Stereo room reverb (the Schroeder/Moorer "Freeverb" design: 8 damped comb filters and 4 all-passes per channel,
 * with the right channel's delays offset for width). Mono in, stereo out ([outL], [outR] after [process]).
 */
class Reverb(sr: Int) {
    private class Comb(size: Int) {
        val buf = FloatArray(size); var i = 0; var store = 0f
        fun process(x: Float, fb: Float, d1: Float, d2: Float): Float {
            val out = buf[i]
            store = out * d2 + store * d1
            buf[i] = x + store * fb
            if (++i >= buf.size) i = 0
            return out
        }
        fun clear() { buf.fill(0f); store = 0f }
    }

    private class AllPass(size: Int) {
        val buf = FloatArray(size); var i = 0
        fun process(x: Float): Float {
            val b = buf[i]
            buf[i] = x + b * 0.5f
            if (++i >= buf.size) i = 0
            return b - x
        }
        fun clear() = buf.fill(0f)
    }

    private val scale = sr / 44100.0
    private fun len(n: Int) = max(8, (n * scale).toInt())
    private val combTune = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTune = intArrayOf(556, 441, 341, 225)
    private val combL = Array(8) { Comb(len(combTune[it])) }
    private val combR = Array(8) { Comb(len(combTune[it] + 23)) }
    private val apL = Array(4) { AllPass(len(apTune[it])) }
    private val apR = Array(4) { AllPass(len(apTune[it] + 23)) }
    private val pre = FloatArray(max(1, (0.12 * sr).toInt()))
    private var preI = 0
    private var preLen = 1

    private var fb = 0.84f
    private var d1 = 0.2f
    private var d2 = 0.8f
    private var wet1 = 1f
    private var wet2 = 0f

    var outL = 0f
        private set
    var outR = 0f
        private set

    /**
     * [size] 0..1 (decay length), [damping] 0..1 (high-frequency absorption), [width] 0..1 (stereo spread),
     * [preDelaySec] up to 0.12 s.
     */
    fun configure(size: Float, damping: Float, width: Float = 1f, preDelaySec: Float = 0f, sr: Int) {
        fb = 0.7f + 0.28f * size.coerceIn(0f, 1f)
        d1 = 0.4f * damping.coerceIn(0f, 1f)
        d2 = 1f - d1
        val w = width.coerceIn(0f, 1f)
        wet1 = 3f * (w / 2f + 0.5f)
        wet2 = 3f * ((1f - w) / 2f)
        preLen = (preDelaySec * sr).toInt().coerceIn(1, pre.size)
        if (preI >= preLen) preI = 0
    }

    fun process(input: Float) {
        val delayed = pre[preI]
        pre[preI] = input
        if (++preI >= preLen) preI = 0
        val x = delayed * 0.015f
        var l = 0f
        var r = 0f
        for (c in combL) l += c.process(x, fb, d1, d2)
        for (c in combR) r += c.process(x, fb, d1, d2)
        for (a in apL) l = a.process(l)
        for (a in apR) r = a.process(r)
        outL = l * wet1 + r * wet2
        outR = r * wet1 + l * wet2
    }

    fun clear() { combL.forEach { it.clear() }; combR.forEach { it.clear() }; apL.forEach { it.clear() }; apR.forEach { it.clear() }; pre.fill(0f) }
}

/**
 * Master-bus peak limiter: instant attack, smooth release. Keeps loud moments (crowd + fanfare + music) from
 * distorting, so effects can be mixed hot.
 */
class Limiter(sr: Int, private val threshold: Float = 0.82f, releaseSec: Float = 0.15f) {
    private val rel = exp(-1.0 / (releaseSec * sr)).toFloat()
    private var env = 0f
    /** Current gain reduction (1 = none). */
    var gain = 1f
        private set

    fun gainFor(peak: Float): Float {
        env = if (peak > env) peak else env * rel + peak * (1f - rel)
        gain = if (env > threshold) threshold / env else 1f
        return gain
    }
}

/** Equal-power pan gains for [pan] -1..1. */
fun panGains(pan: Float): Pair<Float, Float> {
    if (pan == 0f) return Pair(1f, 1f)
    val p = (pan.coerceIn(-1f, 1f) + 1f) * 0.25f * PI.toFloat()
    return Pair(cos(p) * sqrt(2f), sin(p) * sqrt(2f))
}
