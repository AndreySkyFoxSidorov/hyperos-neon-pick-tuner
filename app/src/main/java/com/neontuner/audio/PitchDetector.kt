package com.neontuner.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * McLeod Pitch Method (MPM) on top of an FFT autocorrelation.
 *
 * The normalised square difference function (NSDF) gives a clarity value in 0..1 that
 * we use, together with the input level, to implement the "sensitivity" setting.
 */
class PitchDetector(private val sampleRate: Int, val windowSize: Int) {

    class Result {
        var frequency = 0f
        var clarity = 0f
    }

    private val fftSize = windowSize * 2
    private val fft = Fft(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val nsdf = FloatArray(windowSize / 2 + 2)
    private val peaks = IntArray(256)
    private val result = Result()

    /** Peaks within this fraction of the highest one are candidates; the first wins. */
    var peakThreshold = 0.90f

    /** Returns null when no periodic signal was found in [fMin, fMax]. Not thread-safe. */
    fun detect(x: FloatArray, fMin: Float, fMax: Float): Result? {
        val n = windowSize
        val maxTau = min(n / 2, (sampleRate / fMin).toInt() + 2)
        val minTau = max(2, (sampleRate / fMax).toInt() - 1)

        // Autocorrelation r(tau) via |FFT|^2 -> IFFT (zero padded to 2N: no wrap-around).
        System.arraycopy(x, 0, re, 0, n)
        java.util.Arrays.fill(re, n, fftSize, 0f)
        java.util.Arrays.fill(im, 0f)
        fft.transform(re, im, inverse = false)
        for (i in 0 until fftSize) {
            re[i] = re[i] * re[i] + im[i] * im[i]
            im[i] = 0f
        }
        fft.transform(re, im, inverse = true)

        // m(tau) = sum x_j^2 + x_{j+tau}^2, updated incrementally.
        var m = 0.0
        for (i in 0 until n) m += 2.0 * x[i] * x[i]
        if (m < 1e-9) return null
        nsdf[0] = 1f
        val lastTau = n / 2 - 1
        for (tau in 1..lastTau) {
            val a = x[tau - 1]
            val b = x[n - tau]
            m -= (a * a + b * b).toDouble()
            nsdf[tau] = if (m > 1e-9) (2.0 * re[tau] / m).toFloat() else 0f
        }

        // Skip the lobe around tau = 0, then collect the highest point of each positive lobe.
        var tau = 1
        while (tau < maxTau && nsdf[tau] > 0f) tau++
        var peakCount = 0
        var highest = 0f
        while (tau < maxTau && peakCount < peaks.size) {
            while (tau < maxTau && nsdf[tau] <= 0f) tau++
            if (tau >= maxTau) break
            var best = tau
            while (tau < maxTau && nsdf[tau] > 0f) {
                if (nsdf[tau] > nsdf[best]) best = tau
                tau++
            }
            if (best >= minTau) {
                peaks[peakCount++] = best
                if (nsdf[best] > highest) highest = nsdf[best]
            }
        }
        if (peakCount == 0 || highest <= 0f) return null

        val limit = highest * peakThreshold
        var chosen = peaks[0]
        for (i in 0 until peakCount) {
            if (nsdf[peaks[i]] >= limit) {
                chosen = peaks[i]; break
            }
        }

        // Parabolic interpolation for sub-sample period accuracy (this is what gives 0.1 cent).
        var period = chosen.toFloat()
        var clarity = nsdf[chosen]
        if (chosen in 1 until maxTau) {
            val l = nsdf[chosen - 1]
            val c = nsdf[chosen]
            val r = nsdf[chosen + 1]
            val denom = l - 2f * c + r
            if (denom < 0f) {
                val shift = 0.5f * (l - r) / denom
                if (shift > -1f && shift < 1f) {
                    period += shift
                    clarity = c - 0.25f * (l - r) * shift
                }
            }
        }
        // Short periods (high notes): measure k periods at once and divide, which cuts the
        // interpolation error by ~k. Matters for the top piano octave (C8 = 11.5 samples).
        val k = minOf(16, ((n / 4) / period).toInt())
        if (k >= 2) {
            val centre = Math.round(period * k)
            var best = centre
            for (t in centre - 2..centre + 2) if (t in 1 until lastTau && nsdf[t] > nsdf[best]) best = t
            if (best in 1 until lastTau && nsdf[best] > 0.5f * clarity) {
                val l = nsdf[best - 1]; val c = nsdf[best]; val r = nsdf[best + 1]
                val denom = l - 2f * c + r
                val shift = if (denom < 0f) (0.5f * (l - r) / denom).coerceIn(-1f, 1f) else 0f
                period = (best + shift) / k
            }
        }
        val f = sampleRate / period
        if (f < fMin || f > fMax) return null
        result.frequency = f
        result.clarity = min(1f, clarity)
        return result
    }
}

/** Minimal in-place iterative radix-2 complex FFT. */
class Fft(private val size: Int) {
    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosT = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val rev = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1f else -1f
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosT[k]
                    val s = sign * sinT[k]
                    val l = j + half
                    val tre = re[l] * c - im[l] * s
                    val tim = re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val scale = 1f / size
            for (i in 0 until size) {
                re[i] *= scale; im[i] *= scale
            }
        }
    }
}
