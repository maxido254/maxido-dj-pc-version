package com.example.djapp

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        CrashLog.install(this)
        AppSettings.load(this)
        LayoutPrefs.load(this)
        if (AppSettings.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Settings first (no audio stream exists yet, so this is instant), then open the audio stream ONCE, off the
        // main thread. Opening it can take seconds on a budget phone and used to freeze the first screen (ANR).
        AppSettings.applyToEngine()
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val ok = try { NativeEngine.start() } catch (_: Throwable) { false }
            if (!ok) ui.post { Toast.makeText(this, "Could not start audio output", Toast.LENGTH_LONG).show() }
        }, "audio-start").start()
        Skin.init(this)
        Store.init(this)
        Pro.init(this)
        Sampler.init(this)
        StemSwap.init(this)
        Prepared.init(this)
        Mappings.init(this)
        // USB MIDI is optional: look for devices after the screen is already up.
        ui.postDelayed({ if (!isFinishing && !isDestroyed) Mappings.startMidi(this) }, 2000)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var splash by rememberSaveable { mutableStateOf(true) }
                Box(Modifier.fillMaxSize()) {
                    DjScreen()
                    if (splash) SplashScreen { splash = false }
                }
            }
        }
    }
    /** Keyboard / gamepad keys mapped in Settings > CONTROLS. Unmapped keys behave as usual. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        if (Mappings.onKey(event)) true else super.dispatchKeyEvent(event)

    override fun onDestroy() {
        Mappings.stopMidi(); VideoMixer.releaseAll()
        // Closing the audio stream can block for seconds on a slow phone. Do it on another thread and wait only a moment,
        // so closing the app is instant (the system frees the audio stream when the process ends anyway).
        val t = Thread({ try { NativeEngine.stop() } catch (_: Throwable) { } }, "audio-stop")
        t.start()
        try { t.join(300) } catch (_: InterruptedException) { }
        super.onDestroy()
        // Closed with Back: end the whole process too (audio engine, background preparing, AI process).
        if (isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** Layout weight of the title bar with the deck names (the waveform strip above it and the dock below use LayoutPrefs). */
private const val TOPBAR_W = 8f

