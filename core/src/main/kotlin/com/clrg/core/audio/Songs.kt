package com.clrg.core.audio

class NoteEvent(val beat: Double, val dur: Double, val midi: Float, val inst: Instrument, val vel: Float, val pan: Float)

class Song(val title: String, val bpm: Double, val beatsPerBar: Int, val lengthBeats: Double, notes: List<NoteEvent>, val loop: Boolean = true) {
    val notes: List<NoteEvent> = notes.sortedBy { it.beat }
    val secondsPerBeat: Double get() = 60.0 / bpm
}

/**
 * Original compositions written for this game (melodies, harmony and arrangements are all original; see
 * LICENSES.md). Songs are described with a tiny text notation and arranged procedurally.
 */
object Songs {
    // ---------------------------------------------------------------- notation helpers
    private val noteIndex = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    fun midi(name: String): Float {
        var i = 0
        var n = noteIndex[name[i++]] ?: error("bad note $name")
        while (i < name.length && (name[i] == '#' || name[i] == 'b')) { n += if (name[i] == '#') 1 else -1; i++ }
        val octave = name.substring(i).toInt()
        return (12 * (octave + 1) + n).toFloat()
    }

    /** "A4:1 C5:0.5 R:0.5 | ..." -> events starting at [startBeat]. */
    fun melody(text: String, inst: Instrument, vel: Float = 0.8f, pan: Float = 0f, startBeat: Double = 0.0, transpose: Int = 0, legato: Double = 0.9): Pair<List<NoteEvent>, Double> {
        val out = ArrayList<NoteEvent>()
        var beat = startBeat
        for (tok in text.split(' ', '\n', '\t')) {
            if (tok.isBlank() || tok == "|") continue
            val (n, d) = tok.split(':')
            val dur = d.toDouble()
            if (n != "R") out.add(NoteEvent(beat, dur * legato, midi(n) + transpose, inst, vel, pan))
            beat += dur
        }
        return out to beat
    }

    /** Chord symbol -> midi pitches (root position, root in octave 3). */
    fun chord(sym: String): FloatArray {
        var i = 1
        var root = noteIndex[sym[0]]!!
        if (i < sym.length && (sym[i] == '#' || sym[i] == 'b')) { root += if (sym[i] == '#') 1 else -1; i++ }
        val q = sym.substring(i)
        val intervals = when (q) {
            "" -> intArrayOf(0, 4, 7)
            "m" -> intArrayOf(0, 3, 7)
            "7" -> intArrayOf(0, 4, 7, 10)
            "m7" -> intArrayOf(0, 3, 7, 10)
            "maj7" -> intArrayOf(0, 4, 7, 11)
            "sus4" -> intArrayOf(0, 5, 7)
            else -> error("chord $sym")
        }
        val base = 48 + root
        return FloatArray(intervals.size) { (base + intervals[it]).toFloat() }
    }

    private class Builder(val beatsPerBar: Int) {
        val notes = ArrayList<NoteEvent>()
        fun add(list: List<NoteEvent>) { notes.addAll(list) }
        fun add(beat: Double, dur: Double, midi: Float, inst: Instrument, vel: Float, pan: Float = 0f) { notes.add(NoteEvent(beat, dur, midi, inst, vel, pan)) }

        /** One chord per bar (or "C G" = two chords sharing a bar). */
        fun forEachChord(prog: String, bars: Int, block: (bar: Int, beat: Double, len: Double, chord: FloatArray) -> Unit) {
            val barList = prog.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            for (b in 0 until bars) {
                val parts = barList[b % barList.size].split(' ').filter { it.isNotEmpty() }
                val len = beatsPerBar.toDouble() / parts.size
                parts.forEachIndexed { k, sym -> block(b, b * beatsPerBar + k * len, len, chord(sym)) }
            }
        }

        fun drums(pattern: String, inst: Instrument, bars: Int, vel: Float, stepsPerBeat: Int = 4, pan: Float = 0f) {
            val p = pattern.replace(" ", "")
            val step = 1.0 / stepsPerBeat
            for (b in 0 until bars) for (i in p.indices) {
                val c = p[i]
                if (c == '-' || c == '.') continue
                val v = if (c.isUpperCase() || c == 'x') vel else vel * 0.55f
                add(b * beatsPerBar + i * step, step, 60f, inst, v, pan)
            }
        }
    }

    // ---------------------------------------------------------------- the tracks

