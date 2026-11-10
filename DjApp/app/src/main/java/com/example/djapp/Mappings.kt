package com.example.djapp

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One thing a key / MIDI control can do. Button actions get press (true) and release (false); knob actions get 0..1 from a MIDI CC. */
class MapAction(
    val id: String, val group: String, val label: String, val knob: Boolean,
    val button: (Boolean) -> Unit, val knobRun: (Float) -> Unit
)

/**
 * Custom mappings: keyboard / gamepad keys and USB MIDI controllers to play, cue, effects, hot cues, loops and sampler pads.
 * Bindings are learned in Settings > CONTROLS (tap LEARN, then press the key / pad or turn the knob) and saved on the phone.
 * Every action runs on the main thread (same place as a screen tap).
 */
object Mappings {
    var decks: List<DeckState> = emptyList()           // set by DjScreen
    var onCross: ((Float) -> Unit)? = null              // set by DjScreen
    var onRec: (() -> Unit)? = null                     // set by DjScreen
    var onPanel: ((Panel) -> Unit)? = null              // set by DjScreen: open / close a screen panel

    // ---- state shown in the settings page
    var learning by mutableStateOf<String?>(null)
    var lastMidi by mutableStateOf("")
    var midiDevices by mutableIntStateOf(0)
    private val keyOf = mutableStateMapOf<String, Int>()      // action id -> Android key code
    private val midiOf = mutableStateMapOf<String, String>()  // action id -> "N:ch:num" (note) or "C:ch:num" (controller)

    private val byKey = HashMap<Int, String>()
    private val byMidi = HashMap<String, String>()
    private var prefs: SharedPreferences? = null
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ the actions
    private fun deck(i: Int): DeckState? = decks.getOrNull(i)
    private val list = ArrayList<MapAction>()
    private fun btn(id: String, group: String, label: String, f: (Boolean) -> Unit) { list.add(MapAction(id, group, label, false, f) {}) }
    private fun knob(id: String, group: String, label: String, f: (Float) -> Unit) { list.add(MapAction(id, group, label, true, {}, f)) }

