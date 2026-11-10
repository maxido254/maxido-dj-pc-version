// DjApp audio engine: 4 decks (the phone app uses 2), 4 stems each, 32-pad sampler, per-deck DJ filter / phaser / reverb,
// loops, jog (scratch), master look-ahead limiter, WAV recorder.
#include "dsp.h"
#include "stretch.h"
#include "platform.h"
#ifdef __ANDROID__
#include <jni.h>
#include <oboe/Oboe.h>
#else
#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#include "miniaudio.h"
#endif
#include <atomic>
#include <mutex>
#include <thread>
#include <vector>
#include <memory>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <algorithm>

namespace {

constexpr int CHUNK = 256;
constexpr float kPi = 3.14159265358979f;

inline int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

// ---------------------------------------------------------------- DJ filter
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1[2] = {0, 0}, z2[2] = {0, 0};
    void set(bool low, float fc, float q, float fs) {
        fc = std::max(20.f, std::min(fc, fs * 0.45f));
        float w = 2 * kPi * fc / fs, c = cosf(w), al = sinf(w) / (2 * q), a0 = 1 + al;
        if (low) { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; }
        else     { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; }
        b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0;
    }
    inline float p(int ch, float x) {
        x += 1e-18f;  // anti-denormal
        float y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch];
        z2[ch] = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0; }
};

// One knob: left of centre = low-pass sweeping down, right = high-pass sweeping up. 24 dB/oct.
struct DjFilter {
    Biquad s1, s2;
    float cur = 0;
    bool lastLow = true;
    void process(float *l, float *r, int m, float target, float fs) {
        cur += (target - cur) * 0.25f;
        if (fabsf(cur) < 0.02f) { s1.reset(); s2.reset(); return; }
        bool low = cur < 0;
        if (low != lastLow) { s1.reset(); s2.reset(); lastLow = low; }
        float a = fabsf(cur);
        float fc = low ? 20000.f * powf(0.004f, a) : 20.f * powf(400.f, a);
        s1.set(low, fc, 0.54f, fs);
        s2.set(low, fc, 1.31f, fs);
        for (int i = 0; i < m; i++) {
            l[i] = s2.p(0, s1.p(0, l[i]));
            r[i] = s2.p(1, s1.p(1, r[i]));
        }
    }
};

// ---------------------------------------------------------------- Phaser
// 6 all-pass stages swept by a sine LFO (L/R 90 degrees apart for width), with feedback.
struct Phaser {
    float z[2][6] = {};
    float last[2] = {0, 0};
    float lfo = 0;
    float a0c[2] = {0.f, 0.f};
    void reset() { memset(z, 0, sizeof z); last[0] = last[1] = 0; }
    void process(float *l, float *r, int m, float amt, float rate, float fs) {
        if (amt < 0.005f) { reset(); return; }
        const float mixW = 0.5f * amt, fb = 0.55f;
        // The sweep (cos / pow / tan) is evaluated every 8 samples instead of every sample: same sound, far less CPU.
        float coef[2] = {a0c[0], a0c[1]};
        for (int i = 0; i < m; i++) {
            lfo += rate / fs; if (lfo >= 1.f) lfo -= 1.f;
            if ((i & 7) == 0) {
                for (int ch = 0; ch < 2; ch++) {
                    float ph = lfo + (ch ? 0.25f : 0.f); if (ph >= 1.f) ph -= 1.f;
                    float s = 0.5f - 0.5f * cosf(2 * kPi * ph);
                    float f = 250.f * powf(12.f, s);                 // 250 Hz .. 3 kHz
                    float t = tanf(kPi * f / fs);
                    coef[ch] = (t - 1.f) / (t + 1.f);
                }
            }
            for (int ch = 0; ch < 2; ch++) {
                const float a = coef[ch];
                float x = ch ? r[i] : l[i];
                float y = x + fb * last[ch];
                for (int st = 0; st < 6; st++) {
                    float o = a * y + z[ch][st];
                    z[ch][st] = y - a * o;
                    y = o;
                }
                last[ch] = y;
                (ch ? r[i] : l[i]) = x * (1.f - mixW) + y * mixW;
            }
        }
        a0c[0] = coef[0]; a0c[1] = coef[1];
    }
};

// ---------------------------------------------------------------- Reverb (Freeverb)
struct Reverb {
    static constexpr int NC = 8, NA = 4;
    std::vector<float> cb[2][NC], ab[2][NA];
    int ci[2][NC] = {}, ai[2][NA] = {};
    float cs[2][NC] = {};
    bool cleared = true;

    void init(float fs) {
        static const int ct[NC] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int at[NA] = {556, 441, 341, 225};
        float sc = fs / 44100.f;
        for (int ch = 0; ch < 2; ch++) {
            int spread = ch ? 23 : 0;
            for (int i = 0; i < NC; i++) cb[ch][i].assign(std::max(1, (int)((ct[i] + spread) * sc)), 0.f);
            for (int i = 0; i < NA; i++) ab[ch][i].assign(std::max(1, (int)((at[i] + spread) * sc)), 0.f);
        }
        clear();
    }
    void clear() {
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < NC; i++) { std::fill(cb[ch][i].begin(), cb[ch][i].end(), 0.f); ci[ch][i] = 0; cs[ch][i] = 0; }
            for (int i = 0; i < NA; i++) { std::fill(ab[ch][i].begin(), ab[ch][i].end(), 0.f); ai[ch][i] = 0; }
        }
        cleared = true;
    }
    void process(float *l, float *r, int m, float wet, float room) {
        if (wet < 0.002f) { if (!cleared) clear(); return; }
        cleared = false;
        const float fb = room * 0.28f + 0.7f, damp = 0.35f;
        const float dry = 1.f - 0.3f * wet, g = wet * 1.8f;
        for (int i = 0; i < m; i++) {
            float in = (l[i] + r[i]) * 0.015f + 1e-18f;
            float o[2] = {0, 0};
            for (int ch = 0; ch < 2; ch++) {
                for (int c = 0; c < NC; c++) {
                    std::vector<float> &b = cb[ch][c];
                    int &ix = ci[ch][c];
                    float y = b[ix];
                    cs[ch][c] = y * (1 - damp) + cs[ch][c] * damp;
                    b[ix] = in + cs[ch][c] * fb;
                    if (++ix >= (int)b.size()) ix = 0;
                    o[ch] += y;
                }
                for (int a = 0; a < NA; a++) {
                    std::vector<float> &b = ab[ch][a];
                    int &ix = ai[ch][a];
                    float bo = b[ix], t = o[ch];
                    o[ch] = -t + bo;
                    b[ix] = t + bo * 0.5f;
                    if (++ix >= (int)b.size()) ix = 0;
                }
            }
            l[i] = l[i] * dry + o[0] * g;
            r[i] = r[i] * dry + o[1] * g;
        }
    }
};

// ---------------------------------------------------------------- Stem effects (one per bus: the vocal stem, the instrumental stems)
// Echo: a feedback delay locked to a time the UI sets (one beat). Reverb: the Freeverb above. Both only run while on (plus their tail).
struct StemFx {
    std::atomic<float> echoAmt{0.f}, revAmt{0.f}, echoSec{0.4f};
    std::vector<float> dl, dr;
    int w = 0;
    float g = 0.f, rg = 0.f;
    int idleE = 1 << 30;
    Reverb rev;

