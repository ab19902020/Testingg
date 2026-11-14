package colourfilm.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * DDColor (Qualcomm AI Hub ONNX export of `ddcolor_paper_tiny`) run through ONNX Runtime.
 * The model takes a 1x3xSxS grey RGB image in 0..1 and returns 1x2xSxS Lab a/b.
 *
 * The same class is used on Android (onnxruntime-android AARs) and on the desktop
 * benchmark (onnxruntime JVM), so desktop results exercise exactly this code.
 */
class OrtChromaModel(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    override val id: String,
) : ChromaModel {
    override val inputSize: Int
    private val inputName: String = session.inputNames.first()
    private val input: FloatBuffer
    private val shape: LongArray

    init {
        val info = session.inputInfo.values.first().info as ai.onnxruntime.TensorInfo
        val s = info.shape
        require(s.size == 4 && s[1] == 3L && s[2] == s[3]) { "Unexpected model input shape ${s.contentToString()}" }
        inputSize = s[2].toInt()
        shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        input = ByteBuffer.allocateDirect(4 * 3 * inputSize * inputSize).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    override fun predict(grey: FloatArray, outAb: FloatArray) {
        val n = inputSize * inputSize
        input.clear()
        repeat(3) { input.put(grey, 0, n) }
        input.flip()
        OnnxTensor.createTensor(env, input, shape).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val out = (res.get(0) as OnnxTensor).floatBuffer
                out.get(outAb, 0, 2 * n)
            }
        }
    }

    override fun close() = session.close()
}
