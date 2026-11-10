// Audio building blocks used by engine.cpp. Pure C++ (no Android), so they can be tested on a PC (see tools/dsp_test.cpp).
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace dsp {

// ---------------------------------------------------------------- 4-point cubic (Catmull-Rom) interpolation
// weights for the four samples x[-1], x[0], x[1], x[2] around a fractional position f in [0,1).
// Linear interpolation (2 samples) acts as a low-pass filter whose strength depends on f: at f = 0.5 it already loses
// 2.4 dB at 10 kHz and everything near Nyquist, and while scratching or resampling f is almost never 0. Cubic keeps the highs.
inline void cubicW(float f, float &w0, float &w1, float &w2, float &w3) {
    const float f2 = f * f, f3 = f2 * f;
    w0 = -0.5f * f3 + f2 - 0.5f * f;
    w1 = 1.5f * f3 - 2.5f * f2 + 1.f;
    w2 = -1.5f * f3 + 2.f * f2 + 0.5f * f;
    w3 = 0.5f * f3 - 0.5f * f2;
}

// ---------------------------------------------------------------- 3-band EQ (bass / mid / treble), -26 .. +12 dB
// Differences from the old version: biquads run in double precision (a 110 Hz shelf has poles very close to 1, where 32-bit
// float rounding adds noise), and a boost automatically lowers the level in front of the filters ("headroom"), because a
// +12 dB bass boost on a modern loud track otherwise goes straight into clipping.
struct Eq {
    struct Bq { double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1[2] = {0, 0}, z2[2] = {0, 0}; };
    Bq bq[3];
    float cur[3] = {0, 0, 0};              // smoothed gain in dB (smoothing avoids zipper noise while a fader moves)
    float applied[3] = {1e9f, 1e9f, 1e9f};
    float pre = 1.f;                       // headroom gain currently applied in front of the filters
    float headroom = 0.3f;                 // dB of level removed per dB of boost (0.3: +12 dB boost = -3.6 dB in front; the split limiter catches the rest)

    static void coefs(Bq &q, int band, float dbf, float srf) {
        const double db = dbf, sr = srf, pi = 3.14159265358979323846;
        const double A = std::pow(10.0, db / 40.0);
        const double f = band == 0 ? 110.0 : band == 1 ? 1000.0 : 5000.0;
        const double w0 = 2.0 * pi * f / sr, cs = std::cos(w0), sn = std::sin(w0);
        double b0, b1, b2, a0, a1, a2;
        if (band == 1) {                                   // peaking, Q 0.7
            const double al = sn / (2.0 * 0.7);
            b0 = 1 + al * A; b1 = -2 * cs; b2 = 1 - al * A; a0 = 1 + al / A; a1 = -2 * cs; a2 = 1 - al / A;
        } else {
            const double al = sn / 2.0 * 1.41421356237, t = 2.0 * std::sqrt(A) * al;
            if (band == 0) {                               // low shelf
                b0 = A * ((A + 1) - (A - 1) * cs + t); b1 = 2 * A * ((A - 1) - (A + 1) * cs); b2 = A * ((A + 1) - (A - 1) * cs - t);
                a0 = (A + 1) + (A - 1) * cs + t; a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - t;
            } else {                                       // high shelf
                b0 = A * ((A + 1) + (A - 1) * cs + t); b1 = -2 * A * ((A - 1) + (A + 1) * cs); b2 = A * ((A + 1) + (A - 1) * cs - t);
                a0 = (A + 1) - (A - 1) * cs + t; a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - t;
            }
        }
        q.b0 = b0 / a0; q.b1 = b1 / a0; q.b2 = b2 / a0; q.a1 = a1 / a0; q.a2 = a2 / a0;
    }

    void process(float *l, float *r, int m, const float target[3], float sr) {
        float boost = 0.f;
        for (int b = 0; b < 3; b++) {
            cur[b] += (target[b] - cur[b]) * 0.35f;
            if (std::fabs(target[b] - cur[b]) < 0.01f) cur[b] = target[b];
            boost = std::max(boost, cur[b]);
        }
        // headroom: smooth gain change across the chunk (no zipper noise)
        const float preT = boost > 0.05f ? std::pow(10.f, -headroom * boost / 20.f) : 1.f;
        if (preT != pre || pre != 1.f) {
            for (int i = 0; i < m; i++) {
                const float g = pre + (preT - pre) * (float)(i + 1) / (float)m;
                l[i] *= g; r[i] *= g;
            }
            pre = preT;
        }
        for (int b = 0; b < 3; b++) {
            if (std::fabs(cur[b]) < 0.05f) {               // flat: bypass, no CPU
                bq[b].z1[0] = bq[b].z1[1] = bq[b].z2[0] = bq[b].z2[1] = 0; applied[b] = 1e9f; continue;
            }
            if (std::fabs(cur[b] - applied[b]) > 0.01f) { coefs(bq[b], b, cur[b], sr); applied[b] = cur[b]; }
            Bq &q = bq[b];
            for (int i = 0; i < m; i++) {
                double x = l[i], y = q.b0 * x + q.z1[0];
                q.z1[0] = q.b1 * x - q.a1 * y + q.z2[0]; q.z2[0] = q.b2 * x - q.a2 * y; l[i] = (float)y;
                x = r[i]; y = q.b0 * x + q.z1[1];
                q.z1[1] = q.b1 * x - q.a1 * y + q.z2[1]; q.z2[1] = q.b2 * x - q.a2 * y; r[i] = (float)y;
            }
        }
    }
};

// ---------------------------------------------------------------- stereo biquad (double precision, RBJ cookbook)
// Low frequencies need double precision: a 24 Hz high-pass at 48 kHz has poles within 0.003 of the unit circle.
struct Bq2 {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1[2] = {0, 0}, z2[2] = {0, 0};
    void lowpass(double f, double sr, double Q = 0.70710678118) {
        const double w = 2.0 * 3.14159265358979323846 * f / sr, cs = std::cos(w), al = std::sin(w) / (2.0 * Q), a0 = 1 + al;
        b0 = (1 - cs) / 2 / a0; b1 = (1 - cs) / a0; b2 = b0; a1 = -2 * cs / a0; a2 = (1 - al) / a0;
    }
    void highpass(double f, double sr, double Q = 0.70710678118) {
        const double w = 2.0 * 3.14159265358979323846 * f / sr, cs = std::cos(w), al = std::sin(w) / (2.0 * Q), a0 = 1 + al;
        b0 = (1 + cs) / 2 / a0; b1 = -(1 + cs) / a0; b2 = b0; a1 = -2 * cs / a0; a2 = (1 - al) / a0;
    }
    void clear() { z1[0] = z1[1] = z2[0] = z2[1] = 0; }
    inline double run(int ch, double x) {
        const double y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch]; z2[ch] = b2 * x - a2 * y;
        return y;
    }
};