@Composable
fun DjScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val decks = remember { listOf(DeckState(0), DeckState(1)).also { StemSwap.decks = it } }
    var panel by rememberSaveable { mutableStateOf(Panel.None) }
    var cross by remember { mutableFloatStateOf(0.5f) }
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }
    var recPath by remember { mutableStateOf("") }
    val level = UiClock.level

    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) decks[0].load(ctx, scope, uri) }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) decks[1].load(ctx, scope, uri) }
    val videoA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) VideoMixer.layers[0].load(ctx, uri, lock = false) }
    val videoB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) VideoMixer.layers[1].load(ctx, uri, lock = false) }

    LaunchedEffect(Unit) { decks.forEach { NativeEngine.setKeylock(it.id, it.keylock) } }
    LaunchedEffect(Unit) {
        while (true) {
            for (d in decks) { d.playing = NativeEngine.isPlaying(d.id); d.posSec = NativeEngine.getPosition(d.id) }
            for (d in decks) d.syncTick()
            VideoMixer.tick(decks)
            delay(200)
        }
    }
    LaunchedEffect(Unit) { runUiClock() }
    LaunchedEffect(recording) { if (recording) { recSecs = 0; while (true) { delay(1000); recSecs++ } } }
    BackHandler(panel != Panel.None) { panel = Panel.None }
    // Back twice (within 2 s) on the main screen closes the whole app.
    var lastBack by remember { mutableStateOf(0L) }
    BackHandler(panel == Panel.None) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) (ctx as? android.app.Activity)?.finishAndRemoveTask()
        else { lastBack = now; Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show() }
    }

    fun toggleRec() {
        if (recording) {
            NativeEngine.stopRecording(); recording = false
            Toast.makeText(ctx, "Saved: $recPath", Toast.LENGTH_LONG).show()
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
            val f = File(dir, "DJ_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav")
            if (NativeEngine.startRecording(f.absolutePath)) { recPath = f.absolutePath; recording = true }
            else Toast.makeText(ctx, "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }
    SideEffect {
        Mappings.decks = decks
        Mappings.onCross = { cross = it; NativeEngine.setCrossfader(it); VideoMixer.audioCross = it }
        Mappings.onRec = { toggleRec() }
    }
    fun sync(me: DeckState, other: DeckState) {
        if (me.syncOn) me.stopSync()
        else if (!me.syncTo(other)) Toast.makeText(ctx, "BPM not detected yet", Toast.LENGTH_SHORT).show()
    }
    fun toggle(p: Panel) {
        if (p == Panel.Video && panel != p && !Pro.gate(Pro.VIDEO)) return     // VIDEO MIXER is PRO: the paywall opens instead
        panel = if (panel == p) Panel.None else p
    }
    LaunchedEffect(panel) { if (panel == Panel.Video && !Pro.has(Pro.VIDEO)) panel = Panel.None }   // e.g. restored after a restart
    SideEffect {
        Mappings.onPanel = { toggle(it) }
        DockNav.blocked = panel == Panel.Library || panel == Panel.Settings
    }
    // A deck that is playing is never cut by a mis-tap: ask first (YES stops it and loads the new song, NO keeps playing).
    var pendingLoad by remember { mutableStateOf<Pair<Int, () -> Unit>?>(null) }
    fun loadFromLibrary(deck: Int, uri: android.net.Uri) {
        if (decks[deck].playing) pendingLoad = deck to { decks[deck].load(ctx, scope, uri) }
        else decks[deck].load(ctx, scope, uri)
    }

    fun loadPackFromLibrary(deck: Int, dirName: String) {
        if (decks[deck].playing) pendingLoad = deck to { decks[deck].loadPack(ctx, scope, dirName) }   // screen-level scope: survives the library closing
        else decks[deck].loadPack(ctx, scope, dirName)
    }

    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) {
        val top = LayoutPrefs.wavesOnTop
        val host: @Composable (Modifier) -> Unit = { m ->
            PanelHost(
                panel, decks, ::loadFromLibrary,
                { d -> (if (d == 0) pickA else pickB).launch(arrayOf("audio/*", "video/*")) },
                { d -> (if (d == 0) videoA else videoB).launch(arrayOf("video/*")) },
                ::loadPackFromLibrary,
                modifier = m
            ) { panel = Panel.None }
        }
        Column(Modifier.fillMaxSize().background(BgDark)) {
            // Waveforms: small strip at the very top of the screen (default), or below the top bar.
            if (top) {
                Box(Modifier.weight(LayoutPrefs.topWavePct).fillMaxWidth()) { WaveSection(decks) }
                HLine()
            }
            Box(Modifier.weight(TOPBAR_W).fillMaxWidth()) {
                TopBar(decks, level, recording, recSecs, { panel = Panel.Library }, { panel = Panel.Library }, ::toggleRec)
            }
            HLine()
            Box(Modifier.weight(100f - TOPBAR_W - LayoutPrefs.bottomPct - (if (top) LayoutPrefs.topWavePct else 0f)).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    if (top) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { MiddleSection(decks, level, panel, ::toggle) }
                    } else {
                        Box(Modifier.weight(LayoutPrefs.wavePct).fillMaxWidth()) { WaveSection(decks) }
                        HLine()
                        Box(Modifier.weight(100f - LayoutPrefs.wavePct).fillMaxWidth()) { MiddleSection(decks, level, panel, ::toggle) }
                    }
                }
                // The sampler is a strip over the waveforms only, so the filter / bass faders, platters,
                // CUE and PLAY stay uncovered and can be used together with the pads (multi-touch).
                // With the waveforms on top, that strip is placed over the top of the whole screen (see below).
                if (panel != Panel.None && panel != Panel.Library && panel != Panel.Settings && !(top && panel == Panel.Sampler)) host(
                    if (panel == Panel.Sampler) Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(LayoutPrefs.wavePct / 100f)
                    else Modifier.fillMaxSize()
                )
            }
            HLine()
            Box(Modifier.weight(LayoutPrefs.bottomPct).fillMaxWidth()) {
                BottomBar(decks, cross, { cross = it; NativeEngine.setCrossfader(it); VideoMixer.audioCross = it }, ::sync)
            }
        }
        if (top && panel == Panel.Sampler) host(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight((LayoutPrefs.topWavePct + 12f) / 100f)
        )
        // The library and settings are full-screen pages over the whole app (top bar and bottom bar included).
        if (panel == Panel.Library || panel == Panel.Settings) PanelHost(
            panel, decks, ::loadFromLibrary,
            { d -> (if (d == 0) pickA else pickB).launch(arrayOf("audio/*", "video/*")) },
            { d -> (if (d == 0) videoA else videoB).launch(arrayOf("video/*")) },
            ::loadPackFromLibrary,
            modifier = Modifier.fillMaxSize()
        ) { panel = Panel.None }
        FloatingVideo()
        StemSwapPagesDialog()
        PaywallHost()
        pendingLoad?.let { (deck, run) ->
            val name = if (deck == 0) "A" else "B"
            val col = if (deck == 0) DeckA else DeckB
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingLoad = null },
                title = { Text("Deck $name is playing", fontSize = 15.sp) },
                text = { Text("Load the new song and stop deck $name?", fontSize = 13.sp) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        decks[deck].playing = false; NativeEngine.setPlaying(deck, false)
                        pendingLoad = null; run()
                    }) { Text("YES", color = col, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingLoad = null }) { Text("NO") } }
            )
        }
        CrashLog.last?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).clickable { }.padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("The app closed unexpectedly last time. Please screenshot this and send it to the developer:", color = Color.White, fontSize = 12.sp)
                    Text(msg, color = Color(0xFFFFB4A9), fontSize = 8.sp, modifier = Modifier.weight(1f))
                    PadButton("CLOSE", Modifier.width(100.dp), active = true) { CrashLog.dismiss() }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ waveforms (Serato-style)