    void init(float sr) {
        const size_t n = (size_t)(sr * 2.5f) + 8;
        dl.assign(n, 0.f); dr.assign(n, 0.f);
        w = 0; g = 0.f; rg = 0.f; idleE = 1 << 30;
        rev.init(sr);
    }
    void process(float *l, float *r, int m, float sr) {
        const float ea = echoAmt.load(), ra = revAmt.load();
        if (!dl.empty()) {
            const int thr = (int)(sr * 4.f);
            if (ea > 0.001f) {
                if (idleE >= thr) { std::fill(dl.begin(), dl.end(), 0.f); std::fill(dr.begin(), dr.end(), 0.f); }   // stale echoes from last time
                idleE = 0;
            } else if (idleE < thr) idleE += m;
            if (idleE < thr) {
                const int N = (int)dl.size();
                int d = (int)(echoSec.load() * sr);
                d = std::max(64, std::min(N - 2, d));
                for (int i = 0; i < m; i++) {
                    g += (ea - g) * 0.002f;
                    int rp = w - d; if (rp < 0) rp += N;
                    const float yl = dl[rp], yr = dr[rp];
                    dl[w] = l[i] + yl * 0.5f; dr[w] = r[i] + yr * 0.5f;
                    l[i] += g * yl; r[i] += g * yr;
                    if (++w >= N) w = 0;
                }
            }
        }
        rg += (ra - rg) * 0.15f;                     // glide, so switching it off lets the tail fade instead of cutting
        rev.process(l, r, m, rg < 0.002f ? 0.f : rg, 0.8f);
    }
};

using dsp::Eq;
using dsp::Limiter;
using dsp::BassAmp;
using dsp::Declick;

// ---------------------------------------------------------------- Deck
struct Deck {
    std::mutex lock;                       // audio thread only try_locks it
    const int16_t *stem[4] = {nullptr, nullptr, nullptr, nullptr};
    size_t mapBytes[4] = {0, 0, 0, 0};
    int nStems = 0;                        // 1 = full mix, 4 = stems
    int64_t frames = 0;
    int fileRate = 44100;
    double pos = 0;                        // audio thread only (use seekReq from outside)
    float rate = 0;                        // current smoothed playback rate (1 = normal)
    float stemCur[4] = {1, 1, 1, 1};
    float lastGain = 0;

    std::atomic<bool> playing{false}, jogActive{false}, loopOn{false};
    std::atomic<float> volume{1.f}, tempo{1.f}, jogRate{0.f};
    std::atomic<float> filter{0.f}, revWet{0.f}, revRoom{0.6f}, phAmt{0.f}, phRate{0.5f};
    std::atomic<float> stemVol[4];
    std::atomic<double> posOut{0}, seekReq{-1.0}, loopIn{0}, loopOut{0};
    std::atomic<int64_t> jogStamp{0};
    std::atomic<bool> keylock{false};
    std::atomic<double> jogTarget{0};      // scratch: track position (seconds) the platter is held at
    StretchV st; bool stActive = false;    // key-lock stretcher (audio thread only); two streams: instrumental + vocal
    StemFx fxV, fxI;                       // stem effects on the vocal bus and on the instrumental bus
    float gcur[4] = {1, 1, 1, 1};

    // Stem swap: the vocal stem is replaced by a saved vocal clip that cycles with the deck's own track position.
    std::atomic<int> swapSlot{-1};
    std::atomic<double> swapAnchor{0}, swapCyc{0};   // track frames: where the clip starts / length of one cycle
    float swCur = 0, swG = 0;                        // vocal crossfade original <-> clip (audio thread only)
    std::atomic<int> swapGen{0};                     // bumped by setSwap so the clip player restarts cleanly
    Stretch swSt;                                    // clip player: keeps the clip's ORIGINAL pitch, only its speed follows the deck
    bool swLive = false, swPrevPlay = false;         // audio thread only
    int swGen = 0;
    float swVol = 1.f;

    std::atomic<float> eqDb[3];
    Eq eq;
    Declick dc;                            // smooths the click when the playhead jumps (seek / cue / hot cue)
    DjFilter filt; Phaser phaser; Reverb reverb;
    Deck() { for (auto &v : stemVol) v = 1.f; for (auto &e : eqDb) e = 0.f; }
};

// ---------------------------------------------------------------- Sampler voice (one per pad)
constexpr int NSAMPLES = 32;
struct Voice {
    std::mutex lock;                       // audio thread only try_locks it
    const int16_t *data = nullptr;
    size_t bytes = 0;
    int64_t frames = 0;
    int rate = 44100;
    double pos = 0;
    float env = 0;
    bool gate = false, active = false;     // gate = key held / sound should sound; active = still rendering (release tail)
    std::atomic<int> cmd{0};               // 1 = trigger, 2 = stop
    std::atomic<bool> loop{false}, playingOut{false};
};

// ---------------------------------------------------------------- Stem-swap vocal clips (32 slots = 8 pages x 4 pads, shared by both decks)
constexpr int NSWAP = 32;
struct SwapClip {
    std::mutex lock;                       // audio thread only try_locks it
    std::vector<int16_t> data;             // stereo interleaved
    int64_t frames = 0;
};
static SwapClip gSwap[NSWAP];

// ---------------------------------------------------------------- Recorder
struct Recorder {
    std::atomic<bool> active{false}, running{false};
    std::vector<float> ring;
    std::atomic<size_t> head{0}, tail{0};
    std::thread th;
    FILE *f = nullptr;
    uint32_t dataBytes = 0;

    static void w32(FILE *f, uint32_t v) { fwrite(&v, 4, 1, f); }
    static void w16(FILE *f, uint16_t v) { fwrite(&v, 2, 1, f); }
    void header(int rate) {
        fwrite("RIFF", 1, 4, f); w32(f, 36); fwrite("WAVEfmt ", 1, 8, f);
        w32(f, 16); w16(f, 1); w16(f, 2); w32(f, rate); w32(f, rate * 4); w16(f, 4); w16(f, 16);
        fwrite("data", 1, 4, f); w32(f, 0);
    }
    bool start(const char *path, int rate) {
        if (active.load()) return false;
        f = fopen(path, "wb");
        if (!f) return false;
        header(rate);
        dataBytes = 0;
        ring.assign(1 << 19, 0.f);
        head = 0; tail = 0;
        running = true;
        th = std::thread(&Recorder::run, this);
        active.store(true, std::memory_order_release);
        return true;
    }
    void push(const float *in, int frames) {      // audio thread
        if (!active.load(std::memory_order_acquire)) return;
        size_t cap = ring.size(), h = head.load(std::memory_order_relaxed);
        size_t t = tail.load(std::memory_order_acquire), need = (size_t)frames * 2;
        if (cap - (h - t) < need) return;         // writer too slow: drop
        for (size_t i = 0; i < need; i++) ring[(h + i) & (cap - 1)] = in[i];
        head.store(h + need, std::memory_order_release);
    }
    void run() {
        std::vector<int16_t> tmp(8192);
        size_t cap = ring.size();
        while (true) {
            size_t h = head.load(std::memory_order_acquire), t = tail.load(std::memory_order_relaxed);
            size_t avail = h - t;
            if (avail == 0) {
                if (!running.load()) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(15));
                continue;
            }
            size_t cnt = std::min(avail, tmp.size());
            for (size_t i = 0; i < cnt; i++) {
                float v = ring[(t + i) & (cap - 1)];
                v = std::max(-1.f, std::min(1.f, v));
                tmp[i] = (int16_t)(v * 32767.f);
            }
            fwrite(tmp.data(), 2, cnt, f);
            dataBytes += (uint32_t)(cnt * 2);
            tail.store(t + cnt, std::memory_order_release);
        }
    }
    void stop() {
        if (!active.load()) return;
        active.store(false, std::memory_order_release);
        running = false;
        if (th.joinable()) th.join();
        fseek(f, 4, SEEK_SET); w32(f, 36 + dataBytes);
        fseek(f, 40, SEEK_SET); w32(f, dataBytes);
        fclose(f); f = nullptr;
    }
};

