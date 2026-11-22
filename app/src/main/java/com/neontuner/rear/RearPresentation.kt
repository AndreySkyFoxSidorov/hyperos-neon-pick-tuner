package com.neontuner.rear

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import com.neontuner.audio.TunerEngine
import com.neontuner.ui.TunerView

/** Standard Android Presentation: compact tuner on a secondary display, owned by an Activity. */
class RearPresentation(outer: Context, display: Display) : Presentation(outer, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TunerView(context)
        RearDisplays.configure(view, display, TunerEngine.settings)
        setContentView(view)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
