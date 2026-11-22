package com.neontuner.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class ChordTest {
    private fun pc(name: String) = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B").indexOf(name)

    private fun tab(root: String, q: ChordQuality, board: Fretboard = Fretboard.GUITAR) =
        ChordShapes.voicing(Chord(pc(root), q), board)!!.tab

    @Test
    fun guitarOpenChordsLookFamiliar() {
        assertEquals("x32010", tab("C", ChordQuality.MAJ))
        assertEquals("320003", tab("G", ChordQuality.MAJ))
        assertEquals("xx0232", tab("D", ChordQuality.MAJ))
        assertEquals("022100", tab("E", ChordQuality.MAJ))
        assertEquals("x02220", tab("A", ChordQuality.MAJ))
        assertEquals("x02210", tab("A", ChordQuality.MIN))
        assertEquals("022000", tab("E", ChordQuality.MIN))
        assertEquals("xx0231", tab("D", ChordQuality.MIN))
        assertEquals("x21202", tab("B", ChordQuality.DOM7))
        assertEquals("133211", tab("F", ChordQuality.MAJ))
    }

    @Test
    fun everyGuitarAndUkuleleChordHasAVoicing() {
        for (board in Fretboard.entries) for (root in 0 until 12) for (q in ChordQuality.entries) {
            if (board == Fretboard.UKULELE && q == ChordQuality.POWER) continue
            val v = ChordShapes.voicing(Chord(root, q), board)
            assertNotNull("no voicing for ${Chord(root, q).name} on $board", v)
        }
    }

    @Test
    fun ukuleleBasics() {
        assertEquals("0003", tab("C", ChordQuality.MAJ, Fretboard.UKULELE))
        assertEquals("0232", tab("G", ChordQuality.MAJ, Fretboard.UKULELE))
        assertEquals("2010", tab("F", ChordQuality.MAJ, Fretboard.UKULELE))
        assertEquals("2000", tab("A", ChordQuality.MIN, Fretboard.UKULELE))
    }

    // ---------- detection on synthetic strummed chords ----------

    private val sr = 48000

    private fun strum(midis: IntArray, cents: Float = 0f, noise: Double = 0.003): FloatArray {
        val det = ChordDetector(sr)
        val rnd = Random(3)
        return FloatArray(det.frameSize) { i ->
            val t = i.toDouble() / sr
            var s = 0.0
            for ((k, m) in midis.withIndex()) {
                val f = 440.0 * 2.0.pow((m - 69 + cents / 100.0) / 12.0)
                val delay = k * 0.012 // strum
                if (t < delay) continue
                val env = exp(-(t - delay) * 2.0)
                for (h in 1..6) s += env * sin(2 * PI * f * h * (t - delay)) * 0.8.pow(h - 1) / h
            }
            (0.1 * s + noise * (rnd.nextDouble() - 0.5)).toFloat()
        }
    }

    private fun detect(midis: IntArray, cents: Float = 0f) = ChordDetector(sr).analyse(strum(midis, cents), 440f)?.name

    @Test
    fun detectsCommonGuitarChords() {
        assertEquals("C", detect(intArrayOf(48, 52, 55, 60, 64)))          // x32010
        assertEquals("Am", detect(intArrayOf(45, 52, 57, 60, 64)))         // x02210
        assertEquals("G", detect(intArrayOf(43, 47, 50, 55, 59, 67)))      // 320003
        assertEquals("E7", detect(intArrayOf(40, 47, 50, 56, 59, 64)))     // 020100
        assertEquals("Dm", detect(intArrayOf(50, 57, 62, 65)))             // xx0231
        assertEquals("F", detect(intArrayOf(41, 48, 53, 57, 60, 65)))      // 133211
        assertEquals("Em", detect(intArrayOf(40, 47, 52, 55, 59, 64)))     // 022000
        assertEquals("C", detect(intArrayOf(48, 52, 55, 60, 64), cents = 15f)) // slightly sharp guitar
    }

    @Test
    fun singleNoteIsNotAChord() {
        assertNull(detect(intArrayOf(45)))
    }
}
