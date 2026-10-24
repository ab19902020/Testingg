package com.clrg.core.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

enum class MusicTrack { MENU, GAME, DANCE, VICTORY, CALM }

/**
 * Real-time mixer + music sequencer. [render] is called from the platform audio thread; everything else is called
 * from the game thread and communicates through a lock-free command queue. Music and effects have independent
 * volumes. Audio is generated entirely in code.
 */
class AudioEngine(val sampleRate: Int = 48000) {
    @Volatile var musicVolume = 0.6f
    @Volatile var sfxVolume = 0.85f
    /** Output latency (seconds) reported by the platform, used for beat-accurate visuals. */
    @Volatile var outputLatencySec = 0.06

    private sealed class Cmd {
        class PlaySfx(val sfx: Sfx, val vol: Float, val pan: Float, val pitch: Float) : Cmd()
        class PlayMusic(val track: MusicTrack?, val restart: Boolean, val startBeat: Double = 0.0) : Cmd()
        object StopAll : Cmd()
    }

    private val commands = ConcurrentLinkedQueue<Cmd>()
    private val sfxBank = HashMap<Sfx, FloatArray>()
    @Volatile var ready = false
        private set

    // ---- audio-thread state
    private class SfxVoice { var data: FloatArray? = null; var pos = 0.0; var step = 1.0; var vol = 1f; var pan = 0f }
    private val sfxVoices = Array(24) { SfxVoice() }
    private val voices = Array(48) { Voice() }
    private class Playback(val song: Song, val track: MusicTrack) {
        var beat = 0.0
        var nextIndex = 0
        var gain = 0f
        var target = 1f
        var finished = false
    }
    private var current: Playback? = null
    private var fading: Playback? = null

    // ---- timing published to the game thread
    @Volatile private var pubBeat = 0.0
    @Volatile private var pubNanos = 0L
    @Volatile private var pubBpm = 120.0
    @Volatile private var pubTrack: MusicTrack? = null
    @Volatile var currentTrack: MusicTrack? = null
        private set

    /** Renders the effect samples (call once, off the UI thread if possible). */
    fun prepare() {
        if (ready) return
        for (s in Sfx.values()) sfxBank[s] = SfxSynth.render(s, sampleRate)
        ready = true
    }

    fun play(sfx: Sfx, volume: Float = 1f, pan: Float = 0f, pitch: Float = 1f) {
        commands.add(Cmd.PlaySfx(sfx, volume, pan, pitch))
    }

    fun playMusic(track: MusicTrack?, restart: Boolean = false, startBeat: Double = 0.0) {
        if (!restart && track == currentTrack) return
        currentTrack = track
        commands.add(Cmd.PlayMusic(track, restart, startBeat))
    }

    fun stopAll() {
        currentTrack = null
        commands.add(Cmd.StopAll)
    }

    fun songFor(track: MusicTrack): Song = when (track) {
        MusicTrack.MENU -> Songs.menu
        MusicTrack.GAME -> Songs.game
        MusicTrack.DANCE -> Songs.dance
        MusicTrack.VICTORY -> Songs.victory
        MusicTrack.CALM -> Songs.calm
    }

    /**
     * Beat position of the music the players are *hearing* at [nowNanos] (System.nanoTime clock), or null when the
     * given track is not playing.
     */
    fun audibleBeat(track: MusicTrack, nowNanos: Long): Double? {
        if (pubTrack != track || pubNanos == 0L) return null
        val sec = (nowNanos - pubNanos) / 1e9 - outputLatencySec
        return pubBeat + sec * pubBpm / 60.0
    }