// ---------------------------------------------------------------- Master tap (desktop video recording)
// Lock-free ring that hands the finished master output (float stereo) to another thread, which feeds it to the video encoder
// together with the picture. Same idea as Recorder but nobody writes a file here.
struct Tap {
    std::atomic<bool> active{false};
    std::vector<float> ring;
    std::atomic<size_t> head{0}, tail{0};
    void start() { active.store(false); ring.assign(1 << 20, 0.f); head = 0; tail = 0; active.store(true, std::memory_order_release); }
    void stop() { active.store(false, std::memory_order_release); }
    void push(const float *in, int frames) {          // audio thread
        if (!active.load(std::memory_order_acquire)) return;
        const size_t cap = ring.size(), h = head.load(std::memory_order_relaxed), t = tail.load(std::memory_order_acquire), need = (size_t)frames * 2;
        if (cap - (h - t) < need) return;              // reader too slow: drop
        for (size_t i = 0; i < need; i++) ring[(h + i) & (cap - 1)] = in[i];
        head.store(h + need, std::memory_order_release);
    }
    int read(float *out, int maxFrames) {             // consumer thread
        const size_t cap = ring.size(), h = head.load(std::memory_order_acquire), t = tail.load(std::memory_order_relaxed);
        size_t n = std::min((size_t)maxFrames * 2, h - t) & ~(size_t)1;
        for (size_t i = 0; i < n; i++) out[i] = ring[(t + i) & (cap - 1)];
        tail.store(t + n, std::memory_order_release);
        return (int)(n / 2);
    }
};

// ---------------------------------------------------------------- Engine
#ifdef __ANDROID__
struct EngineBase : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {};
#else
struct EngineBase {};
#endif

class Engine : public EngineBase {
public:
    Deck decks[4];     // the phone app uses decks 0 and 1; the PC app also uses 2 and 3 (C and D)
    std::atomic<float> crossfader{0.5f}, level{0.f};
    Voice voices[NSAMPLES];
    std::atomic<float> sampVol{0.8f}, masterGain{1.f};
    std::atomic<int> xcurve{0};            // 0 smooth (constant power), 1 sharp (cut), 2 linear
#ifdef __ANDROID__
    std::shared_ptr<oboe::AudioStream> stream;
    int32_t lastXrun = 0;
#else
    ma_device device;
    bool devOpen = false;
#endif
    float srate = 44100.f;
    Limiter limiter;                       // master look-ahead limiter (replaces the old tanh soft clip)
    BassAmp bassAmp;                       // subsonic filter + harmonic bass, in front of the limiter
    std::atomic<float> bassAmt{0.5f};     // 0..1 harmonic bass amount (Settings > MIXER > BASS AMP)
    bool wanted = false;
    int audioMode = 0;                     // 0 fast, 1 balanced (budget phones), 2 safe (biggest buffer)
    Recorder rec;
    Tap tap;

#ifdef __ANDROID__
    bool openStream(oboe::SharingMode mode) {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
         ->setPerformanceMode(audioMode == 2 ? oboe::PerformanceMode::None : oboe::PerformanceMode::LowLatency)
         ->setSharingMode(mode)
         ->setFormat(oboe::AudioFormat::Float)
         ->setChannelCount(2)
         ->setUsage(oboe::Usage::Media)
         ->setDataCallback(std::shared_ptr<oboe::AudioStreamDataCallback>(this, [](oboe::AudioStreamDataCallback *) {}))
         ->setErrorCallback(std::shared_ptr<oboe::AudioStreamErrorCallback>(this, [](oboe::AudioStreamErrorCallback *) {}));
        return b.openStream(stream) == oboe::Result::OK;
    }

    bool start() {
        wanted = true;
        if (stream) return true;
        const bool ok = (audioMode == 0) ? (openStream(oboe::SharingMode::Exclusive) || openStream(oboe::SharingMode::Shared))
                                         : openStream(oboe::SharingMode::Shared);
        if (!ok) return false;
        srate = (float)stream->getSampleRate();
        limiter.init(srate);
        bassAmp.init(srate);
        for (auto &d : decks) { d.reverb.init(srate); d.fxV.init(srate); d.fxI.init(srate); d.phaser.reset(); d.filt.s1.reset(); d.filt.s2.reset(); }
        stream->setBufferSizeInFrames(stream->getFramesPerBurst() * (audioMode == 0 ? 3 : audioMode == 1 ? 6 : 16));
        lastXrun = 0;
        return stream->requestStart() == oboe::Result::OK;
    }
    bool setMode(int m) {
        audioMode = m;
        if (!stream) return true;
        stream->stop(); stream->close(); stream.reset();
        return start();
    }
    void stop() {
        wanted = false;
        rec.stop();
        if (stream) { stream->stop(); stream->close(); stream.reset(); }
    }
    // Headphones unplugged / route changed: reopen on the new device.
    void onErrorAfterClose(oboe::AudioStream *, oboe::Result) override {
        stream.reset();
        if (wanted) start();
    }

#else
    // ---- desktop backend (miniaudio: WASAPI on Windows, CoreAudio on macOS, ALSA/Pulse on Linux) ----
    static void dataCb(ma_device *dev, void *out, const void *, ma_uint32 n) {
        static_cast<Engine *>(dev->pUserData)->render(static_cast<float *>(out), (int)n);
    }
    bool openDevice() {
        ma_device_config c = ma_device_config_init(ma_device_type_playback);
        c.playback.format = ma_format_f32;
        c.playback.channels = 2;
        c.sampleRate = 0;                                   // the device's own rate: no extra resampling
        c.dataCallback = dataCb;
        c.pUserData = this;
        // audioMode: 0 fast (8 ms periods), 1 balanced (12 ms), 2 safe (30 ms); 3 periods each. WASAPI shared mode cannot go much lower.
        c.periodSizeInMilliseconds = audioMode == 0 ? 8 : audioMode == 1 ? 12 : 30;
        c.periods = 3;
        c.performanceProfile = ma_performance_profile_low_latency;
        if (ma_device_init(nullptr, &c, &device) != MA_SUCCESS) return false;
        devOpen = true;
        srate = (float)device.sampleRate;
        return true;
    }
    bool start() {
        wanted = true;
        if (devOpen) return true;
        if (!openDevice()) return false;
        limiter.init(srate); bassAmp.init(srate);
        for (auto &d : decks) { d.reverb.init(srate); d.fxV.init(srate); d.fxI.init(srate); d.phaser.reset(); d.filt.s1.reset(); d.filt.s2.reset(); }
        return ma_device_start(&device) == MA_SUCCESS;
    }
    bool setMode(int m) {
        audioMode = m;
        if (!devOpen) return true;
        ma_device_uninit(&device); devOpen = false;
        return start();
    }
    void stop() {
        wanted = false;
        rec.stop();
        if (devOpen) { ma_device_uninit(&device); devOpen = false; }
    }
#endif

