package colourfilm.core

/**
 * A neural colourisation model that predicts Lab a/b chroma for a grey image.
 *
 * Input: [inputSize]x[inputSize] grey image, row-major, values in 0..1 (sRGB-encoded).
 * Output: planar a then b, each [inputSize]x[inputSize], in Lab units.
 */
interface ChromaModel : AutoCloseable {
    val inputSize: Int
    /** Identifies weights + runtime precision; part of the job fingerprint. */
    val id: String
    fun predict(grey: FloatArray, outAb: FloatArray)
    override fun close() {}
}