    /** "Snowfall Waltz" - menus. 3/4, F major. */
    val menu: Song by lazy {
        val b = Builder(3)
        val mel = """
            A4:1 C5:1 F5:1 | E5:1.5 D5:0.5 A4:1 | D5:1 F5:1 D5:1 | C5:2 R:1 |
            A4:1 C5:1 F5:1 | G5:1.5 F5:0.5 E5:1 | D5:1 E5:1 G5:1 | F5:3 |
            C5:1 D5:1 E5:1 | F5:1.5 E5:0.5 D5:1 | Bb4:1 D5:1 F5:1 | E5:2 C5:1 |
            A4:1 C5:1 F5:1 | G5:1 A5:1 Bb5:1 | A5:1 G5:1 E5:1 | F5:3
        """
        b.add(melody(mel, Instrument.BELL, 0.55f, 0.15f).first)
        b.add(melody(mel, Instrument.GLOCK, 0.18f, -0.3f, transpose = 12).first)
        b.forEachChord("F | Dm | Bb | C | F | Am | Bb C | F | C | Dm | Bb | C | F | Bb | C | F", 16) { _, beat, len, ch ->
            b.add(beat, len * 0.9, ch[0] - 12, Instrument.BASS, 0.7f)
            for (k in 1 until (len.toInt().coerceAtLeast(1))) for (n in ch) b.add(beat + k, 0.6, n + 12, Instrument.PLUCK, 0.32f, -0.2f)
            for (n in ch) b.add(beat, len, n + 12, Instrument.PAD, 0.25f, 0.1f)
        }
        b.drums("x-x-x-", Instrument.SLEIGH, 16, 0.25f, stepsPerBeat = 2, pan = 0.4f)
        Song("Snowfall Waltz", 96.0, 3, 48.0, b.notes)
    }

    /** "Jingle Rush" - upbeat gameplay. 4/4, D major. */
    val game: Song by lazy {
        val b = Builder(4)
        val a = """
            F#5:0.5 A5:0.5 F#5:0.5 D5:0.5 E5:1 F#5:1 | D5:0.5 F#5:0.5 D5:0.5 B4:0.5 C#5:1 D5:1 |
            B4:0.5 D5:0.5 G5:0.5 F#5:0.5 E5:1 D5:1 | C#5:1 E5:1 A5:2 |
            F#5:0.5 A5:0.5 F#5:0.5 D5:0.5 E5:1 F#5:1 | B5:0.5 A5:0.5 F#5:0.5 D5:0.5 E5:1 F#5:1 |
            G5:1 E5:1 C#5:1 E5:1 | D5:3 R:1 |
            A5:1 B5:0.5 A5:0.5 F#5:1 D5:1 | G5:1 A5:0.5 G5:0.5 E5:1 C#5:1 |
            F#5:1 G5:0.5 F#5:0.5 D5:1 B4:1 | E5:1 F#5:0.5 E5:0.5 C#5:1 A4:1 |
            D5:0.5 E5:0.5 F#5:0.5 G5:0.5 A5:1 D6:1 | B5:0.5 A5:0.5 G5:0.5 F#5:0.5 E5:2 |
            G5:1 F#5:1 E5:1 C#5:1 | D5:2 A4:1 D5:1
        """
        b.add(melody(a, Instrument.LEAD, 0.8f, 0f).first)
        b.add(melody(a, Instrument.BELL, 0.25f, 0.3f, transpose = 12, legato = 0.5).first)
        b.forEachChord("D | Bm | G | A | D | Bm | Em A | D | D | Em | Bm | A | D | G | Em A | D", 16) { _, beat, len, ch ->
            var k = 0.0
            while (k < len - 1e-6) { b.add(beat + k, 0.4, ch[0] - 12, Instrument.BASS, 0.75f); k += 0.5 }
            for (s in 0 until (len * 2).toInt()) b.add(beat + s * 0.5, 0.25, ch[s % ch.size] + 12, Instrument.PLUCK, 0.22f, -0.35f)
        }
        b.drums("K---s---K-K-s---".replace('K', 'x').replace('s', '-'), Instrument.KICK, 16, 0.8f)
        b.drums("----x-------x---", Instrument.SNARE, 16, 0.6f)
        b.drums("x-x-x-x-x-x-x-x-", Instrument.SLEIGH, 16, 0.35f, pan = 0.45f)
        b.drums("-x-x-x-x-x-x-x-x", Instrument.HAT, 16, 0.4f, pan = -0.3f)
        Song("Jingle Rush", 144.0, 4, 64.0, b.notes)
    }