    // Stem swap clip player. The clip always sounds in its own key: a separate WSOLA stretcher changes only its SPEED so
    // the loop fits the deck's beat grid and tempo. While the deck plays it is phase-locked to the deck's position;
    // while the deck is stopped it free-runs at the deck's tempo (so a swapped vocal can be heard / previewed on a stopped deck).
    // When the deck starts from a stop the clip restarts from its beginning at the play position.
    void renderSwapClip(Deck &k, const int16_t *d, int64_t n, double &anc, double cyc, bool playing, bool jog,
                        double step0, float *cl, float *cr, int m) {
        const float inv = 1.f / 32768.f;
        const double nd = (double)n;
        const int gen = k.swapGen.load();
        if (!k.swLive || gen != k.swGen) { k.swLive = true; k.swGen = gen; k.swSt.reset(0); k.swPrevPlay = playing; }
        if (playing && !k.swPrevPlay && !jog) { anc = k.pos; k.swapAnchor = anc; k.swSt.reset(0); }
        k.swPrevPlay = playing;
        if (jog) { for (int i = 0; i < m; i++) { cl[i] = 0.f; cr[i] = 0.f; } return; }   // scratching: the clip stays quiet
        const double cpf = nd / cyc;                    // clip frames per deck frame at tempo 1
        const double tempo = k.tempo.load();
        auto fetch = [&](int64_t st0, int cnt, float *l, float *r) {
            int64_t p = st0 % n; if (p < 0) p += n;
            for (int j = 0; j < cnt; j++) {
                l[j] = d[2 * p] * inv; r[j] = d[2 * p + 1] * inv;
                if (++p >= n) p = 0;
            }
        };
        for (int i = 0; i < m; i++) {
            while (k.swSt.needsHop()) {
                double alpha;
                if (playing) {
                    const double posNow = k.pos + (double)k.rate * step0 * i;
                    double want = std::fmod((posNow - anc) / cyc, 1.0); if (want < 0) want += 1.0;
                    want *= nd;
                    double cur = std::fmod(k.swSt.inPos, nd); if (cur < 0) cur += nd;
                    double err = want - cur; err -= nd * std::round(err / nd);
                    if (std::fabs(err) > 2048.0) { k.swSt.reset(want); err = 0; }
                    alpha = std::max(0.0, (double)k.rate) * cpf + std::max(-300.0, std::min(300.0, err)) * (0.25 / Stretch::HS);
                } else alpha = tempo * cpf;
                k.swSt.hop((float)std::max(0.0, alpha), fetch);
            }
            k.swSt.read(cl[i], cr[i]);
            k.swSt.rd += step0;
        }
    }

