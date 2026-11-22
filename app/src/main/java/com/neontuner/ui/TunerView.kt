package com.neontuner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.neontuner.audio.Reading
import com.neontuner.audio.TunerEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The whole tuner face, drawn by hand (no libraries):
 *  - note name on top,
 *  - a ±50 cent neon scale with a glowing indicator,
 *  - a "waterfall" below it: every reading leaves a trace that scrolls DOWN,
 *    so you can see which way the pitch is drifting while you turn the peg.
 *
 * [compact] = layout for the small rear display.
 */
class TunerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), TunerEngine.Listener {

    var compact = false
        set(v) { field = v; layoutDirty = true; invalidate() }

    /** Fraction of the width on the left that is hidden behind the camera island (rear display). */
    var leftInsetFraction = 0f
        set(v) { field = v; layoutDirty = true; invalidate() }

    /** Set whenever size, padding or mode change; layout is recomputed lazily in onDraw. */
    private var layoutDirty = true

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        layoutDirty = true
        invalidate()
    }

    // ---------- palette ----------
    private val cBgTop = Color.parseColor("#0A0620")
    private val cBgBottom = Color.parseColor("#010106")
    private val cCyan = Color.parseColor("#00F0FF")
    private val cMagenta = Color.parseColor("#FF2BD6")
    private val cGreen = Color.parseColor("#39FF88")
    private val cAmber = Color.parseColor("#FFC93C")
    private val cGrid = Color.parseColor("#2A2550")
    private val cDim = Color.parseColor("#5A5680")

    private val dp = resources.displayMetrics.density

    // ---------- history ring buffer ----------
    private val cap = 1024
    private val hTime = LongArray(cap)
    private val hCents = FloatArray(cap)
    private val hMidi = IntArray(cap)
    private val hValid = BooleanArray(cap)
    private var hHead = 0 // next write position
    private var hSize = 0

    private var current: Reading? = null
    private var lastPitchTime = 0L
    private var lastReadingTime = 0L
    private var shownCents = 0f
    private var shownMidi = -1
    private var lastFrameNanos = 0L

    // ---------- layout rects ----------
    private val noteRect = RectF()
    private val scaleRect = RectF()
    private val historyRect = RectF()
    private var historySeconds = 6f

    // ---------- paints ----------
    private val bgPaint = Paint()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = cDim
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TunerEngine.addListener(this)
    }

    override fun onDetachedFromWindow() {
        TunerEngine.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onReading(r: Reading) {
        lastReadingTime = r.timeMs
        if (r.hasPitch) {
            current = r
            lastPitchTime = r.timeMs
            if (r.midi != shownMidi) {
                shownMidi = r.midi
                shownCents = r.cents
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutDirty = true
    }

    private fun computeLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val pl = paddingLeft + w * leftInsetFraction
        val pr = w - paddingRight
        val pt = paddingTop.toFloat()
        val pb = h - paddingBottom
        val ch = pb - pt
        val m = (if (compact) 6f else 20f) * dp

        if (compact && w > h * 1.2f) {
            // Landscape rear screen: note on the left, scale + waterfall on the right.
            val split = pl + (pr - pl) * 0.36f
            noteRect.set(pl + m * 0.5f, pt + m, split, pb - m)
            scaleRect.set(split + m, pt + m, pr - m, pt + ch * 0.36f)
            historyRect.set(split + m, scaleRect.bottom + m * 0.5f, pr - m, pb - m * 0.5f)
            historySeconds = 4f
        } else if (compact) {
            noteRect.set(pl + m, pt + m, pr - m, pt + ch * 0.36f)
            scaleRect.set(pl + m, noteRect.bottom, pr - m, pt + ch * 0.52f)
            historyRect.set(pl + m, scaleRect.bottom + m, pr - m, pb - m)
            historySeconds = 4f
        } else {
            noteRect.set(pl + m, pt, pr - m, pt + ch * 0.32f)
            scaleRect.set(pl + m, noteRect.bottom + m * 0.5f, pr - m, pt + ch * 0.44f)
            historyRect.set(pl + m, scaleRect.bottom + m, pr - m, pb - m * 0.5f)
            historySeconds = 6f
        }

        bgPaint.shader = LinearGradient(0f, 0f, 0f, h, cBgTop, cBgBottom, Shader.TileMode.CLAMP)
        val r = max(noteRect.width(), noteRect.height()) * 0.7f
        halo.shader = RadialGradient(
            noteRect.centerX(), noteRect.centerY(), max(1f, r),
            Color.argb(70, 0, 240, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        trackPaint.shader = LinearGradient(
            scaleRect.left, 0f, scaleRect.right, 0f,
            intArrayOf(cMagenta, cAmber, cGreen, cAmber, cMagenta),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ---------- helpers ----------
    private fun xOf(cents: Float, r: RectF) = r.left + (cents.coerceIn(-50f, 50f) + 50f) / 100f * r.width()

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    private fun colorFor(cents: Float): Int {
        val a = abs(cents)
        val tol = TunerEngine.settings.inTuneCents
        return when {
            a <= tol -> cGreen
            a <= 15f -> lerpColor(cGreen, cAmber, (a - tol) / (15f - tol))
            else -> lerpColor(cAmber, cMagenta, (a - 15f) / 20f)
        }
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        if (layoutDirty) { computeLayout(); layoutDirty = false }
        val now = System.currentTimeMillis()
        val nanos = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else min(0.1f, (nanos - lastFrameNanos) / 1e9f)
        lastFrameNanos = nanos

        val target = current?.cents ?: 0f
        // Frame-rate independent lerp; speed comes from the "Needle lerp" setting.
        shownCents += (target - shownCents) * (1f - kotlin.math.exp(-dt * TunerEngine.settings.needleSpeed))

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val age = now - lastPitchTime
        val live = current != null && age < 2500
        recordTrace(now, age)
        if (live) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), halo)

        drawNote(canvas, now, age, live)
        drawScale(canvas, live, age)
        drawHistory(canvas, now)

        val needsMore = abs(target - shownCents) > 0.05f || now - lastReadingTime < (historySeconds * 1000).toLong()
        if (needsMore) postInvalidateOnAnimation()
    }

    private fun drawNote(canvas: Canvas, now: Long, age: Long, live: Boolean) {
        val r = current
        val fade = if (age < 800) 1f else max(0.25f, 1f - (age - 800) / 1700f)
        val landscapeCompact = compact && noteRect.height() > noteRect.width() * 0.9f
        val infoH = if (landscapeCompact) noteRect.height() * 0.28f else noteRect.height() * 0.24f
        val glyphArea = RectF(noteRect.left, noteRect.top, noteRect.right, noteRect.bottom - infoH)

        if (r == null || !live) {
            notePaint.textSize = min(glyphArea.height() * 0.55f, glyphArea.width() * 0.4f)
            notePaint.color = cDim
            notePaint.clearShadowLayer()
            val y = glyphArea.centerY() - (notePaint.descent() + notePaint.ascent()) / 2
            canvas.drawText(if (r == null) "—" else noteGlyph(r.midi), glyphArea.centerX(), y, notePaint.apply { alpha = 120 })
            monoPaint.textSize = infoH * 0.38f
            monoPaint.color = cDim
            monoPaint.clearShadowLayer()
            canvas.drawText(if (compact) "listening…" else "play a note", noteRect.centerX(), noteRect.bottom - infoH * 0.35f, monoPaint)
            return
        }

        val inTune = abs(r.cents) <= TunerEngine.settings.inTuneCents
        val col = if (inTune) cGreen else cCyan
        val name = TunerEngine.noteName(r.midi)
        val letter = name.substring(0, 1)
        val sharp = name.length > 1
        val octave = TunerEngine.octave(r.midi).toString()

        val size = min(glyphArea.height() * 0.95f, glyphArea.width() * (if (compact) 0.75f else 0.55f))
        notePaint.textSize = size
        notePaint.color = withAlpha(col, fade)
        notePaint.setShadowLayer(size * 0.12f, 0f, 0f, withAlpha(col, 0.9f * fade))
        val baseline = glyphArea.centerY() - (notePaint.descent() + notePaint.ascent()) / 2
        val lw = notePaint.measureText(letter)
        val cx = glyphArea.centerX() - size * 0.08f
        canvas.drawText(letter, cx, baseline, notePaint)

        smallPaint.textSize = size * 0.36f
        smallPaint.color = withAlpha(col, fade)
        smallPaint.setShadowLayer(size * 0.06f, 0f, 0f, withAlpha(col, 0.8f * fade))
        smallPaint.textAlign = Paint.Align.LEFT
        val sx = cx + lw / 2 + size * 0.03f
        if (sharp) canvas.drawText("♯", sx, baseline - size * 0.42f, smallPaint)
        smallPaint.color = withAlpha(cMagenta, fade)
        smallPaint.setShadowLayer(size * 0.06f, 0f, 0f, withAlpha(cMagenta, 0.8f * fade))
        canvas.drawText(octave, sx, baseline, smallPaint)

        // Cents + Hz
        val cents = r.cents
        val centsText = String.format(Locale.US, "%+.1f¢", cents)
        monoPaint.textSize = min(infoH * (if (compact) 0.5f else 0.42f), noteRect.width() * 0.17f)
        monoPaint.color = withAlpha(colorFor(cents), fade)
        monoPaint.setShadowLayer(8f * dp, 0f, 0f, withAlpha(colorFor(cents), 0.7f * fade))
        if (compact) {
            canvas.drawText(centsText, noteRect.centerX(), noteRect.bottom - infoH * 0.3f, monoPaint)
        } else {
            val y = noteRect.bottom - infoH * 0.2f
            canvas.drawText(centsText, noteRect.centerX() - noteRect.width() * 0.18f, y, monoPaint)
            monoPaint.color = withAlpha(cDim, fade)
            monoPaint.clearShadowLayer()
            canvas.drawText(String.format(Locale.US, "%.2f Hz", r.frequency), noteRect.centerX() + noteRect.width() * 0.2f, y, monoPaint)
        }
    }

    /**
     * The waterfall is the path of the pick itself: every frame stores where the pick is drawn
     * (after the lerp), so the trace leaves the pick's tip and scrolls down exactly as it moved.
     */
    private var lastTraceTime = 0L

    private fun recordTrace(now: Long, pitchAge: Long) {
        if (now - lastTraceTime < 12) return
        lastTraceTime = now
        hTime[hHead] = now
        hCents[hHead] = shownCents
        hMidi[hHead] = shownMidi
        hValid[hHead] = current != null && pitchAge < 160
        hHead = (hHead + 1) % cap
        if (hSize < cap) hSize++
    }

    private fun noteGlyph(midi: Int) = TunerEngine.noteName(midi).replace("#", "♯")

    private fun drawScale(canvas: Canvas, live: Boolean, age: Long) {
        val r = scaleRect
        val cy = r.centerY()
        val tol = TunerEngine.settings.inTuneCents

        // in-tune zone
        fill.color = withAlpha(cGreen, 0.16f)
        fill.clearShadowLayer()
        canvas.drawRoundRect(xOf(-tol, r), r.top + r.height() * 0.15f, xOf(tol, r), r.bottom - r.height() * 0.15f, 4 * dp, 4 * dp, fill)

        // glowing track
        trackPaint.strokeWidth = 3f * dp
        trackPaint.alpha = 200
        canvas.drawLine(r.left, cy, r.right, cy, trackPaint)

        // ticks every 5 cents, labels every 10 (every 25 in compact mode)
        line.shader = null
        line.clearShadowLayer()
        labelPaint.textSize = (if (compact) 9f else 11f) * dp
        for (c in -50..50 step 5) {
            val x = xOf(c.toFloat(), r)
            val major = c % 10 == 0
            val th = r.height() * (if (c == 0) 0.30f else if (major) 0.26f else 0.14f)
            line.strokeWidth = (if (c == 0) 2.5f else 1.2f) * dp
            line.color = if (c == 0) withAlpha(cGreen, 0.6f) else if (major) cGrid.brighten() else cGrid
            canvas.drawLine(x, cy - th, x, cy + th, line)
            val showLabel = if (compact) c % 25 == 0 else major
            if (showLabel) {
                val t = if (c > 0) "+$c" else "$c"
                canvas.drawText(t, x, r.bottom, labelPaint)
            }
        }
        if (!compact) {
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("♭ flat", r.left, r.top + labelPaint.textSize, labelPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("sharp ♯", r.right, r.top + labelPaint.textSize, labelPaint)
            labelPaint.textAlign = Paint.Align.CENTER
        }

        // indicator: a neon guitar pick whose tip emits the beam that draws the waterfall
        val alpha = if (live) (if (age < 800) 1f else max(0.25f, 1f - (age - 800) / 1700f)) else 0.22f
        val x = xOf(shownCents, r)
        val col = if (live) colorFor(shownCents) else cDim
        val pw = min((if (compact) 17f else 30f) * dp, r.height() * 0.75f)
        val ph = pw * 1.18f
        val tipY = cy + r.height() * 0.22f
        val top = tipY - ph

        // beam from the tip down to the waterfall
        line.strokeWidth = (if (compact) 5f else 8f) * dp
        line.color = withAlpha(col, 0.22f * alpha)
        canvas.drawLine(x, tipY, x, historyRect.top, line)
        line.strokeWidth = (if (compact) 1.6f else 2.2f) * dp
        line.color = withAlpha(col, alpha)
        canvas.drawLine(x, tipY, x, historyRect.top, line)

        buildPick(x, top, pw, ph)
        // body: dark translucent fill tinted with the colour
        fill.color = withAlpha(lerpColor(cBgTop, col, 0.35f), 0.85f * alpha)
        fill.setShadowLayer(18f * dp, 0f, 0f, withAlpha(col, 0.8f * alpha))
        canvas.drawPath(path, fill)
        fill.clearShadowLayer()
        // neon outline
        line.strokeWidth = (if (compact) 1.8f else 2.6f) * dp
        line.color = withAlpha(col, alpha)
        line.setShadowLayer(10f * dp, 0f, 0f, withAlpha(col, alpha))
        line.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, line)
        line.clearShadowLayer()
        // small highlight near the shoulder, like light on celluloid
        line.strokeWidth = 1.2f * dp
        line.color = withAlpha(Color.WHITE, 0.55f * alpha)
        canvas.drawLine(x - pw * 0.22f, top + ph * 0.22f, x - pw * 0.08f, top + ph * 0.5f, line)
    }

    /** Classic 351-shape pick pointing down: wide rounded shoulders, rounded tip at the bottom. */
    private fun buildPick(cx: Float, top: Float, w: Float, h: Float) {
        val hw = w / 2f
        val sy = top + h * 0.2f
        path.reset()
        path.moveTo(cx - hw, sy)
        path.cubicTo(cx - hw * 0.9f, top - h * 0.04f, cx + hw * 0.9f, top - h * 0.04f, cx + hw, sy)
        path.cubicTo(cx + hw * 1.1f, top + h * 0.5f, cx + hw * 0.25f, top + h * 0.9f, cx, top + h)
        path.cubicTo(cx - hw * 0.25f, top + h * 0.9f, cx - hw * 1.1f, top + h * 0.5f, cx - hw, sy)
        path.close()
    }

    private fun Int.brighten() = lerpColor(this, Color.WHITE, 0.25f)

    private fun drawHistory(canvas: Canvas, now: Long) {
        val r = historyRect
        if (r.height() <= 0) return
        val pxPerMs = r.height() / (historySeconds * 1000f)

        canvas.save()
        canvas.clipRect(r.left - 20 * dp, r.top, r.right + 20 * dp, r.bottom)

        // vertical grid at -50, -25, 0, +25, +50
        line.shader = null
        line.clearShadowLayer()
        for (c in intArrayOf(-50, -25, 0, 25, 50)) {
            line.strokeWidth = (if (c == 0) 1.6f else 1f) * dp
            line.color = if (c == 0) withAlpha(cGreen, 0.35f) else withAlpha(cGrid, 0.8f)
            val x = xOf(c.toFloat(), r)
            canvas.drawLine(x, r.top, x, r.bottom, line)
        }
        // horizontal "time" lines that scroll down, one per second
        line.strokeWidth = 1f * dp
        line.color = withAlpha(cGrid, 0.45f)
        val firstSec = now / 1000
        for (k in 0..historySeconds.toInt() + 1) {
            val y = r.top + (now - (firstSec - k) * 1000) * pxPerMs
            if (y in r.top..r.bottom) canvas.drawLine(r.left, y, r.right, y, line)
        }

        // trace: newest at top, older readings move down
        val maxAgeMs = historySeconds * 1000f
        var prevX = 0f; var prevY = 0f; var prevMidi = Int.MIN_VALUE; var prevT = 0L; var havePrev = false
        var runStartX = 0f; var runStartY = 0f; var runMidi = Int.MIN_VALUE; var inRun = false
        labelPaint.textSize = (if (compact) 9f else 12f) * dp
        for (i in 0 until hSize) {
            val idx = ((hHead - 1 - i) % cap + cap) % cap
            val t = hTime[idx]
            val ageMs = now - t
            if (ageMs > maxAgeMs) break
            if (!hValid[idx]) {
                if (inRun) drawRunLabel(canvas, runStartX, runStartY, runMidi, r)
                inRun = false; havePrev = false
                continue
            }
            val c = hCents[idx]
            val x = xOf(c, r)
            val y = r.top + ageMs * pxPerMs
            val m = hMidi[idx]
            val a = 1f - ageMs / maxAgeMs
            if (havePrev && m == prevMidi && prevT - t < 200) {
                val col = colorFor(c)
                line.color = withAlpha(col, 0.22f * a)
                line.strokeWidth = (if (compact) 5f else 8f) * dp
                canvas.drawLine(prevX, prevY, x, y, line)
                line.color = withAlpha(col, a)
                line.strokeWidth = (if (compact) 1.6f else 2.2f) * dp
                canvas.drawLine(prevX, prevY, x, y, line)
            } else {
                if (inRun) drawRunLabel(canvas, runStartX, runStartY, runMidi, r)
                inRun = true
                fill.color = withAlpha(colorFor(c), a)
                canvas.drawCircle(x, y, 2f * dp, fill)
            }
            if (inRun) { runStartX = x; runStartY = y; runMidi = m }
            prevX = x; prevY = y; prevMidi = m; prevT = t; havePrev = true
        }
        if (inRun) drawRunLabel(canvas, runStartX, runStartY, runMidi, r)
        canvas.restore()
    }

    /** Writes the note name where a held note began, so note changes are visible in the waterfall. */
    private fun drawRunLabel(canvas: Canvas, x: Float, y: Float, midi: Int, r: RectF) {
        val text = noteGlyph(midi) + TunerEngine.octave(midi)
        labelPaint.color = withAlpha(cCyan, 0.85f)
        val left = x > r.centerX()
        labelPaint.textAlign = if (left) Paint.Align.RIGHT else Paint.Align.LEFT
        val off = 8 * dp
        canvas.drawText(text, if (left) x - off else x + off, y + labelPaint.textSize * 0.35f, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = cDim
    }
}
