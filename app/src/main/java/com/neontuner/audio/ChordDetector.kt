package com.neontuner.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ChordQuality(
    val suffix: String,
    val label: String,
    val intervals: IntArray,
    /** Small bias so that, when evidence is equal, simpler chords win. */
    val penalty: Float,
) {
    MAJ("", "major", intArrayOf(0, 4, 7), 0f),
    MIN("m", "minor", intArrayOf(0, 3, 7), 0f),
    DOM7("7", "dominant 7th", intArrayOf(0, 4, 7, 10), 0.03f),
    MAJ7("maj7", "major 7th", intArrayOf(0, 4, 7, 11), 0.04f),
    MIN7("m7", "minor 7th", intArrayOf(0, 3, 7, 10), 0.03f),
    SUS2("sus2", "suspended 2nd", intArrayOf(0, 2, 7), 0.06f),
    SUS4("sus4", "suspended 4th", intArrayOf(0, 5, 7), 0.06f),
    DIM("dim", "diminished", intArrayOf(0, 3, 6), 0.07f),
    AUG("aug", "augmented", intArrayOf(0, 4, 8), 0.08f),
    POWER("5", "power chord", intArrayOf(0, 7), 0.10f),
}

/** A chord: root and bass are pitch classes (0 = C). */
data class Chord(val root: Int, val quality: ChordQuality, val bass: Int = root) {
    val tones: IntArray get() = IntArray(quality.intervals.size) { (root + quality.intervals[it]) % 12 }

    val name: String
        get() {
            val base = NoteNames.of(root) + quality.suffix
            return if (bass != root && tones.contains(bass)) base + "/" + NoteNames.of(bass) else base
        }

    /** Root-position version (diagrams fall back to it when no slash voicing exists). */
    val rootPosition get() = if (bass == root) this else copy(bass = root)
}

/**
 * Chord recognition, classic MIR pipeline (Fujishima 1999; Mauch & Dixon 2010; Müller, FMP ch. 5):
 *  1. Hann-windowed FFT of a ~340 ms frame, spectral peaks only.
 *  2. Peaks folded onto a semitone axis (MIDI notes) with sub-bin frequency interpolation.
 *  3. Cheap harmonic suppression: each detected note removes the expected 2nd..6th partials,
 *     so the 3rd harmonic of C (a G) is not mistaken for a chord tone.
 *  4. 12-bin chroma of the remaining notes, smoothed over time.
 *  5. Template matching against 12 roots x [ChordQuality]; bass note decides inversions/sus ambiguity.
 *  6. Hysteresis: a new chord must win twice in a row (~200 ms) before it is shown.
 */