    void renderDeck(Deck &k, float xgain, int m, float *o) {
        float bl[CHUNK], br[CHUNK], vbl[CHUNK], vbr[CHUNK];     // bl / br: the deck (instrumental), vbl / vbr: the vocal stem on its own
        memset(bl, 0, sizeof(float) * m);
        memset(br, 0, sizeof(float) * m);
        memset(vbl, 0, sizeof(float) * m);
        memset(vbr, 0, sizeof(float) * m);

        std::unique_lock<std::mutex> g(k.lock, std::try_to_lock);
        bool have = g.owns_lock() && k.frames >= 2 && k.nStems > 0;
        if (!have && k.nStems <= 0) return;      // a deck that never had a track: nothing to mix, skip EQ / filter / reverb (less CPU, less heat)

        // Stem swap clip for this chunk (only on stem tracks; if the clip is being rewritten right now we just skip it).
        std::unique_lock<std::mutex> swg;
        const int16_t *swd = nullptr; int64_t swn = 0;
        double swAnc = 0, swCyc = 0;
        if (have && k.nStems == 4) {
            const int ss = k.swapSlot.load();
            swAnc = k.swapAnchor.load(); swCyc = k.swapCyc.load();
            if (ss >= 0 && ss < NSWAP && swCyc > 16) {
                swg = std::unique_lock<std::mutex>(gSwap[ss].lock, std::try_to_lock);
                if (swg.owns_lock() && gSwap[ss].frames > 8) { swd = gSwap[ss].data.data(); swn = gSwap[ss].frames; }
            }
        }
        const float swTarget = swd ? 1.f : 0.f;
        const float swFrom = k.swCur; k.swCur = swTarget;

        if (have) {
            double sr = k.seekReq.exchange(-1.0);
            if (sr >= 0) { k.pos = std::min(sr * k.fileRate, (double)(k.frames - 2)); k.stActive = false; k.dc.trigger(); }

            bool jog = k.jogActive.load();
            if (jog && nowMs() - k.jogStamp.load() > 1500) { jog = false; k.jogActive = false; }   // UI went away: let go
            const float tempo = k.tempo.load();
            const float target = jog ? 0.f : (k.playing.load() ? tempo : 0.f);
            const float dtl = fabsf(tempo - 1.f);
            const bool stretch = k.keylock.load() && !jog && (k.stActive ? dtl > 0.0015f : dtl > 0.004f);
            const float coef = 0.0015f;

            float s0[4], s1[4];
            for (int i = 0; i < 4; i++) { s0[i] = k.stemCur[i]; s1[i] = k.stemVol[i].load(); k.stemCur[i] = s1[i]; }

            const bool lo = k.loopOn.load();
            const double li = k.loopIn.load(), lout = k.loopOut.load();
            const double step0 = (double)k.fileRate / srate;
            const float inv = 1.f / 32768.f;

            // Stem swap clip for this chunk, rendered on its own (original pitch) and added after the stems below.
            float swL[CHUNK], swR[CHUNK];
            for (int i = 0; i < m; i++) { swL[i] = 0.f; swR[i] = 0.f; }
            const float swv0 = k.swVol, swv1 = s1[0]; k.swVol = swv1;
            if (swd) renderSwapClip(k, swd, swn, swAnc, swCyc, k.playing.load(), jog, step0, swL, swR, m);
            else k.swLive = false;

            if (stretch) {
                // ---- key lock: WSOLA on the stem-summed signal, tempo changes without pitch change
                if (!k.stActive) { k.st.resetAll(k.pos); k.stActive = true; for (int i = 0; i < 4; i++) k.gcur[i] = s1[i]; }
                auto fetch = [&](int64_t st0, int n, float *l, float *r, float *vl, float *vr) {
                    for (int j = 0; j < n; j++) { l[j] = 0.f; r[j] = 0.f; vl[j] = 0.f; vr[j] = 0.f; }
                    const int64_t a0 = std::max<int64_t>(st0, 0), b0 = std::min<int64_t>(st0 + n, k.frames);
                    if (b0 <= a0) return;
                    for (int si = 0; si < k.nStems; si++) {
                        const float g = k.gcur[si] * inv;
                        if (g < 0.0005f * inv) continue;
                        const int16_t *a = k.stem[si];
                        const bool voc = (si == 0 && k.nStems == 4);       // the vocal stem goes to its own stream (for the stem effects)
                        float *dl = voc ? vl : l, *dr = voc ? vr : r;
                        if (si == 0 && k.swG > 0.001f) {
                            const float w = 1.f - k.swG;      // the original vocal fades out; the clip is mixed in separately at its own pitch
                            for (int64_t p = a0; p < b0; p++) { dl[p - st0] += g * a[2 * p] * w; dr[p - st0] += g * a[2 * p + 1] * w; }
                        } else
                            for (int64_t p = a0; p < b0; p++) { dl[p - st0] += g * a[2 * p]; dr[p - st0] += g * a[2 * p + 1]; }
                    }
                };
                for (int i = 0; i < m; i++) {
                    k.rate += (target - k.rate) * coef;
                    if (fabsf(k.rate) < 1e-4f && target == 0.f) { k.rate = 0; k.stActive = false; continue; }
                    if (k.st.inPos >= (double)(k.frames - 2) && k.rate > 0) {
                        k.playing = false; k.rate = 0; k.stActive = false; k.pos = (double)(k.frames - 2); break;
                    }
                    while (k.st.needsHop()) {
                        for (int si = 0; si < 4; si++) k.gcur[si] += (k.stemVol[si].load() - k.gcur[si]) * 0.5f;
                        k.swG += (swTarget - k.swG) * 0.5f;
                        k.st.hopV(std::max(0.f, k.rate), fetch);
                        if (lo && lout > li && k.st.inPos >= lout) k.st.inPos -= (lout - li);
                    }
                    k.st.read(bl[i], br[i]);
                    k.st.readV(vbl[i], vbr[i]);
                    k.st.rd += step0;
                }
                if (k.stActive) k.pos = std::max(0.0, k.st.posAt());
            } else {
                k.stActive = false;
                // Scratch: the platter position is the master. The deck follows it with a short smoothing time,
                // so finger jitter between touch events never shows up as zipper noise, and holding still = silence.
                const double jogTgt = k.jogTarget.load() * k.fileRate;
                const double jogA = 1.0 / (0.018 * (double)srate);
                for (int i = 0; i < m; i++) {
                    float ga = 1.f;
                    if (jog) {
                        float want = (float)(((jogTgt - k.pos) * jogA) / step0);
                        want = std::max(-24.f, std::min(24.f, want));
                        k.rate += (want - k.rate) * 0.05f;
                        ga = std::min(1.f, fabsf(k.rate) * 6.f);
                    } else {
                        k.rate += (target - k.rate) * coef;
                        if (fabsf(k.rate) < 1e-4f && target == 0.f) { k.rate = 0; continue; }
                    }
                    if (k.pos < 0) { k.pos = 0; if (k.rate < 0) k.rate = 0; }
                    int64_t p = (int64_t)k.pos;
                    if (p + 1 >= k.frames) {
                        if (k.rate > 0) { k.playing = false; k.rate = 0; k.pos = (double)(k.frames - 2); }
                        continue;
                    }
                    float f = (float)(k.pos - (double)p), l = 0, r = 0, vl = 0, vr = 0;
                    float cw0, cw1, cw2, cw3; dsp::cubicW(f, cw0, cw1, cw2, cw3);    // 4-point cubic: keeps the highs at any speed
                    const int64_t pm = p > 0 ? -2 : 0;                               // frame before / after (clamped at the track ends)
                    const int64_t pn = (p + 2 < k.frames) ? 4 : 2;
                    float t = (float)(i + 1) / (float)m;
                    for (int si = 0; si < k.nStems; si++) {
                        float gs = s0[si] + (s1[si] - s0[si]) * t;
                        if (gs < 0.0005f) continue;
                        const int16_t *a = &k.stem[si][p * 2], *c = a + 2, *am = a + pm, *cn = a + pn;
                        float sl = cw0 * am[0] + cw1 * a[0] + cw2 * c[0] + cw3 * cn[0];
                        float sr2 = cw0 * am[1] + cw1 * a[1] + cw2 * c[1] + cw3 * cn[1];
                        if (si == 0 && (swd || swFrom > 0.001f)) {
                            const float w = swFrom + (swTarget - swFrom) * t;
                            sl *= (1.f - w); sr2 *= (1.f - w);   // the original vocal steps aside for the clip
                        }
                        if (si == 0 && k.nStems == 4) { vl += gs * sl; vr += gs * sr2; }    // vocal stem: own bus
                        else { l += gs * sl; r += gs * sr2; }
                    }
                    bl[i] = l * inv * ga; br[i] = r * inv * ga;
                    vbl[i] = vl * inv * ga; vbr[i] = vr * inv * ga;
                    k.pos += (double)k.rate * step0;
                    if (lo && k.rate > 0 && lout > li && k.pos >= lout) k.pos -= (lout - li);
                }
            }
            if (swd || swFrom > 0.001f) {
                for (int i = 0; i < m; i++) {
                    const float t = (float)(i + 1) / (float)m;
                    const float c = (swFrom + (swTarget - swFrom) * t) * (swv0 + (swv1 - swv0) * t);
                    bl[i] += swL[i] * c; br[i] += swR[i] * c;
                }
            }
            k.posOut = k.pos / k.fileRate;
        }

        // Stem effects, then the vocal joins the rest of the deck again.
        k.fxV.process(vbl, vbr, m, srate);
        k.fxI.process(bl, br, m, srate);
        for (int i = 0; i < m; i++) { bl[i] += vbl[i]; br[i] += vbr[i]; }

        k.dc.process(bl, br, m);

        // Effects always run (so reverb tails ring out after pause).
        { const float eg[3] = {k.eqDb[0].load(), k.eqDb[1].load(), k.eqDb[2].load()}; k.eq.process(bl, br, m, eg, srate); }
        k.filt.process(bl, br, m, k.filter.load(), srate);
        k.phaser.process(bl, br, m, k.phAmt.load(), k.phRate.load(), srate);
        k.reverb.process(bl, br, m, k.revWet.load(), k.revRoom.load());

        float tg = k.volume.load() * xgain, lg = k.lastGain;
        for (int i = 0; i < m; i++) {
            float gain = lg + (tg - lg) * (float)(i + 1) / (float)m;
            o[2 * i] += bl[i] * gain;
            o[2 * i + 1] += br[i] * gain;
        }
        k.lastGain = tg;
    }