@Composable
private fun WaveSection(decks: List<DeckState>) {
    Column(Modifier.fillMaxSize()) {
        for (i in 0..1) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (LayoutPrefs.showOverview && LayoutPrefs.wavesOnTop) {
                    // Small top strip: a fixed-height overview you can touch to jump anywhere in the song.
                    ScrollingWave(decks[i], if (i == 0) "A" else "B", Modifier.weight(1f).fillMaxWidth())
                    OverviewWave(decks[i], Modifier.height(8.dp).fillMaxWidth())
                } else if (LayoutPrefs.showOverview) {
                    ScrollingWave(decks[i], if (i == 0) "A" else "B", Modifier.weight(3f).fillMaxWidth())
                    OverviewWave(decks[i], Modifier.weight(1f).fillMaxWidth())
                } else {
                    ScrollingWave(decks[i], if (i == 0) "A" else "B", Modifier.weight(1f).fillMaxWidth())
                }
            }
            if (i == 0) HLine()
        }
    }
}

@Composable private fun HLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.6f)))

// ------------------------------------------------------------------ top bar
@Composable
private fun TopBar(
    decks: List<DeckState>, level: State<Float>, recording: Boolean, recSecs: Int,
    loadA: () -> Unit, loadB: () -> Unit, onRec: () -> Unit
) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(Modifier.fillMaxHeight(0.86f).aspectRatio(1f), loadA, borderColor = Color.White) {
            PlusIcon(DeckA, Modifier.fillMaxSize(0.42f))
        }
        DeckTitle(decks[0], Modifier.weight(1f))
        Row(Modifier.width(300.dp).fillMaxHeight(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (Skin.activeId == Skin.PRO_ID) {      // lower-case wordmark: bold name, light grey "DJ"
                        Text("maxido", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text(" DJ", color = TextGray, fontSize = 19.sp, fontWeight = FontWeight.Light, maxLines = 1)
                    } else {
                        Text("MAXIDO", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, maxLines = 1)
                        Text(" DJ", color = DeckA, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Text("EXPERIENCE THE BEAT", color = TextGray, fontSize = 7.sp, letterSpacing = 1.6.sp, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp, 20.dp)) {
                    val lv = level.value.coerceIn(0f, 1f)
                    val hs = listOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)
                    val bw = 2.2.dp.toPx(); val gap = (size.width - bw * 5) / 4
                    for (i in 0..4) {
                        val h = size.height * (0.18f + 0.82f * lv * hs[i])
                        drawRect(Color.White, Offset(i * (bw + gap), (size.height - h) / 2), Size(bw, h))
                    }
                }
            }
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRec).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                RecDot(recording)
                Spacer(Modifier.width(5.dp))
                Text(
                    if (recording) "%d:%02d".format(recSecs / 60, recSecs % 60) else "REC",
                    color = Color.White, fontSize = 13.sp
                )
            }
        }
        DeckTitle(decks[1], Modifier.weight(1f))
        CircleIconButton(Modifier.fillMaxHeight(0.86f).aspectRatio(1f), loadB, borderColor = Color.White) {
            PlusIcon(DeckB, Modifier.fillMaxSize(0.42f))
        }
    }
}

/** The red REC dot. The blink animation only exists while recording (an always-running animation keeps the screen redrawing). */
@Composable
private fun RecDot(recording: Boolean) {
    val alpha = if (recording) {
        val blink by rememberInfiniteTransition(label = "rec").animateFloat(
            1f, 0.25f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "recAlpha"
        )
        blink
    } else 1f
    Box(Modifier.size(15.dp).clip(CircleShape).background(Color(0xFFFF3B4E).copy(alpha = alpha)))
}

@Composable
private fun DeckTitle(d: DeckState, modifier: Modifier) {
    val sub = buildList {
        if (d.loading) add("decoding…")
        else if (d.loaded) {
            add(if (d.bpm > 0f) "%.2f BPM".format(d.effectiveBpm) else "analysing BPM…")
            if (d.key.isNotEmpty()) add(d.key)
            when {
                d.stemProg in 0f..1f -> add(if (AppSettings.modelStatus.isNotEmpty()) AppSettings.modelStatus else "stems ${(d.stemProg * 100).toInt()}%")
                d.stemsReady -> add("stems ready")
                else -> {}
            }
        } else add("tap + to load")
    }.joinToString("  ·  ")
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            d.name.uppercase(), color = TextGray, fontSize = 14.sp, fontWeight = FontWeight.Light,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Text(sub, color = d.accent.copy(alpha = 0.9f), fontSize = 9.sp, maxLines = 1)
    }
}

