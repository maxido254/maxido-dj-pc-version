// Plain C API of the engine for Windows / macOS / Linux / iOS (loaded from Kotlin with JNA, or from Swift).
// Mirrors the Android JNI functions one to one: dj_xxx(...) = NativeEngine.xxx(...). Included at the end of engine.cpp.
#ifdef _WIN32
#define DJ_API extern "C" __declspec(dllexport)
#else
#define DJ_API extern "C" __attribute__((visibility("default")))
#endif

DJ_API int  dj_start() { return engine.start(); }
DJ_API void dj_stop() { engine.stop(); }

static bool djMapStems(const char *const *paths, const int16_t *p[4], size_t b[4]) {
    bool ok = true;
    for (int i = 0; i < 4 && ok; i++) ok = mapFile(paths[i], p[i], b[i]);
    if (!ok) for (int i = 0; i < 4; i++) if (p[i]) { plat_unmap(p[i], b[i]); p[i] = nullptr; }
    return ok;
}

// ---- .mxs stem files: one file holds the 4 stems, each at a 64 KiB aligned offset; they are mapped straight from that file.
static bool djMapMxs(const char *path, const int64_t *off, const int64_t *len, const int16_t *p[4], size_t b[4]) {
    bool ok = true;
    for (int i = 0; i < 4 && ok; i++) {
        const void *m = nullptr;
        ok = off[i] > 0 && len[i] >= 8 && (off[i] % 65536) == 0 && plat_map_range(path, (uint64_t)off[i], (uint64_t)len[i], m);
        if (ok) { plat_advise(m, std::min<size_t>((size_t)len[i], 8u << 20)); p[i] = (const int16_t *)m; b[i] = (size_t)len[i]; }
    }
    if (!ok) for (int i = 0; i < 4; i++) if (p[i]) { plat_unmap(p[i], b[i]); p[i] = nullptr; }
    return ok;
}

// ---- decoding: any WAV / MP3 / FLAC to raw 16-bit stereo (the engine's track format) at the file's own sample rate.
// Returns the sample rate (> 0) or 0 on failure. Run it on a background thread; it takes about 1 s per 4 minutes of MP3.
DJ_API int dj_decode_to_raw(const char *inPath, const char *outPath) {
    ma_decoder_config cfg = ma_decoder_config_init(ma_format_s16, 2, 0);
    ma_decoder dec;
    if (ma_decoder_init_file(inPath, &cfg, &dec) != MA_SUCCESS) return 0;
    FILE *f = fopen(outPath, "wb");
    if (!f) { ma_decoder_uninit(&dec); return 0; }
    std::vector<int16_t> buf(4096 * 2);
    int rate = (int)dec.outputSampleRate;
    for (;;) {
        ma_uint64 got = 0;
        ma_result r = ma_decoder_read_pcm_frames(&dec, buf.data(), 4096, &got);
        if (got > 0) fwrite(buf.data(), 4, (size_t)got, f);
        if (r != MA_SUCCESS || got == 0) break;
    }
    fclose(f);
    ma_decoder_uninit(&dec);
    return rate;
}

DJ_API void dj_load_deck(int d, const char *path, int rate) {
    Deck &k = engine.decks[d];
    k.playing = false; k.loopOn = false; k.jogActive = false;
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    int n = mapFile(path, p[0], b[0]) ? 1 : 0;
    swapIn(k, p, b, n, rate, true);
}
DJ_API int dj_replace_deck(int d, const char *path, int rate) {
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (!mapFile(path, p[0], b[0])) return 0;
    swapIn(engine.decks[d], p, b, 1, rate, false);
    return 1;
}
DJ_API void dj_set_stems(int d, const char *const *paths) {
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (djMapStems(paths, p, b)) swapIn(engine.decks[d], p, b, 4, 0, false);
}
DJ_API void dj_set_stems_mxs(int d, const char *path, const int64_t *offsets, const int64_t *lengths) {
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (djMapMxs(path, offsets, lengths, p, b)) swapIn(engine.decks[d], p, b, 4, 0, false);
}
DJ_API int dj_load_deck_mxs(int d, const char *path, const int64_t *offsets, const int64_t *lengths, int rate) {
    Deck &k = engine.decks[d];
    k.playing = false; k.loopOn = false; k.jogActive = false;
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (!djMapMxs(path, offsets, lengths, p, b)) return 0;
    swapIn(k, p, b, 4, rate, true);
    return 1;
}
DJ_API int dj_load_deck_stems(int d, const char *const *paths, int rate) {
    Deck &k = engine.decks[d];
    k.playing = false; k.loopOn = false; k.jogActive = false;
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (!djMapStems(paths, p, b)) return 0;
    swapIn(k, p, b, 4, rate, true);
    return 1;
}

