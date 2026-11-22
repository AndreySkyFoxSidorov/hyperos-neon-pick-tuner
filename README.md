<p align="center">
  <img src="docs/screenshots/icon.png" width="140" alt="Neon Pick Tuner icon">
</p>

<h1 align="center">Neon Pick Tuner</h1>

<p align="center">
  Lightweight native Android chromatic tuner with a neon guitar-pick needle, a scrolling pitch waterfall and live chord recognition.<br>
  Runs on <b>both screens of the Xiaomi 18 Pro Max</b>, including the HyperOS rear display.
</p>

<p align="center">
  <a href="https://github.com/AndreySkyFoxSidorov/hyperos-neon-pick-tuner/releases/latest"><b>Download the APK</b></a>
  ·
  Kotlin · no third-party libraries · ~220 KB APK · MIT license
</p>

---

## Screenshots

| Main screen | Settings |
|:---:|:---:|
| <img src="docs/screenshots/main.png" width="300"> | <img src="docs/screenshots/settings.png" width="300"> |

**Rear screen (976 × 596).** The left ~30 % is left free for the camera island; the chord card sits in the bottom-right corner:

<img src="docs/screenshots/rear.png" width="600">

> The screenshots were taken on the Android emulator in demo mode, where synthetic plucked strings and strummed chords go through the real detectors (that is also why the room level in the settings shot reads high). The rear-screen shot uses a simulated 976×596 display, the same size as the Xiaomi rear panel.

## Features

- **Neon pick needle.** A glowing guitar pick shows how far you are from the note, from −50 to +50 cents. The pick turns green when you are in tune, amber when you are close and magenta when you are far off.
- **Pitch waterfall.** A beam from the pick tip draws the trace, and the trace scrolls down. You can see which way the pitch drifts while you turn the peg, and where each new note started.
- **Accurate detection.** The tuner uses the McLeod Pitch Method on an FFT autocorrelation, with parabolic and multi-period refinement. Unit tests show at most 0.5 cent error from A0 (27.5 Hz) to C8 (4186 Hz).
- **Chord recognition.** Strum a chord and its name and fingering appear in the corner of both screens: major, minor, 7, maj7, m7, sus2, sus4, dim, aug and slash chords (e.g. C/G). Chroma features with harmonic suppression, template matching and hysteresis; a single string never shows a chord.
- **Chord diagrams with hints.** Guitar shapes for every preset (piano included), ukulele shapes for the Ukulele preset. Finger numbers, barre, muted (×) and open (○) strings, tab (`x32010`) and chord tones. Voicings are found automatically by a small fretboard search, so every chord has a playable shape.
- **Instrument presets:** Piano (26–4300 Hz, long window, high sensitivity), Guitar, Ukulele and Bass.
- **Noise control:**
  - **auto noise gate**: follows the measured room noise, no slider fiddling;
  - strict when a note starts, lenient while the same note rings, so fading strings are followed almost down to the room noise;
  - manual gate with a live input-level meter, if you prefer;
  - low-cut filter for hum, rumble and handling noise;
  - note-stability filter.
- **Settings:** sensitivity, smoothing, *needle lerp* (pick inertia, almost off by default), A4 reference (415–466 Hz), chord diagrams on/off, rear screen on/off, **Reset preset** and **Reset to default**. Close the panel with ✕, the back gesture or a tap outside it.
- **Two screens at once.** On the Xiaomi 18 Pro Max a compact tuner runs on the rear display, and both screens stay awake while the app is open.
- **Small.** The release APK is about 220 KB. It uses plain Android Views and the Kotlin stdlib, with no other libraries.

## Install