// ------------------------------------------------------------------ middle: tempo | platter | controls | platter | tempo
@Composable
private fun MiddleSection(decks: List<DeckState>, level: State<Float>, panel: Panel, toggle: (Panel) -> Unit) {
    Row(Modifier.fillMaxSize()) {
        TempoColumn(decks[0], Modifier.weight(7f).fillMaxHeight()) {
            CircleIconButton(Modifier.size(32.dp), { toggle(Panel.Stems) }, active = panel == Panel.Stems, accent = DeckALight) {
                WaveIcon(Color.White, Modifier.size(18.dp))
            }
        }
        Box(Modifier.weight(LayoutPrefs.deckPct).fillMaxHeight()) { DeckZone(decks[0], DeckA, DeckADark) }
        CenterColumn(decks, level, panel, toggle, Modifier.weight(LayoutPrefs.centerPct).fillMaxHeight())
        Box(Modifier.weight(LayoutPrefs.deckPct).fillMaxHeight()) { DeckZone(decks[1], DeckB, DeckBDark) }
        TempoColumn(decks[1], Modifier.weight(7f).fillMaxHeight()) {
            CircleIconButton(Modifier.size(32.dp), { toggle(Panel.Settings) }, active = panel == Panel.Settings, accent = Accent) {
                GearIcon(Color.White, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun GearIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val c = center; val r = size.minDimension * 0.28f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
        drawLine(color, c + Offset(dx * r, dy * r), c + Offset(dx * r * 1.65f, dy * r * 1.65f), r * 0.62f, StrokeCap.Butt)
    }
    drawCircle(color, r * 1.25f, c); drawCircle(BgDark, r * 0.55f, c)
}

@Composable
private fun TempoColumn(deck: DeckState, modifier: Modifier, icon: @Composable () -> Unit) {
    Column(modifier.padding(vertical = 8.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        TempoSlider(deck, Modifier.weight(1f).fillMaxWidth())
        Box(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp))
                .background(if (deck.keylock) deck.accent else Color(0xFF111B2E)).clickable { deck.toggleKeylock() },
            contentAlignment = Alignment.Center
        ) { Text("KEYLOCK", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

@Composable
private fun TempoSlider(deck: DeckState, modifier: Modifier = Modifier) {
    val range = AppSettings.tempoRange
    BoxWithConstraints(modifier.padding(vertical = 17.dp)) {
        val h = maxHeight
        val frac = (deck.tempoPct / range / 2f + 0.5f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().faderTouch(true) { f ->
            var p = (f - 0.5f) * 2f * range
            if (abs(p) < range * 0.03f) p = 0f          // detent at 0 %
            deck.applyTempo(p)
        })
        Canvas(Modifier.fillMaxSize()) {
            val tw = 17.dp.toPx()
            drawRoundRect(Color(0xFF070B14), Offset((size.width - tw) / 2, -4.dp.toPx()), Size(tw, size.height + 8.dp.toPx()), CornerRadius(tw / 2))
            drawCircle(deck.accent, 3.5.dp.toPx(), Offset(if (deck.id == 0) 0f else size.width, size.height / 2))
        }
        val pct = deck.tempoPct * 100f
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = h * (1f - frac) - 15.dp).fillMaxWidth(0.98f).height(30.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0xFFE6E6E8)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (abs(pct) < 0.05f) "0.0 %" else "%+.1f %%".format(pct), color = Color(0xFF666870), fontSize = 9.sp, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ platter with scratch
/** One deck's play area. Upper part: filter + bass faders, the jog wheel and the effect buttons. Lower part: the hot-cue
 *  pads and, further down, the four stem buttons. Both rows use the full width of the deck so the buttons are big, and
 *  the faders stop well above them (gap) so a finger on a fader can never hit a pad or a stem button by accident. */
@Composable
private fun DeckZone(deck: DeckState, color: Color, inner: Color) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (LayoutPrefs.showInfo) ProDeckInfo(deck, Modifier.width(60.dp).fillMaxHeight())
            if (LayoutPrefs.showFilter) FilterFader(deck, Modifier.width(LayoutPrefs.faderDp.dp).fillMaxHeight().padding(vertical = 6.dp))
            if (LayoutPrefs.showFilter && LayoutPrefs.showBass) Spacer(Modifier.width(10.dp))          // the two faders never touch
            if (LayoutPrefs.showBass) BassFader(deck, Modifier.width(LayoutPrefs.faderDp.dp).fillMaxHeight().padding(vertical = 6.dp))
            Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp)) { Platter(deck, color, inner) }
            if (LayoutPrefs.showFxCol) FxColumn(deck, Modifier.width(54.dp).fillMaxHeight().padding(start = 2.dp, top = 6.dp, bottom = 6.dp))
        }
        if (LayoutPrefs.showPads) ProPadRow(deck, Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
        if (LayoutPrefs.showStems) StemButtons(deck, Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = if (LayoutPrefs.showSwap) 2.dp else 5.dp))
        if (LayoutPrefs.showStems && LayoutPrefs.showSwap) SwapPads(deck, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 5.dp))
    }
}

/** Performance effects on the right of the jog wheel: LOOP OUT, ECHO OUT and BACKSPIN. */
@Composable
private fun FxColumn(d: DeckState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton("LOOP\nOUT", Modifier.fillMaxWidth().weight(1f), active = d.fxBusy == "LOOP", color = d.accent, enabled = d.loaded, fontSize = 9) { d.loopOutFx() }
        PadButton("ECHO\nOUT", Modifier.fillMaxWidth().weight(1f), active = d.fxBusy == "ECHO", color = d.accent, enabled = d.loaded, fontSize = 9) { d.echoOutFx() }
        PadButton(if (d.fxBusy == "SPIN") "DROP" else "BACK\nSPIN", Modifier.fillMaxWidth().weight(1f), active = d.fxBusy == "SPIN", color = d.accent, enabled = d.loaded, fontSize = 9) { d.backspinFx() }
    }
}

