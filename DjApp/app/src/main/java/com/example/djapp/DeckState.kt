package com.example.djapp

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.*

/** One low-priority thread for stem prep: decks queue up and the audio thread is never starved. */
private val prepDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "stem-prep")
}.asCoroutineDispatcher()

/** BPM / key / waveform analysis of a freshly loaded track: one background thread, so two quick loads never starve the audio. */
private val analysisDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "load-analysis")
}.asCoroutineDispatcher()

object AppSettings {
    var tempoRange by mutableFloatStateOf(0.08f)      // +/- fraction
    var modelStatus by mutableStateOf("")
    var stemQuality by mutableIntStateOf(1)           // live stems: 0 fast, 1 better, 2 pro (slow), 3 AI (SCNet, heavy on the CPU: strong phones only)
    var stemAuto by mutableStateOf(true)              // prepare stems in the background when a track is loaded
    val aiStems get() = stemQuality == 3
    var prepQuality by mutableIntStateOf(2)           // prepared files: 0 better, 1 pro, 2 AI (default), 3 AI MAX (about 7x slower than AI)
    var autoPrep by mutableStateOf(false)             // phones without live stems: a loaded, unprepared track is prepared automatically and its stems switch on when done
    var prepPause by mutableStateOf(true)             // pause preparing while a deck is playing
    var prepCapMb by mutableIntStateOf(3000)          // storage cap for prepared tracks
    // Performance (budget phones)
    var audioMode by mutableIntStateOf(0)             // 0 fast, 1 balanced, 2 safe (bigger audio buffer)
    var liteUi by mutableStateOf(false)               // 30 fps UI, lighter waveform
    var keylock by mutableStateOf(true)               // key lock default for both decks
    // Loop Out effect (like VirtualDJ: Length dial, Count dial, Mode dial)
    var loopOutBeats by mutableFloatStateOf(8f)       // length of the loop in beats (default 8)
    var loopOutCount by mutableIntStateOf(8)          // how many times it repeats (default 8 = 64 beats in total)
    var loopOutMode by mutableIntStateOf(0)           // fade type at the end: 0 volume, 1 filter, 2 cut (stops straight away)

    // Mixer
    var crossCurve by mutableIntStateOf(0)            // 0 smooth, 1 sharp (cut), 2 linear
    var masterGain by mutableFloatStateOf(1f)
    var bassAmp by mutableFloatStateOf(0.5f)          // harmonic bass amount 0..1
    // Sampler
    var padMode by mutableIntStateOf(0)               // 0 one-shot, 1 hold, 2 loop (toggle)
    var sampleVol by mutableFloatStateOf(0.8f)
    // Video
    var videoBlend by mutableIntStateOf(0)            // 0 blend, 1 dip to black, 2 cut
    var videoFollowCross by mutableStateOf(true)      // video fader follows the audio crossfader
    var videoFollowTempo by mutableStateOf(true)      // video speed follows deck tempo
    var videoBright by mutableFloatStateOf(1f)
    var videoOverlay by mutableStateOf(true)          // single picture goes straight to the screen (SurfaceView): smoother; turn off if the picture is black
    var video480 by mutableStateOf(false)             // picture output is made 480p (a 480p copy of bigger videos) for budget phones
    // Display
    var keepAwake by mutableStateOf(true)

