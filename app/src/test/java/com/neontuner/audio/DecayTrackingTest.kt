package com.neontuner.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A plucked string that rings out into room noise, run through the same frame pipeline as the app
 * (4096-sample window, 40 frames/s, level from the last 2048 samples, guitar defaults, auto gate).
 * Measures how long the note is followed after the pluck, before and after the tracker changes.
 */
class DecayTrackingTest {
    private val sr = 48000
    private val hop = sr / 40

    /** 2 s of room noise, then a string decaying ~45 dB over 4 s, with beating partials. */
    private fun signal(f0: Double, seed: Int): FloatArray {
        val rnd = Random(seed)
        val pre = 2.0
        val dur = 6.0
        val n = ((pre + dur) * sr).toInt()
        var pink = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            pink = 0.97 * pink + 0.03 * (rnd.nextDouble() - 0.5) * 2
            var s = 0.0005 * (rnd.nextDouble() - 0.5) + 0.004 * pink // ~ -65 dBFS room
            // short handling / room bursts (~40 ms) every 0.7 s: these used to break the lock
            if (t >= pre && ((t - pre) % 0.7) < 0.04) s += 0.02 * (rnd.nextDouble() - 0.5)
            if (t >= pre) {
                val tt = t - pre
                val env = 0.3 * exp(-tt * 1.1) // ~9.5 dB/s, like a real acoustic string
                for (h in 1..8) {
                    val fh = f0 * h * sqrt(1 + 0.0001 * h * h) // slight inharmonicity
                    val hd = exp(-tt * 0.5 * h) // upper partials die faster
                    // two slightly detuned copies -> beating, like a real string
                    s += env * hd * 0.8.pow(h - 1) *
                        (sin(2 * PI * fh * tt) + 0.6 * sin(2 * PI * (fh + 0.7) * tt + h)) / h
                }
            }
            s.toFloat()
        }
    }

    private fun run(x: FloatArray, f0: Double, legacy: Boolean): Double {
        val s = TunerSettings() // guitar defaults, auto gate on
        val det = PitchDetector(sr, s.instrument.windowSize)
        val win = FloatArray(s.instrument.windowSize)
        val tracker = NoteTracker()
        val legacyTracker = LegacyTracker()
        val expected = 69f + 12f * (ln(f0 / 440.0) / ln(2.0)).toFloat()
        var lastGood = -1.0
        var started = false
        var pos = 0
        while (pos + hop <= x.size) {
            System.arraycopy(win, hop, win, 0, win.size - hop)
            System.arraycopy(x, pos, win, win.size - hop, hop)
            pos += hop
            var sum = 0.0
            for (i in win.size - 2048 until win.size) sum += win[i] * win[i]
            val db = (20 * log10(sqrt(sum / 2048) + 1e-12)).toFloat()
            val m = if (legacy) legacyTracker.process(db, s) { det.detect(win, s.instrument.fMin, s.instrument.fMax) }
            else tracker.process(db, s) { det.detect(win, s.instrument.fMin, s.instrument.fMax) }
            val t = pos.toDouble() / sr - 2.0
            val ok = m != null && abs(m.roundToInt() - expected.roundToInt()) == 0
            if (ok && t > 0) started = true
            if (started && ok) lastGood = t
            if (started && !ok && t - lastGood > 0.3) break // a real gap: the waterfall breaks here
        }
        return lastGood
    }

    @Test
    fun decayingStringsAreFollowedLonger() {
        val strings = doubleArrayOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)
        var oldSum = 0.0
        var newSum = 0.0
        for ((k, f) in strings.withIndex()) {
            val x = signal(f, k)
            val old = run(x, f, legacy = true)
            val new = run(x, f, legacy = false)
            println(String.format("%.2f Hz: legacy %.2f s  ->  new %.2f s", f, old, new))
            oldSum += old; newSum += new
            assertTrue("string $f tracked shorter than before", new >= old - 0.05)
        }
        println(String.format("average: legacy %.2f s  ->  new %.2f s", oldSum / 6, newSum / 6))
        assertTrue("expected a clear improvement", newSum > oldSum * 1.2)
    }

    /** The previous behaviour, kept here only for comparison. */
    private class LegacyTracker {
        private val floor = NoteTracker.NoiseFloor()
        private val smoother = NoteTracker.Smoother()
        private var gateOpen = false
        private var locked = false

        fun process(db: Float, s: TunerSettings, detect: () -> PitchDetector.Result?): Float? {
            if (!locked) floor.push(db)
            val gate = (floor.value + s.autoMarginDb).coerceIn(-85f, -30f)
            gateOpen = when {
                db >= gate -> true
                gateOpen && locked -> db >= minOf(gate - 4f, floor.value + 4f)
                gateOpen -> db >= gate - 4f
                else -> false
            }
            var out: Float? = null
            if (gateOpen) {
                val r = detect()
                if (r != null && r.clarity >= s.minClarity) {
                    out = smoother.push(69f + 12f * (ln(r.frequency / s.a4) / ln(2f)), s.alpha, s.stableFrames)
                } else smoother.miss()
            } else smoother.miss()
            locked = out != null
            return out
        }
    }
}