/** Vertical fader with a centre detent: [pos] -1..1, 0 = off/flat. Tap the label to reset. */
@Composable
private fun CenterFader(label: String, active: Boolean, pos: Float, accent: Color, modifier: Modifier, onPos: (Float) -> Unit, onReset: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label, color = if (active) accent else TextGray, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.clickable { onReset() }.padding(bottom = 3.dp)
        )
        Box(Modifier.weight(1f).fillMaxWidth().faderTouch(true) { f ->
            val v = f * 2f - 1f
            onPos(if (abs(v) < 0.07f) 0f else v.coerceIn(-1f, 1f))        // snaps to the middle
        }) {
            Canvas(Modifier.fillMaxSize()) {
                val tw = 6.dp.toPx(); val tx = size.width / 2
                val th = 14.dp.toPx(); val top = th / 2; val bot = size.height - th / 2
                val mid = (top + bot) / 2
                val y = bot - (bot - top) * ((pos + 1f) / 2f)
                drawRoundRect(Color(0xFF070B14), Offset(tx - tw / 2, top), Size(tw, bot - top), androidx.compose.ui.geometry.CornerRadius(tw / 2))
                drawRect(if (active) accent else Color(0xFF3A3D45), Offset(tx - tw / 2, minOf(y, mid)), Size(tw, abs(y - mid)))
                drawLine(Color(0xFF6A6D75), Offset(tx - 14.dp.toPx(), mid), Offset(tx + 14.dp.toPx(), mid), 1.5.dp.toPx())
                drawRoundRect(if (active) Color.White else Color(0xFFB9BBC2), Offset(tx - 15.dp.toPx(), y - th / 2), Size(30.dp.toPx(), th), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                drawLine(Color(0xFF30333A), Offset(tx - 11.dp.toPx(), y), Offset(tx + 11.dp.toPx(), y), 1.5.dp.toPx())
            }
        }
    }
}

/** DJ filter: centre = off, down = low-pass, up = high-pass. */
@Composable
private fun FilterFader(d: DeckState, modifier: Modifier) =
    CenterFader(
        when { d.filter < -0.02f -> "LP"; d.filter > 0.02f -> "HP"; else -> "FILTER" }, d.filter != 0f, d.filter, d.accent, modifier,
        { d.filter = it; d.pushFilter() }, { d.filter = 0f; d.pushFilter() }
    )

/** Cut-only fader (like a bass kill): top = flat, pushing it DOWN cuts the bass, bottom = fully cut. It stays where you
 *  leave it (it never springs back or goes up into a boost). Tap the label to put it back to flat. */
@Composable
private fun CutFader(label: String, active: Boolean, frac: Float, accent: Color, modifier: Modifier, onFrac: (Float) -> Unit, onReset: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label, color = if (active) accent else TextGray, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.clickable { onReset() }.padding(bottom = 3.dp)
        )
        Box(Modifier.weight(1f).fillMaxWidth().faderTouch(true) { f -> onFrac(f.coerceIn(0f, 1f)) }) {
            Canvas(Modifier.fillMaxSize()) {
                val tw = 6.dp.toPx(); val tx = size.width / 2
                val th = 16.dp.toPx(); val top = th / 2; val bot = size.height - th / 2
                val y = bot - (bot - top) * frac.coerceIn(0f, 1f)                 // frac 1 = top = flat
                drawRoundRect(Color(0xFF070B14), Offset(tx - tw / 2, top), Size(tw, bot - top), CornerRadius(tw / 2))
                // the part that has been cut away is filled from the top down to the knob
                drawRect(if (active) accent else Color(0xFF3A3D45), Offset(tx - tw / 2, top), Size(tw, (y - top).coerceAtLeast(0f)))
                drawLine(Color(0xFF6A6D75), Offset(tx - 14.dp.toPx(), top), Offset(tx + 14.dp.toPx(), top), 1.5.dp.toPx())
                drawRoundRect(if (active) Color.White else Color(0xFFB9BBC2), Offset(tx - 16.dp.toPx(), y - th / 2), Size(32.dp.toPx(), th), CornerRadius(3.dp.toPx()))
                drawLine(Color(0xFF30333A), Offset(tx - 12.dp.toPx(), y), Offset(tx + 12.dp.toPx(), y), 1.5.dp.toPx())
            }
        }
    }
}

/** Finger position 0..1 (1 = top) to bass gain: a small flat zone at the top, a full-cut zone at the bottom. */
private fun bassDbFromFrac(f: Float): Float = when {
    f >= 0.92f -> 0f
    f <= 0.05f -> -26f
    else -> -26f * (1f - (f - 0.05f) / 0.87f)
}
private fun bassFracFromDb(db: Float): Float = when {
    db >= -0.5f -> 1f
    db <= -25.5f -> 0f
    else -> 0.05f + 0.87f * (1f + db / 26f)
}

