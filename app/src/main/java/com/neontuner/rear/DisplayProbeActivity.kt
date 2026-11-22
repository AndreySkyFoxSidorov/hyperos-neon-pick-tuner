package com.neontuner.rear

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Diagnostics screen = the "minimal Presentation test":
 * lists every display, marks those in DISPLAY_CATEGORY_PRESENTATION, and lets you
 * put a big "A4" on any of them or launch the compact tuner Activity there.
 */
class DisplayProbeActivity : Activity() {
    private var presentation: Presentation? = null
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 140, 48, 140)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#05040D"))
            addView(root)
        })
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(text("Displays (DisplayManager)", 22f, Color.parseColor("#00F0FF")))
        root.addView(text(RearDisplays.describe(this), 14f, Color.parseColor("#C8C4FF")))
        val dm = getSystemService(DisplayManager::class.java)
        val found = RearDisplays.find(this)
        root.addView(text("\nUsed for the tuner: " + (found?.let { "id=${it.displayId}" } ?: "no second display available"), 15f, Color.parseColor("#39FF88")))
        for (d in dm.displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY) continue
            root.addView(button("Show \"A4\" on id=${d.displayId} (Presentation)") { showA4(d) })
            root.addView(button("Launch tuner on id=${d.displayId} (setLaunchDisplayId)") { launchOn(d.displayId) })
        }
        root.addView(button("Refresh") { render() })
        root.addView(text(
            "\nHyperOS allows only Activities that declare meta-data miui.rear.policy=1 on the rear " +
                "screen; windows such as Presentation are blocked for non-system apps.\n\nFrom a PC:\n" +
                "adb shell am start --display ${found?.displayId ?: 1} -n $packageName/.rear.RearActivity",
            13f, Color.parseColor("#8A86B0")
        ))
    }

    private fun showA4(d: Display) {
        presentation?.dismiss()
        presentation = try {
            A4Presentation(this, d).also { it.show() }
        } catch (e: Exception) {
            toast("Refused: ${e.javaClass.simpleName}: ${e.message}"); null
        }
    }

    private fun launchOn(id: Int) {
        if (!RearActivity.launchOn(this, id)) toast("Refused by the system (see logcat)")
    }

    override fun onStop() {
        presentation?.dismiss(); presentation = null
        super.onStop()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = Typeface.MONOSPACE
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    /** The whole test: a Presentation whose content is a big neon "A4". */
    class A4Presentation(ctx: Context, display: Display) : Presentation(ctx, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(TextView(context).apply {
                text = "A4"
                textSize = 96f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#00F0FF"))
                setShadowLayer(40f, 0f, 0f, Color.parseColor("#00F0FF"))
                setBackgroundColor(Color.BLACK)
            })
        }
    }
}