    void renderSampler(int m, float *o) {
        const float sv = sampVol.load();
        const float inv = 1.f / 32768.f;
        for (auto &v : voices) {
            std::unique_lock<std::mutex> g(v.lock, std::try_to_lock);
            if (!g.owns_lock() || !v.data || v.frames < 2) continue;
            int c = v.cmd.exchange(0);
            if (c == 1) { v.pos = 0; v.active = true; v.gate = true; }
            else if (c == 2) v.gate = false;
            if (!v.active) { v.playingOut.store(false); continue; }
            const bool lp = v.loop.load();
            const double step = (double)v.rate / srate;
            for (int i = 0; i < m; i++) {
                if (v.gate) v.env = std::min(1.f, v.env + 1.f / 48.f);
                else { v.env -= 1.f / 480.f; if (v.env <= 0.f) { v.env = 0; v.active = false; break; } }
                int64_t p = (int64_t)v.pos;
                if (p + 1 >= v.frames) {
                    if (lp) { v.pos = 0; p = 0; }
                    else { v.active = false; v.gate = false; v.env = 0; break; }
                }
                float f = (float)(v.pos - (double)p);
                const int16_t *a = &v.data[p * 2], *b = a + 2;
                float gn = v.env * sv * inv;
                o[2 * i]     += gn * (a[0] + (b[0] - a[0]) * f);
                o[2 * i + 1] += gn * (a[1] + (b[1] - a[1]) * f);
                v.pos += step;
            }
            v.playingOut.store(v.active);
        }
    }

#ifdef __ANDROID__
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *data, int32_t n) override {
        auto xr = s->getXRunCount();
        if (xr && xr.value() > lastXrun) {   // grow buffer on underrun (weak CPUs)
            lastXrun = xr.value();
            s->setBufferSizeInFrames(s->getBufferSizeInFrames() + s->getFramesPerBurst());
        }
        render(static_cast<float *>(data), n);
        return oboe::DataCallbackResult::Continue;
    }
#endif

    // Fills `out` with n interleaved stereo float frames (the whole mixer: decks, sampler, master gain, bass amp, limiter).
    void render(float *out, int n) {
        memset(out, 0, sizeof(float) * 2 * n);
        float x = crossfader.load();
        float xg[2];
        switch (xcurve.load()) {
            case 1:  xg[0] = std::min(1.f, (1.f - x) * 8.f); xg[1] = std::min(1.f, x * 8.f); break;
            case 2:  xg[0] = 1.f - x; xg[1] = x; break;
            default: xg[0] = cosf(x * kPi / 2); xg[1] = sinf(x * kPi / 2); break;
        }
        const float mg = masterGain.load();
        float peak = 0;
        int done = 0;
        while (done < n) {
            int m = std::min(CHUNK, n - done);
            float *o = out + 2 * done;
            // A and C sit on the left side of the crossfader, B and D on the right side (an idle deck costs almost nothing)
            for (int di = 0; di < 4; di++) renderDeck(decks[di], xg[di & 1], m, o);
            renderSampler(m, o);
            for (int i = 0; i < 2 * m; i++) o[i] *= mg;
            bassAmp.process(o, m, bassAmt.load());
            limiter.process(o, m);
            for (int i = 0; i < 2 * m; i++) peak = std::max(peak, fabsf(o[i]));
            done += m;
        }
        level.store(std::max(peak, level.load() * 0.95f));
        rec.push(out, n);
        tap.push(out, n);
    }
};

Engine engine;

bool mapFile(const char *path, const int16_t *&out, size_t &bytes) {
    const void *m = nullptr; size_t sz = 0;
    if (!plat_map(path, m, sz)) return false;
    // No MAP_POPULATE: reading 4 whole stems (~170 MB per song) up front made loading slow and filled the phone's RAM,
    // after which Android threw the pages out again and the audio thread stalled on disk reads (= lag while playing stems).
    // Instead the first ~45 s are read ahead now; prefetchLoop() then reads the whole song in gently in the background.
    plat_advise(m, std::min<size_t>(sz, 8u << 20));
    out = (const int16_t *)m; bytes = sz;
    return true;
}

// Guards the stem mappings against the read-ahead thread (the audio thread never takes this lock).
std::mutex gMapMu;
void prefetchLoop() {
    plat_lowpri();                      // this thread only; never competes with the audio thread
    const int16_t *warmed[4] = {nullptr, nullptr, nullptr, nullptr};          // which mapping is being read in completely
    size_t warmOff[4] = {0, 0, 0, 0};
    for (;;) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        for (int di = 0; di < 4; di++) {
            Deck &k = engine.decks[di];
            std::lock_guard<std::mutex> g(gMapMu);
            if (k.nStems <= 0 || k.fileRate <= 0) { warmed[di] = nullptr; continue; }
            // 1) A newly loaded song is read in COMPLETELY, 4 MB per stem per tick (about 10 s for a whole song), so playing,
            //    seeking and jumping around never wait for the disk. It stays in the page cache, which Android can still
            //    reclaim, so it does not count against the app's own memory.
            if (warmed[di] != k.stem[0]) { warmed[di] = k.stem[0]; warmOff[di] = 0; }
            const size_t step = 4u << 20;                       // gentle: a slow phone's storage is shared with video and the app
            for (int i = 0; i < k.nStems; i++) {
                if (!k.stem[i] || warmOff[di] >= k.mapBytes[i]) continue;
                plat_advise((char *)k.stem[i] + warmOff[di], std::min(step, k.mapBytes[i] - warmOff[di]));
            }
            if (warmOff[di] < step * 256) warmOff[di] += step;
            // 2) If Android threw pages out meanwhile, the stretch around the playhead is read in again (24 s ahead).
            const double pos = k.posOut.load();
            int64_t a = (int64_t)(std::max(0.0, pos - 1.0) * k.fileRate) * 4;
            a &= ~(int64_t)4095;
            const size_t want = (size_t)(24.0 * k.fileRate) * 4;
            // The next ~6 s are not just hinted but actually touched, here on this thread, so any page that Android dropped
            // is back in memory before the audio thread gets there (a page fault on the audio thread is an audible glitch).
            const size_t touch = (size_t)(6.0 * k.fileRate) * 4;
            static volatile int sink;
            for (int i = 0; i < k.nStems; i++) {
                if (!k.stem[i] || (size_t)a >= k.mapBytes[i]) continue;
                plat_advise((char *)k.stem[i] + a, std::min(want, k.mapBytes[i] - (size_t)a));
                const size_t end = std::min((size_t)a + touch, k.mapBytes[i]);
                int acc = 0;
                for (size_t o = (size_t)a; o < end; o += 4096) acc += ((const volatile char *)k.stem[i])[o];
                sink = acc;
            }
        }
    }
}

// Mapping happens outside the lock; only the pointer swap is locked so audio never stalls.
void swapIn(Deck &k, const int16_t *p[4], size_t b[4], int n, int rate, bool resetPos) {
    int64_t frames = 0;
    if (n > 0) { frames = (int64_t)(b[0] / 4); for (int i = 1; i < n; i++) frames = std::min(frames, (int64_t)(b[i] / 4)); }
    static std::once_flag once;
    std::call_once(once, [] { std::thread(prefetchLoop).detach(); });
    std::lock_guard<std::mutex> gm(gMapMu);                // before k.lock: the audio thread never waits for the read-ahead thread
    const int16_t *oldP[4]; size_t oldB[4];                // old mappings are released AFTER the deck lock is dropped (no audio dropout)
    {
    std::lock_guard<std::mutex> g(k.lock);
    for (int i = 0; i < 4; i++) { oldP[i] = k.stem[i]; oldB[i] = k.mapBytes[i]; }
    for (int i = 0; i < 4; i++) { k.stem[i] = i < n ? p[i] : nullptr; k.mapBytes[i] = i < n ? b[i] : 0; }
    k.nStems = n; k.frames = frames;
    if (rate > 0) k.fileRate = rate;
    if (resetPos) {
        k.swapSlot = -1; k.swCur = 0; k.swG = 0; k.swLive = false;
        k.pos = 0; k.rate = 0; k.posOut = 0; k.seekReq = -1.0; k.loopOn = false; k.stActive = false;
        for (auto &v : k.stemVol) v = 1.f;
        for (auto &v : k.stemCur) v = 1.f;
    } else if (k.pos >= frames - 2) k.pos = std::max<int64_t>(0, frames - 2);
    }
    for (int i = 0; i < 4; i++) if (oldP[i]) plat_unmap(oldP[i], oldB[i]);
}

}  // namespace