// ---------------------------------------------------------------- BASS AMP (master stage, in front of the limiter)
// What a good power amplifier + subwoofer does for bass, done in software:
//  1. SUBSONIC FILTER: 4th-order high-pass at 24 Hz. Rumble, DC and sub-20 Hz energy cannot be heard but eat limiter headroom
//     and over-drive small speakers; removing it makes the audible bass tighter and louder. 60 Hz is untouched (<0.1 dB).
//  2. HARMONIC BASS ("missing fundamental"): the 35-90 Hz band is driven through a level-independent saturator and only the
//     resulting harmonics (105-320 Hz) are mixed in. The ear hears the harmonics and infers the low note, so bass is
//     felt on phone, laptop and PA speakers that cannot reproduce 40 Hz, and it sounds fuller on big systems too.
//     The original low band is never touched, so no extra low-frequency energy is added: nothing clips because of it.
struct BassAmp {
    Bq2 sub[2];                                  // subsonic high-pass, 2 x 2nd-order = 4th-order Butterworth
    Bq2 srcHp, srcLp;                            // 35-90 Hz band that feeds the saturator
    Bq2 hHp[2], hLp;                             // harmonics-only band 105-320 Hz (4th-order up, 2nd-order down)
    double env = 0;                              // follower of the source band (peak, 4 ms attack / 80 ms release)
    double atk = 0, rel = 0;
    float amt = 0.f;                             // smoothed amount (full amount 1.0 puts the 3rd harmonic about 9 dB under the note)
    bool ready = false;

