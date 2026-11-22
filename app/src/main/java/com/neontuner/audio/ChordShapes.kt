package com.neontuner.audio

/** A fingering: one entry per string, low to high. -1 = muted, 0 = open, n = fret. */
class Voicing(
    val frets: IntArray,
    /** Finger number per string (0 = none). */
    val fingers: IntArray,
    /** Fret of the index-finger barre, or 0 if none. */
    val barreFret: Int,
    /** First string (index) covered by the barre. */
    val barreFrom: Int,
) {
    val minFret get() = frets.filter { it > 0 }.minOrNull() ?: 0
    val maxFret get() = frets.maxOrNull() ?: 0

    /** Tab-style string, e.g. "x32010". Frets >= 10 are wrapped in parentheses. */
    val tab: String get() = frets.joinToString("") { if (it < 0) "x" else if (it < 10) "$it" else "($it)" }
}

enum class Fretboard(val label: String, val openMidi: IntArray, val allowMutes: Boolean, val rootInBass: Boolean) {
    GUITAR("Guitar", intArrayOf(40, 45, 50, 55, 59, 64), allowMutes = true, rootInBass = true),
    // Re-entrant G C E A: the G string is high, so the "bass" is not the lowest string.
    UKULELE("Ukulele", intArrayOf(67, 60, 64, 69), allowMutes = false, rootInBass = false),
}

/**
 * Finds a playable, conventional-looking voicing for any chord by brute force over 4-fret windows
 * (instead of a hand-written table). Cost favours: low position, open strings, few fingers,
 * small stretch, no barre, root (or slash bass) as the lowest guitar note.
 * Results are cached; the search takes ~1 ms per chord.
 */
object ChordShapes {
    private val cache = HashMap<String, Voicing?>()

    fun voicing(chord: Chord, board: Fretboard): Voicing? {
        val key = "${board.name}:${chord.root}:${chord.quality.name}:${chord.bass}"
        return synchronized(cache) {
            cache.getOrPut(key) {
                search(chord, board) ?: if (chord.bass != chord.root) search(chord.rootPosition, board) else null
            }
        }
    }

    private fun search(chord: Chord, board: Fretboard): Voicing? {
        val tones = chord.tones
        val required = tones.toMutableSet()
        // 4-note chords may drop the fifth (very common on guitar and ukulele).
        val fifth = (chord.root + 7) % 12
        val optional = if (tones.size >= 4 && chord.quality.intervals.contains(7)) setOf(fifth) else emptySet()
        val power = chord.quality == ChordQuality.POWER
        val n = board.openMidi.size
        val frets = IntArray(n)
        var best: IntArray? = null
        var bestCost = Float.MAX_VALUE

        fun evaluate() {
            val sounding = frets.count { it >= 0 }
            if (sounding < (if (power) 2 else if (n == 6) 4 else n)) return
            // mutes: guitar only, contiguous from the bass side (power chords may also mute the top)
            if (board.allowMutes) {
                var i = 0
                while (i < n && frets[i] < 0) i++
                var j = n - 1
                if (power) while (j > i && frets[j] < 0) j--
                for (k in i..j) if (frets[k] < 0) return
                if (!power && frets[n - 1] < 0) return
            }
            val present = HashSet<Int>()
            var lowest = Int.MAX_VALUE
            var lowestPc = -1
            for (s in 0 until n) if (frets[s] >= 0) {
                val m = board.openMidi[s] + frets[s]
                present.add(m % 12)
                if (m < lowest) { lowest = m; lowestPc = m % 12 }
            }
            for (t in required) if (t !in optional && t !in present) return
            if (board.rootInBass && lowestPc != chord.bass) return

            val fretted = frets.filter { it > 0 }
            val minF = fretted.minOrNull() ?: 0
            val maxF = fretted.maxOrNull() ?: 0
            if (maxF - minF > 3) return
            var barre = false
            if (minF > 0 && fretted.count { it == minF } >= 2) {
                val first = frets.indexOfFirst { it == minF }
                barre = (first until n).all { frets[it] >= minF }
            }
            val fingers = if (barre) 1 + fretted.count { it > minF } else fretted.size
            if (fingers > 4) return
            val mutes = frets.count { it < 0 }
            var cost = minF * 2.0f + (maxF - minF) * 0.6f + fingers * 0.6f + mutes * 0.7f - sounding * 0.3f
            if (barre) cost += 1.5f
            if (cost < bestCost) { bestCost = cost; best = frets.copyOf() }
        }

        fun rec(s: Int, lo: Int, hi: Int) {
            if (s == n) { evaluate(); return }
            if (board.allowMutes) { frets[s] = -1; rec(s + 1, lo, hi) }
            frets[s] = 0
            if ((board.openMidi[s] % 12) in tones) rec(s + 1, lo, hi)
            for (f in lo..hi) {
                if ((board.openMidi[s] + f) % 12 !in tones) continue
                frets[s] = f
                rec(s + 1, lo, hi)
            }
        }

        for (w in 0..9) rec(0, maxOf(1, w), w + 3)
        val b = best ?: return null
        return withFingers(b)
    }

    /** Index finger takes the barre (if any); remaining notes get fingers in fret/string order. */
    private fun withFingers(frets: IntArray): Voicing {
        val n = frets.size
        val fingers = IntArray(n)
        val fretted = (0 until n).filter { frets[it] > 0 }
        val minF = fretted.minOfOrNull { frets[it] } ?: 0
        var barreFret = 0
        var barreFrom = 0
        if (minF > 0 && fretted.count { frets[it] == minF } >= 2) {
            val first = frets.indexOfFirst { it == minF }
            if ((first until n).all { frets[it] >= minF }) {
                barreFret = minF; barreFrom = first
            }
        }
        var next = 1
        if (barreFret > 0) {
            for (s in fretted) if (frets[s] == barreFret) fingers[s] = 1
            next = 2
        }
        val rest = fretted.filter { fingers[it] == 0 }.sortedWith(compareBy({ frets[it] }, { it }))
        for (s in rest) fingers[s] = minOf(4, next++)
        return Voicing(frets, fingers, barreFret, barreFrom)
    }
}
