package com.clrg.core.pose

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The real BlazePose models, run by the Python helper (tools/pose-validation/tflite_server.py) for tests on the
 * build machine. [variant] 'l' = lite, 'f' = full.
 */
class RealModelServer(python: String, models: String) : AutoCloseable {
    private val p: Process = ProcessBuilder(python, "-I", File("../tools/pose-validation/tflite_server.py").absolutePath, models)
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val out = BufferedOutputStream(p.outputStream, 1 shl 20)
    private val inp = DataInputStream(BufferedInputStream(p.inputStream, 1 shl 20))
    var detectorCalls = 0
    var landmarkCalls = 0

    private fun send(op: Char, variant: Char, data: FloatArray) {
        out.write(op.code); out.write(variant.code)
        val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(data)
        out.write(bb.array()); out.flush()
    }

    private fun recv(n: Int): FloatArray {
        val bytes = ByteArray(n * 4)
        inp.readFully(bytes)
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(n).also { fb.get(it) }
    }

    fun runner(variant: Char): PoseModelRunner = object : PoseModelRunner {
        override fun runDetector(input: FloatArray, regressors: FloatArray, scores: FloatArray) {
            detectorCalls++
            send('D', variant, input)
            recv(regressors.size).copyInto(regressors)
            recv(scores.size).copyInto(scores)
        }

        override fun runLandmarks(input: FloatArray, landmarks: FloatArray, flag: FloatArray, heatmap: FloatArray?) {
            landmarkCalls++
            send('L', variant, input)
            recv(landmarks.size).copyInto(landmarks)
            recv(1).copyInto(flag)
            val hm = recv(BlazePose.HEATMAP_SIZE * BlazePose.HEATMAP_SIZE * BlazePose.NUM_LANDMARKS_ALL)
            heatmap?.let { hm.copyInto(it) }
        }
    }

    var handCalls = 0

    /** The MediaPipe hand landmark model: [variant] 'l' = lite, 'f' = full. */
    fun handRunner(variant: Char): HandModelRunner = object : HandModelRunner {
        override fun runHand(input: FloatArray, landmarks: FloatArray, presence: FloatArray) {
            handCalls++
            send('H', variant, input)
            recv(63).copyInto(landmarks)
            recv(1).copyInto(presence)
        }
    }

    override fun close() {
        try { p.outputStream.write(byteArrayOf('Q'.code.toByte(), 'Q'.code.toByte())); p.outputStream.close() } catch (_: java.io.IOException) {}
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
    }
}