DJ_API void   dj_set_stem_volume(int d, int s, float v) { engine.decks[d].stemVol[s] = v; }
DJ_API void   dj_set_playing(int d, int p) { engine.decks[d].playing = p != 0; }
DJ_API int    dj_is_playing(int d) { return engine.decks[d].playing.load(); }
DJ_API void   dj_set_volume(int d, float v) { engine.decks[d].volume = v; }
DJ_API void   dj_set_tempo(int d, float t) { engine.decks[d].tempo = t; }
DJ_API void   dj_set_crossfader(float x) { engine.crossfader = x; }
DJ_API double dj_get_position(int d) { return engine.decks[d].posOut.load(); }
DJ_API double dj_get_duration(int d) { Deck &k = engine.decks[d]; return k.fileRate > 0 ? (double)k.frames / k.fileRate : 0.0; }
DJ_API void   dj_seek(int d, double sec) { engine.decks[d].seekReq = std::max(0.0, sec); }
DJ_API float  dj_get_level() { return engine.level.load(); }
DJ_API void   dj_set_jog(int d, int active, double targetSec) {
    Deck &k = engine.decks[d];
    k.jogTarget = targetSec; k.jogStamp = nowMs(); k.jogActive = active != 0;
}
DJ_API void   dj_set_keylock(int d, int on) { engine.decks[d].keylock = on != 0; }
DJ_API int    dj_set_audio_mode(int m) { return engine.setMode(m); }
DJ_API void   dj_set_loop(int d, double s, double e, int on) {
    Deck &k = engine.decks[d];
    k.loopOn = false;
    k.loopIn = s * k.fileRate; k.loopOut = e * k.fileRate;
    k.loopOn = (on && e > s);
}
DJ_API void   dj_set_eq(int d, int band, float db) { if (band >= 0 && band < 3) engine.decks[d].eqDb[band] = std::max(-26.f, std::min(12.f, db)); }
DJ_API void   dj_set_filter(int d, float v) { engine.decks[d].filter = v; }
DJ_API void   dj_set_reverb(int d, float wet, float room) { engine.decks[d].revWet = wet; engine.decks[d].revRoom = room; }
// Stem effects: bus 0 = vocal stem, 1 = instrumental stems; kind 0 = echo, 1 = reverb; amount 0 = off .. 1 = full;
// param = echo time in seconds (one beat), ignored for reverb. Needs a stem track (4 stems).
DJ_API void   dj_set_stem_fx(int d, int bus, int kind, float amount, float param) {
    if (d < 0 || d >= 4) return;
    StemFx &f = bus ? engine.decks[d].fxI : engine.decks[d].fxV;
    amount = std::max(0.f, std::min(1.f, amount));
    if (kind == 0) { f.echoAmt = amount; if (param > 0.05f) f.echoSec = std::min(param, 2.0f); }
    else f.revAmt = amount;
}
DJ_API void   dj_set_phaser(int d, float amt, float rateHz) { engine.decks[d].phAmt = amt; engine.decks[d].phRate = rateHz; }
DJ_API int    dj_start_recording(const char *path) { return engine.rec.start(path, (int)engine.srate); }
DJ_API void   dj_stop_recording() { engine.rec.stop(); }

DJ_API int dj_load_sample(int slot, const char *path, int rate) {
    if (slot < 0 || slot >= NSAMPLES) return 0;
    const int16_t *p = nullptr; size_t b = 0;
    if (!mapFile(path, p, b)) return 0;
    Voice &v = engine.voices[slot];
    std::lock_guard<std::mutex> g(v.lock);
    if (v.data) plat_unmap(v.data, v.bytes);
    v.data = p; v.bytes = b; v.frames = (int64_t)(b / 4); v.rate = rate > 0 ? rate : 44100;
    v.pos = 0; v.env = 0; v.active = false; v.gate = false; v.cmd = 0; v.playingOut = false;
    return 1;
}
DJ_API void dj_clear_sample(int slot) {
    if (slot < 0 || slot >= NSAMPLES) return;
    Voice &v = engine.voices[slot];
    std::lock_guard<std::mutex> g(v.lock);
    if (v.data) plat_unmap(v.data, v.bytes);
    v.data = nullptr; v.bytes = 0; v.frames = 0; v.active = false; v.gate = false; v.cmd = 0; v.playingOut = false;
}
DJ_API void dj_trigger_sample(int slot, int loop) {
    if (slot < 0 || slot >= NSAMPLES) return;
    Voice &v = engine.voices[slot];
    v.loop = loop != 0; v.cmd = 1; v.playingOut = true;
}
DJ_API void dj_stop_sample(int slot) { if (slot >= 0 && slot < NSAMPLES) engine.voices[slot].cmd = 2; }
DJ_API int  dj_is_sample_playing(int slot) { return (slot >= 0 && slot < NSAMPLES) ? engine.voices[slot].playingOut.load() : 0; }
DJ_API void dj_set_sample_volume(float v) { engine.sampVol = v; }
DJ_API void dj_set_master_gain(float v) { engine.masterGain = v; }
DJ_API void dj_set_bass_amp(float v) { engine.bassAmt = std::max(0.f, std::min(1.f, v)); }
DJ_API void dj_set_crossfader_curve(int c) { engine.xcurve = c; }

// Master tap: start it, then read blocks of the finished master mix (float stereo, dj_get_sample_rate() Hz) in a loop.
DJ_API void dj_tap_start() { engine.tap.start(); }
DJ_API void dj_tap_stop() { engine.tap.stop(); }
DJ_API int  dj_tap_read(float *out, int maxFrames) { return engine.tap.read(out, maxFrames); }
DJ_API int  dj_get_sample_rate() { return (int)engine.srate; }

// Renders audio without a sound card (tests, offline export). Call dj_offline_begin(rate) first instead of dj_start().
DJ_API void dj_offline_begin(int rate) {
    engine.srate = (float)rate;
    engine.limiter.init(engine.srate); engine.bassAmp.init(engine.srate);
    for (auto &d : engine.decks) { d.reverb.init(engine.srate); d.fxV.init(engine.srate); d.fxI.init(engine.srate); d.phaser.reset(); d.filt.s1.reset(); d.filt.s2.reset(); }
}
DJ_API void dj_offline_render(float *out, int frames) { engine.render(out, frames); }