1. Download `NeonPickTuner-v1.1.0.apk` from [Releases](https://github.com/AndreySkyFoxSidorov/hyperos-neon-pick-tuner/releases/latest).
2. Open it on the phone and allow installing from this source.
3. Grant the microphone permission. That is the only permission the app asks for. The status notification with the current note can be enabled in the app settings if you want it.

> Upgrading from 1.0.0 works in place. On Xiaomi, if a USB install is rejected with `INSTALL_FAILED_USER_RESTRICTED`, enable Neon Tuner on the "Install via USB" screen that HyperOS opens.

Requires Android 12 (API 31) or newer.

## HyperOS rear screen: how it works

Xiaomi has no public API for drawing custom content on the rear display. We tested on a **Xiaomi 18 Pro Max** with HyperOS `OS4.0.7.0.XEOEUXM` (EEA, Android 17). On that device the gate is a single manifest flag:

- The rear panel is `displayId 1`: 976×596, internal, `FLAG_PRESENTATION | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP`. It is **not** listed in `DISPLAY_CATEGORY_PRESENTATION`.
- `ActivityStarterImpl.isShouldShowOnRearDisplay` in `/system_ext/framework/miui-services.jar` checks the meta-data key **`miui.rear.policy`**:
  - on the activity: `0` blocks it, `1` allows it;
  - if the activity has no key, the value on the application is used.

  Without the flag the launch is aborted with result code **102** (`should not allow app = … show on rear display`).
- The stock Xiaomi Camera declares `miui.rear.policy = 1`. Neon Pick Tuner does the same for its compact `RearActivity`:

  ```xml
  <activity android:name=".rear.RearActivity" android:exported="true" android:resizeableActivity="true">
      <meta-data android:name="miui.rear.policy" android:value="1" />
  </activity>
  ```

  and starts it with `ActivityOptions.makeBasic().setLaunchDisplayId(rearDisplayId)`. No root, no Shizuku.
- **Windows** from non-system apps are refused on the rear display (`Not allow non-system app add window type = 2037 on rear display`). This covers `Presentation` and `TYPE_APPLICATION_OVERLAY`. On HyperOS only the Activity route works. The other two routes are kept as fallbacks for other dual-screen devices and for the emulator.
- When the rear panel dozes, `com.xiaomi.subscreencenter` puts its clock back on top. While the main app is open, it brings the tuner back every few seconds.

**Display diagnostics** (⚙ → Display diagnostics) lists every display with its flags. It can also show a test "A4" via `Presentation` or launch the tuner on a chosen display. Use it to check other devices or firmware versions.

From a PC you can also start the rear tuner with:

```
adb shell am start --display 1 -n com.neontuner/.rear.RearActivity
```

The original research notes, in Russian, with the Xiaomi docs and community sources (Chinese quotes translated): [docs/rear-screen-research.ru.md](docs/rear-screen-research.ru.md).

## Build

Requirements: JDK 17+ (the Android Studio JBR works) and Android SDK 36.

```
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # pitch, chord, voicing and decay-tracking tests
```

Release signing reads `keystore.properties` from the project root. The file is git-ignored and holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, the release build is signed with the debug key.

Demo mode for UI work (debug builds only):

```
adb shell am start -n com.neontuner/.MainActivity --ez demo true
```

To simulate the rear screen on an emulator:

```
adb shell settings put global overlay_display_devices 976x596/240
```

To regenerate the launcher icon: `java tools/IconGen.java`.

## Project layout

```
app/src/main/java/com/neontuner/
  audio/PitchDetector.kt    MPM pitch detection + FFT
  audio/TunerEngine.kt      AudioRecord loop, low-cut, chord analysis
  audio/NoteTracker.kt      auto noise gate, note lock on decaying strings, smoothing
  audio/ChordDetector.kt    chroma + chord templates + hysteresis
  audio/ChordShapes.kt      guitar / ukulele voicing search
  audio/TunerSettings.kt    presets and persisted settings
  ui/TunerView.kt           note, neon pick, scale and waterfall (custom View)
  ui/ChordCard.kt           neon chord diagram card
  rear/RearActivity.kt      compact tuner for the rear display (miui.rear.policy=1)
  rear/RearDisplays.kt      rear display lookup + camera-island layout
  rear/DisplayProbeActivity.kt  display diagnostics / Presentation test
  TunerService.kt           microphone foreground service
  MainActivity.kt           main screen + settings
```

## License

[MIT](LICENSE) © 2026 AndreySkyFoxSidorov