/** Bass EQ as a kill fader: down cuts (to a near kill for transitions), up returns to flat. No boost here (the Mixer panel still has one). */
@Composable
private fun BassFader(d: DeckState, modifier: Modifier) {
    val db = d.eq[0].coerceAtMost(0f)
    CutFader(
        if (db > -0.5f) "BASS" else eqText(db).replace(" dB", ""), db <= -0.5f, bassFracFromDb(db), d.accent, modifier,
        { d.setEq(0, bassDbFromFrac(it)) }, { d.setEq(0, 0f) }
    )
}

/** VOCAL / MELODY / BASS / INSTR (drums): tap to mute or bring back that part of the track. Lit = playing. */
@Composable
private fun StemButtons(d: DeckState, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, idx) in listOf("VOCAL" to 0, "MELODY" to 3, "BASS" to 2, "INSTR" to 1))
            PadButton(label, Modifier.weight(1f).height(LayoutPrefs.padDp.dp), active = d.stemOn[idx] && d.stemsReady, color = d.accent, enabled = d.stemsReady, fontSize = 11) { d.toggleStem(idx) }
    }
}

/** STEM SWAP pads under the stem buttons, used like hot cues: tap an empty pad to store the vocal, tap a filled one to swap it in.
 *  Long-press a filled pad to erase it. The page button shows the page name: tap = next page, long-press = the pages list (jump to any page, name pages). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SwapPads(d: DeckState, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val looping = d.loopActive && d.loopOut > d.loopIn
        PadButton(if (looping) "LOOP" else "${StemSwap.lenBeats.toInt()}b", Modifier.weight(1f).height(LayoutPrefs.padDp.dp), enabled = !looping, fontSize = 9) { StemSwap.cycleLength() }
        for (i in 0 until StemSwap.PER_PAGE) {
            val slot = StemSwap.page * StemSwap.PER_PAGE + i
            val clip = StemSwap.clips[slot]
            val on = d.swapSlot == slot
            val col = HotColorsSwap[i]
            Box(
                Modifier.weight(1.15f).height(LayoutPrefs.padDp.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) col else if (clip.filled) col.copy(alpha = 0.30f) else BtnBottom)
                    .combinedClickable(onClick = { StemSwap.tap(d, slot) }, onLongClick = { StemSwap.erase(slot) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    StemSwap.label(slot), color = if (on) Color.Black else if (clip.filled) col else Color(0xFF777A82),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
        Box(
            Modifier.weight(0.9f).height(LayoutPrefs.padDp.dp).clip(RoundedCornerShape(10.dp)).background(BtnBottom)
                .combinedClickable(onClick = { StemSwap.nextPage(1) }, onLongClick = { StemSwap.pagesOpen = true }),
            contentAlignment = Alignment.Center
        ) { Text(StemSwap.pageLabel(StemSwap.page), color = Color(0xFFE6E7EA), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, modifier = Modifier.padding(horizontal = 2.dp)) }
    }
}

/** All 8 stem swap pages: type a name for each, tap GO to jump to it. */
@Composable
private fun StemSwapPagesDialog() {
    if (!StemSwap.pagesOpen) return
    val close = { StemSwap.savePageNames(); StemSwap.pagesOpen = false }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { close() },
        title = { Text("Stem swap pages", fontSize = 15.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in 0 until StemSwap.PAGES) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = StemSwap.pageNames[p], onValueChange = { StemSwap.setPageName(p, it) }, singleLine = true,
                        label = { Text(if (p == StemSwap.page) "Page ${p + 1} (showing)" else "Page ${p + 1}") }, modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(onClick = { StemSwap.page = p; close() }) { Text("Go") }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { close() }) { Text("Done") } }
    )
}

private val HotColorsSwap = listOf(Color(0xFFFF5C7A), Color(0xFFFFB74D), Color(0xFF64B5F6), Color(0xFFBA68C8))

