package com.neontuner.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.neontuner.audio.Chord
import com.neontuner.audio.ChordShapes
import com.neontuner.audio.Fretboard
import com.neontuner.audio.NoteNames
import kotlin.math.min

/**
 * Neon chord-diagram card: chord name, a 4-fret grid with finger dots / barre / x-o markers,
 * and (full size only) hints: tab string, chord tones and the chord type.
 */
class ChordCard(private val dp: Float) {
    private val cCyan = Color.parseColor("#00F0FF")
    private val cMagenta = Color.parseColor("#FF2BD6")
    private val cGrid = Color.parseColor("#8A86B0")
    private val cDim = Color.parseColor("#8A86B0")
    private val cBg = Color.parseColor("#0B0820")

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); textAlign = Paint.Align.CENTER
    }

    private fun a(c: Int, alpha: Float) = Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    /** Height/width ratio the card wants for a given width. */
    fun aspect(compact: Boolean) = if (compact) 1.25f else 1.5f

    fun draw(canvas: Canvas, r: RectF, chord: Chord, board: Fretboard, alpha: Float, compact: Boolean) {
        val v = ChordShapes.voicing(chord, board) ?: return
        val pad = r.width() * 0.08f
        val corner = min(14 * dp, r.width() * 0.12f)
        val glow = min(8 * dp, r.width() * 0.06f)

        // card
        bg.color = a(cBg, 0.82f * alpha)
        canvas.drawRoundRect(r, corner, corner, bg)
        stroke.color = a(cMagenta, 0.55f * alpha)
        stroke.strokeWidth = 1.2f * dp
        stroke.setShadowLayer(glow, 0f, 0f, a(cMagenta, 0.6f * alpha))
        canvas.drawRoundRect(r, corner, corner, stroke)
        stroke.clearShadowLayer()

        // title
        title.textSize = r.width() * (if (compact) 0.22f else 0.2f)
        title.color = a(cCyan, alpha)
        title.setShadowLayer(glow * 1.2f, 0f, 0f, a(cCyan, 0.8f * alpha))
        val titleY = r.top + pad + title.textSize * 0.85f
        canvas.drawText(chord.name, r.centerX(), titleY, title)
        title.clearShadowLayer()

        // grid area
        val n = v.frets.size
        val hintsH = if (compact) 0f else r.height() * 0.2f
        val gridTop = titleY + title.textSize * 0.55f + r.width() * 0.07f // leaves room for x/o markers
        val gridBottom = r.bottom - pad - hintsH
        val leftLabel = r.width() * 0.14f
        val gridLeft = r.left + pad + leftLabel * 0.6f
        val gridRight = r.right - pad - leftLabel * 0.4f
        val sx = (gridRight - gridLeft) / (n - 1)
        val rows = 4
        val sy = (gridBottom - gridTop) / rows
        val start = if (v.maxFret <= 4) 1 else v.minFret
        val dot = min(sx, sy) * 0.36f

        // frets (horizontal) and strings (vertical)
        stroke.color = a(cGrid, 0.7f * alpha)
        stroke.strokeWidth = 1f * dp
        for (i in 0..rows) {
            val y = gridTop + i * sy
            stroke.strokeWidth = if (i == 0 && start == 1) 3.5f * dp else 1f * dp
            stroke.color = if (i == 0 && start == 1) a(Color.WHITE, 0.85f * alpha) else a(cGrid, 0.7f * alpha)
            canvas.drawLine(gridLeft, y, gridRight, y, stroke)
        }
        stroke.color = a(cGrid, 0.8f * alpha)
        for (s in 0 until n) {
            stroke.strokeWidth = (1f + (n - 1 - s) * 0.18f) * dp // thicker bass strings
            val x = gridLeft + s * sx
            canvas.drawLine(x, gridTop, x, gridBottom, stroke)
        }
        if (start > 1) {
            small.textSize = sy * 0.42f
            small.color = a(cDim, alpha)
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText("${start}fr", gridLeft - dot * 0.7f, gridTop + sy * 0.62f, small)
            small.textAlign = Paint.Align.CENTER
        }

        // x / o markers above the nut
        small.textSize = min(sx, sy) * 0.55f
        for (s in 0 until n) {
            val x = gridLeft + s * sx
            val y = gridTop - min(sx, sy) * 0.3f
            when {
                v.frets[s] < 0 -> { small.color = a(cMagenta, 0.9f * alpha); canvas.drawText("×", x, y, small) }
                v.frets[s] == 0 -> {
                    stroke.color = a(cCyan, 0.9f * alpha); stroke.strokeWidth = 1.4f * dp
                    canvas.drawCircle(x, y - small.textSize * 0.32f, small.textSize * 0.32f, stroke)
                }
            }
        }

        // barre
        fun rowY(f: Int) = gridTop + (f - start + 0.5f) * sy
        if (v.barreFret > 0) {
            fill.color = a(cMagenta, 0.9f * alpha)
            fill.setShadowLayer(glow, 0f, 0f, a(cMagenta, 0.8f * alpha))
            val y = rowY(v.barreFret)
            canvas.drawRoundRect(
                gridLeft + v.barreFrom * sx - dot, y - dot * 0.75f, gridRight + dot, y + dot * 0.75f, dot, dot, fill
            )
            fill.clearShadowLayer()
        }
        // finger dots
        finger.textSize = dot * 1.25f
        for (s in 0 until n) {
            val f = v.frets[s]
            if (f <= 0) continue
            if (f == v.barreFret && s >= v.barreFrom) continue
            val x = gridLeft + s * sx
            val y = rowY(f)
            fill.color = a(cMagenta, alpha)
            fill.setShadowLayer(glow, 0f, 0f, a(cMagenta, 0.8f * alpha))
            canvas.drawCircle(x, y, dot, fill)
            fill.clearShadowLayer()
            if (v.fingers[s] > 0) {
                finger.color = a(cBg, alpha)
                canvas.drawText(v.fingers[s].toString(), x, y + finger.textSize * 0.36f, finger)
            }
        }
        if (v.barreFret > 0) {
            finger.color = a(cBg, alpha)
            val y = rowY(v.barreFret)
            canvas.drawText("1", gridLeft + v.barreFrom * sx, y + finger.textSize * 0.36f, finger)
        }

        if (compact) return
        // hints: tab, chord tones, type
        small.textSize = maxOf(r.width() * 0.11f, 8.5f * dp)
        small.color = a(Color.WHITE, 0.85f * alpha)
        var y = gridBottom + hintsH * 0.36f
        canvas.drawText(v.tab, r.centerX(), y, small)
        small.color = a(cDim, alpha)
        small.textSize = maxOf(r.width() * 0.09f, 7.5f * dp)
        y += small.textSize * 1.35f
        canvas.drawText(chord.tones.joinToString(" ") { NoteNames.of(it) }, r.centerX(), y, small)
    }
}
