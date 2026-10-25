package com.clrg.core.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class MusicTrack { MENU, GAME, DANCE, VICTORY, CALM }

/**
 * Real-time mixer + music sequencer. [render] is called from the platform audio thread; everything else is called
 * from the game thread and communicates through a lock-free command queue. Music, effects and the background
 * ambience have independent volumes. Effects are stereo, pass through a shared room reverb (the [Room] of the current
 * screen) and the whole mix goes through a peak limiter. Audio is generated entirely in code.
 */
class AudioEngine(val sampleRate: Int = 48000) {
    @Volatile var musicVolume = 0.6f
    @Volatile var sfxVolume = 0.85f
    /** Background ambience level 0..1, relative to the effects volume. */
    @Volatile var ambienceVolume = 0.7f
    /** Output latency (seconds) reported by the platform, used for beat-accurate visuals. */
    @Volatile var outputLatencySec = 0.06

    private sealed class Cmd {
        class PlaySfx(val sfx: Sfx, val vol: Float, val pan: Float, val pitch: Float) : Cmd()
        class PlayMusic(val track: MusicTrack?, val restart: Boolean, val startBeat: Double = 0.0) : Cmd()
        class SetAmbience(val gen: AmbienceGen?) : Cmd()
        class SetRoom(val room: Room) : Cmd()
        object StopAll : Cmd()
    }

    private val commands = ConcurrentLinkedQueue<Cmd>()
    private val bank = AtomicReferenceArray<SfxData?>(Sfx.values().size)
    @Volatile var ready = false
        private set
    /** Seconds the last [prepare] took on this device (shown in diagnostics; not a benchmark). */
    @Volatile var prepareSeconds = 0.0
        private set

    /** Sample memory held by the rendered effects. */
    val bankBytes: Long get() = (0 until bank.length()).sumOf { bank.get(it)?.bytes ?: 0L }

    fun sfxData(s: Sfx): SfxData? = bank.get(s.ordinal)

    // ---- audio-thread state
    private class SfxVoice {
        var data: SfxData? = null; var pos = 0.0; var step = 1.0; var gl = 1f; var gr = 1f; var send = 0f; var duck = 0f
    }
    private val sfxVoices = Array(32) { SfxVoice() }
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
    private val reverb = Reverb(sampleRate).also { it.configure(Room.STUDIO.size, Room.STUDIO.damp, Room.STUDIO.width, Room.STUDIO.pre, sampleRate) }
    private var roomWet = Room.STUDIO.wet
    private val limiter = Limiter(sampleRate)
    private var amb: AmbienceGen? = null
    private var ambGain = 0f
    private var ambOld: AmbienceGen? = null
    private var ambOldGain = 0f
    private var duck = 1f
    private val rnd = Noise(4242)

    // ---- timing published to the game thread
    @Volatile private var pubBeat = 0.0
    @Volatile private var pubNanos = 0L
    @Volatile private var pubBpm = 120.0
    @Volatile private var pubTrack: MusicTrack? = null
    @Volatile var currentTrack: MusicTrack? = null
        private set
    @Volatile var currentAmbience: Ambience = Ambience.NONE
        private set
    @Volatile var currentRoom: Room = Room.STUDIO
        private set

