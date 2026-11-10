package com.example.djapp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Screen layout the user can customise in Settings > LAYOUT: which control groups are shown and how big the main areas are.
 * The defaults are exactly the original layout. Settings, the LIB / FX / SMP / VID buttons and the transport (CUE, PLAY)
 * can never be hidden, so the user can always get back to this page.
 */
object LayoutPrefs {
    // ---- show / hide
    var showOverview by mutableStateOf(true)     // thin full-track overview under each scrolling waveform
    var showFilter by mutableStateOf(true)       // filter fader left of each platter
    var showBass by mutableStateOf(true)         // bass fader left of each platter
    var showStems by mutableStateOf(true)        // VOCAL / MELODY / BASS / INSTR buttons under each platter
    var showSwap by mutableStateOf(false)         // STEM SWAP vocal pads under the stem buttons
    var showFxCol by mutableStateOf(true)        // LOOP OUT / ECHO OUT / BACKSPIN column right of each platter
    var showMeter by mutableStateOf(true)        // level meter in the centre
    var showSync by mutableStateOf(true)         // SYNC buttons in the bottom bar
    var showInfo by mutableStateOf(false)        // big BPM / key / time readout beside each platter
    var showPads by mutableStateOf(true)         // 4 colored hot-cue pads under each platter
    var showMixer by mutableStateOf(true)        // channel strips (HI / MID / LOW / volume) in the centre
    var showDock by mutableStateOf(false)         // library table under the decks (crate list + track table)

    // ---- waveforms at the very top of the screen (above the top bar), small
    var wavesOnTop by mutableStateOf(true)
    var topWavePct by mutableFloatStateOf(16f)   // height of the top waveform strip, % of the screen (14..45)

    // ---- sizes
    var wavePct by mutableFloatStateOf(46f)      // waveform area, % of the area between the top and bottom bars (28..62)
    var deckPct by mutableFloatStateOf(33f)      // width of each platter area, in layout units (26..37; centre gets the rest)
    var bottomPct by mutableFloatStateOf(15f)    // height of the CUE / PLAY / crossfader bar, units (8..22)
    var dockPct by mutableFloatStateOf(30f)      // height of the library dock, % of the screen (20..55)
    var iconDp by mutableFloatStateOf(44f)       // size of the round buttons in the centre column (26..46)
    var padDp by mutableFloatStateOf(42f)        // height of the hot-cue, stem and stem-swap pads under each platter (24..44), medium by default
    var faderDp by mutableFloatStateOf(40f)      // width of the filter / bass faders (26..52)

    /** Layout weight of the centre column; always at least 12 so its buttons fit. */
    val centerPct: Float get() = (100f - 14f - 2f * deckPct).coerceAtLeast(12f)

    private var prefs: SharedPreferences? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("djlayout", Context.MODE_PRIVATE); prefs = p
        // Layout v2 = library dock + medium pads / buttons. Older saved layouts are replaced once by the new defaults.
        if (p.getInt("layoutVer", 0) < 4) { p.edit().clear().putInt("layoutVer", 4).apply() }
        showOverview = p.getBoolean("showOverview", true)
        showFilter = p.getBoolean("showFilter", true)
        showBass = p.getBoolean("showBass", true)
        showStems = p.getBoolean("showStems", true)
        showFxCol = p.getBoolean("showFxCol", true)
        showSwap = p.getBoolean("showSwap", false)
        showMeter = p.getBoolean("showMeter", true)
        showSync = p.getBoolean("showSync", true)
        showInfo = p.getBoolean("showInfo", false)
        showPads = p.getBoolean("showPads", true)
        showMixer = p.getBoolean("showMixer", true)
        showDock = false                                   // the library dock under the decks was removed (the LIB button opens the library)
        dockPct = p.getFloat("dockPct", 30f).coerceIn(20f, 55f)
        wavesOnTop = p.getBoolean("wavesOnTop", true)
        topWavePct = p.getFloat("topWavePct", 16f).coerceIn(14f, 45f)
        wavePct = p.getFloat("wavePct", 46f).coerceIn(28f, 62f)
        deckPct = p.getFloat("deckPct", 33f).coerceIn(26f, 37f)
        bottomPct = p.getFloat("bottomPct", 15f).coerceIn(8f, 26f)
        iconDp = p.getFloat("iconDp", 44f).coerceIn(26f, 60f)
        faderDp = p.getFloat("faderDp", 40f).coerceIn(26f, 64f)
        padDp = p.getFloat("padDp", 42f).coerceIn(24f, 64f)
    }

    fun save() {
        prefs?.edit()
            ?.putBoolean("showOverview", showOverview)?.putBoolean("showFilter", showFilter)?.putBoolean("showBass", showBass)
            ?.putBoolean("showStems", showStems)?.putBoolean("showSwap", showSwap)?.putBoolean("showFxCol", showFxCol)?.putBoolean("showMeter", showMeter)
            ?.putBoolean("showSync", showSync)?.putBoolean("wavesOnTop", wavesOnTop)?.putBoolean("showInfo", showInfo)?.putBoolean("showPads", showPads)?.putBoolean("showMixer", showMixer)?.putFloat("topWavePct", topWavePct)
            ?.putFloat("wavePct", wavePct)?.putFloat("deckPct", deckPct)?.putFloat("bottomPct", bottomPct)
            ?.putFloat("iconDp", iconDp)?.putFloat("faderDp", faderDp)?.putFloat("padDp", padDp)?.putBoolean("showDock", showDock)?.putFloat("dockPct", dockPct)?.putInt("layoutVer", 4)
            ?.apply()
    }

    fun reset() {
        showOverview = true; showFilter = true; showBass = true; showStems = true; showSwap = false; showFxCol = true; showMeter = true; showSync = true
        showInfo = false; showPads = true; showMixer = true; showDock = false
        wavesOnTop = true; topWavePct = 16f; dockPct = 30f
        wavePct = 46f; deckPct = 33f; bottomPct = 15f; iconDp = 44f; faderDp = 40f; padDp = 42f
        save()
    }

    /** Hides everything that can be hidden: a clean, big-platter screen. */
    fun minimal() {
        showOverview = false; showFilter = false; showBass = false; showStems = false; showFxCol = false; showMeter = false; showSync = false
        showInfo = false; showPads = false; showMixer = false; showDock = false
        wavesOnTop = true; topWavePct = 22f
        wavePct = 34f; deckPct = 36f
        save()
    }
}
