package com.example.djapp

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Constant-tempo beat grid: beats fall at offsetSec + k * 60 / bpm (track time). */
class BeatGrid(val bpm: Float, val offsetSec: Double)

/**
 * BPM + beat-grid detection over the WHOLE track (not a short slice).
 *
 * VirtualDJ and Serato use closed, proprietary detectors, so their exact code cannot be reused. This is the same
 * well-proven family that open DJ software (Mixxx, via the QM-DSP library) and research systems use:
 *  1. Onset detection function from a 1024-point STFT: complex-domain spectral difference (phase + magnitude)
 *     combined with log-magnitude spectral flux, plus a separate low-band (kick) function.
 *  2. Tempo from a comb-filter bank over the autocorrelation (Davies & Plumbley) with a Rayleigh tempo prior at 120 BPM,
 *     plus an octave fix that prefers the half tempo for 160-172 BPM picks when it scores nearly as high.
 *  3. Fine tempo: every tempo within +-2 BPM in 0.01 steps over the full track; the one that folds all onsets
 *     onto the sharpest beat phase wins. Near-integer results snap to the integer (like DJ software does).
 *  4. Beat phase from the folded kick-weighted onset histogram -> first beat offset.
 */
object BpmDetector {
    const val VERSION = 3          // bump when the algorithm changes: stored results of older versions are re-analysed

    private const val N = 1024
    private const val HOP = 512
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 175.0
    private const val MAX_ANALYSE_SEC = 480.0     // longer files (DJ mixes): analyse 8 minutes from the middle

    /** Anything that can hand out mono float samples by frame position. */
    private interface Mono { val frames: Long; fun read(start: Long, out: FloatArray, count: Int) }

    private class PcmMono(f: File) : Mono {
        private val raf = RandomAccessFile(f, "r")
        private var tmp = ByteArray(0)
        override val frames = f.length() / 4
        override fun read(start: Long, out: FloatArray, count: Int) {
            if (tmp.size < count * 4) tmp = ByteArray(count * 4)
            raf.seek(start * 4); raf.readFully(tmp, 0, count * 4)
            val sb = ByteBuffer.wrap(tmp, 0, count * 4).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (i in 0 until count) out[i] = (sb.get(2 * i) + sb.get(2 * i + 1)) * (0.5f / 32768f)
        }
        fun close() = raf.close()
    }

    /** Sum of several stem files (they add up to the original mix). */
    private class StemMono(files: List<File>) : Mono {
        private val parts = files.map { PcmMono(it) }
        private var t = FloatArray(0)
        override val frames = parts.minOf { it.frames }
        override fun read(start: Long, out: FloatArray, count: Int) {
            if (t.size < count) t = FloatArray(count)
            java.util.Arrays.fill(out, 0, count, 0f)
            for (p in parts) { p.read(start, t, count); for (i in 0 until count) out[i] += t[i] }
        }
        fun close() = parts.forEach { it.close() }
    }

    fun detect(pcm: File, rate: Int): Float = grid(pcm, rate)?.bpm ?: 0f

    /** [startSec]: track time of the first sample of [pcm] (when only a slice of the track was decoded). */
    fun grid(pcm: File, rate: Int, startSec: Double = 0.0): BeatGrid? = try {
        val m = PcmMono(pcm)
        try { analyze(m, rate, startSec) } finally { m.close() }
    } catch (_: Exception) { null }

