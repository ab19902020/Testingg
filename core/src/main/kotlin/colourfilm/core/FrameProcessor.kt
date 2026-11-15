package colourfilm.core

/**
 * Restores and colourises a stream of frames given as 8-bit luma planes.
 *
 * Per frame: dust removal (needs the following frame, so output runs one frame behind when it is
 * enabled) → flicker/contrast correction → colour estimation with temporal consistency → grain
 * reduction and sharpening along the tracked motion → full-resolution chroma.
 *
 * Usage: call [push] for every source frame; whenever it returns true a finished frame is in
 * [outY], [outU], [outV]. After the last frame call [flush] until it returns false.
 * Memory use is constant: a few full-resolution luma buffers plus analysis-grid buffers.
 */
class FrameProcessor(
    val width: Int,
    val height: Int,
    colour: VideoColour,
    model: ChromaModel,
    private val settings: ColouriseSettings,
    private val parallel: Parallel,
) {
    private val temporal = TemporalColouriser(model, settings)
    private val s = temporal.size
    private val lut = colour.lumaToUnit
    private val grey = FloatArray(s * s)
    private val greyTmp = FloatArray(height * s)
    private val ab = FloatArray(2 * s * s)
    val composer = ChromaComposer(width, height, colour, settings, parallel)
    private val dust = DustRemover(width, height, settings.dust)
    private val enhancer = LumaEnhancer(width, height, colour, settings, parallel)

    /** Finished frame: restored luma (tightly packed width x height) and 4:2:0 chroma. */
    val outY: ByteArray get() = dust.out
    val outU = ByteArray((width / 2) * (height / 2))
    val outV = ByteArray((width / 2) * (height / 2))

    /** Number of frames the output runs behind the input (0 or 1). */
    val delay: Int get() = if (settings.dust) 1 else 0

    val stats get() = temporal
    val dustStats get() = dust
    val enhancerStats get() = enhancer

    var analysisNanos = 0L; private set
    var composeNanos = 0L; private set
    var restoreNanos = 0L; private set

    /** Feeds one source frame. Returns true when a finished frame is available in the out buffers. */
    fun push(y: ByteArray, yOffset: Int, yStride: Int): Boolean {
        val t = System.nanoTime()
        val ready = dust.push(y, yOffset, yStride)
        restoreNanos += System.nanoTime() - t
        if (ready) finish()
        return ready
    }

    /** Call after the last [push] until it returns false. */
    fun flush(): Boolean {
        if (!dust.flush()) return false
        finish()
        return true
    }

    private fun finish() {
        val y = dust.out
        val t0 = System.nanoTime()
        enhancer.levels(y, 0, width)
        val t1 = System.nanoTime()
        Resample.lumaToGrid(y, 0, width, width, height, lut, s, s, grey, greyTmp)
        temporal.next(grey, ab)
        val t2 = System.nanoTime()
        enhancer.denoise(y, 0, width, temporal.motionX, temporal.motionY, s, temporal.lastHistoryValid)
        val t3 = System.nanoTime()
        composer.compose(y, 0, width, ab, s, outU, outV)
        restoreNanos += (t1 - t0) + (t3 - t2)
        analysisNanos += t2 - t1
        composeNanos += System.nanoTime() - t3
    }

    /** Clears all history (colour, shots, restoration). */
    fun reset() { temporal.reset(); dust.reset(); enhancer.reset() }
    fun exportState() = temporal.exportState()
    fun importState(st: FloatArray?) = temporal.importState(st)
}