class ChordDetector(private val sampleRate: Int) {
    val frameSize = 16384
    private val fft = Fft(frameSize)
    private val re = FloatArray(frameSize)
    private val im = FloatArray(frameSize)
    private val mag = FloatArray(frameSize / 2)
    private val hann = FloatArray(frameSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (frameSize - 1))).toFloat() }

    private val loNote = 28
    private val hiNote = 100
    private val notes = FloatArray(hiNote + 8)
    private val chroma = FloatArray(12)
    private val smooth = FloatArray(12)

    private var shown: Chord? = null
    private var candidate: Chord? = null
    private var candidateHits = 0

    /** Debug/UI: notes (pitch classes) that were found in the last frame. */
    val activePitchClasses = BooleanArray(12)

    fun reset() {
        smooth.fill(0f); shown = null; candidate = null; candidateHits = 0
        activePitchClasses.fill(false)
    }

    /**
     * [frame] must hold [frameSize] samples (oldest first). Returns the chord to display now, or null.
     * When [active] is false (silence / gate closed) the state decays towards "no chord".
     */
    fun process(frame: FloatArray, a4: Float, active: Boolean): Chord? {
        if (!active) {
            for (i in 0 until 12) smooth[i] *= 0.5f
            candidate = null; candidateHits = 0
            activePitchClasses.fill(false)
            if (smooth.sum() < 0.05f) shown = null
            return shown
        }
        val best = analyse(frame, a4)
        if (best == null) {
            candidate = null; candidateHits = 0
            return shown
        }
        if (best == shown) {
            candidate = null; candidateHits = 0
        } else if (best == candidate) {
            if (++candidateHits >= 2) { shown = best; candidate = null; candidateHits = 0 }
        } else {
            candidate = best; candidateHits = 1
        }
        return shown
    }

    /** One frame -> best chord (no hysteresis). Visible for tests. */
    fun analyse(frame: FloatArray, a4: Float): Chord? {
        for (i in 0 until frameSize) { re[i] = frame[i] * hann[i]; im[i] = 0f }
        fft.transform(re, im, inverse = false)
        for (k in 0 until frameSize / 2) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])

        // 1-2: spectral peaks -> semitone bins
        notes.fill(0f)
        val binHz = sampleRate.toFloat() / frameSize
        val kLo = (50f / binHz).toInt().coerceAtLeast(2)
        val kHi = (2400f / binHz).toInt().coerceAtMost(frameSize / 2 - 2)
        var peakMax = 0f
        for (k in kLo..kHi) if (mag[k] > peakMax) peakMax = mag[k]
        if (peakMax <= 1e-6f) return null
        val floor = peakMax * 0.02f
        for (k in kLo..kHi) {
            val c = mag[k]
            if (c < floor || c < mag[k - 1] || c < mag[k + 1]) continue
            val l = mag[k - 1]; val r = mag[k + 1]
            val d = l - 2f * c + r
            val shift = if (d < 0f) (0.5f * (l - r) / d).coerceIn(-0.5f, 0.5f) else 0f
            val f = (k + shift) * binHz
            val midi = 69f + 12f * (ln(f / a4) / ln(2f))
            val n = midi.roundToInt()
            if (n < loNote || n > hiNote) continue
            val off = midi - n // -0.5..0.5; tolerate +-35 cents (detuned strings)
            val w = cos(PI * off).toFloat().coerceAtLeast(0f)
            notes[n] = max(notes[n], c * w * w)
        }

        // 3: harmonic suppression, low to high
        val offs = intArrayOf(12, 19, 24, 28, 31)
        val ws = floatArrayOf(0.7f, 0.5f, 0.4f, 0.3f, 0.25f)
        for (n in loNote..hiNote) {
            val e = notes[n]
            if (e <= 0f || e < notes[n - 1] || e < notes[n + 1]) continue
            for (h in offs.indices) {
                val t = n + offs[h]
                if (t <= hiNote) notes[t] = max(0f, notes[t] - ws[h] * e)
            }
        }

        // 4: chroma of the notes that are clearly present
        var nMax = 0f
        for (n in loNote..hiNote) if (notes[n] > nMax) nMax = notes[n]
        if (nMax <= 0f) return null
        chroma.fill(0f)
        var bass = -1
        for (n in loNote..hiNote) {
            val e = notes[n]
            if (e < nMax * 0.18f) continue
            if (bass < 0 && e >= nMax * 0.3f) bass = n % 12
            chroma[n % 12] += sqrt(e / nMax) // compress: quiet chord tones still count
        }
        var sum = chroma.sum()
        if (sum <= 0f) return null
        for (i in 0 until 12) {
            chroma[i] /= sum
            smooth[i] = 0.5f * smooth[i] + 0.5f * chroma[i]
        }
        sum = smooth.sum()
        var present = 0
        for (i in 0 until 12) {
            val v = smooth[i] / sum
            activePitchClasses[i] = v > 0.06f
            if (v > 0.06f) present++
        }
        // A single string has strong partials (octave, 12th) that look like a 2-note "power chord";
        // require three distinct pitch classes so tuning one string never shows a chord card.
        if (present < 3) return null

        // 5: template matching
        var best: Chord? = null
        var bestScore = -1f
        for (root in 0 until 12) for (q in ChordQuality.entries) {
            if (q == ChordQuality.POWER) continue
            val iv = q.intervals
            var inside = 0f
            var missing = 0f
            val tau = 0.5f / iv.size
            for (x in iv) {
                val v = smooth[(root + x) % 12] / sum
                inside += v
                if (v < tau) missing += tau - v
            }
            var score = inside - (1f - inside) - 1.5f * missing - q.penalty
            if (bass == root) score += 0.08f
            if (score > bestScore) {
                bestScore = score
                best = Chord(root, q, if (bass >= 0) bass else root)
            }
        }
        if (bestScore < 0.45f || best == null) return null
        // Keep the slash only when the bass is a chord tone (otherwise it's noise).
        return if (best.tones.contains(best.bass)) best else best.rootPosition
    }
}

/** Note names without any Android dependency (usable from unit tests). */
object NoteNames {
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun of(pitchClass: Int): String = NAMES[((pitchClass % 12) + 12) % 12]
}
