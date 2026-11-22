package com.neontuner.rear

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.neontuner.audio.TunerEngine
import com.neontuner.ui.TunerView

/**
 * Service-owned window on the rear display (TYPE_APPLICATION_OVERLAY through a window context).
 * Unlike a Presentation it survives the main Activity going to the background, for example when
 * the phone is flipped face-down. Needs the "display over other apps" permission.
 */
class RearOverlay(private val context: Context) : DisplayManager.DisplayListener {
    private val dm = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var shownOn: Int = -1
    private var wm: WindowManager? = null
    private var view: TunerView? = null
    private var started = false

    val isShowing get() = view != null

    fun start() {
        if (!started) {
            started = true
            dm.registerDisplayListener(this, handler)
        }
        update()
    }

    fun stop() {
        if (!started) return
        started = false
        dm.unregisterDisplayListener(this)
        remove()
    }

    fun update() {
        if (!started || !Settings.canDrawOverlays(context)) { remove(); return }
        val d = RearDisplays.find(context)
        if (d == null) { remove(); return }
        val v = view
        if (d.displayId == shownOn && v != null) {
            RearDisplays.configure(v, d, TunerEngine.settings)
            return
        }
        remove()
        show(d)
    }

    private fun show(d: Display) {
        try {
            val wctx = context.createDisplayContext(d)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val w = wctx.getSystemService(WindowManager::class.java)
            val v = TunerView(wctx)
            RearDisplays.configure(v, d, TunerEngine.settings)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            ).apply { title = "NeonTuner rear" }
            w.addView(v, lp)
            wm = w; view = v; shownOn = d.displayId
        } catch (e: Exception) {
            // HyperOS may refuse third-party windows on the rear panel.
            Log.w("RearOverlay", "cannot show on display ${d.displayId}", e)
            wm = null; view = null; shownOn = -1
        }
    }

    private fun remove() {
        val v = view
        if (v != null) runCatching { wm?.removeViewImmediate(v) }
        view = null; wm = null; shownOn = -1
    }

    override fun onDisplayAdded(displayId: Int) = update()
    override fun onDisplayRemoved(displayId: Int) = update()
    override fun onDisplayChanged(displayId: Int) {
        // State flips (ON/OFF) of the rear panel arrive here; only react if relevant.
        if (displayId != Display.DEFAULT_DISPLAY) update()
    }
}