    /** "Elf Groove" - the dance track: a clear four-on-the-floor beat at 112 BPM. */
    val dance: Song by lazy {
        val b = Builder(4)
        val hook = """
            A4:0.75 C5:0.75 E5:0.5 D5:1 C5:1 | A4:0.75 C5:0.75 F5:0.5 E5:1 C5:1 |
            G4:0.75 C5:0.75 E5:0.5 G5:1 E5:1 | D5:0.75 B4:0.75 G4:0.5 B4:1 D5:1 |
            A4:0.75 C5:0.75 E5:0.5 A5:1 G5:1 | F5:0.75 E5:0.75 C5:0.5 A4:1 C5:1 |
            E5:0.75 G5:0.75 C6:0.5 B5:1 G5:1 | B5:0.75 A5:0.75 G5:0.5 E5:2
        """
        b.add(melody(hook, Instrument.LEAD, 0.7f, -0.1f, startBeat = 0.0).first)
        b.add(melody(hook, Instrument.GLOCK, 0.3f, 0.3f, startBeat = 0.0, transpose = 12, legato = 0.4).first)
        b.add(melody(hook, Instrument.LEAD, 0.7f, -0.1f, startBeat = 32.0).first)
        b.add(melody(hook, Instrument.BELL, 0.35f, 0.3f, startBeat = 32.0, transpose = 12).first)
        b.forEachChord("Am | F | C | G", 16) { _, beat, len, ch ->
            for (s in 0 until (len * 2).toInt()) b.add(beat + s * 0.5 + 0.25, 0.2, ch[0] - 12 + (if (s % 2 == 1) 12 else 0), Instrument.BASS, 0.7f)
            for (n in ch) b.add(beat, len, n + 12, Instrument.PAD, 0.22f)
        }
        b.drums("x---x---x---x---", Instrument.KICK, 16, 0.95f)
        b.drums("----x-------x---", Instrument.CLAP, 16, 0.7f)
        b.drums("--x---x---x---x-", Instrument.HAT, 16, 0.45f, pan = 0.3f)
        b.drums("x-x-x-x-x-x-x-x-", Instrument.SLEIGH, 16, 0.2f, pan = -0.4f)
        Song("Elf Groove", 112.0, 4, 64.0, b.notes)
    }

    /** "Victory Bells" - short results fanfare. */
    val victory: Song by lazy {
        val b = Builder(4)
        val fan = "C5:0.5 E5:0.5 G5:0.5 C6:1.5 R:1 | A5:0.5 F5:0.5 A5:0.5 C6:1.5 R:1 | G5:0.5 B5:0.5 D6:0.5 G6:1.5 R:1 | C6:4"
        b.add(melody(fan, Instrument.LEAD, 0.75f).first)
        b.add(melody(fan, Instrument.BELL, 0.45f, 0.3f, transpose = -12).first)
        b.forEachChord("C | F | G | C", 4) { _, beat, len, ch ->
            b.add(beat, len, ch[0] - 12, Instrument.BASS, 0.7f)
            for (n in ch) b.add(beat, len, n + 12, Instrument.PAD, 0.3f)
        }
        b.drums("x-x-x-x-x-x-x-x-", Instrument.SLEIGH, 4, 0.4f, pan = 0.4f)
        b.drums("x-------x-------", Instrument.KICK, 3, 0.7f)
        Song("Victory Bells", 120.0, 4, 16.0, b.notes, loop = false)
    }

    /** "Fireside" - calm background for set-up and calibration. */
    val calm: Song by lazy {
        val b = Builder(4)
        b.forEachChord("Cmaj7 | Am7 | Fmaj7 | G | Cmaj7 | Am7 | Fmaj7 | Gsus4 G", 8) { bar, beat, len, ch ->
            b.add(beat, len, ch[0] - 12, Instrument.BASS, 0.45f)
            for (n in ch) b.add(beat, len, n + 12, Instrument.PAD, 0.28f)
            val order = intArrayOf(0, 1, 2, ch.size - 1, 2, 1)
            for (s in 0 until (len * 1.5).toInt()) b.add(beat + s * (2.0 / 3.0), 0.6, ch[order[(s + bar) % order.size] % ch.size] + 24, Instrument.GLOCK, 0.25f, if (s % 2 == 0) -0.4f else 0.4f)
        }
        Song("Fireside", 80.0, 4, 32.0, b.notes)
    }
}
