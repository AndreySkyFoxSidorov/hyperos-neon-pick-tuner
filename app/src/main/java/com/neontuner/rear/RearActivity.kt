package com.neontuner.rear

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.neontuner.TunerService
import com.neontuner.audio.TunerEngine
import com.neontuner.audio.TunerSettings
import com.neontuner.ui.TunerView
import java.lang.ref.WeakReference

/**
 * Compact tuner as a normal Activity, launched ON the rear display.
 *
 * HyperOS (checked on Xiaomi 18 Pro Max, OS4.0.7.0.XEOEUXM) blocks third-party *windows*
 * (Presentation, overlays) on the rear display, but lets an *Activity* start there when it
 * declares meta-data `miui.rear.policy = 1` (see AndroidManifest.xml); Xiaomi Camera does the same.
 */
class RearActivity : Activity() {

    companion object {
        private var current: WeakReference<RearActivity>? = null

        /** Display id the rear tuner is VISIBLE on; null if absent or covered by the system launcher. */
        fun runningOn(): Int? = current?.get()
            ?.takeIf { !it.isFinishing && TunerService.isUiVisible("rear") }
            ?.display?.displayId

        fun finishRunning() {
            current?.get()?.finish()
        }

        /** Returns false if the system refused synchronously. HyperOS may also abort silently. */
        fun launchOn(context: Context, displayId: Int): Boolean = try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(
                Intent(context, RearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle()
            )
            true
        } catch (e: Exception) {
            Log.w("RearActivity", "launch on display $displayId refused", e)
            false
        }
    }

    private lateinit var view: TunerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Wake the rear panel when we are (re)launched and keep it on while the tuner shows.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (!TunerEngine.isRunning) TunerEngine.settings = TunerSettings.load(this)
        view = TunerView(this)
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        TunerService.uiStarted(this, "rear")
    }

    override fun onResume() {
        super.onResume()
        RearDisplays.configure(view, display, TunerEngine.settings)
    }

    override fun onStop() {
        TunerService.uiStopped(this, "rear")
        super.onStop()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