#ifdef __ANDROID__
#define JNI(name) Java_com_example_djapp_NativeEngine_##name

extern "C" {
JNIEXPORT jboolean JNICALL JNI(start)(JNIEnv *, jobject) { return engine.start(); }
JNIEXPORT void JNICALL JNI(stop)(JNIEnv *, jobject) { engine.stop(); }

JNIEXPORT void JNICALL JNI(loadDeck)(JNIEnv *env, jobject, jint d, jstring path, jint rate) {
    Deck &k = engine.decks[d];
    k.playing = false; k.loopOn = false; k.jogActive = false;
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    const char *s = env->GetStringUTFChars(path, nullptr);
    int n = mapFile(s, p[0], b[0]) ? 1 : 0;
    env->ReleaseStringUTFChars(path, s);
    swapIn(k, p, b, n, rate, true);
}

JNIEXPORT jboolean JNICALL JNI(replaceDeck)(JNIEnv *env, jobject, jint d, jstring path, jint rate) {
    Deck &k = engine.decks[d];
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    const char *s = env->GetStringUTFChars(path, nullptr);
    bool ok = mapFile(s, p[0], b[0]);
    env->ReleaseStringUTFChars(path, s);
    if (!ok) return JNI_FALSE;
    swapIn(k, p, b, 1, rate, false);                      // keeps position, play state, loop and tempo
    return JNI_TRUE;
}

static bool mapStems(JNIEnv *env, jobjectArray paths, const int16_t *p[4], size_t b[4]) {
    bool ok = env->GetArrayLength(paths) == 4;
    for (int i = 0; i < 4 && ok; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(paths, i);
        const char *s = env->GetStringUTFChars(js, nullptr);
        ok = mapFile(s, p[i], b[i]);
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);
    }
    if (!ok) for (int i = 0; i < 4; i++) if (p[i]) { plat_unmap(p[i], b[i]); p[i] = nullptr; }
    return ok;
}

JNIEXPORT void JNICALL JNI(setStems)(JNIEnv *env, jobject, jint d, jobjectArray paths) {
    Deck &k = engine.decks[d];
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (!mapStems(env, paths, p, b)) return;
    swapIn(k, p, b, 4, 0, false);   // keeps position and keeps playing
}