@Composable
private fun Platter(deck: DeckState, color: Color, inner: Color) {
    // Angle and progress come from the shared UiClock and are read only while drawing, so the platter never recomposes per frame.
    val clockPos = UiClock.pos[deck.id.coerceIn(0, 1)]
    fun angleNow(): Float = ((clockPos.doubleValue * 200.0) % 360.0).toFloat()          // 200 deg/s ~ 33 rpm
    fun progressNow(): Float = if (deck.duration > 0) (clockPos.doubleValue / deck.duration).toFloat().coerceIn(0f, 1f) else 0f
    val secShown by remember(deck) { derivedStateOf { deck.posSec.toInt() } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val dia = minOf(maxWidth, maxHeight)
        Box(
            Modifier.size(dia).pointerInput(deck) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cx = size.width / 2f; val cy = size.height / 2f
                    fun ang(p: Offset) = Math.toDegrees(atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())).toFloat()
                    var last = ang(down.position); var lastT = down.uptimeMillis
                    var engaged = false; var travelled = 0f; var acc = 0f; var basePos = 0.0
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val a = ang(c.position)
                        var da = a - last
                        if (da > 180f) da -= 360f; if (da < -180f) da += 360f
                        travelled += abs(da)
                        if (!engaged && travelled > 3f) {           // plain taps don't stop the music
                            deck.cancelFx()                          // touching the platter takes over from any running effect
                            engaged = true; acc = 0f; basePos = NativeEngine.getPosition(deck.id)
                        } else if (engaged) acc += da
                        if (engaged) {
                            // The platter angle IS the track position (200 deg/s = 33 rpm); the engine follows it smoothly.
                            val t = (basePos + acc / 200.0).coerceIn(0.0, maxOf(deck.duration, 0.0))
                            NativeEngine.setJog(deck.id, true, t)
                        }
                        last = a; lastT = c.uptimeMillis; c.consume()
                    }
                    if (engaged) NativeEngine.setJog(deck.id, false, 0.0)
                }
            },
            contentAlignment = Alignment.Center
        ) {
            // Static artwork: reads no changing state, so it is recorded once instead of every frame.
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2; val c = center
                drawCircle(Brush.radialGradient(listOf(Color(0xFF1B2333), Color(0xFF0A0E16), Color(0xFF04060A)), center = c, radius = r), r, c)
                drawCircle(color.copy(alpha = 0.25f), r * 0.94f, c, style = Stroke(3.dp.toPx()))
                for (k in listOf(0.55f, 0.66f, 0.77f, 0.88f)) drawCircle(Color.White.copy(alpha = 0.05f), r * k, c, style = Stroke(1.dp.toPx()))
                drawCircle(Color(0xFF1B2740), r, c, style = Stroke(1.5.dp.toPx()))
                drawCircle(inner, r * 0.355f, c)
                drawCircle(color, r * 0.355f, c, style = Stroke(1.5.dp.toPx()))
            }
            // Moving parts: progress ring and the tick on the label.
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2; val c = center
                val progress = progressNow(); val angle = angleNow()
                if (progress > 0f) drawArc(
                    color, -90f, progress * 360f, false,
                    topLeft = c - Offset(r * 0.94f, r * 0.94f), size = Size(r * 1.88f, r * 1.88f),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
                rotate(angle, pivot = c) {
                    // small tick on the label so you can see the platter turn
                    drawRect(Color.White, c + Offset(-r * 0.012f, -r * 0.355f), Size(r * 0.024f, r * 0.07f))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (deck.loaded && deck.bpm > 0f) "%.1f".format(deck.effectiveBpm) else "---", color = Color.White,
                    fontSize = (dia.value * 0.16f).coerceIn(10f, 26f).sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
                Text("BPM", color = color, fontSize = (dia.value * 0.07f).coerceIn(6f, 10f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(
                if (deck.loaded) "%d:%02d / %d:%02d".format(secShown / 60, secShown % 60, deck.duration.toInt() / 60, deck.duration.toInt() % 60) else "",
                color = Color.White.copy(alpha = 0.75f), fontSize = (dia.value * 0.075f).coerceIn(7f, 11f).sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dia * 0.1f)
            )
            val badge = buildString {
                if (deck.loopActive) append("LOOP ")
                if (deck.filter != 0f) append("FLT ")
                if (deck.reverb > 0f) append("REV ")
                if (deck.phaser > 0f) append("PHS ")
            }.trim()
            if (badge.isNotEmpty()) Text(
                badge, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = dia * 0.1f)
            )
        }
    }
}

// ------------------------------------------------------------------ centre column
@Composable
private fun MasterMeter(level: State<Float>, modifier: Modifier) {
    Box(modifier.background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val lv = level.value.coerceIn(0f, 1f)
            val top = size.height * (1f - lv)
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFFFF4B4B), Color(0xFFFFC93D), Color(0xFF2BE37A)), 0f, size.height),
                Offset(0f, top), Size(size.width, size.height - top)
            )
        }
    }
}

