package com.clrg.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.clrg.core.audio.AudioEngine

/** Streams the core synthesiser to an AudioTrack on its own thread and reports output latency for beat sync. */
class AndroidAudio(private val engine: AudioEngine) {
    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio").also { it.start() }
    }

    fun setPaused(p: Boolean) { paused = p }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        // effects are rendered on their own thread (MainActivity); music plays straight away and effects join as they are ready
        val sr = engine.sampleRate
        val frames = 256
        val track = try {
            val minBytes = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minBytes, frames * 8 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Throwable) {
            Log.e("AndroidAudio", "AudioTrack failed", e)
            running = false
            return
        }
        val buf = FloatArray(frames * 2)
        val ts = AudioTimestamp()
        var written = 0L
        var latency = 0.08
        try {
            track.play()
            while (running) {
                if (paused) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    Thread.sleep(30)
                    continue
                } else if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                engine.render(buf, frames, System.nanoTime())
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                written += frames
                if (written % (frames * 16) == 0L && track.getTimestamp(ts)) {
                    // audio queued ahead of the speaker = written frames not yet presented
                    val presentedNow = ts.framePosition + (System.nanoTime() - ts.nanoTime) * sr / 1_000_000_000L
                    val queued = (written - presentedNow).coerceAtLeast(0L) / sr.toDouble()
                    latency += 0.2 * (queued - latency)
                    engine.outputLatencySec = latency
                }
            }
        } catch (e: Throwable) {
            Log.e("AndroidAudio", "audio loop", e)
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
    }
}