    private var prefs: SharedPreferences? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("maxxidodj", Context.MODE_PRIVATE); prefs = p
        tempoRange = p.getFloat("tempoRange", tempoRange)
        val low = lowEnd(ctx)
        stemQuality = p.getInt("stemQuality", if (p.getBoolean("aiStems", false)) 3 else if (p.getBoolean("betterStems", true)) 1 else 0)
        stemAuto = p.getBoolean("stemAuto", !low)
        // One-time: strong phones separate with the AI live and automatically; budget phones rely on prepared tracks.
        if (!p.getBoolean("defaultsV16", false)) {
            if (!low && ramOkForAi(ctx)) { stemQuality = 3; stemAuto = true }
            p.edit().putBoolean("defaultsV16", true).putInt("stemQuality", stemQuality).putBoolean("stemAuto", stemAuto).apply()
        }
        prepQuality = p.getInt("prepQuality", prepQuality)
        // One-time: preparing now defaults to AI MAX for everyone (slow but sure); it can still be changed in Settings.
        if (!p.getBoolean("prepMaxV23", false)) { prepQuality = 3; p.edit().putBoolean("prepMaxV23", true).putInt("prepQuality", 3).apply() }
        // One-time: AI MAX runs the network 5 times with double overlap (about 7x the work of AI, hours per song on a budget phone).
        // Plain AI is the default again; AI MAX stays available in Settings for overnight jobs.
        if (!p.getBoolean("prepAiV24", false)) { prepQuality = 2; p.edit().putBoolean("prepAiV24", true).putInt("prepQuality", 2).apply() }
        prepPause = p.getBoolean("prepPause", prepPause)
        autoPrep = p.getBoolean("autoPrep", false)
        prepCapMb = p.getInt("prepCapMb", prepCapMb)
        audioMode = p.getInt("audioMode", if (low) 1 else 0)
        liteUi = p.getBoolean("liteUi", low)
        keylock = p.getBoolean("keylock", keylock)
        loopOutBeats = p.getFloat("loopOutBeats", loopOutBeats)
        loopOutCount = p.getInt("loopOutCount", loopOutCount)
        loopOutMode = p.getInt("loopOutMode", loopOutMode)
        crossCurve = p.getInt("crossCurve", crossCurve)
        masterGain = p.getFloat("masterGain", masterGain)
        bassAmp = p.getFloat("bassAmp", bassAmp)
        padMode = p.getInt("padMode", padMode)
        sampleVol = p.getFloat("sampleVol", sampleVol)
        videoBlend = p.getInt("videoBlend", videoBlend)
        videoFollowCross = p.getBoolean("videoFollowCross", videoFollowCross)
        videoFollowTempo = p.getBoolean("videoFollowTempo", videoFollowTempo)
        videoBright = p.getFloat("videoBright", videoBright)
        videoOverlay = p.getBoolean("videoOverlay", true)
        video480 = false
        keepAwake = p.getBoolean("keepAwake", keepAwake)
    }

    fun save() {
        prefs?.edit()
            ?.putFloat("tempoRange", tempoRange)?.putInt("stemQuality", stemQuality)?.putBoolean("stemAuto", stemAuto)
            ?.putInt("prepQuality", prepQuality)?.putBoolean("prepPause", prepPause)?.putBoolean("autoPrep", autoPrep)?.putInt("prepCapMb", prepCapMb)
            ?.putInt("audioMode", audioMode)?.putBoolean("liteUi", liteUi)?.putBoolean("keylock", keylock)
            ?.putFloat("loopOutBeats", loopOutBeats)?.putInt("loopOutCount", loopOutCount)?.putInt("loopOutMode", loopOutMode)
            ?.putInt("crossCurve", crossCurve)?.putFloat("masterGain", masterGain)?.putFloat("bassAmp", bassAmp)
            ?.putInt("padMode", padMode)?.putFloat("sampleVol", sampleVol)
            ?.putInt("videoBlend", videoBlend)?.putBoolean("videoFollowCross", videoFollowCross)
            ?.putBoolean("videoFollowTempo", videoFollowTempo)?.putFloat("videoBright", videoBright)?.putBoolean("video480", video480)?.putBoolean("videoOverlay", videoOverlay)
            ?.putBoolean("keepAwake", keepAwake)
            ?.apply()
    }

    /** Pushes the engine-side settings (call after the engine has started). */
    fun applyToEngine() {
        NativeEngine.setCrossfaderCurve(crossCurve)
        NativeEngine.setMasterGain(masterGain)
        NativeEngine.setBassAmp(bassAmp)
        NativeEngine.setSampleVolume(sampleVol)
        NativeEngine.setAudioMode(audioMode)
    }

    /** Budget phone: Android flags it as low-RAM, or it has under ~5 GB (the Itel A80 reports ~3.7 GB). */
    fun lowEnd(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return am.isLowRamDevice || mi.totalMem < 5_200_000_000L
    }

    /** Live AI stems keep the CPU busy while you play, so they stay limited to stronger phones (prepared tracks work on any phone). */
    fun ramOkForAi(ctx: Context): Boolean {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem >= 5_000_000_000L
    }
}

class DeckState(val id: Int) {
    /** Deck colours follow the active skin. */
    val accent: Color get() = if (id == 0) DeckA else DeckB
    val dark: Color get() = if (id == 0) DeckADark else DeckBDark
    var name by mutableStateOf(if (id == 0) "DECK A" else "DECK B")
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var bpm by mutableFloatStateOf(0f)
    var key by mutableStateOf("")                     // e.g. "8A Am" (Camelot + name), empty until detected
    var duration by mutableDoubleStateOf(0.0)
    var posSec by mutableDoubleStateOf(0.0)

    // Mixer / tempo
    var tempoPct by mutableFloatStateOf(0f)           // -range..+range
    var vol by mutableFloatStateOf(1f)

    // Effects
    var filter by mutableFloatStateOf(0f)
    var reverb by mutableFloatStateOf(0f)
    var room by mutableFloatStateOf(0.6f)
    var phaser by mutableFloatStateOf(0f)
    var phRate by mutableFloatStateOf(0.5f)

    // Stems
    val stemOn = mutableStateListOf(true, true, true, true)
    val stemLevel = mutableStateListOf(1f, 1f, 1f, 1f)
    var stemEngine by mutableStateOf("")                // who made the current stems: AI / AI MAX / PRO / BETTER / FAST
    var stemProg by mutableFloatStateOf(-1f)          // -1 none, 0..1 preparing, 2 ready
    val stemsReady get() = stemProg == 2f
    var swapSlot by mutableIntStateOf(-1)             // stem swap: clip slot that replaces this deck's vocal (-1 = original vocal)