    /** For prepared packs that only keep the 4 stems: the mix is rebuilt on the fly. */
    fun gridFromStems(paths: Array<String>, rate: Int): BeatGrid? = try {
        val m = StemMono(paths.map { File(it) })
        try { analyze(m, rate, 0.0) } finally { m.close() }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------------------------------------ FFT
    private val cosT = FloatArray(N / 2) { cos(2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { (-sin(2.0 * PI * it / N)).toFloat() }
    private val rev = IntArray(N).also { r ->
        val bits = Integer.numberOfTrailingZeros(N)
        for (i in 0 until N) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val hann = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / N)).toFloat() }

    private fun fft(re: FloatArray, im: FloatArray) {
        for (i in 0 until N) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= N) {
            val half = len / 2; val step = N / len
            var i = 0
            while (i < N) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }

    // ------------------------------------------------------------------------------------------------ analysis
    /** Half-wave rectified onset strength minus its local mean, scaled so loud and quiet tracks look alike. */
    private fun prep(a: FloatArray, envRate: Double): FloatArray? {
        val n = a.size
        val w = max(3, (0.4 * envRate).roundToInt())
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + a[i]
        val r = FloatArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - w / 2); val hi = min(n, i + w / 2 + 1)
            r[i] = max(0f, (a[i] - (pre[hi] - pre[lo]) / (hi - lo)).toFloat())
        }
        val sorted = r.copyOf(); sorted.sort()
        val p95 = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)]
        if (p95 < 1e-7f) return null                     // silence
        for (i in 0 until n) r[i] /= p95
        return r
    }

    private fun analyze(src: Mono, rate: Int, sliceStartSec: Double): BeatGrid? {
        val total = src.frames
        val maxFrames = (MAX_ANALYSE_SEC * rate).toLong()
        val start = if (total > maxFrames) (total - maxFrames) / 2 else 0L
        val count = min(total - start, maxFrames)
        if (count < rate * 8L + N) return null
        val n = ((count - N) / HOP).toInt()
        val nb = N / 2
        val hiBin = min(nb - 1, (8000.0 * N / rate).toInt())
        val lowBin = max(2, min(hiBin, (250.0 * N / rate).toInt()))

        val cs = FloatArray(n); val lf = FloatArray(n); val low = FloatArray(n)
        val re = FloatArray(N); val im = FloatArray(N)
        val pm = FloatArray(nb); val u1r = FloatArray(nb); val u1i = FloatArray(nb)
        val u2r = FloatArray(nb); val u2i = FloatArray(nb); val plg = FloatArray(nb)
        val buf = FloatArray(N + HOP * 128)
        var bufStart = -1L; var bufLen = 0
        for (t in 0 until n) {
            val s = start + t.toLong() * HOP
            if (bufStart < 0 || s < bufStart || s + N > bufStart + bufLen) {
                bufStart = s; bufLen = min(buf.size.toLong(), total - s).toInt()
                src.read(s, buf, bufLen)
            }
            val o = (s - bufStart).toInt()
            for (i in 0 until N) { re[i] = buf[o + i] * hann[i]; im[i] = 0f }
            fft(re, im)
            var dSum = 0f; var fSum = 0f; var lSum = 0f
            for (b in 1 until nb) {
                val xr = re[b]; val xi = im[b]
                val m = sqrt(xr * xr + xi * xi)
                val ur = xr / (m + 1e-9f); val ui = xi / (m + 1e-9f)
                val lg = ln(1f + 1000f * m / N)
                if (t >= 2 && b <= hiBin) {
                    // phase / magnitude prediction from the two previous frames
                    val a = u1r[b] * u1r[b] - u1i[b] * u1i[b]; val bb = 2f * u1r[b] * u1i[b]
                    val pr = (a * u2r[b] + bb * u2i[b]) * pm[b]; val pi = (bb * u2r[b] - a * u2i[b]) * pm[b]
                    dSum += sqrt((xr - pr) * (xr - pr) + (xi - pi) * (xi - pi))
                    val fl = max(0f, lg - plg[b])
                    fSum += fl; if (b <= lowBin) lSum += fl
                }
                u2r[b] = u1r[b]; u2i[b] = u1i[b]; u1r[b] = ur; u1i[b] = ui; pm[b] = m; plg[b] = lg
            }
            cs[t] = dSum; lf[t] = fSum; low[t] = lSum
        }

        val envRate = rate / HOP.toDouble()
        val a1 = prep(cs, envRate) ?: return null
        val a2 = prep(lf, envRate) ?: return null
        val lo = prep(low, envRate) ?: FloatArray(n)
        val onset = FloatArray(n) { a1[it] + a2[it] }
        val phaseOnset = FloatArray(n) { onset[it] + 1.5f * lo[it] }     // kick-weighted: the grid should sit on the kick

        // ---- tempo: autocorrelation + comb filter bank + Rayleigh prior (peak at 120 BPM)
        val minLag = floor(envRate * 60 / 190).toInt()
        val maxLag = ceil(envRate * 60 / 60).toInt()
        val L = maxLag * 4 + 8
        if (n < L * 3) return null
        var mean = 0.0; for (v in onset) mean += v; mean /= n
        val oc = FloatArray(n) { (onset[it] - mean).toFloat() }
        val ac = FloatArray(L)
        for (lag in 1 until L) {
            var s = 0.0
            for (i in 0 until n - lag) s += oc[i] * oc[i + lag]
            ac[lag] = (s / (n - lag)).toFloat()
        }
        val sc = DoubleArray(maxLag + 2)
        val bR = 0.5 * envRate
        var best = -1; var bestScore = 0.0
        for (lag in (minLag - 1)..(maxLag + 1)) {
            var s = 0.0
            for (a in 1..4) for (b in (1 - a) until a) {
                val i = a * lag + b
                if (i in 1 until L) s += ac[i] / (2 * a - 1)
            }
            s *= lag / (bR * bR) * exp(-lag.toDouble() * lag / (2 * bR * bR))
            sc[lag] = s
            if (lag in minLag..maxLag && s > bestScore) { bestScore = s; best = lag }
        }
        if (best < 0) return null
        // Octave fix: slow material (roots reggae, dub, half-time grooves) has offbeat hats/skank that make the
        // doubled tempo score almost as high as the real one. In the 160..172 BPM window (above hardstyle/trance,
        // below drum & bass) take the half tempo whenever it is nearly as strong.
        if (60.0 * envRate / best in 160.0..172.0) {
            val h = best * 2
            if (h + 1 <= maxLag + 1) {
                var hb = h; for (l in (h - 1)..(h + 1)) if (sc[l] > sc[hb]) hb = l
                if (hb in minLag..maxLag && sc[hb] >= 0.85 * bestScore) best = hb
            }
        }
        val y0 = sc[best - 1]; val y1 = sc[best]; val y2 = sc[best + 1]
        val den = y0 - 2 * y1 + y2
        val off = if (den != 0.0) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
        var coarse = 60.0 * envRate / (best + off)
        while (coarse < MIN_BPM) coarse *= 2.0
        while (coarse > MAX_BPM) coarse /= 2.0

        // ---- fine tempo: fold onsets at every candidate tempo; the sharpest histogram is the right tempo
        val P = 64
        val hist = DoubleArray(P)
        fun fold(bpm: Double, w: FloatArray): Double {
            val period = envRate * 60.0 / bpm
            java.util.Arrays.fill(hist, 0.0)
            for (i in 0 until n) {
                val o = w[i]
                if (o <= 0f) continue
                val ph = i / period
                val pos = (ph - floor(ph)) * P
                val b0 = pos.toInt() % P
                val fr = pos - floor(pos)
                hist[b0] += o * (1.0 - fr); hist[(b0 + 1) % P] += o * fr
            }
            var e = 0.0; for (h in hist) e += h * h
            return e
        }
        var bestE = -1.0; var bestBpm = coarse
        var cand = coarse - 2.0
        while (cand <= coarse + 2.0 + 1e-9) {
            val e = fold(cand, onset)
            if (e > bestE) { bestE = e; bestBpm = cand }
            cand += 0.01
        }
        // DJ software reports whole BPMs for (almost all) electronic music: snap when the fold agrees
        val snapped = bestBpm.roundToInt().toDouble()
        if (abs(bestBpm - snapped) < 0.06 && snapped in MIN_BPM..MAX_BPM) bestBpm = snapped

        // ---- beat phase from the kick-weighted fold
        fold(bestBpm, phaseOnset)
        var mx = -1.0; var mi = 0
        for (b in 0 until P) {
            val sB = hist[(b + P - 1) % P] + 2 * hist[b] + hist[(b + 1) % P]
            if (sB > mx) { mx = sB; mi = b }
        }
        val l = hist[(mi + P - 1) % P]; val c = hist[mi]; val r = hist[(mi + 1) % P]
        val phase = ((mi + (r - l) / (l + c + r + 1e-12)) / P).let { it - floor(it) }
        val periodSec = 60.0 / bestBpm
        val firstInSlice = phase * (envRate * 60.0 / bestBpm) / envRate + (N / 2.0) / rate   // frame centre
        var offset = (sliceStartSec + start.toDouble() / rate + firstInSlice) % periodSec
        if (offset < 0) offset += periodSec
        return BeatGrid((bestBpm * 100.0).roundToInt() / 100f, offset)
    }
}