    /** Fills [out] with [frames] interleaved stereo float samples in -1..1. */
    fun render(out: FloatArray, frames: Int, nowNanos: Long = System.nanoTime()) {
        drainCommands()
        val cur = current
        if (cur != null) { pubBeat = cur.beat; pubBpm = cur.song.bpm; pubTrack = cur.track; pubNanos = nowNanos } else pubTrack = null
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        if (!ready) return
        val mVol = musicVolume
        val sVol = sfxVolume
        // schedule notes for this block (sample-accurate)
        current?.let { schedule(it, frames) }
        fading?.let { schedule(it, frames) }
        val fadeStep = 1f / (0.6f * sampleRate)
        for (i in 0 until frames) {
            var l = 0f
            var r = 0f
            for (v in voices) {
                if (!v.active) continue
                val s = v.next(sampleRate)
                l += s * (1f - max(0f, v.pan))
                r += s * (1f + min(0f, v.pan))
            }
            // music gain: crossfade between current and fading playback
            val cg = current?.let { p -> p.gain = approach(p.gain, p.target, fadeStep); p.gain } ?: 0f
            fading?.let { p -> p.gain = approach(p.gain, 0f, fadeStep) }
            val g = mVol * if (fading != null) max(cg, fading!!.gain) else cg
            l *= g; r *= g
            for (sv in sfxVoices) {
                val d = sv.data ?: continue
                val idx = sv.pos.toInt()
                if (idx >= d.size - 1) { sv.data = null; continue }
                val f = (sv.pos - idx).toFloat()
                val s = (d[idx] * (1f - f) + d[idx + 1] * f) * sv.vol * sVol
                l += s * (1f - max(0f, sv.pan))
                r += s * (1f + min(0f, sv.pan))
                sv.pos += sv.step
            }
            out[i * 2] = softClip(l)
            out[i * 2 + 1] = softClip(r)
        }
        if (fading != null && fading!!.gain <= 0f) fading = null
        current?.let { advance(it, frames) }
        fading?.let { advance(it, frames) }
    }

    private fun approach(v: Float, t: Float, step: Float) = if (v < t) min(t, v + step) else max(t, v - step)

    /** Cubic soft clipper: transparent for normal levels, rounds off peaks instead of hard clipping. */
    private fun softClip(x: Float): Float = when {
        x > 1.5f -> 1f
        x < -1.5f -> -1f
        else -> x - (4f / 27f) * x * x * x
    }

    private fun drainCommands() {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Cmd.PlaySfx -> {
                    val data = sfxBank[c.sfx] ?: continue
                    val v = sfxVoices.firstOrNull { it.data == null } ?: sfxVoices.minByOrNull { -it.pos }!!
                    v.data = data; v.pos = 0.0; v.step = c.pitch.toDouble(); v.vol = c.vol; v.pan = c.pan.coerceIn(-1f, 1f)
                }
                is Cmd.PlayMusic -> {
                    val cur = current
                    if (c.track == null) {
                        if (cur != null) { cur.target = 0f; fading = cur; current = null }
                    } else if (cur == null || cur.track != c.track || c.restart) {
                        if (cur != null) { fading = cur }
                        val song = songFor(c.track)
                        current = Playback(song, c.track).also { pb ->
                            pb.gain = if (cur == null) 1f else 0f
                            pb.beat = if (song.loop) c.startBeat % song.lengthBeats else c.startBeat
                            while (pb.nextIndex < song.notes.size && song.notes[pb.nextIndex].beat < pb.beat) pb.nextIndex++
                        }
                    }
                }
                Cmd.StopAll -> {
                    current = null; fading = null
                    voices.forEach { it.active = false }
                    sfxVoices.forEach { it.data = null }
                }
            }
        }
    }

    private fun schedule(p: Playback, frames: Int) {
        if (p.finished) return
        val beatsPerSample = p.song.bpm / 60.0 / sampleRate
        val end = p.beat + frames * beatsPerSample
        val notes = p.song.notes
        while (true) {
            if (p.nextIndex >= notes.size) {
                if (!p.song.loop) { p.finished = true; return }
                break
            }
            val n = notes[p.nextIndex]
            if (n.beat >= end) break
            val delay = ((n.beat - p.beat) / beatsPerSample).toInt().coerceIn(0, frames - 1)
            val v = voices.firstOrNull { !it.active } ?: voices.maxByOrNull { it.t }!!
            v.start(n.inst, midiToHz(n.midi), n.vel, (n.dur * 60.0 / p.song.bpm).toFloat(), n.pan, delay, sampleRate)
            p.nextIndex++
        }
    }

    private fun advance(p: Playback, frames: Int) {
        p.beat += frames * p.song.bpm / 60.0 / sampleRate
        if (p.song.loop && p.beat >= p.song.lengthBeats) {
            p.beat -= p.song.lengthBeats
            p.nextIndex = 0
            // skip notes before the wrapped position
            while (p.nextIndex < p.song.notes.size && p.song.notes[p.nextIndex].beat < p.beat) p.nextIndex++
        }
    }
}