    init {
        // A track that was loaded without stems and has just been prepared in the background gets its stems hot-swapped in.
        PrepQueue.listeners.add { ctx, uri ->
            if (curUri == uri && loaded && Pro.has(Pro.STEMS) && (stemProg != 2f || !stemEngine.startsWith("AI"))) {
                Prepared.find(ctx, uri)?.let { prep ->
                    NativeEngine.setStems(id, prep.stemPaths)
                    for (i in 0..3) pushStem(i)
                    stemEngine = prep.engine.ifEmpty { "older pack" }
                    stemProg = 2f
                }
            }
        }
    }

    // Cue / hot cues / loops
    var cue = 0.0
    var wave by mutableStateOf<WaveData?>(null)
    val hotcues = mutableStateListOf(-1.0, -1.0, -1.0, -1.0)
    var loopIn by mutableDoubleStateOf(-1.0)
    var loopOut by mutableDoubleStateOf(-1.0)
    var loopActive by mutableStateOf(false)
    var loopBeats by mutableFloatStateOf(0f)          // 0 = manual loop

    // Key lock + beat sync
    var keylock by mutableStateOf(AppSettings.keylock)
    var beatOffset = 0.0                                // track time of a beat (see BeatGrid)
    var syncOn by mutableStateOf(false)
    private var syncMaster: DeckState? = null
    var curUri: Uri? = null
    var curTitle = ""

    private var prepJob: Job? = null
    /** Bumped on every load: a slower, older load that finishes late must never overwrite the newer track. */
    @Volatile private var loadGen = 0
    private var curMix: File? = null
    private var lastPackDir = ""
    private var previewing = false

    val effectiveBpm get() = bpm * (1f + tempoPct)
    fun pos(): Double = NativeEngine.getPosition(id)

    // ---------------------------------------------------------------- transport
    fun togglePlay() { playing = !playing; NativeEngine.setPlaying(id, playing) }

    /** CDJ-style cue: playing -> back to cue & pause; paused elsewhere -> set cue; hold at cue -> preview. */
    fun cueDown() {
        val p = pos()
        when {
            playing -> { NativeEngine.setPlaying(id, false); playing = false; NativeEngine.seek(id, cue) }
            abs(p - cue) < 0.06 -> { previewing = true; NativeEngine.setPlaying(id, true) }
            else -> cue = p
        }
    }
    fun cueUp() {
        if (previewing) { previewing = false; NativeEngine.setPlaying(id, false); NativeEngine.seek(id, cue) }
    }

    fun hotcue(i: Int) {
        if (hotcues[i] < 0) hotcues[i] = pos() else NativeEngine.seek(id, hotcues[i])
    }
    fun clearHotcue(i: Int) { hotcues[i] = -1.0 }

    // ---------------------------------------------------------------- tempo / sync
    /** Tempo fader moved by the user: this also ends sync (the DJ took over). */
    fun applyTempo(p: Float) { syncOn = false; syncMaster = null; setTempoRaw(p, 0f) }

    private fun setTempoRaw(p: Float, nudge: Float) {
        tempoPct = p.coerceIn(-0.5f, 0.5f)
        NativeEngine.setTempo(id, (1f + tempoPct) * (1f + nudge))
    }

    fun toggleKeylock() { keylock = !keylock; NativeEngine.setKeylock(id, keylock) }

    /** Fractional beat position (0..1) of the playhead on this deck's grid, or null without a grid. */
    private fun beatPhase(periodMult: Double = 1.0): Double? {
        if (bpm <= 0f) return null
        val period = 60.0 / bpm * periodMult
        val x = (pos() - beatOffset) / period
        return x - floor(x)
    }

    /**
     * Beat sync: this deck plays at EXACTLY [other]'s BPM (not a hair more or less), jumps to the same place
     * in the beat, and stays locked. The tempo is never nudged: if the beats ever drift apart (after a scratch,
     * say) the deck is re-aligned with a small jump instead. Tap the tempo fader to leave sync.
     */
    fun syncTo(other: DeckState): Boolean {
        if (bpm <= 0f || other.bpm <= 0f) return false
        if (other.syncMaster === this) other.stopSync()     // never two decks chasing each other: the followed deck keeps its tempo and leads
        syncMaster = other; syncOn = true
        updateSync(hard = true)
        return true
    }

    fun stopSync() { syncOn = false; syncMaster = null; setTempoRaw(tempoPct, 0f) }

    /** Decks that were following this one stop following it: this deck is about to hold a different song. */
    private fun releaseFollowers() { for (d in StemSwap.decks) if (d !== this && d.syncMaster === this) d.stopSync() }

