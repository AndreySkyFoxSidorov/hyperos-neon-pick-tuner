package com.neontuner.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Per-frame decision logic between the raw pitch detector and the screen: noise gate, clarity
 * check, note lock and smoothing. Pure Kotlin so it can be unit-tested on synthetic signals.
 *
 * Key idea: be strict when a note *starts* (so room noise does not produce notes), and lenient
 * while the *same* note keeps ringing (so a decaying guitar string is followed to the end):
 *  - the gate holds down to just above the measured room noise while a note is locked;
 *  - the lock survives short dropouts (a few frames without a clean detection);
 *  - the clarity requirement is lowered for readings that continue the locked note;
 *  - octave/12th errors typical for decaying strings are folded back onto the locked note;
 *  - frames that belong to a ringing note are never fed into the room-noise estimate.
 */
class NoteTracker {
    val floor = NoiseFloor()
    private val smoother = Smoother()
    var gateOpen = false
        private set
    var gateDb = -60f
        private set

    private var track = Float.NaN
    private var lostFrames = HOLD_FRAMES

    /** True while a note is locked (including short dropouts). */
    val locked get() = !track.isNaN() && lostFrames < HOLD_FRAMES

    fun reset() {
        smoother.reset(); track = Float.NaN; lostFrames = HOLD_FRAMES; gateOpen = false
    }

    /**
     * [db] = input level of the current frame; [detect] runs the pitch detector (only called when
     * the gate is open). Returns the smoothed pitch as a fractional MIDI number, or null.
     */
    fun process(db: Float, s: TunerSettings, detect: () -> PitchDetector.Result?): Float? {
        val wasLocked = locked
        if (!wasLocked) floor.push(db)
        val gate = if (s.autoGate) (floor.value + s.autoMarginDb).coerceIn(-85f, -30f) else s.gateDb
        gateDb = gate
        gateOpen = when {
            db >= gate -> true
            wasLocked -> db >= min(gate - 8f, floor.value + 2f) // follow the decay almost to the floor
            gateOpen -> db >= gate - 4f
            else -> false
        }

        var out: Float? = null
        if (gateOpen) {
            val res = detect()
            if (res != null) {
                var m = 69f + 12f * (ln(res.frequency / s.a4) / ln(2f))
                val accept = when {
                    res.clarity >= s.minClarity -> true
                    wasLocked -> {
                        // Continuation of the ringing note: fold octave / 12th slips, relax clarity.
                        for (k in FOLDS) if (abs(m - k - track) < 0.7f) { m -= k; break }
                        abs(m - track) < 0.7f && res.clarity >= max(0.45f, s.minClarity - 0.3f)
                    }
                    else -> false
                }
                if (accept) out = smoother.push(m, s.alpha, s.stableFrames)
            }
        }
        if (out == null) smoother.miss()

        if (out != null) {
            track = out; lostFrames = 0
        } else if (lostFrames < HOLD_FRAMES) {
            lostFrames++
        }
        return out
    }

    companion object {
        /** ~200 ms at 40 frames/s. */
        const val HOLD_FRAMES = 8
        private val FOLDS = floatArrayOf(12f, -12f, 19f, 24f, -24f)
    }

    /**
     * Room noise floor for the auto gate: 20th percentile of the input level over the last
     * ~6 s of frames without a locked note ("minimum statistics", Martin 2001, simplified).
     */
    class NoiseFloor {
        private val ring = FloatArray(240)
        private val tmp = FloatArray(240)
        private var count = 0
        private var head = 0
        var value = -75f
            private set

        fun push(db: Float) {
            ring[head] = db.coerceIn(-100f, 0f)
            head = (head + 1) % ring.size
            if (count < ring.size) count++
            if (head % 8 == 0 && count >= 16) {
                System.arraycopy(ring, 0, tmp, 0, count)
                java.util.Arrays.sort(tmp, 0, count)
                value = tmp[(count * 0.2f).toInt()]
            }
        }
    }

    /**
     * Running median of 5 against single-frame glitches, then an exponential filter in the
     * semitone domain. A new note (> half a semitone away) must persist for `stable` frames.
     */
    class Smoother {
        private val last = FloatArray(5)
        private val sorted = FloatArray(5)
        private var count = 0
        private var value = Float.NaN
        private var misses = 0
        private var candidate = Float.NaN
        private var candidateFrames = 0

        fun reset() {
            count = 0; value = Float.NaN; misses = 0; candidate = Float.NaN; candidateFrames = 0
        }

        fun miss() {
            if (++misses > HOLD_FRAMES) reset()
        }

        fun push(midi: Float, alpha: Float, stable: Int): Float? {
            misses = 0
            last[count % last.size] = midi
            count++
            val k = min(count, last.size)
            System.arraycopy(last, 0, sorted, 0, k)
            java.util.Arrays.sort(sorted, 0, k)
            val m = sorted[k / 2]

            if (!value.isNaN() && abs(m - value) <= 0.5f) {
                candidate = Float.NaN; candidateFrames = 0
                value += alpha * (m - value)
                return value
            }
            if (!candidate.isNaN() && abs(m - candidate) <= 0.5f) candidateFrames++ else {
                candidate = m; candidateFrames = 1
            }
            if (candidateFrames >= stable) {
                value = m
                candidate = Float.NaN; candidateFrames = 0
                return value
            }
            return if (value.isNaN()) null else value
        }
    }
}