@Composable
private fun CenterColumn(decks: List<DeckState>, level: State<Float>, panel: Panel, toggle: (Panel) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
    // 4 round buttons sit in one row: whatever size is chosen, they must fit the width of this column.
    val fit = (maxWidth - 16.dp) / 4          // 16 dp = 4 dp margin + 3 gaps of 4 dp
    val want = LayoutPrefs.iconDp.dp
    Column(Modifier.fillMaxSize().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val bs = Modifier.size(if (want < fit) want else fit)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CircleIconButton(bs, { toggle(Panel.Loop) }, active = panel == Panel.Loop || decks.any { it.loopActive }) {
                Text("↻", color = Color.White, fontSize = 17.sp)
            }
            CircleIconButton(bs, { toggle(Panel.Mixer) }, active = panel == Panel.Mixer) {
                MixerIcon(Color.White, Modifier.size(16.dp))
            }
            CircleIconButton(bs, { toggle(Panel.Fx) }, active = panel == Panel.Fx || decks.any { it.reverb > 0f || it.phaser > 0f }) {
                Text("FX", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
            }
            CircleIconButton(bs, { toggle(Panel.Library) }, active = panel == Panel.Library || Library.analysing) {
                Text("LIB", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (LayoutPrefs.showMixer) {
            // Channel strips (HI / MID / LOW / VOL) for both decks with the master level between them.
            Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ChannelStrip(decks[0], Modifier.weight(1f).fillMaxHeight())
                if (LayoutPrefs.showMeter) MasterMeter(level, Modifier.width(9.dp).fillMaxHeight())
                ChannelStrip(decks[1], Modifier.weight(1f).fillMaxHeight())
            }
        } else if (!LayoutPrefs.showMeter) Spacer(Modifier.weight(1f))
        else MasterMeter(level, Modifier.weight(1f).padding(vertical = 10.dp).width(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CircleIconButton(bs, { toggle(Panel.Pads) }, active = panel == Panel.Pads) {
                GridIcon(Color.White, Modifier.size(15.dp))
            }
            CircleIconButton(bs, { toggle(Panel.Sampler) }, active = panel == Panel.Sampler || Sampler.pads.any { it.playing }, accent = DeckALight) {
                Text("SMP", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            CircleIconButton(bs, { toggle(Panel.Video) }, active = panel == Panel.Video || VideoMixer.layers.any { it.ready }, accent = Accent) {
                Text("VID", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    }
}

// ------------------------------------------------------------------ bottom: transport | crossfader | transport
@Composable
private fun BottomBar(decks: List<DeckState>, cross: Float, onCross: (Float) -> Unit, onSync: (DeckState, DeckState) -> Unit) {
    val a = decks[0]; val b = decks[1]
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(31f).height(TRANSPORT_H), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CueButton(a, Modifier.weight(1f))
            PlayButton(a, DeckA, DeckALight, Modifier.weight(1f))
            if (LayoutPrefs.showSync) SyncButton(Modifier.weight(1f), a.syncOn) { onSync(a, b) }
        }
        Crossfader(cross, onCross, Modifier.weight(23f).fillMaxHeight())
        Row(Modifier.weight(31f).height(TRANSPORT_H), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (LayoutPrefs.showSync) SyncButton(Modifier.weight(1f), b.syncOn) { onSync(b, a) }
            CueButton(b, Modifier.weight(1f))
            PlayButton(b, DeckB, DeckBLight, Modifier.weight(1f))
        }
    }
}

/** Medium-size CUE / SYNC / PLAY buttons: a fixed height, whatever the height of the bar. */
private val TRANSPORT_H = 64.dp      // upper limit: the buttons grow with the bar (Settings > LAYOUT > BOTTOM) up to this height

@Composable
private fun GrayButton(label: String, modifier: Modifier, touch: Modifier, on: Boolean = false) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(if (on) listOf(Color(0xFF3A2A08), Color(0xFF2B2108)) else listOf(BtnTop, BtnBottom))).then(touch),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color(0xFFEDEDEF), fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

@Composable
private fun CueButton(d: DeckState, modifier: Modifier) =
    GrayButton("CUE", modifier, Modifier.pressable({ d.cueDown() }, { d.cueUp() }))

@Composable
private fun SyncButton(modifier: Modifier, on: Boolean, onClick: () -> Unit) =
    GrayButton("SYNC", modifier, Modifier.clickable(onClick = onClick), on)

@Composable
private fun PlayButton(d: DeckState, c1: Color, c2: Color, modifier: Modifier) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(c1, c2, c1))).clickable { d.togglePlay() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            if (d.playing) {
                val w = size.width * 0.28f
                drawRect(Color.Black, Offset(size.width * 0.1f, 0f), Size(w, size.height))
                drawRect(Color.Black, Offset(size.width * 0.62f, 0f), Size(w, size.height))
            } else {
                drawPath(Path().apply { moveTo(size.width * 0.12f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.12f, size.height); close() }, Color.Black)
            }
        }
    }
}

@Composable
private fun Crossfader(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    Box(modifier.faderTouch(false) { f -> onChange(if (abs(f - 0.5f) < 0.03f) 0.5f else f) }) {
        Canvas(Modifier.fillMaxSize()) {
            val tw = 18.dp.toPx(); val th = minOf(34.dp.toPx(), size.height)
            val cy = size.height / 2; val bar = 9.dp.toPx()
            val tx = tw / 2 + (size.width - tw) * value
            drawRoundRect(Color(0xFF0A1020), Offset(0f, cy - bar / 2 - 1.dp.toPx()), Size(size.width, bar + 2.dp.toPx()), CornerRadius(bar))
            drawRoundRect(DeckA, Offset(1.dp.toPx(), cy - bar / 2), Size(tx - 1.dp.toPx(), bar), CornerRadius(bar / 2))
            drawRoundRect(DeckB, Offset(tx, cy - bar / 2), Size(size.width - tx - 1.dp.toPx(), bar), CornerRadius(bar / 2))
            drawRoundRect(Color(0xFFE4E4E6), Offset(tx - tw / 2, cy - th / 2), Size(tw, th), CornerRadius(3.dp.toPx()))
            drawLine(Color(0xFF2A2D35), Offset(tx, cy - th / 2 + 2.dp.toPx()), Offset(tx, cy + th / 2 - 2.dp.toPx()), 2.dp.toPx())
        }
    }
}