    private fun updateSync(hard: Boolean) {
        val o = syncMaster ?: return
        if (bpm <= 0f || o.bpm <= 0f) return
        // Exact match whenever the tempo range can reach it (within +/-50 %). Only a track that is further away
        // than that (e.g. 64 vs 128 BPM) is matched in half / double time.
        var ratio = o.effectiveBpm / bpm
        var mult = 1.0                                   // my beat = mult of the other deck's beats
        while (ratio > 1.5f) { ratio /= 2f; mult *= 2.0 }
        while (ratio < 0.5f) { ratio *= 2f; mult /= 2.0 }
        val mine = beatPhase() ?: run { setTempoRaw(ratio - 1f, 0f); return }
        val theirs = o.beatPhase(mult) ?: run { setTempoRaw(ratio - 1f, 0f); return }
        var e = theirs - mine                            // beats; + means the other deck is ahead of me
        e -= Math.rint(e)
        setTempoRaw(ratio - 1f, 0f)                      // identical BPM, no nudging
        if (abs(e) > (if (hard) 0.004 else 0.06)) NativeEngine.seek(id, (pos() + e * 60.0 / bpm).coerceAtLeast(0.0))
    }

    /** ~5x per second from the UI loop: keeps a synced deck locked to its partner. */
    fun syncTick() {
        if (!syncOn || !playing || !loaded) return
        val m = syncMaster ?: return
        if (!m.playing) return
        updateSync(hard = false)
    }

    // ---------------------------------------------------------------- loops
    private fun pushLoop() = NativeEngine.setLoop(id, loopIn, loopOut, loopActive)

    fun beatLoop(beats: Float) {
        if (bpm <= 0f) return
        if (loopActive && loopBeats == beats) { exitLoop(); return }
        val s = pos()
        loopIn = s; loopOut = s + beats * 60.0 / bpm; loopBeats = beats; loopActive = true; pushLoop()
    }
    fun loopInSet() {
        loopIn = pos(); loopBeats = 0f
        if (loopOut > loopIn + 0.02) pushLoop() else { loopOut = -1.0; loopActive = false; pushLoop() }
    }
    /** "Loop out": closes the loop at the current position and starts looping. */
    fun loopOutSet() {
        val p = pos()
        if (loopIn < 0 || p <= loopIn + 0.02) return
        loopOut = p; loopBeats = 0f; loopActive = true; pushLoop()
    }
    fun exitLoop() { loopActive = false; if (loopIn >= 0 && loopOut > loopIn) pushLoop() }
    fun reloop() {
        if (loopIn >= 0 && loopOut > loopIn) { loopActive = true; pushLoop(); NativeEngine.seek(id, loopIn) }
    }
    fun halveLoop() {
        if (!loopActive) return
        val len = (loopOut - loopIn) / 2
        if (len < 0.02) return
        loopOut = loopIn + len; if (loopBeats > 0) loopBeats /= 2; pushLoop()
    }
    fun doubleLoop() {
        if (!loopActive) return
        loopOut = loopIn + (loopOut - loopIn) * 2; if (loopBeats > 0) loopBeats *= 2; pushLoop()
    }

    // ---------------------------------------------------------------- performance effects (right of the jog wheel)
    /** "" = none, otherwise LOOP / ECHO / SPIN while that effect is running. */
    var fxBusy by mutableStateOf("")
    private var fxJob: Job? = null
    private val fxScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Stops a running effect. A backspin that is cancelled keeps the deck playing from where it is. */
    fun cancelFx() {
        val was = fxBusy
        fxJob?.cancel(); fxJob = null
        if (was.isEmpty()) return
        fxBusy = ""
        loopActive = false; pushLoop()
        pushVolume(); pushReverb(); pushFilter()
        if (was == "SPIN") {
            NativeEngine.setJog(id, false, 0.0)
            playing = true; NativeEngine.setPlaying(id, true)
        }
    }

    private fun fxBeatSec() = 60.0 / (if (bpm > 0f) bpm else 120f)          // one beat in track seconds
    private fun fxWallMs(trackSec: Double) = (trackSec / (1f + tempoPct).coerceAtLeast(0.3f) * 1000).toLong()

    private suspend fun fadeOutAndStop(ms: Long) {
        val steps = 12
        for (i in 1..steps) { NativeEngine.setVolume(id, vol * (1f - i / steps.toFloat())); delay(ms / steps) }
        NativeEngine.setPlaying(id, false); playing = false
        pushVolume()
    }