    void init(float srf) {
        const double sr = srf;
        sub[0].highpass(24.0, sr, 0.5411961); sub[1].highpass(24.0, sr, 1.3065630);   // Butterworth 4th-order Q pair
        srcHp.highpass(35.0, sr); srcLp.lowpass(90.0, sr);
        hHp[0].highpass(105.0, sr, 0.5411961); hHp[1].highpass(105.0, sr, 1.3065630);
        hLp.lowpass(320.0, sr);
        atk = 1.0 - std::exp(-1.0 / (0.004 * sr)); rel = 1.0 - std::exp(-1.0 / (0.08 * sr));
        reset(); ready = true;
    }
    void reset() {
        for (auto &b : sub) b.clear();
        srcHp.clear(); srcLp.clear(); hHp[0].clear(); hHp[1].clear(); hLp.clear();
        env = 0; amt = 0.f;
    }
    // interleaved stereo, in place. amount 0..1 (0 = harmonics off, subsonic filter stays on)
    void process(float *io, int frames, float amount) {
        if (!ready) return;
        const float amtT = std::max(0.f, std::min(1.f, amount));
        const float a0 = amt, da = (amtT - amt) / (float)std::max(1, frames);
        for (int i = 0; i < frames; i++) {
            double l = io[2 * i], r = io[2 * i + 1];
            l = sub[1].run(0, sub[0].run(0, l)); r = sub[1].run(1, sub[0].run(1, r));
            const float a = a0 + da * (float)(i + 1);
            if (a > 0.001f) {
                const double mono = 0.5 * (l + r);
                const double src = srcLp.run(0, srcHp.run(0, mono));
                const double mag = std::fabs(src);
                env += ((mag > env) ? atk : rel) * (mag - env);
                // level-independent drive: normalise, saturate (odd harmonics) with a small asymmetric term (even harmonics),
                // then restore the level. The gate stops hiss from being "found" in silence.
                const double n = src / std::max(env, 0.02);
                const double shaped = std::tanh(2.2 * n + 0.45 * n * n) * env;
                double h = hLp.run(0, hHp[1].run(0, hHp[0].run(0, shaped)));
                h *= 2.0 * (double)a;
                l += h; r += h;
            }
            io[2 * i] = (float)l; io[2 * i + 1] = (float)r;
        }
        amt = amtT;
    }
};

// ---------------------------------------------------------------- master limiter (look-ahead, ~1 ms)
// Replaces the old per-sample tanh soft clip. A clipper reshapes every peak, which on sustained bass sounds like fuzz.
// This turns the whole signal down BEFORE the peak arrives and brings it back up slowly (80 ms), so low notes keep their
// shape. Guarantee: output never exceeds `ceilingLevel` (shown by the tests).
struct Limiter {
    static constexpr int MAXL = 256;
    static constexpr int CAP = MAXL + 2;
    int L = 48;                            // look-ahead in samples (about 1 ms)
    float ceilingLevel = 0.95f;
    float rcBass = 0.0003f, rcHigh = 0.0003f;   // release coefficients: bass band 150 ms, everything above 35 ms
    float dly[MAXL][2];
    int dp = 0;
    long long qi[CAP]; float qv[CAP];
    int qh = 0, qn = 0;
    float ma[MAXL]; double maSum = 0; int mp = 0;
    long long n = 0;
    float gBass = 1.f, gHigh = 1.f;
    Bq2 xlo[2], xhi[2];                    // 150 Hz Linkwitz-Riley 4th-order crossover (low / high band)

