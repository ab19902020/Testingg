package com.clrg.core.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the synthesised sound: every effect renders cleanly at its intended level, the mixer stays inside full
 * scale with lots going on, ambiences sit quietly under everything and crossfade, rooms and ducking work.
 * These are signal measurements; they say nothing about how the sounds are perceived through a TV's speakers.
 */
class AudioTest {
    private val sr = 48000

    private fun db(x: Double) = 20 * log10(max(x, 1e-12))

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / max(1, to - from))
    }

    @Test
    fun everyEffectRendersCleanly() {
        val t0 = System.nanoTime()
        var bytes = 0L
        for (s in Sfx.values()) {
            val d = SfxSynth.render(s, sr)
            bytes += d.bytes
            val sec = d.frames / sr.toFloat()
            assertTrue(sec in 0.03f..4f, "$s length $sec s")
            var peak = 0f
            for (i in 0 until d.frames) {
                peak = max(peak, max(abs(d.left(i)), abs(d.right(i))))
            }
            assertTrue(peak <= 0.951f, "$s peak $peak")
            assertTrue(peak > 0.05f, "$s is nearly silent (peak $peak)")
            val loud = db(rms(FloatArray(d.frames) { d.left(it) }))
            assertTrue(loud > -60, "$s RMS $loud dB")
            // no click at the end: the last few samples are near zero
            assertTrue(abs(d.left(d.frames - 1)) < 0.01f && abs(d.right(d.frames - 1)) < 0.01f, "$s ends with a click")
        }
        val sec = (System.nanoTime() - t0) / 1e9
        println("rendered ${Sfx.values().size} effects in ${"%.2f".format(sec)} s on this test machine (JVM), ${bytes / 1024} KiB")
        assertTrue(bytes < 14L * 1024 * 1024, "effects use ${bytes / 1024 / 1024} MiB")
    }

    @Test
    fun renderingIsDeterministic() {
        for (s in listOf(Sfx.CROWD_CHEER, Sfx.GLASS, Sfx.BANG, Sfx.UI_HOVER)) {
            val a = SfxSynth.render(s, sr)
            val b = SfxSynth.render(s, sr)
            assertTrue(a.l.contentEquals(b.l) && a.r.contentEquals(b.r), "$s differs between renders")
        }
    }

    @Test
    fun crowdsAndRoomsAreStereo() {
        for (s in listOf(Sfx.CROWD_CHEER, Sfx.APPLAUSE, Sfx.CROWD_OOH, Sfx.FANFARE, Sfx.SPARKLE, Sfx.BANG)) {
            val d = SfxSynth.render(s, sr)
            assertTrue(!d.mono, "$s should be stereo")
            var diff = 0.0
            for (i in 0 until d.frames) diff += abs(d.left(i) - d.right(i))
            assertTrue(diff / d.frames > 1e-3, "$s channels nearly identical")
        }
        assertTrue(SfxSynth.render(Sfx.TICK, sr).mono, "simple centred clicks are stored once")
    }

    private fun engine(): AudioEngine = AudioEngine(sr).also { it.prepare() }

    private fun run(e: AudioEngine, sec: Float, block: Int = 256): Pair<FloatArray, FloatArray> {
        val frames = (sec * sr).toInt() / block * block
        val l = FloatArray(frames); val r = FloatArray(frames)
        val buf = FloatArray(block * 2)
        var i = 0
        while (i < frames) {
            e.render(buf, block, 0L)
            for (k in 0 until block) { l[i + k] = buf[k * 2]; r[i + k] = buf[k * 2 + 1] }
            i += block
        }
        return l to r
    }

    @Test
    fun busyMixStaysInsideFullScale() {
        val e = engine()
        e.musicVolume = 1f; e.sfxVolume = 1f; e.ambienceVolume = 1f
        e.playMusic(MusicTrack.GAME)
        e.setAmbience(Ambience.ARENA)
        e.setRoom(Room.ARENA)
        for (s in listOf(Sfx.CROWD_CHEER, Sfx.FANFARE, Sfx.BANG, Sfx.STRIKE, Sfx.PUNCH_HEAVY, Sfx.KNOCKDOWN, Sfx.GLASS, Sfx.GO)) e.play(s, 1f)
        repeat(20) { e.play(Sfx.values()[it * 3 % Sfx.values().size], 1f, (it % 5 - 2) / 2f) }
        val (l, r) = run(e, 2f)
        var peak = 0f
        for (i in l.indices) { assertTrue(l[i].isFinite() && r[i].isFinite()); peak = max(peak, max(abs(l[i]), abs(r[i]))) }
        assertTrue(peak <= 1f, "peak $peak")
        assertTrue(db(rms(l)) > -30, "busy mix is audible: ${db(rms(l))} dB")
    }

    @Test
    fun ambiencesSitQuietlyUnderEverything() {
        for (a in Ambience.values().filter { it != Ambience.NONE }) {
            val e = engine()
            e.ambienceVolume = 1f; e.sfxVolume = 1f
            e.setAmbience(a)
            val (l, r) = run(e, 8f)
            val from = 3 * sr
            val level = db((rms(l, from) + rms(r, from)) / 2)
            var peak = 0f
            for (i in l.indices) peak = max(peak, max(abs(l[i]), abs(r[i])))
            println("ambience $a: ${"%.1f".format(level)} dBFS RMS, peak ${"%.2f".format(peak)}")
            assertTrue(level in -50.0..-22.0, "$a level $level dB")
            assertTrue(peak < 0.5f, "$a peak $peak")
        }
    }

    @Test
    fun ambienceCrossfadesAndStops() {
        val e = engine()
        e.ambienceVolume = 1f; e.sfxVolume = 1f
        e.setAmbience(Ambience.PARK)
        run(e, 3f)
        e.setAmbience(Ambience.NONE)
        assertEquals(Ambience.NONE, e.currentAmbience)
        run(e, 2.5f)
        val (l, _) = run(e, 1f)
        assertTrue(db(rms(l)) < -90, "ambience faded out: ${db(rms(l))} dB")
    }

    @Test
    fun biggerRoomsRingLonger() {
        fun tail(room: Room): Double {
            val e = engine()
            e.sfxVolume = 1f
            e.setRoom(room)
            e.play(Sfx.PUNCH, 1f)
            val (l, r) = run(e, 1.6f)
            // energy well after the dry punch has ended
            return (rms(l, (0.9 * sr).toInt()) + rms(r, (0.9 * sr).toInt())) / 2
        }
        val arena = tail(Room.ARENA)
        val outdoor = tail(Room.OUTDOOR)
        assertTrue(arena > outdoor * 3, "arena tail ${db(arena)} dB vs outdoor ${db(outdoor)} dB")
    }

    @Test
    fun bigMomentsDipTheMusic() {
        fun musicLevel(withFanfare: Boolean): Double {
            val e = engine()
            e.musicVolume = 1f; e.sfxVolume = 1f
            e.playMusic(MusicTrack.MENU)
            run(e, 1f)
            if (withFanfare) e.play(Sfx.FANFARE, 1f)
            // measure the music alone is impossible from the mix, so compare with the effect muted afterwards
            e.sfxVolume = if (withFanfare) 0f else 1f
            val (l, _) = run(e, 1f)
            return rms(l, sr / 4)
        }
        val normal = musicLevel(false)
        val ducked = musicLevel(true)
        assertTrue(ducked < normal * 0.6, "music under the fanfare ${db(ducked)} dB vs ${db(normal)} dB")
    }

    @Test
    fun effectsPlayBeforeEveryEffectIsReady() {
        val e = AudioEngine(sr)
        e.play(Sfx.UI_HOVER)
        val (l, _) = run(e, 0.2f)
        assertTrue(l.all { it.isFinite() })
        assertTrue(!e.ready && e.sfxData(Sfx.UI_HOVER) == null, "nothing rendered yet, nothing crashes")
    }

    /** Developer tool (-Pclrg.sfxdump=<dir>): writes every effect and 10 s of each ambience as WAV files. */
    @Test
    fun dump() {
        val dir = System.getProperty("clrg.sfxdump")
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null, "developer tool: run with -Pclrg.sfxdump=<dir>")
        val out = File(dir!!).also { it.mkdirs() }
        for (s in Sfx.values()) {
            val t0 = System.nanoTime()
            val d = SfxSynth.render(s, sr)
            val ms = (System.nanoTime() - t0) / 1e6
            println("STAT ${s.name} ms=${"%.0f".format(ms)} sec=${"%.2f".format(d.frames / sr.toFloat())} mono=${d.mono} kib=${d.bytes / 1024}")
            writeWav(File(out, "sfx_${s.name.lowercase()}.wav"), FloatArray(d.frames) { d.left(it) }, FloatArray(d.frames) { d.right(it) })
        }
        for (a in Ambience.values().filter { it != Ambience.NONE }) {
            val e = engine(); e.ambienceVolume = 1f; e.sfxVolume = 1f
            e.setAmbience(a)
            val (l, r) = run(e, 10f)
            writeWav(File(out, "amb_${a.name.lowercase()}.wav"), l, r)
        }
        println("wrote ${out.listFiles()?.size} files to $out")
    }

    private fun writeWav(f: File, l: FloatArray, r: FloatArray) {
        val n = l.size
        RandomAccessFile(f, "rw").use { w ->
            w.setLength(0)
            val bb = java.nio.ByteBuffer.allocate(44 + n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 4); bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(2); bb.putInt(sr); bb.putInt(sr * 4); bb.putShort(4); bb.putShort(16)
            bb.put("data".toByteArray()); bb.putInt(n * 4)
            for (i in 0 until n) {
                bb.putShort((l[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                bb.putShort((r[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
            }
            w.write(bb.array())
        }
    }
}