    /**
     * LOOP OUT (like VirtualDJ): loops [beats] beats of audio [repeats] times (defaults 8 x 8 = 64 beats, set in
     * Settings > PERFORMANCE), then fades out with the chosen mode (0 volume, 1 filter, 2 cut) and stops the deck.
     */
    fun loopOutFx(beats: Float = AppSettings.loopOutBeats, repeats: Int = AppSettings.loopOutCount, mode: Int = AppSettings.loopOutMode) {
        if (!loaded) return
        if (fxBusy == "LOOP") { cancelFx(); return }
        cancelFx()
        if (!playing) { playing = true; NativeEngine.setPlaying(id, true) }
        fxBusy = "LOOP"
        fxJob = fxScope.launch {
            val len = beats * fxBeatSec()
            val s = pos()
            loopIn = s; loopOut = s + len; loopBeats = beats; loopActive = true; pushLoop()
            delay(fxWallMs(len) * repeats.coerceAtLeast(1))
            when (mode) {
                2 -> {                                       // CUT: stop straight away
                    loopActive = false; pushLoop()
                    NativeEngine.setPlaying(id, false); playing = false
                }
                1 -> {                                       // FILTER: close a low-pass filter while it keeps looping
                    val ms = (fxWallMs(fxBeatSec() * 2)).coerceIn(600L, 2500L)
                    val steps = 20
                    for (i in 1..steps) { NativeEngine.setFilter(id, -i / steps.toFloat()); delay(ms / steps) }
                    NativeEngine.setVolume(id, 0f)
                    loopActive = false; pushLoop()
                    NativeEngine.setPlaying(id, false); playing = false
                    pushVolume(); pushFilter()
                }
                else -> {                                    // VOLUME: fade out while it keeps looping
                    val ms = (fxWallMs(fxBeatSec() * 2)).coerceIn(600L, 2500L)
                    loopActive = true
                    fadeOutAndStop(ms)
                    loopActive = false; pushLoop()
                }
            }
            fxBusy = ""; fxJob = null
        }
    }

    /** ECHO OUT: the last beat repeats with a long reverb tail, getting quieter each time, then the deck stops. */
    fun echoOutFx() {
        if (!loaded) return
        if (fxBusy == "ECHO") { cancelFx(); return }
        cancelFx()
        if (!playing) { playing = true; NativeEngine.setPlaying(id, true) }
        fxBusy = "ECHO"
        fxJob = fxScope.launch {
            val beat = fxBeatSec()
            val e = pos()
            val s = (e - beat).coerceAtLeast(0.0)
            loopIn = s; loopOut = maxOf(e, s + 0.1); loopBeats = 0f; loopActive = true; pushLoop()
            NativeEngine.setReverb(id, 0.6f, 0.95f)
            var g = 1f
            repeat(7) {
                NativeEngine.setVolume(id, vol * g)
                delay(fxWallMs(loopOut - loopIn))
                g *= 0.55f
            }
            loopActive = false; pushLoop()
            NativeEngine.setVolume(id, 0f)
            delay(1200)                                     // let the reverb tail ring out
            NativeEngine.setPlaying(id, false); playing = false
            pushVolume(); pushReverb()
            fxBusy = ""; fxJob = null
        }
    }

    /** BACKSPIN: the record spins backwards, faster and faster. Press again to drop it where you want; otherwise it ends at the start. */
    fun backspinFx() {
        if (!loaded) return
        if (fxBusy == "SPIN") { cancelFx(); return }          // released: keeps playing from here
        cancelFx()
        fxBusy = "SPIN"
        if (!playing) { playing = true }
        NativeEngine.setPlaying(id, true)
        fxJob = fxScope.launch {
            var t = pos()
            var speed = 2.0                                  // track seconds per second, ramps up like a hand-spun platter
            NativeEngine.setJog(id, true, t)
            while (t > 0.0) {
                delay(16)
                speed = minOf(speed + 0.9, 20.0)
                t = (t - speed * 0.016).coerceAtLeast(0.0)
                NativeEngine.setJog(id, true, t)
            }
            delay(250)
            NativeEngine.setJog(id, false, 0.0)
            NativeEngine.setPlaying(id, false); playing = false
            NativeEngine.seek(id, 0.0)
            fxBusy = ""; fxJob = null
        }
    }

    // ---------------------------------------------------------------- effects
    fun pushFilter() = NativeEngine.setFilter(id, filter)

    /** EQ gains in dB for bass / mid / treble (-26 = almost off, +12 = boost). */
    val eq = mutableStateListOf(0f, 0f, 0f)
    fun setEq(band: Int, db: Float) { eq[band] = db; NativeEngine.setEq(id, band, db) }
    fun pushReverb() = NativeEngine.setReverb(id, reverb, room)
    fun pushPhaser() = NativeEngine.setPhaser(id, phaser, phRate)
    fun pushVolume() = NativeEngine.setVolume(id, vol)
    fun resetFx() {
        filter = 0f; reverb = 0f; phaser = 0f; pushFilter(); pushReverb(); pushPhaser()
        for (b in 0..2) setEq(b, 0f)
    }