    // Why two release times: one gain for everything makes a loud kick pull the hi-hats down with it and let them swell back
    // ("pumping"). Here the same attack protects both bands, but the highs recover quickly while the bass is let go slowly,
    // so bass notes keep their shape and the top end stays steady.
    void init(float sr) {
        L = std::max(8, std::min(MAXL - 1, (int)(0.001f * sr + 0.5f)));
        rcBass = 1.f - std::exp(-1.f / (0.15f * sr));
        rcHigh = 1.f - std::exp(-1.f / (0.035f * sr));
        // LR4 = two cascaded 2nd-order Butterworth sections per band; the two bands sum to an all-pass (flat level)
        xlo[0].lowpass(150.0, sr, 0.70710678); xlo[1].lowpass(150.0, sr, 0.70710678);
        xhi[0].highpass(150.0, sr, 0.70710678); xhi[1].highpass(150.0, sr, 0.70710678);
        reset();
    }
    void reset() {
        std::memset(dly, 0, sizeof dly);
        dp = 0; qh = 0; qn = 0; n = 0; gBass = 1.f; gHigh = 1.f; mp = 0;
        xlo[0].clear(); xlo[1].clear(); xhi[0].clear(); xhi[1].clear();
        for (int i = 0; i < MAXL; i++) ma[i] = 1.f;
        maSum = (double)L;
    }
    // interleaved stereo, in place
    void process(float *io, int frames) {
        const int D = L - 1;               // delay of the audio = look-ahead - 1
        for (int i = 0; i < frames; i++) {
            const float x0 = io[2 * i], x1 = io[2 * i + 1];
            const float peak = std::max(std::fabs(x0), std::fabs(x1));
            const float gt = peak > ceilingLevel ? ceilingLevel / peak : 1.f;
            // sliding minimum of gt over the last L samples (monotonic queue)
            while (qn > 0) {
                const int back = (qh + qn - 1) % CAP;
                if (qv[back] >= gt) qn--; else break;
            }
            const int pos = (qh + qn) % CAP;
            qi[pos] = n; qv[pos] = gt; qn++;
            while (qn > 0 && qi[qh] <= n - L) { qh = (qh + 1) % CAP; qn--; }
            const float mn = qv[qh];
            // moving average of that minimum over L samples: smooth attack that is finished when the peak arrives
            maSum += (double)mn - (double)ma[mp]; ma[mp] = mn; mp = (mp + 1) % L;
            const float gAvg = (float)(maSum / (double)L);
            // both gains fall at once (already smooth) but rise at their own speed; gHigh >= gBass always
            gBass = std::min(gAvg, gBass + (1.f - gBass) * rcBass);
            gHigh = std::min(gAvg, gHigh + (1.f - gHigh) * rcHigh);
            // delayed audio
            const double o0 = dly[dp][0], o1 = dly[dp][1];
            dly[dp][0] = x0; dly[dp][1] = x1;
            if (++dp >= D) dp = 0;
            // The crossover is always running (its state must stay current) but is only heard while the limiter is working:
            // the LR4 sum is an all-pass (flat level, bass phase turned by up to 180 deg), so when there is no gain reduction
            // the output is the untouched delayed signal and quiet music stays bit-transparent. The blend is smooth.
            const double lo0 = xlo[1].run(0, xlo[0].run(0, o0)), lo1 = xlo[1].run(1, xlo[0].run(1, o1));
            const double hi0 = xhi[1].run(0, xhi[0].run(0, o0)), hi1 = xhi[1].run(1, xhi[0].run(1, o1));
            const double act = 1.0 - std::min(gBass, gHigh);
            const double w = std::min(1.0, act * 30.0);
            const double s0 = lo0 * gBass + hi0 * gHigh, s1 = lo1 * gBass + hi1 * gHigh;
            const float y0 = (float)(o0 * (1.0 - w) * std::min(gBass, gHigh) + s0 * w), y1 = (float)(o1 * (1.0 - w) * std::min(gBass, gHigh) + s1 * w);
            // the two gains differ only while the highs recover ahead of the bass; the rare tiny overshoot is trimmed here
            io[2 * i] = std::max(-ceilingLevel, std::min(ceilingLevel, y0));
            io[2 * i + 1] = std::max(-ceilingLevel, std::min(ceilingLevel, y1));
            n++;
        }
    }
};

// ---------------------------------------------------------------- click smoother for position jumps (seek, cue, hot cue)
// When the playhead jumps, the output would step from the last old sample to the first new one: a click. We keep the output
// continuous and let the difference die away over ~4 ms, which turns the click into a soft thud nobody notices.
struct Declick {
    static constexpr int J = 192;
    float dL = 0, dR = 0, lastL = 0, lastR = 0;
    int left = 0;
    bool pending = false;
    void trigger() { pending = true; }
    void process(float *l, float *r, int m) {
        if (m <= 0) return;
        if (pending) { dL = lastL - l[0]; dR = lastR - r[0]; left = J; pending = false; }
        for (int i = 0; i < m && left > 0; i++, left--) {
            const float w = (float)left / (float)J;
            l[i] += dL * w; r[i] += dR * w;
        }
        lastL = l[m - 1]; lastR = r[m - 1];
    }
};

}  // namespace dsp
