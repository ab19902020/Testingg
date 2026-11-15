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
    /** New frames to create between each pair of source frames (0 = keep the source frame rate). */
    val inBetween: Int = 0,
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

    // ---- frame-rate increase ----
    private val interpolator = if (inBetween > 0) FrameInterpolator(width, height, parallel) else null
    private val prevY = ByteArray(if (inBetween > 0) width * height else 0)
    private val prevU = ByteArray(if (inBetween > 0) outU.size else 0)
    private val prevV = ByteArray(if (inBetween > 0) outU.size else 0)
    private var haveOut = false
    private var havePrev = false
    private var pairReady = false
    var interpolateNanos = 0L; private set
    var inBetweenMade = 0L; private set
    var inBetweenSkipped = 0L; private set

    /** In-between frame produced by [renderInBetween]. */
    val midY: ByteArray get() = interpolator!!.midY
    val midU: ByteArray get() = interpolator!!.midU
    val midV: ByteArray get() = interpolator!!.midV

    /**
     * After [push]/[flush] returned true: how many in-between frames can be made between the
     * previously finished frame and this one (0 at the first frame and across a change of shot).
     * Render and output them BEFORE outputting the finished frame.
     */
    val inBetweenAvailable: Int get() = if (pairReady) inBetween else 0

    /**
     * Renders in-between frame [j] of [inBetweenAvailable] (1-based) into [midY]/[midU]/[midV].
     * Returns false when the motion could not be followed; then no frame should be output for it.
     */
    fun renderInBetween(j: Int): Boolean {
        val t0 = System.nanoTime()
        val ok = interpolator!!.render(prevY, prevU, prevV, outY, outU, outV, j.toFloat() / (inBetween + 1))
        interpolateNanos += System.nanoTime() - t0
        if (ok) inBetweenMade++ else inBetweenSkipped++
        return ok
    }

    private fun keepPrevious() {
        if (inBetween == 0 || !haveOut) return
        System.arraycopy(outY, 0, prevY, 0, prevY.size); System.arraycopy(outU, 0, prevU, 0, prevU.size)
        System.arraycopy(outV, 0, prevV, 0, prevV.size)
        havePrev = true
    }

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
        keepPrevious()
        val t = System.nanoTime()
        val ready = dust.push(y, yOffset, yStride)
        restoreNanos += System.nanoTime() - t
        if (ready) finish()
        return ready
    }

    /** Call after the last [push] until it returns false. */
    fun flush(): Boolean {
        keepPrevious()
        if (!dust.flush()) { pairReady = false; return false }
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
        haveOut = true
        // In-between frames are only made within a shot, never across a cut.
        pairReady = interpolator != null && havePrev && temporal.lastHistoryValid
        if (pairReady) { val ti = System.nanoTime(); interpolator!!.prepare(prevY, y); interpolateNanos += System.nanoTime() - ti }
    }

    /** Call before feeding a new, separately encoded stretch of frames: drops any frame still buffered. */
    fun startSegment() { dust.reset() }

    /** Call when the next frames do not follow on from the last ones (e.g. a resumed job). */
    fun breakContinuity() { haveOut = false; havePrev = false; pairReady = false }

    /** Clears all history (colour, shots, restoration). */
    fun reset() { temporal.reset(); dust.reset(); enhancer.reset(); breakContinuity() }
    fun exportState() = temporal.exportState()
    fun importState(st: FloatArray?) = temporal.importState(st)
}
