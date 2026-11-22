package com.neontuner.audio

import android.content.Context

/** Instrument presets: frequency range, analysis window and sensible defaults. */
enum class Instrument(
    val label: String,
    val fMin: Float,
    val fMax: Float,
    val windowSize: Int,
    val defaultSensitivity: Float,
    val defaultSmoothing: Float,
    val defaultGateDb: Float,
) {
    // Piano: whole keyboard, long window for low strings, very sensitive (quiet decays, inharmonicity).
    PIANO("Piano", 26f, 4300f, 8192, 0.85f, 0.45f, -68f),
    GUITAR("Guitar", 60f, 1400f, 4096, 0.65f, 0.55f, -55f),
    UKULELE("Ukulele", 180f, 1400f, 4096, 0.45f, 0.60f, -50f),
    BASS("Bass", 28f, 500f, 8192, 0.65f, 0.55f, -58f),
}

data class TunerSettings(
    val instrument: Instrument = Instrument.GUITAR,
    /** 0..1. Higher = accepts less "clean" tones (piano inharmonicity, weak signals). */
    val sensitivity: Float = Instrument.GUITAR.defaultSensitivity,
    /** 0..1. Higher = steadier pitch estimate and fewer note jumps. */
    val smoothing: Float = Instrument.GUITAR.defaultSmoothing,
    /** 0..1. Inertia of the pick/needle on screen. Near 0 = follows the pitch almost instantly. */
    val needleLerp: Float = 0.08f,
    /** Auto noise gate: threshold follows the measured room noise + [autoMarginDb]. */
    val autoGate: Boolean = true,
    val autoMarginDb: Float = 8f,
    /** Noise gate in dBFS: anything quieter is ignored. -90 (everything) .. -20 (only loud). */
    val gateDb: Float = Instrument.GUITAR.defaultGateDb,
    /** High-pass filter below the instrument's range (rumble, hum, handling noise). */
    val lowCut: Boolean = true,
    val a4: Float = 440f,
    /** Leave room for the camera island on the Xiaomi rear display. */
    val rearCameraInset: Boolean = true,
    /** Show the chord diagram card when a chord is detected. */
    val showChords: Boolean = true,
    /** Show the compact tuner on the rear display when one is available. */
    val rearEnabled: Boolean = true,
) {
    /** Minimum MPM clarity: 0.96 at minimum sensitivity, 0.74 at maximum. */
    val minClarity: Float get() = 0.96f - 0.22f * sensitivity

    /** Exponential smoothing factor per analysis frame (40 fps): 0.7 .. 0.05. */
    val alpha: Float get() = 0.7f - 0.65f * smoothing

    /** Needle lerp speed in 1/s: 40 (instant) .. 1.5 (very lazy); quadratic so low values stay subtle. */
    val needleSpeed: Float get() = 1.5f + 38.5f * (1f - needleLerp) * (1f - needleLerp)

    /** Frames a new note must persist before the display switches to it (40 fps). */
    val stableFrames: Int get() = 2 + (smoothing * 6).toInt()

    /** Cents within which a note counts as "in tune". Sensitive modes are stricter. */
    val inTuneCents: Float get() = if (sensitivity > 0.8f) 1.5f else 3f

    fun withInstrument(i: Instrument) = copy(
        instrument = i,
        sensitivity = i.defaultSensitivity,
        smoothing = i.defaultSmoothing,
        gateDb = i.defaultGateDb,
        needleLerp = 0.08f,
    )

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("instrument", instrument.name)
            .putFloat("sensitivity", sensitivity)
            .putFloat("smoothing", smoothing)
            .putFloat("needleLerp", needleLerp)
            .putFloat("gateDb", gateDb)
            .putBoolean("autoGate", autoGate)
            .putFloat("autoMarginDb", autoMarginDb)
            .putBoolean("lowCut", lowCut)
            .putFloat("a4", a4)
            .putBoolean("rearInset", rearCameraInset)
            .putBoolean("rearEnabled", rearEnabled)
            .putBoolean("showChords", showChords)
            .apply()
    }

    companion object {
        private const val PREFS = "tuner2"

        fun load(context: Context): TunerSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val inst = runCatching { Instrument.valueOf(p.getString("instrument", null)!!) }
                .getOrDefault(Instrument.GUITAR)
            return TunerSettings(
                instrument = inst,
                sensitivity = p.getFloat("sensitivity", inst.defaultSensitivity),
                smoothing = p.getFloat("smoothing", inst.defaultSmoothing),
                needleLerp = p.getFloat("needleLerp", 0.08f),
                gateDb = p.getFloat("gateDb", inst.defaultGateDb),
                autoGate = p.getBoolean("autoGate", true),
                autoMarginDb = p.getFloat("autoMarginDb", 8f),
                lowCut = p.getBoolean("lowCut", true),
                a4 = p.getFloat("a4", 440f),
                rearCameraInset = p.getBoolean("rearInset", true),
                rearEnabled = p.getBoolean("rearEnabled", true),
                showChords = p.getBoolean("showChords", true),
            )
        }
    }
}