    // ---------------------------------------------------------------- stems
    private fun stemGain(i: Int) = if (stemOn[i]) stemLevel[i] else 0f
    fun pushStem(i: Int) = NativeEngine.setStemVolume(id, i, stemGain(i))
    fun toggleStem(i: Int) { stemOn[i] = !stemOn[i]; pushStem(i) }
    fun setStemLevel(i: Int, v: Float) { stemLevel[i] = v; if (!stemOn[i] && v > 0f) stemOn[i] = true; pushStem(i) }
    fun soloStem(i: Int) { for (j in 0..3) { stemOn[j] = j == i; pushStem(j) } }
    fun muteOnly(i: Int) { for (j in 0..3) { stemOn[j] = j != i; pushStem(j) } }
    fun resetStems() { for (j in 0..3) { stemOn[j] = true; stemLevel[j] = 1f; pushStem(j) } }

    // ---------------------------------------------------------------- loading
    fun load(ctx: Context, scope: CoroutineScope, uri: Uri) {
        cancelFx()
        prepJob?.cancel()
        // Video files carry their picture along: it is locked to this deck's track position.
        val layer = VideoMixer.layers[id]
        if (ctx.contentResolver.getType(uri)?.startsWith("video/") == true) { Pro.nudge(Pro.VIDEO); layer.load(ctx, uri, lock = true) }
        else if (layer.locked) layer.clear()
        stemProg = -1f; resetStemsUi(); wave = null
        syncOn = false; syncMaster = null; releaseFollowers(); setTempoRaw(tempoPct, 0f); beatOffset = 0.0
        curUri = uri; lastPackDir = ""
        val gen = ++loadGen
        loading = true; loaded = false; name = "Loading…"; playing = false; bpm = 0f; key = ""; duration = 0.0; posSec = 0.0
        NativeEngine.setPlaying(id, false)
        NativeEngine.setKeylock(id, keylock)
        cue = 0.0; loopActive = false; loopIn = -1.0; loopOut = -1.0; loopBeats = 0f
        for (i in 0..3) hotcues[i] = -1.0
        scope.launch {
            try {
                // Prepared track (made ahead of time with PREP): instant, no decoding or analysis.
                val prep = withContext(Dispatchers.IO) { Prepared.find(ctx, uri) }
                if (prep != null && Pro.has(Pro.STEMS) && withContext(Dispatchers.IO) { NativeEngine.loadDeckStems(id, prep.stemPaths, prep.rate) }) {
                    curMix = null; AnalysisCache.pin(id, null)
                    name = displayName(ctx, uri); curTitle = name
                    duration = NativeEngine.getDuration(id)
                    bpm = prep.bpm; beatOffset = prep.offset; key = prep.key; wave = prep.wave
                    stemProg = 2f; stemEngine = prep.engine.ifEmpty { "older pack" }
                    loading = false; loaded = true
                    upgradeOldBpm(prep)
                    // A video song keeps its picture when it is played from prepared stems (same track position).
                    val src: Uri? = prep.src.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
                        ?: Library.tracks.firstOrNull { Prepared.keyOf(it.uri) == prep.dir.name }?.uri      // packs made before the source was saved
                    if (src != null) {
                        val isVideo = withContext(Dispatchers.IO) {
                            try { ctx.contentResolver.getType(src)?.startsWith("video/") == true } catch (_: Exception) { false }
                        }
                        if (isVideo) layer.load(ctx, src, lock = true)
                    }
                    return@launch
                }
                if (gen != loadGen) return@launch
                // A song decoded before (even in an earlier session) is reused as it is: no decoding at all.
                val savedRate = withContext(Dispatchers.IO) { AnalysisCache.pcmRate(ctx, uri) } ?: 0
                val rate: Int
                val f: File
                if (savedRate > 0) {
                    rate = savedRate; f = AnalysisCache.pcmFile(ctx, uri)
                    withContext(Dispatchers.IO) { NativeEngine.loadDeck(id, f.absolutePath, rate) }   // maps the file: keep it off the UI thread
                } else {
                    curMix = null; AnalysisCache.pin(id, null)        // the previous song's file is no longer this deck's
                    // PROGRESSIVE LOAD: one decode pass writes a growing file. The deck becomes playable as soon as the first
                    // ~5 s of audio exist (a fraction of a second), and the part the engine can play is extended while the
                    // rest decodes, so the music never waits for the whole song. Normal priority unless the other deck is playing.
                    val other = 1 - id
                    val busy = try { NativeEngine.isPlaying(other) } catch (_: Throwable) { false }
                    val tmp = AnalysisCache.pcmTemp(ctx, uri)
                    var started = false
                    rate = withContext(Dispatchers.IO) {
                        if (busy) Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
                        try {
                            Decoder.decode(ctx, uri, tmp, isCancelled = { gen != loadGen }) { r, durSec ->
                                if (gen == loadGen) {
                                    if (!started) {
                                        NativeEngine.loadDeck(id, tmp.absolutePath, r)
                                        started = true
                                        scope.launch {
                                            if (gen == loadGen) {
                                                name = displayName(ctx, uri); curTitle = name
                                                duration = durSec ?: NativeEngine.getDuration(id)
                                                loading = false; loaded = true
                                            }
                                        }
                                    } else NativeEngine.replaceDeck(id, tmp.absolutePath, r)   // same song, more of it; keeps position and play state
                                }
                            }
                        } catch (e: Exception) {
                            if (!started) throw e           // nothing playable yet: report the error
                            -1                                // cut short after the music had started: keep what we have
                        } finally { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
                    }
                    if (gen != loadGen) { tmp.delete(); return@launch }          // a newer track took the deck
                    if (rate <= 0) {
                        name = displayName(ctx, uri) + "  (decoding stopped early)"
                        return@launch                                              // not saved as a complete song, not analysed
                    }
                    f = withContext(Dispatchers.IO) { AnalysisCache.commitPcm(ctx, uri, tmp, rate) }
                    val swapped = withContext(Dispatchers.IO) { NativeEngine.replaceDeck(id, f.absolutePath, rate) }
                    if (!swapped) { name = "Error: could not open the decoded song"; return@launch }
                }
                curMix = f; AnalysisCache.pin(id, f)
                name = displayName(ctx, uri); curTitle = name
                duration = NativeEngine.getDuration(id)
                // Saved analysis from an earlier session: BPM, beat grid, key and waveform appear instantly.
                val saved = withContext(Dispatchers.IO) { AnalysisCache.get(ctx, uri) }
                val savedWave = withContext(Dispatchers.IO) { AnalysisCache.getWave(ctx, uri) }
                if (saved != null && saved.full) { bpm = saved.bpm; beatOffset = saved.offset }
                if (saved != null && saved.keyDone) key = saved.key
                if (savedWave != null) wave = savedWave
                loading = false; loaded = true
                val uriNow = uri
                launch(analysisDispatcher) {
                    // If a newer track is loaded on this deck while we analyse, the result is simply no longer wanted.
                    try {
                        if (curMix != f || !f.exists()) return@launch
                        if (saved == null || !saved.full) {
                            val g = BpmDetector.grid(f, rate)
                            if (g != null) {
                                AnalysisCache.putGrid(ctx, uriNow, g, true)
                                if (curMix == f) { beatOffset = g.offsetSec; bpm = g.bpm }
                            }
                        }
                        if (curMix != f || !f.exists()) return@launch
                        if (saved == null || !saved.keyDone) {
                            val k = KeyDetector.detect(f, rate)
                            val ks = if (k != null) "${k.camelot} ${k.name}" else ""
                            AnalysisCache.putKey(ctx, uriNow, ks)
                            if (curMix == f) key = ks
                        }
                        if (curMix != f || !f.exists()) return@launch
                        if (savedWave == null) {
                            val wd = WaveAnalyzer.analyze(f, rate)
                            AnalysisCache.putWave(ctx, uriNow, wd)
                            if (curMix == f) wave = wd
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) { /* file replaced or unreadable: ignore */ }
                }
                // Track is playable right away. Stems are prepared in the background only when allowed
                // (budget phones: use PREP ahead of time instead, so nothing heavy runs during a set).
                curRate = rate
                if (AppSettings.stemAuto) startLiveStems(ctx, scope)
                else if (AppSettings.autoPrep && !Prepared.has(uri)) {
                    // Optional (off by default): prepare this track first (it jumps the queue); the stems switch on by themselves when done.
                    PrepQueue.add(ctx, uri, name, urgent = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { name = "Error: ${e.message}"; loading = false }
        }
    }


    private var curRate = 44100

    /** The user's choice "play stems live": separates the loaded track now, in the background, with the live quality from Settings. */
    fun startLiveStems(ctx: Context, scope: CoroutineScope) {
        if (!Pro.has(Pro.STEMS)) return
        val f = curMix ?: return
        val uri = curUri ?: return
        val rate = curRate
        prepJob?.cancel()
        stemProg = 0f
            val sep: StemSeparator = when (AppSettings.stemQuality) { 0 -> LiteSeparator(); 2 -> ProSeparator(); else -> HpssSeparator() }
            prepJob = scope.launch(prepDispatcher) {
              try {
                val key = uri.toString().hashCode().toUInt().toString(16)
                var paths: Array<String>? = null
                var eng = ""
                if (AppSettings.aiStems && AppSettings.ramOkForAi(ctx)) {
                    paths = StemCache.prepare(ctx, f, rate, key, { stemProg = it.coerceIn(0f, 1f) }, { !isActive }, AiSeparator(ctx))
                    if (paths != null) eng = "AI"
                    if (paths == null && isActive) stemProg = 0f
                }
                if (paths == null && isActive) {
                    paths = StemCache.prepare(ctx, f, rate, key, { stemProg = it.coerceIn(0f, 1f) }, { !isActive }, sep)
                    eng = when (AppSettings.stemQuality) { 0 -> "FAST"; 2 -> "PRO"; else -> "BETTER" }
                }
                if (isActive && paths != null && Pro.has(Pro.STEMS)) { NativeEngine.setStems(id, paths); stemProg = 2f; stemEngine = eng }
                else if (isActive) stemProg = -1f
              } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
              } catch (_: Exception) { stemProg = -1f }   // temp file replaced by a newer load: stems just stay off
            }
    }

    /** Loads a prepared pack straight from the stem store: the original song file is not needed (it may be deleted). */
    fun loadPack(ctx: Context, scope: CoroutineScope, dirName: String) {
        cancelFx()
        prepJob?.cancel()
        val layer = VideoMixer.layers[id]
        if (layer.locked) layer.clear()
        stemProg = -1f; resetStemsUi(); wave = null
        syncOn = false; syncMaster = null; releaseFollowers(); setTempoRaw(tempoPct, 0f); beatOffset = 0.0
        curUri = null; lastPackDir = ""
        loading = true; loaded = false; name = "Loading…"; playing = false; bpm = 0f; key = ""; duration = 0.0; posSec = 0.0
        NativeEngine.setPlaying(id, false)
        NativeEngine.setKeylock(id, keylock)
        cue = 0.0; loopActive = false; loopIn = -1.0; loopOut = -1.0; loopBeats = 0f
        for (i in 0..3) hotcues[i] = -1.0
        scope.launch {
            try {
                val prep = withContext(Dispatchers.IO) { Prepared.findPack(ctx, dirName) }
                if (prep != null && Pro.has(Pro.STEMS) && withContext(Dispatchers.IO) { NativeEngine.loadDeckStems(id, prep.stemPaths, prep.rate) }) {
                    curMix = null; AnalysisCache.pin(id, null)
                    name = prep.title.ifEmpty { "Prepared track" }; curTitle = name
                    duration = NativeEngine.getDuration(id)
                    bpm = prep.bpm; beatOffset = prep.offset; key = prep.key; wave = prep.wave
                    stemProg = 2f; stemEngine = prep.engine.ifEmpty { "older pack" }
                    loading = false; loaded = true
                    upgradeOldBpm(prep)
                } else if (prep != null && !Pro.has(Pro.STEMS)) {
                    name = "Prepared stems need PRO"; loading = false; Pro.gate(Pro.STEMS)
                } else { name = "Error: prepared pack missing"; loading = false }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { name = "Error: ${e.message}"; loading = false }
        }
    }

    /**
     * Fixes a wrong tempo reading (half or double time, common in reggae and dancehall): [factor] 0.5 or 2.0.
     * The beat grid keeps its first beat; the correction is remembered for this track.
     */
    fun scaleBpm(ctx: Context, factor: Double) {
        if (bpm <= 0f) return
        val nb = (bpm * factor).toFloat()
        if (nb < 40f || nb > 220f) return
        bpm = nb
        val u = curUri; val pack = lastPackDir; val off = beatOffset
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (u != null) AnalysisCache.putGrid(ctx, u, BeatGrid(nb, off), true)
                else if (pack.isNotEmpty()) Prepared.setBpm(ctx, pack, nb)
            } catch (_: Exception) {}
        }
    }

    /** Prepared tracks made before the accurate detector: fix their BPM once, in the background, and keep it. */
    private fun upgradeOldBpm(prep: PrepInfo) {
        val dir = prep.dir
        lastPackDir = dir.name
        if (prep.bpmVer >= BpmDetector.VERSION) return
        CoroutineScope(analysisDispatcher).launch {
            try {
                val g = Prepared.upgradeBpm(prep) ?: return@launch
                if (loaded && curMix == null && name.isNotEmpty() && packDirNow == dir.name) { bpm = g.bpm; beatOffset = g.offsetSec }
            } catch (_: Exception) {}
        }
    }
    private val packDirNow: String get() = lastPackDir

    private fun resetStemsUi() { for (i in 0..3) { stemOn[i] = true; stemLevel[i] = 1f }; swapSlot = -1; NativeEngine.setSwap(id, -1, 0.0, 0.0) }

    private fun displayName(ctx: Context, uri: Uri): String {
        var n: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) n = it.getString(0)
            }
        } catch (_: Exception) {}
        return (n ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Loaded").substringBeforeLast('.')
    }
}

/** EQ fader position (0..1, middle = flat) <-> dB. Down to -26 dB (nearly a kill), up to +12 dB (boost). */
fun eqFromFrac(f: Float): Float = if (f < 0.5f) -26f * (1f - 2f * f) else 12f * (2f * f - 1f)
fun eqToFrac(db: Float): Float = if (db < 0f) 0.5f - db / 52f else 0.5f + db / 24f
fun eqText(db: Float): String = if (db < -25f) "KILL" else if (abs(db) < 0.5f) "FLAT" else "%+.0f dB".format(db)
