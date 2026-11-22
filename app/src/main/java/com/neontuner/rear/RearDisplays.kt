package com.neontuner.rear

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.neontuner.audio.TunerSettings
import com.neontuner.ui.TunerView

/** Finding the rear (secondary) display and laying the compact tuner out on it. */
object RearDisplays {

    /**
     * 1. Standard Android route: a display in DISPLAY_CATEGORY_PRESENTATION.
     * 2. Fallback: any other public, non-default display that is not switched off.
     * On HyperOS the rear panel may not be exposed to third-party apps at all; then this returns null.
     */
    fun find(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java)
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?.let { return it }
        return dm.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY &&
                it.flags and Display.FLAG_PRIVATE == 0 &&
                it.state != Display.STATE_OFF
        }
    }

    fun describe(context: Context): String {
        val dm = context.getSystemService(DisplayManager::class.java)
        val presentationIds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        return dm.displays.joinToString("\n\n") { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(m)
            buildString {
                append("id=${d.displayId}  «${d.name}»\n")
                append("${m.widthPixels}×${m.heightPixels}  ${m.densityDpi}dpi  ${"%.0f".format(d.refreshRate)}Hz\n")
                append("state=${stateName(d.state)}  flags=0x${Integer.toHexString(d.flags)}")
                if (d.flags and Display.FLAG_PRESENTATION != 0) append(" PRESENTATION")
                if (d.flags and Display.FLAG_PRIVATE != 0) append(" PRIVATE")
                if (d.flags and Display.FLAG_SECURE != 0) append(" SECURE")
                append("\ncategory PRESENTATION: ${d.displayId in presentationIds}")
            }
        }
    }

    private fun stateName(s: Int) = when (s) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        else -> s.toString()
    }

    /**
     * The Xiaomi 17 Pro Max rear panel is reported by the community as 976x596 with the
     * camera island covering the left ~296 px (about 30 %). We keep the tuner out of that area.
     */
    fun configure(view: TunerView, display: Display?, settings: TunerSettings) {
        view.compact = true
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") display?.getRealMetrics(m)
        val landscape = m.widthPixels >= m.heightPixels
        if (settings.rearCameraInset && landscape) {
            view.setPadding(0, 0, 0, 0)
            view.leftInsetFraction = 0.30f
        } else if (settings.rearCameraInset) {
            view.leftInsetFraction = 0f
            view.setPadding(0, (m.heightPixels * 0.30f).toInt(), 0, 0)
        } else {
            view.leftInsetFraction = 0f
            view.setPadding(0, 0, 0, 0)
        }
    }
}
