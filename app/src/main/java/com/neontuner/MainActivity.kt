package com.neontuner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.neontuner.audio.Instrument
import com.neontuner.audio.Reading
import com.neontuner.audio.TunerEngine
import com.neontuner.audio.TunerSettings
import com.neontuner.rear.DisplayProbeActivity
import com.neontuner.rear.RearActivity
import com.neontuner.rear.RearDisplays
import com.neontuner.rear.RearPresentation
import com.neontuner.ui.TunerView
import kotlin.math.roundToInt

class MainActivity : Activity(), DisplayManager.DisplayListener {

    private lateinit var settings: TunerSettings
    private lateinit var tuner: TunerView
    private lateinit var panelHost: View
    private lateinit var chips: LinearLayout
    private lateinit var gate: SeekBar
    private lateinit var level: ProgressBar
    private lateinit var sens: SeekBar
    private lateinit var smooth: SeekBar
    private lateinit var lerp: SeekBar
    private lateinit var gateLabel: TextView
    private lateinit var gateHint: TextView
    private lateinit var sensLabel: TextView
    private lateinit var smoothLabel: TextView
    private lateinit var lerpLabel: TextView
    private lateinit var a4Value: TextView
    private lateinit var a4Badge: TextView
    private lateinit var rearBadge: TextView
    private lateinit var autoGateSwitch: Switch
    private lateinit var lowCutSwitch: Switch
    private lateinit var chordsSwitch: Switch
    private lateinit var rearSwitch: Switch
    private lateinit var insetSwitch: Switch
    private lateinit var rearStatus: TextView

    private var presentation: RearPresentation? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastLevelUpdate = 0L

