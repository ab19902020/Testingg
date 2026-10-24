package com.clrg.core.math

/**
 * Small deterministic PRNG (SplitMix64). Identical seeds give identical sequences on every device and on
 * the JVM, which is how both players receive equivalent challenge patterns.
 */
class Rng(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = ((nextLong() ushr 40).toFloat() / (1L shl 24).toFloat())

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * nextFloat()

    /** Uniform integer in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 33) % bound).toInt()
    }

    fun intRange(lo: Int, hiInclusive: Int): Int = lo + nextInt(hiInclusive - lo + 1)

    fun chance(p: Float): Boolean = nextFloat() < p

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
    }

    fun fork(salt: Long): Rng = Rng(nextLong() xor salt)
}
