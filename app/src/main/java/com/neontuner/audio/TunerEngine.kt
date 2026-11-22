package com.neontuner.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One analysis result, delivered on the main thread. */
class Reading(
    val hasPitch: Boolean,
    val frequency: Float,
    /** MIDI note number of the nearest note (69 = A4). */
    val midi: Int,
    /** Deviation from [midi] in cents, -50..+50. */
    val cents: Float,
    val clarity: Float,
    val levelDb: Float,
    val timeMs: Long,
    /** Effective noise gate (auto or manual) and the measured room noise floor, dBFS. */
    val gateDb: Float = -60f,
    val floorDb: Float = -90f,
    /** Chord currently shown (held for a moment after it stops ringing), or null. */
    val chord: Chord? = null,
)

/**
 * Process-wide microphone + pitch analysis loop. Several screens (main display, rear display,
 * notification) subscribe to the same stream so the microphone is opened only once.
 */
object TunerEngine {
    private const val TAG = "TunerEngine"
    private const val CHUNK = 512
    private const val UPDATES_PER_SECOND = 40

    fun interface Listener {
        fun onReading(r: Reading)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var settings = TunerSettings()

    @Volatile
    var lastReading: Reading? = null
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    @Synchronized
    fun start(context: Context): Boolean {
        if (running) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val appContext = context.applicationContext
        running = true
        thread = Thread({ loop(appContext) }, "tuner-audio").also { it.start() }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop(context: Context) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val am = context.getSystemService(AudioManager::class.java)
        // UNPROCESSED skips noise suppression / AGC, which otherwise smear the pitch.
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION

        var record: AudioRecord? = null
        var sampleRate = 0
        for (sr in intArrayOf(48000, 44100)) {
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (minBuf <= 0) continue
            val r = try {
                AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(minBuf, CHUNK * 4 * 8))
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord($sr) failed", e); null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                record = r; sampleRate = sr; break
            }
            r?.release()
        }
        if (record == null) {
            Log.e(TAG, "No usable microphone configuration")
            running = false
            return
        }

        val hop = sampleRate / UPDATES_PER_SECOND
        val chunk = FloatArray(CHUNK)
        var detector: PitchDetector? = null
        var window = FloatArray(0)
        var sinceLast = 0
        val hpf = HighPass()
        var hpfFor: Instrument? = null
        val chordDetector = ChordDetector(sampleRate)
        val chordBuf = FloatArray(chordDetector.frameSize)
        var chordTick = 0
        var chord: Chord? = null
        val tracker = NoteTracker()

        try {
            record.startRecording()
            while (running) {
                val s = settings
                if (detector == null || detector.windowSize != s.instrument.windowSize) {
                    detector = PitchDetector(sampleRate, s.instrument.windowSize)
                    window = FloatArray(s.instrument.windowSize)
                    tracker.reset()
                }
                if (hpfFor != s.instrument) {
                    // Cut well below the lowest note of the instrument (2nd-order Butterworth).
                    hpf.design(maxOf(20f, s.instrument.fMin * 0.6f), sampleRate)
                    hpfFor = s.instrument
                }
                var n = record.read(chunk, 0, CHUNK, AudioRecord.READ_BLOCKING)
                if (demo && n > 0) n = synthesize(chunk, n, sampleRate)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "read error $n")
                    continue
                }
                if (s.lowCut) hpf.process(chunk, n)
                // Slide the analysis window left and append the new samples.
                System.arraycopy(window, n, window, 0, window.size - n)
                System.arraycopy(chunk, 0, window, window.size - n, n)
                System.arraycopy(chordBuf, n, chordBuf, 0, chordBuf.size - n)
                System.arraycopy(chunk, 0, chordBuf, chordBuf.size - n, n)
                sinceLast += n
                if (sinceLast < hop) continue
                sinceLast = 0

                // Level is measured on the most recent ~43 ms only, so decays are tracked quickly.
                var sum = 0.0
                val recent = minOf(2048, window.size)
                for (i in window.size - recent until window.size) sum += window[i] * window[i]
                val rms = sqrt(sum / recent)
                val db = if (rms > 1e-9) (20 * log10(rms)).toFloat() else -120f

                val now = System.currentTimeMillis()
                var reading: Reading? = null
                val det = detector!!
                val win = window
                val midiF = tracker.process(db, s) { det.detect(win, s.instrument.fMin, s.instrument.fMax) }
                if (midiF != null) {
                    val note = midiF.roundToInt()
                    val cents = (midiF - note) * 100f
                    val freq = s.a4 * Math.pow(2.0, ((midiF - 69f) / 12f).toDouble()).toFloat()
                    reading = Reading(true, freq, note, cents, 1f, db, now)
                }
                val gate = tracker.gateDb
                val floor = tracker.floor
                val gateOpen = tracker.gateOpen
                // Chords: ~10 analyses per second on a 340 ms window.
                if (++chordTick >= 4) {
                    chordTick = 0
                    chord = chordDetector.process(chordBuf, s.a4, gateOpen)
                }
                publish(
                    if (reading != null) Reading(true, reading.frequency, reading.midi, reading.cents, reading.clarity, db, now, gate, floor.value, chord)
                    else Reading(false, 0f, 0, 0f, 0f, db, now, gate, floor.value, chord)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop crashed", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            running = false
        }
    }