    /**
     * Renders the effect samples (call once, off the UI and audio threads). Menu sounds come first so the interface
     * has sound straight away; the audio thread plays whatever is ready and skips the rest.
     */
    @Synchronized
    fun prepare() {
        if (ready) return
        val t0 = System.nanoTime()
        val first = listOf(Sfx.UI_HOVER, Sfx.TICK, Sfx.SELECT, Sfx.UI_CONFIRM, Sfx.UI_BACK, Sfx.UI_OPEN, Sfx.BEEP, Sfx.GO)
        for (s in first + Sfx.values().filter { it !in first }) {
            if (bank.get(s.ordinal) == null) bank.set(s.ordinal, SfxSynth.render(s, sampleRate))
        }
        prepareSeconds = (System.nanoTime() - t0) / 1e9
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

    /** Crossfades to a new background ambience (NONE fades it out). */
    fun setAmbience(a: Ambience) {
        if (a == currentAmbience) return
        currentAmbience = a
        commands.add(Cmd.SetAmbience(if (a == Ambience.NONE) null else AmbienceGen(sampleRate, a, a.ordinal * 17)))
    }

    /** Changes the acoustic space the effects are heard in. */
    fun setRoom(r: Room) {
        if (r == currentRoom) return
        currentRoom = r
        commands.add(Cmd.SetRoom(r))
    }

    fun stopAll() {
        currentTrack = null
        currentAmbience = Ambience.NONE
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
        val mVol = musicVolume
        val sVol = sfxVolume
        val sScaled = sVol * SfxData.SCALE
        val ambTarget = ambienceVolume * sVol
        // schedule notes for this block (sample-accurate)
        current?.let { schedule(it, frames) }
        fading?.let { schedule(it, frames) }
        val fadeStep = 1f / (0.6f * sampleRate)
        val ambStep = 1f / (1.5f * sampleRate)
        val duckDown = 1f / (0.03f * sampleRate)
        val duckUp = 1f / (0.45f * sampleRate)
        for (i in 0 until frames) {
            var l = 0f
            var r = 0f
            for (v in voices) {
                if (!v.active) continue
                val s = v.next(sampleRate)
                l += s * (1f - max(0f, v.pan))
                r += s * (1f + min(0f, v.pan))
            }
            // music gain: crossfade between current and fading playback, dipped under big effects
            val cg = current?.let { p -> p.gain = approach(p.gain, p.target, fadeStep); p.gain } ?: 0f
            fading?.let { p -> p.gain = approach(p.gain, 0f, fadeStep) }
            val g = mVol * duck * if (fading != null) max(cg, fading!!.gain) else cg
            l *= g; r *= g
            var send = (l + r) * MUSIC_SEND
            var duckWant = 1f
            for (sv in sfxVoices) {
                val d = sv.data ?: continue
                val idx = sv.pos.toInt()
                if (idx >= d.frames - 1) { sv.data = null; continue }
                val f = (sv.pos - idx).toFloat()
                val dl = d.l
                val a = (dl[idx] * (1f - f) + dl[idx + 1] * f) * sScaled
                val b = if (d.mono) a else (d.r[idx] * (1f - f) + d.r[idx + 1] * f) * sScaled
                val sl = a * sv.gl
                val sr = b * sv.gr
                l += sl; r += sr
                send += (sl + sr) * 0.5f * sv.send
                if (sv.duck > 0f) duckWant = min(duckWant, 1f - sv.duck)
                sv.pos += sv.step
            }
            duck = if (duckWant < duck) max(duckWant, duck - duckDown) else min(duckWant, duck + duckUp)
            reverb.process(send)
            l += reverb.outL * roomWet
            r += reverb.outR * roomWet
            amb?.let { a -> ambGain = approach(ambGain, ambTarget, ambStep); a.next(); l += a.outL * ambGain; r += a.outR * ambGain }
            ambOld?.let { a ->
                ambOldGain = approach(ambOldGain, 0f, ambStep)
                if (ambOldGain <= 0f) ambOld = null else { a.next(); l += a.outL * ambOldGain; r += a.outR * ambOldGain }
            }
            val lim = limiter.gainFor(max(abs(l), abs(r)))
            out[i * 2] = softClip(l * lim)
            out[i * 2 + 1] = softClip(r * lim)
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
                    val data = bank.get(c.sfx.ordinal) ?: continue
                    var v: SfxVoice? = null
                    var most = -1.0
                    for (sv in sfxVoices) {
                        val d = sv.data
                        if (d == null) { v = sv; break }
                        val done = sv.pos / d.frames
                        if (done > most) { most = done; v = sv }
                    }
                    val sv = v ?: continue
                    val p = panGains(c.pan)
                    sv.data = data; sv.pos = 0.0
                    sv.step = (c.pitch * (1f + c.sfx.vary * rnd.white())).toDouble()
                    sv.gl = p.first * c.vol; sv.gr = p.second * c.vol
                    sv.send = c.sfx.send; sv.duck = c.sfx.duck * min(1f, c.vol)
                }
                is Cmd.SetAmbience -> {
                    if (amb != null) { ambOld = amb; ambOldGain = ambGain }
                    amb = c.gen; ambGain = 0f
                }
                is Cmd.SetRoom -> {
                    val rm = c.room
                    reverb.configure(rm.size, rm.damp, rm.width, rm.pre, sampleRate)
                    roomWet = rm.wet
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
                    amb = null; ambOld = null
                    reverb.clear()
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

    private companion object {
        /** How much of the music goes to the room reverb. */
        const val MUSIC_SEND = 0.05f
    }
}