    val actions: List<MapAction> by lazy {
        for (i in 0..1) {
            val n = if (i == 0) "A" else "B"
            val g = "DECK $n"
            btn("play$i", g, "Play / Pause") { dn -> if (dn) deck(i)?.togglePlay() }
            btn("cue$i", g, "Cue (hold to preview)") { dn -> deck(i)?.let { if (dn) it.cueDown() else it.cueUp() } }
            btn("sync$i", g, "Sync") { dn ->
                val d = deck(i); val o = deck(1 - i)
                if (dn && d != null && o != null) { if (d.syncOn) d.stopSync() else d.syncTo(o) }
            }
            btn("keylock$i", g, "Key lock") { dn -> if (dn) deck(i)?.toggleKeylock() }
            for (h in 0..3) btn("hot$i.$h", g, "Hot cue ${h + 1}") { dn -> if (dn) deck(i)?.hotcue(h) }
            btn("loop4$i", g, "Loop 4 beats on / off") { dn -> if (dn) deck(i)?.beatLoop(4f) }
            btn("loop1$i", g, "Loop 1 beat on / off") { dn -> if (dn) deck(i)?.beatLoop(1f) }
            btn("loop2$i", g, "Loop 2 beats on / off") { dn -> if (dn) deck(i)?.beatLoop(2f) }
            btn("loop8$i", g, "Loop 8 beats on / off") { dn -> if (dn) deck(i)?.beatLoop(8f) }
            btn("loop16$i", g, "Loop 16 beats on / off") { dn -> if (dn) deck(i)?.beatLoop(16f) }
            btn("loop32$i", g, "Loop 32 beats on / off") { dn -> if (dn) deck(i)?.beatLoop(32f) }
            btn("loop64$i", g, "Loop 64 beats on / off") { dn -> if (dn) deck(i)?.beatLoop(64f) }
            btn("loopin$i", g, "Loop IN") { dn -> if (dn) deck(i)?.loopInSet() }
            btn("loopset$i", g, "Loop OUT (close the loop)") { dn -> if (dn) deck(i)?.loopOutSet() }
            btn("loopexit$i", g, "Loop exit / reloop") { dn -> if (dn) deck(i)?.let { if (it.loopActive) it.exitLoop() else it.reloop() } }
            btn("loophalf$i", g, "Loop shorter (half)") { dn -> if (dn) deck(i)?.halveLoop() }
            btn("loopdbl$i", g, "Loop longer (double)") { dn -> if (dn) deck(i)?.doubleLoop() }
            knob("ktempo$i", g, "Tempo fader (centre = 0 %)") { v ->
                deck(i)?.let { var t = (v - 0.5f) * 2f * AppSettings.tempoRange; if (kotlin.math.abs(t) < AppSettings.tempoRange * 0.03f) t = 0f; it.applyTempo(t) }
            }
            val stems = listOf("Vocal", "Melody", "Bass", "Drums")
            for (s in 0..3) btn("stem$i.$s", g, "Mute ${stems[s]}") { dn -> if (dn) deck(i)?.toggleStem(s) }

            // Stem swap pads: pad k of the page that is showing (same as tapping the pad on screen).
            for (k in 0 until StemSwap.PER_PAGE)
                btn("swap$i.$k", "STEM SWAP $n", "Swap pad ${k + 1}  (current page)") { dn ->
                    if (dn) deck(i)?.let { StemSwap.tap(it, StemSwap.page * StemSwap.PER_PAGE + k) }
                }

            btn("acap$i", g, "Stems: acapella only") { dn -> if (dn) deck(i)?.soloStem(0) }
            btn("instr$i", g, "Stems: no vocals") { dn -> if (dn) deck(i)?.muteOnly(0) }
            btn("beatless$i", g, "Stems: beatless") { dn -> if (dn) deck(i)?.muteOnly(1) }
            btn("stemreset$i", g, "Stems: all back on") { dn -> if (dn) deck(i)?.resetStems() }

            val e = "EFFECTS $n"
            btn("rev$i", e, "Reverb on / off") { dn -> deck(i)?.let { if (dn) { it.reverb = if (it.reverb > 0f) 0f else 0.5f; it.pushReverb() } } }
            btn("pha$i", e, "Phaser on / off") { dn -> deck(i)?.let { if (dn) { it.phaser = if (it.phaser > 0f) 0f else 0.5f; it.pushPhaser() } } }
            btn("revhold$i", e, "Reverb (hold)") { dn -> deck(i)?.let { it.reverb = if (dn) 0.7f else 0f; it.pushReverb() } }
            btn("phahold$i", e, "Phaser (hold)") { dn -> deck(i)?.let { it.phaser = if (dn) 0.7f else 0f; it.pushPhaser() } }
            btn("bkill$i", e, "Bass kill (hold)") { dn -> deck(i)?.setEq(0, if (dn) -26f else 0f) }
            btn("eqflat$i", e, "EQ flat") { dn -> if (dn) deck(i)?.let { for (b in 0..2) it.setEq(b, 0f) } }
            knob("kroom$i", e, "Reverb room size knob") { v -> deck(i)?.let { it.room = v; it.pushReverb() } }
            knob("kphrate$i", e, "Phaser rate knob") { v -> deck(i)?.let { it.phRate = 0.1f + v * 3.9f; it.pushPhaser() } }
            knob("keqhi$i", e, "EQ high knob") { v -> deck(i)?.setEq(2, eqFromFrac(if (kotlin.math.abs(v - 0.5f) < 0.03f) 0.5f else v)) }
            knob("keqmid$i", e, "EQ mid knob") { v -> deck(i)?.setEq(1, eqFromFrac(if (kotlin.math.abs(v - 0.5f) < 0.03f) 0.5f else v)) }
            knob("keqlow$i", e, "EQ low / bass knob") { v -> deck(i)?.setEq(0, eqFromFrac(if (kotlin.math.abs(v - 0.5f) < 0.03f) 0.5f else v)) }
            btn("lp$i", e, "Low-pass filter (hold)") { dn -> deck(i)?.let { it.filter = if (dn) -0.65f else 0f; it.pushFilter() } }
            btn("hp$i", e, "High-pass filter (hold)") { dn -> deck(i)?.let { it.filter = if (dn) 0.65f else 0f; it.pushFilter() } }
            btn("echo$i", e, "Echo out") { dn -> if (dn) deck(i)?.echoOutFx() }
            btn("loopfx$i", e, "Loop out") { dn -> if (dn) deck(i)?.loopOutFx() }
            btn("spin$i", e, "Backspin") { dn -> if (dn) deck(i)?.backspinFx() }
            btn("fxoff$i", e, "All effects off") { dn -> if (dn) deck(i)?.resetFx() }
            knob("kfilter$i", e, "Filter knob (centre = off)") { v ->
                deck(i)?.let { val f = v * 2f - 1f; it.filter = if (kotlin.math.abs(f) < 0.06f) 0f else f; it.pushFilter() }
            }
            knob("krev$i", e, "Reverb knob") { v -> deck(i)?.let { it.reverb = v; it.pushReverb() } }
            knob("kpha$i", e, "Phaser knob") { v -> deck(i)?.let { it.phaser = v; it.pushPhaser() } }
            knob("kvol$i", "MIXER", "Volume $n fader") { v -> deck(i)?.let { it.vol = v; it.pushVolume() } }
        }
        btn("swapNext", "STEM SWAP", "Next swap page") { dn -> if (dn) StemSwap.nextPage(1) }
        btn("swapPrev", "STEM SWAP", "Previous swap page") { dn -> if (dn) StemSwap.nextPage(-1) }
        btn("swapLen", "STEM SWAP", "Swap length (beats)") { dn -> if (dn) StemSwap.cycleLength() }
        knob("kcross", "MIXER", "Crossfader") { v -> onCross?.invoke(v) }
        btn("crossA", "MIXER", "Crossfader to A") { dn -> if (dn) onCross?.invoke(0f) }
        btn("crossC", "MIXER", "Crossfader to centre") { dn -> if (dn) onCross?.invoke(0.5f) }
        btn("crossB", "MIXER", "Crossfader to B") { dn -> if (dn) onCross?.invoke(1f) }
        knob("kmaster", "MIXER", "Master volume") { v ->
            val g = 0.25f + v * 1.0f; AppSettings.masterGain = g; NativeEngine.setMasterGain(g)
        }
        knob("ksample", "SAMPLER PADS", "Sampler volume") { v -> AppSettings.sampleVol = v; NativeEngine.setSampleVolume(v) }

        // screen panels
        for ((id, pn, label) in listOf(
            Triple("pnStems", Panel.Stems, "Stems panel"), Triple("pnLoop", Panel.Loop, "Loop panel"), Triple("pnMixer", Panel.Mixer, "Mixer panel"),
            Triple("pnFx", Panel.Fx, "Effects panel"), Triple("pnPads", Panel.Pads, "Hot cues panel"), Triple("pnLib", Panel.Library, "Full library page"),
            Triple("pnSmp", Panel.Sampler, "Sampler panel"), Triple("pnVid", Panel.Video, "Video mixer panel"), Triple("pnSet", Panel.Settings, "Settings"),
            Triple("pnNone", Panel.None, "Close the open panel")
        )) btn(id, "SCREEN", label) { dn -> if (dn) onPanel?.invoke(pn) }

        // library dock: move the highlighted row and load it
        btn("libUp", "LIBRARY", "Move up") { dn -> if (dn) DockNav.move(-1) }
        btn("libDown", "LIBRARY", "Move down") { dn -> if (dn) DockNav.move(1) }
        btn("libPgUp", "LIBRARY", "Move up 5") { dn -> if (dn) DockNav.move(-5) }
        btn("libPgDown", "LIBRARY", "Move down 5") { dn -> if (dn) DockNav.move(5) }
        btn("libA", "LIBRARY", "Load highlighted track to deck A") { dn -> if (dn) DockNav.loadTo(0) }
        btn("libB", "LIBRARY", "Load highlighted track to deck B") { dn -> if (dn) DockNav.loadTo(1) }
        knob("klib", "LIBRARY", "Browse knob (turn to scroll)") { v -> DockNav.scrub(v) }
        btn("rec", "MIXER", "Record on / off") { dn -> if (dn) onRec?.invoke() }

        for (p in 0 until Sampler.BANKS * Sampler.PER_BANK) {
            val bank = p / Sampler.PER_BANK + 1
            btn("pad$p", "SAMPLER PADS", "Pad ${p + 1}  (bank $bank)") { dn ->
                if (p in Sampler.pads.indices) { if (dn) Sampler.down(p) else Sampler.up(p) }
            }
        }
        btn("padstop", "SAMPLER PADS", "Stop all samples") { dn -> if (dn) Sampler.stopAll() }
        list
    }