    /** Live input level + effective gate for the settings panel (only while it is open). */
    private val levelListener = TunerEngine.Listener { r: Reading ->
        if (panelHost.visibility != View.VISIBLE || r.timeMs - lastLevelUpdate < 100) return@Listener
        lastLevelUpdate = r.timeMs
        level.progress = (r.levelDb + 90f).roundToInt().coerceIn(0, 70)
        level.progressTintList = ColorStateList.valueOf(getColor(if (r.levelDb >= r.gateDb) R.color.green else R.color.dim))
        if (settings.autoGate) {
            gateLabel.text = "Noise gate  ·  auto ${r.gateDb.roundToInt()} dB   (room ${r.floorDb.roundToInt()} dB)"
            gate.progress = (r.gateDb + 90f).roundToInt().coerceIn(0, 70)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        settings = TunerSettings.load(this)
        TunerEngine.settings = settings
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        TunerEngine.demo = debuggable && intent.getBooleanExtra("demo", false)

        tuner = findViewById(R.id.tuner)
        panelHost = findViewById(R.id.panelHost)
        chips = findViewById(R.id.chips)
        gate = findViewById(R.id.gate)
        level = findViewById(R.id.level)
        sens = findViewById(R.id.sens)
        smooth = findViewById(R.id.smooth)
        lerp = findViewById(R.id.lerp)
        gateLabel = findViewById(R.id.gateLabel)
        gateHint = findViewById(R.id.gateHint)
        sensLabel = findViewById(R.id.sensLabel)
        smoothLabel = findViewById(R.id.smoothLabel)
        lerpLabel = findViewById(R.id.lerpLabel)
        a4Value = findViewById(R.id.a4Value)
        a4Badge = findViewById(R.id.a4Badge)
        rearBadge = findViewById(R.id.rearBadge)
        autoGateSwitch = findViewById(R.id.autoGateSwitch)
        lowCutSwitch = findViewById(R.id.lowCutSwitch)
        chordsSwitch = findViewById(R.id.chordsSwitch)
        rearSwitch = findViewById(R.id.rearSwitch)
        insetSwitch = findViewById(R.id.insetSwitch)
        rearStatus = findViewById(R.id.rearStatus)

        applyInsets()
        buildChips()

        findViewById<View>(R.id.gear).setOnClickListener { togglePanel() }
        a4Badge.setOnClickListener { togglePanel() }
        findViewById<View>(R.id.closeBtn).setOnClickListener { showPanel(false) }
        panelHost.setOnClickListener { showPanel(false) } // tap outside the card
        rearBadge.setOnClickListener { update(settings.copy(rearEnabled = !settings.rearEnabled)) }
        findViewById<View>(R.id.a4Minus).setOnClickListener { update(settings.copy(a4 = (settings.a4 - 1f).coerceAtLeast(415f))) }
        findViewById<View>(R.id.a4Plus).setOnClickListener { update(settings.copy(a4 = (settings.a4 + 1f).coerceAtMost(466f))) }
        findViewById<View>(R.id.resetBtn).setOnClickListener { update(settings.withInstrument(settings.instrument)) }
        // Everything back to factory defaults (Guitar preset, auto gate, chords and rear screen on).
        findViewById<View>(R.id.resetAllBtn).setOnClickListener {
            update(TunerSettings())
            android.widget.Toast.makeText(this, "All settings reset to default", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.probeBtn).setOnClickListener { startActivity(Intent(this, DisplayProbeActivity::class.java)) }

        gate.setOnSeekBarChangeListener(seek { p -> update(settings.copy(gateDb = -90f + p, autoGate = false)) })
        sens.setOnSeekBarChangeListener(seek { p -> update(settings.copy(sensitivity = p / 100f)) })
        smooth.setOnSeekBarChangeListener(seek { p -> update(settings.copy(smoothing = p / 100f)) })
        lerp.setOnSeekBarChangeListener(seek { p -> update(settings.copy(needleLerp = p / 100f)) })
        autoGateSwitch.setOnCheckedChangeListener { _, c -> if (c != settings.autoGate) update(settings.copy(autoGate = c)) }
        lowCutSwitch.setOnCheckedChangeListener { _, c -> if (c != settings.lowCut) update(settings.copy(lowCut = c)) }
        chordsSwitch.setOnCheckedChangeListener { _, c -> if (c != settings.showChords) update(settings.copy(showChords = c)) }
        rearSwitch.setOnCheckedChangeListener { _, c -> if (c != settings.rearEnabled) update(settings.copy(rearEnabled = c)) }
        insetSwitch.setOnCheckedChangeListener { _, c -> if (c != settings.rearCameraInset) update(settings.copy(rearCameraInset = c)) }

        syncUi()
        requestNeededPermissions()
    }

    /** Back gesture closes the settings sheet first (Android 13+ predictive back API). */
    private val closePanelOnBack: Any? =
        if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { showPanel(false) } else null

    // ---------------- lifecycle ----------------

    override fun onStart() {
        super.onStart()
        TunerEngine.addListener(levelListener)
        TunerService.uiStarted(this, "main")
    }

    override fun onResume() {
        super.onResume()
        getSystemService(DisplayManager::class.java).registerDisplayListener(this, handler)
        handler.postDelayed({ updateRear() }, 300)
        handler.postDelayed(rearWatchdog, 3000)
    }

    /** HyperOS puts its clock back on the rear screen when it dozes; bring the tuner back. */
    private val rearWatchdog = object : Runnable {
        override fun run() {
            if (settings.rearEnabled && RearActivity.runningOn() == null) updateRear()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(rearWatchdog)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        super.onPause()
    }

    override fun onStop() {
        dismissPresentation()
        TunerEngine.removeListener(levelListener)
        // The microphone keeps running while the rear-screen tuner is still visible.
        TunerService.uiStopped(this, "main")
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMic()) TunerService.start(this)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val need = buildList {
            // Only the microphone is asked for. The microphone service still runs without the
            // notification permission (its status notification is just not shown in the shade).
            if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    // ---------------- settings ----------------

    private fun update(s: TunerSettings) {
        val rearChanged = s.rearEnabled != settings.rearEnabled || s.rearCameraInset != settings.rearCameraInset
        settings = s
        TunerEngine.settings = s
        s.save(this)
        syncUi()
        if (rearChanged) {
            dismissPresentation()
            RearActivity.finishRunning()
            TunerService.refresh(this)
            lastRearLaunch = 0L
            handler.postDelayed({ updateRear() }, 300)
        }
    }

    private fun syncUi() {
        for (i in 0 until chips.childCount) {
            chips.getChildAt(i).isSelected = chips.getChildAt(i).tag == settings.instrument
        }
        val gp = (settings.gateDb + 90f).roundToInt().coerceIn(0, 70)
        val sp = (settings.sensitivity * 100).roundToInt()
        val mp = (settings.smoothing * 100).roundToInt()
        val lp = (settings.needleLerp * 100).roundToInt()
        if (!settings.autoGate && gate.progress != gp) gate.progress = gp
        if (sens.progress != sp) sens.progress = sp
        if (smooth.progress != mp) smooth.progress = mp
        if (lerp.progress != lp) lerp.progress = lp
        autoGateSwitch.isChecked = settings.autoGate
        gate.alpha = if (settings.autoGate) 0.45f else 1f
        if (!settings.autoGate) gateLabel.text = "Noise gate  ·  ${settings.gateDb.roundToInt()} dB"
        else if (gateLabel.text.isNullOrEmpty()) gateLabel.text = "Noise gate  ·  auto"
        gateHint.setText(if (settings.autoGate) R.string.gate_hint_auto else R.string.gate_hint)
        sensLabel.text = "Sensitivity  ·  $sp%"
        smoothLabel.text = "Smoothing  ·  $mp%"
        lerpLabel.text = "Needle lerp  ·  $lp%"
        lowCutSwitch.isChecked = settings.lowCut
        chordsSwitch.isChecked = settings.showChords
        a4Value.text = settings.a4.roundToInt().toString()
        a4Badge.text = "A4 ${settings.a4.roundToInt()}"
        rearSwitch.isChecked = settings.rearEnabled
        insetSwitch.isChecked = settings.rearCameraInset
        rearBadge.isSelected = settings.rearEnabled
    }

    private fun buildChips() {
        for (inst in Instrument.entries) {
            val chip = layoutInflater.inflate(R.layout.chip, chips, false) as TextView
            chip.text = inst.label
            chip.tag = inst
            chip.setOnClickListener { update(settings.withInstrument(inst)) }
            chips.addView(chip)
        }
    }

    private fun togglePanel() = showPanel(panelHost.visibility != View.VISIBLE)

    private fun showPanel(show: Boolean) {
        if ((panelHost.visibility == View.VISIBLE) == show) return
        panelHost.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.gear).isSelected = show
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = closePanelOnBack as android.window.OnBackInvokedCallback
            if (show) onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb
            ) else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        }
    }

    private fun seek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
            if (fromUser) onChange(p)
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        val top = findViewById<View>(R.id.topBar)
        val bottom = findViewById<View>(R.id.bottom)
        val tTop = top.paddingTop
        val bBottom = bottom.paddingBottom
        val d = resources.displayMetrics.density
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top.setPadding(top.paddingLeft, tTop + bars.top, top.paddingRight, top.paddingBottom)
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bBottom + bars.bottom)
            // Keep the tuner face clear of the top bar and the instrument chips.
            tuner.setPadding(0, bars.top + (56 * d).toInt(), 0, bars.bottom + (64 * d).toInt())
            insets
        }
        // The settings sheet lives between the two bars, so both stay usable while it is open.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val lp = panelHost.layoutParams as ViewGroup.MarginLayoutParams
            if (lp.topMargin != top.height || lp.bottomMargin != bottom.height) {
                lp.topMargin = top.height
                lp.bottomMargin = bottom.height
                panelHost.layoutParams = lp
            }
        }
        top.addOnLayoutChangeListener(relayout)
        bottom.addOnLayoutChangeListener(relayout)
    }

    // ---------------- rear screen ----------------

    private var lastRearLaunch = 0L

    /**
     * 1) HyperOS: start [RearActivity] on the rear display (allowed by meta-data miui.rear.policy=1).
     * 2) Other phones / emulator: the service overlay or a standard Presentation.
     */
    private fun updateRear() {
        if (isFinishing || isDestroyed) return
        if (!settings.rearEnabled) {
            dismissPresentation()
            RearActivity.finishRunning()
            rearStatus.text = "Rear screen tuner is off."
            return
        }
        val runningOn = RearActivity.runningOn()
        if (runningOn != null) {
            dismissPresentation()
            rearStatus.text = "Rear screen (display $runningOn): mini tuner is showing."
            return
        }
        val display = RearDisplays.find(this)
        if (display == null) {
            dismissPresentation()
            rearStatus.text = "No second display is visible to apps. Open Display diagnostics."
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRearLaunch > 5000) {
            lastRearLaunch = now
            if (RearActivity.launchOn(this, display.displayId)) {
                rearStatus.text = "Opening the mini tuner on display ${display.displayId}…"
                // Re-check: shows "showing" on success, falls back to overlay / Presentation otherwise.
                handler.postDelayed({ updateRear() }, 1500)
                return
            }
        }
        if (TunerService.rearOverlayShowing) {
            rearStatus.text = "Rear screen (display ${display.displayId}): showing."
            return
        }
        if (presentation?.display?.displayId != display.displayId) {
            dismissPresentation()
            presentation = try {
                RearPresentation(this, display).also { it.show() }
            } catch (e: Exception) {
                rearStatus.text = "The system refused display ${display.displayId}: ${e.javaClass.simpleName}"
                null
            }
        }
        if (presentation != null) {
            rearStatus.text = "Rear screen (display ${display.displayId}): showing while the app is open."
        }
    }

    private fun dismissPresentation() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
    }

    override fun onDisplayAdded(displayId: Int) = updateRear()
    override fun onDisplayRemoved(displayId: Int) = updateRear()
    override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) updateRear()
    }
}
