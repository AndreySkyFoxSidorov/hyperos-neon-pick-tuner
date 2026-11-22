package com.neontuner.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

class PitchDetectorTest {
    private val sr = 48000

    /** Plucked-string-like tone: fundamental + decaying harmonics + a bit of noise. */
    private fun tone(f: Double, n: Int, harmonics: Int = 6, noise: Double = 0.01, weakFundamental: Boolean = false): FloatArray {
        val rnd = Random(1)
        return FloatArray(n) { i ->
            var s = 0.0
            for (h in 1..harmonics) {
                val amp = if (h == 1 && weakFundamental) 0.15 else 1.0 / h
                s += amp * sin(2 * PI * f * h * i / sr)
            }
            (0.2 * s + noise * (rnd.nextDouble() - 0.5)).toFloat()
        }
    }

    private fun cents(a: Float, b: Double) = 1200 * ln(a / b) / ln(2.0)

    @Test
    fun guitarStrings() {
        val d = PitchDetector(sr, 4096)
        for (f in doubleArrayOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)) {
            val r = d.detect(tone(f, 4096), 60f, 1400f)
            assertNotNull("no pitch for $f", r)
            assertEquals("cents error at $f", 0.0, cents(r!!.frequency, f), 0.5)
        }
    }

    @Test
    fun pianoRangeAndSmallDetune() {
        val d = PitchDetector(sr, 8192)
        for (f in doubleArrayOf(27.5, 55.0, 261.63 * Math.pow(2.0, 3.0 / 1200), 1760.0, 4186.0)) {
            val r = d.detect(tone(f, 8192, harmonics = 4), 26f, 4300f)
            assertNotNull("no pitch for $f", r)
            assertEquals("cents error at $f", 0.0, cents(r!!.frequency, f), 0.5)
        }
    }

    @Test
    fun weakFundamentalNoOctaveError() {
        val d = PitchDetector(sr, 4096)
        val f = 110.0
        val r = d.detect(tone(f, 4096, weakFundamental = true), 60f, 1400f)
        assertNotNull(r)
        assertEquals(0.0, cents(r!!.frequency, f), 1.0)
    }

    @Test
    fun noiseIsRejectedOrUnclear() {
        val rnd = Random(7)
        val x = FloatArray(4096) { (rnd.nextDouble() - 0.5).toFloat() }
        val r = PitchDetector(sr, 4096).detect(x, 60f, 1400f)
        if (r != null) assert(r.clarity < 0.72f) { "noise clarity ${r.clarity}" }
    }

    @Test
    fun silenceGivesNothing() {
        assertNull(PitchDetector(sr, 4096).detect(FloatArray(4096), 60f, 1400f))
    }
}