    private fun find(id: String): MapAction? = actions.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ saved bindings
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("djmap", Context.MODE_PRIVATE); prefs = p
        for ((k, v) in p.all) {
            if (k.startsWith("k.") && v is Int) keyOf[k.substring(2)] = v
            else if (k.startsWith("m.") && v is String) midiOf[k.substring(2)] = v
        }
        rebuild()
    }

    private fun rebuild() {
        byKey.clear(); byMidi.clear()
        for ((id, c) in keyOf) byKey[c] = id
        for ((id, m) in midiOf) byMidi[m] = id
    }

    private fun save() {
        val e = prefs?.edit() ?: return
        e.clear()
        for ((id, c) in keyOf) e.putInt("k.$id", c)
        for ((id, m) in midiOf) e.putString("m.$id", m)
        e.apply()
    }

    fun clear(id: String) { keyOf.remove(id); midiOf.remove(id); rebuild(); save() }
    fun clearAll() { keyOf.clear(); midiOf.clear(); learning = null; rebuild(); save() }

    private fun bindKey(id: String, code: Int) {
        keyOf.entries.removeAll { it.value == code }          // one key = one action
        keyOf[id] = code; rebuild(); save()
    }

    private fun bindMidi(id: String, m: String) {
        midiOf.entries.removeAll { it.value == m }
        midiOf[id] = m; rebuild(); save()
    }

    /** Text for the settings page, e.g. "Key Q  |  Note 36 ch1". */
    fun describe(id: String): String {
        val parts = ArrayList<String>()
        keyOf[id]?.let { parts.add("Key " + KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_")) }
        midiOf[id]?.let {
            val s = it.split(":")
            if (s.size == 3) parts.add((if (s[0] == "N") "Note " else "CC ") + s[2] + " ch" + (s[1].toInt() + 1))
        }
        return if (parts.isEmpty()) "not set" else parts.joinToString("  |  ")
    }

    // ------------------------------------------------------------------ keyboard / gamepad
    /** Called from MainActivity.dispatchKeyEvent. Returns true when the key was used. */
    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        // Never take over Back, Home or the phone volume keys.
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_APP_SWITCH) return false
        val l = learning
        if (l != null) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                val a = find(l)
                if (a != null && !a.knob) { bindKey(l, code); learning = null }
            }
            return true
        }
        val id = byKey[code] ?: return libraryKey(e)
        val a = find(id) ?: return false
        if (a.knob) return false
        if (e.repeatCount > 0) return true
        a.button(e.action == KeyEvent.ACTION_DOWN)
        return true
    }

    /** Fixed library keys (always on, like any DJ program) for keys the user has not mapped to something else:
     *  Up / Down = move, Page Up / Page Down = move 5, Left = load to deck A, Right = load to deck B,
     *  Enter = load to the deck that is not playing (A when both are stopped). */
    private fun libraryKey(e: KeyEvent): Boolean {
        if (!LayoutPrefs.showDock || DockNav.typing || DockNav.blocked || e.action != KeyEvent.ACTION_DOWN) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> DockNav.move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> DockNav.move(1)
            KeyEvent.KEYCODE_PAGE_UP -> DockNav.move(-5)
            KeyEvent.KEYCODE_PAGE_DOWN -> DockNav.move(5)
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (e.repeatCount == 0) DockNav.loadTo(0) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (e.repeatCount == 0) DockNav.loadTo(1) }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.repeatCount == 0) DockNav.loadTo(if (decks.getOrNull(0)?.playing == true && decks.getOrNull(1)?.playing != true) 1 else 0)
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ MIDI
    private var midiManager: MidiManager? = null
    private val opened = HashMap<Int, MidiDevice>()

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val copy = data.copyOfRange(offset, offset + count)
            main.post { parse(copy) }
        }
    }

    fun startMidi(ctx: Context) {
        if (midiManager != null) return
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) return
        val mm = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        midiManager = mm
        try {
            for (info in mm.devices) openDevice(mm, info)
            mm.registerDeviceCallback(object : MidiManager.DeviceCallback() {
                override fun onDeviceAdded(info: MidiDeviceInfo) { openDevice(mm, info) }
                override fun onDeviceRemoved(info: MidiDeviceInfo) {
                    opened.remove(info.id)?.let { try { it.close() } catch (_: Exception) {} }
                    midiDevices = opened.size
                }
            }, main)
        } catch (_: Exception) { }
    }

    private fun openDevice(mm: MidiManager, info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 || opened.containsKey(info.id)) return
        try {
            mm.openDevice(info, MidiManager.OnDeviceOpenedListener { dev ->
                if (dev == null) return@OnDeviceOpenedListener
                try {
                    for (p in 0 until info.outputPortCount) dev.openOutputPort(p)?.connect(receiver)
                    opened[info.id] = dev
                    midiDevices = opened.size
                } catch (_: Exception) { }
            }, main)
        } catch (_: Exception) { }
    }

    fun stopMidi() {
        for (d in opened.values) try { d.close() } catch (_: Exception) { }
        opened.clear(); midiDevices = 0
    }

    private fun parse(d: ByteArray) {
        var i = 0
        val end = d.size
        while (i < end) {
            val s = d[i].toInt() and 0xFF
            if (s < 0x80) { i++; continue }                 // data byte without a status (running status is not used by USB MIDI)
            val ch = s and 0x0F
            when (s and 0xF0) {
                0x80, 0x90, 0xB0 -> {
                    if (i + 2 >= end) return
                    val d1 = d[i + 1].toInt() and 0x7F
                    val d2 = d[i + 2].toInt() and 0x7F
                    handle(s and 0xF0, ch, d1, d2)
                    i += 3
                }
                0xE0 -> i += 3
                0xC0, 0xD0 -> i += 2
                else -> i += 1
            }
        }
    }

    private fun handle(type: Int, ch: Int, num: Int, value: Int) {
        val isCc = type == 0xB0
        val down = if (isCc) value >= 64 else type == 0x90 && value > 0
        lastMidi = (if (isCc) "CC $num = $value" else "Note $num ${if (down) "on" else "off"}") + "  (ch ${ch + 1})"
        val key = (if (isCc) "C:" else "N:") + ch + ":" + num

        val l = learning
        if (l != null) {
            val a = find(l) ?: return
            if (a.knob) { if (isCc) { bindMidi(l, key); learning = null } }
            else if (down) { bindMidi(l, key); learning = null }
            return
        }
        val id = byMidi[key] ?: return
        val a = find(id) ?: return
        if (a.knob) { if (isCc) a.knobRun(value / 127f) }
        else a.button(down)
    }
}
