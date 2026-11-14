package colourfilm.core

/**
 * Colourises a stream of frames given as 8-bit luma planes, producing 4:2:0 chroma planes.
 * Memory use is constant: a handful of analysis-grid buffers plus one frame of chroma.
 */
class FrameProcessor(
    val width: Int,
    val height: Int,
    colour: VideoColour,
    model: ChromaModel,
    settings: ColouriseSettings,
    private val parallel: Parallel,
) {
    private val temporal = TemporalColouriser(model, settings)
    private val s = temporal.size
    private val lut = colour.lumaToUnit
    private val grey = FloatArray(s * s)
    private val greyTmp = FloatArray(height * s)
    private val ab = FloatArray(2 * s * s)
    val composer = ChromaComposer(width, height, colour, settings, parallel)

    val stats get() = temporal

    var analysisNanos = 0L; private set
    var composeNanos = 0L; private set

    fun process(y: ByteArray, yOffset: Int, yStride: Int, outU: ByteArray, outV: ByteArray) {
        val t0 = System.nanoTime()
        Resample.lumaToGrid(y, yOffset, yStride, width, height, lut, s, s, grey, greyTmp)
        temporal.next(grey, ab)
        val t1 = System.nanoTime()
        composer.compose(y, yOffset, yStride, ab, s, outU, outV)
        analysisNanos += t1 - t0
        composeNanos += System.nanoTime() - t1
    }

    fun reset() = temporal.reset()
    fun exportState() = temporal.exportState()
    fun importState(st: FloatArray?) = temporal.importState(st)
}