    /**
     * Debug builds only (enabled from MainActivity with `--ez demo true`): replaces the microphone
     * samples with a synthetic plucked string that drifts through notes, so the UI can be checked
     * on an emulator. The signal still goes through the real detector.
     */
    @Volatile
    var demo = false
    private var demoPhase = 0.0
    private var demoT = 0.0

    private val demoC = intArrayOf(48, 52, 55, 60, 64)
    private val demoAm = intArrayOf(45, 52, 57, 60, 64)
    private val demoChordPh = DoubleArray(6)

    private fun synthesize(buf: FloatArray, n: Int, sr: Int): Int {
        for (i in 0 until n) {
            demoT += 1.0 / sr
            val seg = (demoT / 4.0).toInt()            // a new "string" every 4 s
            val local = demoT - seg * 4.0
            if (seg % 6 >= 4) {
                // every 3rd pair of segments: a strummed chord (C, then Am)
                val notes = if (seg % 6 == 4) demoC else demoAm
                var s = 0.0
                for ((k, m) in notes.withIndex()) {
                    val t = local - k * 0.015
                    if (t < 0) continue
                    val f = 440.0 * Math.pow(2.0, (m - 69) / 12.0)
                    demoChordPh[k] += 2 * Math.PI * f / sr
                    val env = Math.exp(-t * 0.9) * if (local > 3.6) 0.0 else 0.12
                    for (h in 1..5) s += env * Math.sin(demoChordPh[k] * h) * Math.pow(0.8, h - 1.0) / h
                }
                buf[i] = s.toFloat()
                continue
            }
            val base = doubleArrayOf(110.0, 146.83, 196.0, 82.41)[seg % 4]
            // starts 35 cents flat/sharp and is "tuned" towards pitch, with a little wobble
            val start = if (seg % 2 == 0) -35.0 else 28.0
            val cents = start * Math.exp(-local * 1.1) + 1.5 * Math.sin(local * 5.0)
            val f = base * Math.pow(2.0, cents / 1200.0)
            demoPhase += 2 * Math.PI * f / sr
            val env = if (local > 3.6) 0.0 else 0.25
            var s = 0.0
            for (h in 1..5) s += Math.sin(demoPhase * h) / h
            buf[i] = (env * s).toFloat()
        }
        return n
    }

    private fun publish(r: Reading) {
        lastReading = r
        main.post { for (l in listeners) l.onReading(r) }
    }

    /** 2nd-order Butterworth high-pass (RBJ biquad), in place. */
    private class HighPass {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        fun design(cutoff: Float, sr: Int) {
            val w = 2.0 * Math.PI * cutoff / sr
            val cosw = Math.cos(w)
            val alpha = Math.sin(w) / (2.0 * 0.7071)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosw) / 2.0 / a0).toFloat()
            b1 = (-(1.0 + cosw) / a0).toFloat()
            b2 = b0
            a1 = (-2.0 * cosw / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }

        fun process(buf: FloatArray, n: Int) {
            for (i in 0 until n) {
                val x = buf[i]
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x; y2 = y1; y1 = y
                buf[i] = y
            }
        }
    }

    fun noteName(midi: Int): String = NoteNames.of(midi)
    fun octave(midi: Int): Int = Math.floorDiv(midi, 12) - 1
}