// Loads a prepared track: the 4 stems are the track (no separate full-mix file, no waiting).
JNIEXPORT jboolean JNICALL JNI(loadDeckStems)(JNIEnv *env, jobject, jint d, jobjectArray paths, jint rate) {
    Deck &k = engine.decks[d];
    k.playing = false; k.loopOn = false; k.jogActive = false;
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (!mapStems(env, paths, p, b)) return JNI_FALSE;
    swapIn(k, p, b, 4, rate, true);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(setStemVolume)(JNIEnv *, jobject, jint d, jint s, jfloat v) { engine.decks[d].stemVol[s] = v; }
JNIEXPORT void JNICALL JNI(setPlaying)(JNIEnv *, jobject, jint d, jboolean p) { engine.decks[d].playing = p; }
JNIEXPORT jboolean JNICALL JNI(isPlaying)(JNIEnv *, jobject, jint d) { return engine.decks[d].playing.load(); }
JNIEXPORT void JNICALL JNI(setVolume)(JNIEnv *, jobject, jint d, jfloat v) { engine.decks[d].volume = v; }
JNIEXPORT void JNICALL JNI(setTempo)(JNIEnv *, jobject, jint d, jfloat t) { engine.decks[d].tempo = t; }
JNIEXPORT void JNICALL JNI(setCrossfader)(JNIEnv *, jobject, jfloat x) { engine.crossfader = x; }
JNIEXPORT jdouble JNICALL JNI(getPosition)(JNIEnv *, jobject, jint d) { return engine.decks[d].posOut.load(); }
JNIEXPORT jdouble JNICALL JNI(getDuration)(JNIEnv *, jobject, jint d) {
    Deck &k = engine.decks[d];
    return k.fileRate > 0 ? (double)k.frames / k.fileRate : 0.0;
}
JNIEXPORT void JNICALL JNI(seek)(JNIEnv *, jobject, jint d, jdouble sec) { engine.decks[d].seekReq = std::max(0.0, sec); }
JNIEXPORT jfloat JNICALL JNI(getLevel)(JNIEnv *, jobject) { return engine.level.load(); }

// Jog / scratch: while active the deck follows the platter position `targetSec` (forwards, backwards, held = silence).
JNIEXPORT void JNICALL JNI(setJog)(JNIEnv *, jobject, jint d, jboolean active, jdouble targetSec) {
    Deck &k = engine.decks[d];
    k.jogTarget = targetSec; k.jogStamp = nowMs(); k.jogActive = active;
}
JNIEXPORT void JNICALL JNI(setKeylock)(JNIEnv *, jobject, jint d, jboolean on) { engine.decks[d].keylock = on; }
JNIEXPORT jboolean JNICALL JNI(setAudioMode)(JNIEnv *, jobject, jint m) { return engine.setMode(m); }

// Loop points are in seconds of the track (not affected by tempo).
JNIEXPORT void JNICALL JNI(setLoop)(JNIEnv *, jobject, jint d, jdouble s, jdouble e, jboolean on) {
    Deck &k = engine.decks[d];
    k.loopOn = false;
    k.loopIn = s * k.fileRate; k.loopOut = e * k.fileRate;
    k.loopOn = (on && e > s);
}

// Effects (per deck)
JNIEXPORT void JNICALL JNI(setEq)(JNIEnv *, jobject, jint d, jint band, jfloat db) {
    if (band >= 0 && band < 3) engine.decks[d].eqDb[band] = std::max(-26.f, std::min(12.f, db));
}
JNIEXPORT void JNICALL JNI(setFilter)(JNIEnv *, jobject, jint d, jfloat v) { engine.decks[d].filter = v; }
JNIEXPORT void JNICALL JNI(setReverb)(JNIEnv *, jobject, jint d, jfloat wet, jfloat room) {
    engine.decks[d].revWet = wet; engine.decks[d].revRoom = room;
}
JNIEXPORT void JNICALL JNI(setPhaser)(JNIEnv *, jobject, jint d, jfloat amt, jfloat rateHz) {
    engine.decks[d].phAmt = amt; engine.decks[d].phRate = rateHz;
}

// Master recording to a 16-bit stereo WAV
JNIEXPORT jboolean JNICALL JNI(startRecording)(JNIEnv *env, jobject, jstring path) {
    const char *s = env->GetStringUTFChars(path, nullptr);
    bool ok = engine.rec.start(s, (int)engine.srate);
    env->ReleaseStringUTFChars(path, s);
    return ok;
}
JNIEXPORT void JNICALL JNI(stopRecording)(JNIEnv *, jobject) { engine.rec.stop(); }

// ---- Stem swap ----
// Copies [startSec, startSec+lenSec) of the deck's VOCAL stem into clip slot `slot`. Returns false if the deck has no stems.
JNIEXPORT jboolean JNICALL JNI(captureSwap)(JNIEnv *, jobject, jint d, jint slot, jdouble startSec, jdouble lenSec) {
    if (slot < 0 || slot >= NSWAP || d < 0 || d > 1) return JNI_FALSE;
    Deck &k = engine.decks[d];
    std::vector<int16_t> tmp;
    {
        std::lock_guard<std::mutex> g(k.lock);
        if (k.nStems != 4 || !k.stem[0] || k.fileRate <= 0) return JNI_FALSE;
        int64_t a = (int64_t)(startSec * k.fileRate), n = (int64_t)(lenSec * k.fileRate);
        if (a < 0) a = 0;
        if (n < 64 || a + n > k.frames) return JNI_FALSE;
        tmp.assign(k.stem[0] + a * 2, k.stem[0] + (a + n) * 2);
    }
    std::lock_guard<std::mutex> g(gSwap[slot].lock);
    gSwap[slot].data.swap(tmp);
    gSwap[slot].frames = (int64_t)(gSwap[slot].data.size() / 2);
    return JNI_TRUE;
}
JNIEXPORT void JNICALL JNI(clearSwap)(JNIEnv *, jobject, jint slot) {
    if (slot < 0 || slot >= NSWAP) return;
    for (auto &k : engine.decks) if (k.swapSlot.load() == slot) k.swapSlot = -1;
    std::lock_guard<std::mutex> g(gSwap[slot].lock);
    gSwap[slot].data.clear(); gSwap[slot].data.shrink_to_fit(); gSwap[slot].frames = 0;
}
// slot < 0 = off (the original vocal comes back).  anchorSec/cycleSec are in the deck's own track time.
JNIEXPORT void JNICALL JNI(setSwap)(JNIEnv *, jobject, jint d, jint slot, jdouble anchorSec, jdouble cycleSec) {
    if (d < 0 || d > 1) return;
    Deck &k = engine.decks[d];
    if (slot < 0 || slot >= NSWAP || gSwap[slot].frames <= 0) { k.swapSlot = -1; return; }
    k.swapAnchor = anchorSec * k.fileRate; k.swapCyc = cycleSec * k.fileRate;
    k.swapGen++;
    k.swapSlot = slot;
}
JNIEXPORT jboolean JNICALL JNI(saveSwap)(JNIEnv *env, jobject, jint slot, jstring path) {
    if (slot < 0 || slot >= NSWAP) return JNI_FALSE;
    const char *s = env->GetStringUTFChars(path, nullptr);
    bool ok = false;
    { std::lock_guard<std::mutex> g(gSwap[slot].lock);
      if (gSwap[slot].frames > 0) if (FILE *f = fopen(s, "wb")) {
          ok = fwrite(gSwap[slot].data.data(), 4, (size_t)gSwap[slot].frames, f) == (size_t)gSwap[slot].frames;
          fclose(f);
      } }
    env->ReleaseStringUTFChars(path, s);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL JNI(loadSwap)(JNIEnv *env, jobject, jint slot, jstring path) {
    if (slot < 0 || slot >= NSWAP) return JNI_FALSE;
    const char *s = env->GetStringUTFChars(path, nullptr);
    std::vector<int16_t> tmp; bool ok = false;
    if (FILE *f = fopen(s, "rb")) {
        fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
        if (sz >= 256 && sz % 4 == 0 && sz < 64L * 1024 * 1024) {
            tmp.resize((size_t)sz / 2);
            ok = fread(tmp.data(), 1, (size_t)sz, f) == (size_t)sz;
        }
        fclose(f);
    }
    env->ReleaseStringUTFChars(path, s);
    if (!ok) return JNI_FALSE;
    std::lock_guard<std::mutex> g(gSwap[slot].lock);
    gSwap[slot].data.swap(tmp);
    gSwap[slot].frames = (int64_t)(gSwap[slot].data.size() / 2);
    return JNI_TRUE;
}

// ---- Sampler (32 pad slots = 4 banks x 8) ----
JNIEXPORT jboolean JNICALL JNI(loadSample)(JNIEnv *env, jobject, jint slot, jstring path, jint rate) {
    if (slot < 0 || slot >= NSAMPLES) return JNI_FALSE;
    const int16_t *p = nullptr; size_t b = 0;
    const char *s = env->GetStringUTFChars(path, nullptr);
    bool ok = mapFile(s, p, b);
    env->ReleaseStringUTFChars(path, s);
    if (!ok) return JNI_FALSE;
    Voice &v = engine.voices[slot];
    std::lock_guard<std::mutex> g(v.lock);
    if (v.data) plat_unmap(v.data, v.bytes);
    v.data = p; v.bytes = b; v.frames = (int64_t)(b / 4); v.rate = rate > 0 ? rate : 44100;
    v.pos = 0; v.env = 0; v.active = false; v.gate = false; v.cmd = 0; v.playingOut = false;
    return JNI_TRUE;
}
JNIEXPORT void JNICALL JNI(clearSample)(JNIEnv *, jobject, jint slot) {
    if (slot < 0 || slot >= NSAMPLES) return;
    Voice &v = engine.voices[slot];
    std::lock_guard<std::mutex> g(v.lock);
    if (v.data) plat_unmap(v.data, v.bytes);
    v.data = nullptr; v.bytes = 0; v.frames = 0; v.active = false; v.gate = false; v.cmd = 0; v.playingOut = false;
}
JNIEXPORT void JNICALL JNI(triggerSample)(JNIEnv *, jobject, jint slot, jboolean loop) {
    if (slot < 0 || slot >= NSAMPLES) return;
    Voice &v = engine.voices[slot];
    v.loop = loop; v.cmd = 1; v.playingOut = true;
}
JNIEXPORT void JNICALL JNI(stopSample)(JNIEnv *, jobject, jint slot) {
    if (slot < 0 || slot >= NSAMPLES) return;
    engine.voices[slot].cmd = 2;
}
JNIEXPORT jboolean JNICALL JNI(isSamplePlaying)(JNIEnv *, jobject, jint slot) {
    return (slot >= 0 && slot < NSAMPLES) ? engine.voices[slot].playingOut.load() : JNI_FALSE;
}
JNIEXPORT void JNICALL JNI(setSampleVolume)(JNIEnv *, jobject, jfloat v) { engine.sampVol = v; }
JNIEXPORT void JNICALL JNI(setMasterGain)(JNIEnv *, jobject, jfloat v) { engine.masterGain = v; }
JNIEXPORT void JNICALL JNI(setBassAmp)(JNIEnv *, jobject, jfloat v) { engine.bassAmt = std::max(0.f, std::min(1.f, v)); }
JNIEXPORT void JNICALL JNI(setCrossfaderCurve)(JNIEnv *, jobject, jint c) { engine.xcurve = c; }
}
#else
#include "engine_desktop_api.inc"
#endif
