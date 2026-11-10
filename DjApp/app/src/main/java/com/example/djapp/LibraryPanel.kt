package com.example.djapp

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browse every audio file on the device, with BPM + key analysed offline, sortable, loadable to either deck. */
@Composable
fun LibraryPanel(onLoad: (Int, Uri) -> Unit, onBrowse: (Int) -> Unit, onLoadPack: (Int, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
               else android.Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // Videos need their own permission on Android 13+; ask once (people who allowed audio with an older version never saw it).
    val askVideo = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) scope.launch { Library.scan(ctx) } }
    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
        ) {
            val sp = ctx.getSharedPreferences("maxxidodj", android.content.Context.MODE_PRIVATE)
            if (!sp.getBoolean("askedVideo", false)) { sp.edit().putBoolean("askedVideo", true).apply(); askVideo.launch(android.Manifest.permission.READ_MEDIA_VIDEO) }
        }
    }

    LaunchedEffect(Unit) { Library.loadFolders(ctx) }

    // Refresh the file list every time the panel opens (picks up newly added tracks; analysis results are kept).
    LaunchedEffect(granted) { if (granted) Library.scan(ctx) }

    if (!granted) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Allow access to the audio files on this device to build your library. " +
                    "Tracks are read and analysed on the phone; nothing is uploaded.",
                color = TextGray, fontSize = 11.sp
            )
            PadButton("ALLOW ACCESS", Modifier.width(150.dp), active = true) { ask.launch(perm) }
        }
        return
    }

    val allTracks = Library.tracks
    // Every song is grouped under the folder it lives in; the library first shows only the folders.
    val groups = remember(allTracks) { allTracks.groupBy { Library.dirOf(it) } }
    val dir = Library.openDir
    // An opened folder shows every song in it.
    val all = remember(groups, dir) { if (dir == null) emptyList() else groups[dir] ?: emptyList() }
    val analysedCount = remember(all) { all.count { it.analysed } }
    val rows = remember(all, Library.sortBy, Library.ascending) { Library.sorted(all, Library.sortBy, Library.ascending) }

    // Back steps out of the open folder first, and only then closes the panel.
    BackHandler(dir != null) { Library.openDir(ctx, null) }

    Column(Modifier.fillMaxSize()) {
        if (dir == PREPARED_DIR) {
            PreparedView(onLoadPack) { Library.openDir(ctx, null) }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (dir != null) {
                PadButton("‹ FOLDERS", Modifier.width(84.dp).height(28.dp), active = true, color = Accent, fontSize = 10) { Library.openDir(ctx, null) }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                when {
                    Library.scanning && allTracks.isEmpty() -> "Scanning…"
                    dir == null -> "${groups.size} folders · ${allTracks.size} tracks"
                    Library.analysing -> "${folderName(dir)} · ${all.size} tracks · analysing…  $analysedCount / ${all.size}"
                    else -> "${folderName(dir)} · ${all.size} tracks · $analysedCount analysed"
                },
                color = if (dir != null) Color.White else TextGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            PadButton("FILE A", Modifier.width(64.dp).height(28.dp), fontSize = 10) { onBrowse(0) }
            Spacer(Modifier.width(4.dp))
            PadButton("FILE B", Modifier.width(64.dp).height(28.dp), fontSize = 10) { onBrowse(1) }
            Spacer(Modifier.width(6.dp))
            PadButton("RESCAN", Modifier.width(76.dp).height(28.dp), enabled = !Library.scanning, fontSize = 10) {
                scope.launch { Library.scan(ctx) }
            }
            Spacer(Modifier.width(6.dp))
            PadButton(
                if (Library.analysing) "STOP" else "ANALYSE", Modifier.width(90.dp).height(28.dp),
                active = Library.analysing, color = Accent, fontSize = 10,
                enabled = Library.analysing || (dir != null && all.isNotEmpty() && analysedCount < all.size)
            ) { if (Library.analysing) Library.stopAnalysis() else Library.startAnalysis(ctx) { t -> Library.dirOf(t) == dir } }
        }

        if (dir == null) {
            // ---------------------------------------------------------------- folder list
            Row(
                Modifier.fillMaxWidth().height(52.dp).clickable { Library.openDir(ctx, PREPARED_DIR) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💾", fontSize = 18.sp, modifier = Modifier.width(34.dp))
                Column(Modifier.weight(1f).padding(end = 6.dp)) {
                    Text("Prepared stems (.mxs)", color = Accent, fontSize = 13.sp, maxLines = 1)
                    Text("Play without the original song file", color = TextGray, fontSize = 9.sp, maxLines = 1)
                }
                Text("${Prepared.count()} packs", color = Accent, fontSize = 11.sp)
                Text("  ›", color = TextGray, fontSize = 16.sp)
            }
            if (groups.isEmpty() && !Library.scanning) {
                Text("No audio files found on this device.", color = TextGray, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            } else {
                val folderRows = remember(groups) { groups.entries.sortedBy { folderName(it.key).lowercase() } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), state = Library.folderListState) {
                    items(folderRows, key = { it.key }) { e ->
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clickable { Library.openDir(ctx, e.key) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁", fontSize = 18.sp, modifier = Modifier.width(34.dp))
                            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                                Text(folderName(e.key), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(folderHint(e.key), color = TextGray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${e.value.size} tracks", color = Accent, fontSize = 11.sp)
                            Text("  ›", color = TextGray, fontSize = 16.sp)
                        }
                    }
                }
            }
            return@Column
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    PrepQueue.running && PrepQueue.paused -> "PREP paused while a deck plays · ${PrepQueue.current} · ${PrepQueue.queue.size} queued"
                    PrepQueue.running -> "PREP ${(PrepQueue.progress * 100).toInt()}% · ${PrepQueue.current} · ${PrepQueue.queue.size} queued" + (if (AiStatus.text.isNotEmpty()) "  |  AI: ${AiStatus.text}" else "")
                    PrepQueue.failed.isNotEmpty() -> PrepQueue.failed
                    else -> "P = prepare a track's stems ahead of time (instant load, no CPU while you play)"
                },
                color = if (PrepQueue.running) Accent else TextGray, fontSize = 9.sp, maxLines = 2, modifier = Modifier.weight(1f)
            )
            PadButton("PREP ALL", Modifier.width(76.dp).height(24.dp), enabled = rows.isNotEmpty(), fontSize = 9) {
                if (Pro.gate(Pro.STEMS)) rows.forEach { PrepQueue.add(ctx, it.uri, it.title) }
            }
            Spacer(Modifier.width(4.dp))
            PadButton("STOP PREP", Modifier.width(76.dp).height(24.dp), enabled = PrepQueue.running, fontSize = 9) { PrepQueue.stop() }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in SortBy.values()) {
                val on = Library.sortBy == s
                PadButton(
                    s.label + if (on) (if (Library.ascending) " ▲" else " ▼") else "",
                    Modifier.weight(1f).height(26.dp), active = on, fontSize = 10
                ) {
                    if (on) Library.ascending = !Library.ascending
                    else { Library.sortBy = s; Library.ascending = s != SortBy.NEWEST }     // NEW starts with the newest songs on top
                }
            }
        }

        if (rows.isEmpty() && !Library.scanning) {
            Text("No audio or video found in this folder.", color = TextGray, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        } else {
            // back to the top only when the view itself changes (folder / sort), never when a song is loaded
            val viewKey = "${Library.openDir}/${Library.sortBy}/${Library.ascending}"
            LaunchedEffect(viewKey) {
                if (Library.lastView.isNotEmpty() && Library.lastView != viewKey) Library.listState.scrollToItem(0)
                Library.lastView = viewKey
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp), state = Library.listState) {
                itemsIndexed(rows, key = { _, t -> t.id }) { i, t -> TrackRow(t, i, onLoad, ctx) }
            }
        }
    }
}

/** Special "folder" key: the list of prepared stem packs (.mxs). */
private const val PREPARED_DIR = "::prepared"

@Composable
private fun PreparedView(onLoadPack: (Int, String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<Prepared.Pack>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(refresh) { packs = withContext(Dispatchers.IO) { Prepared.listPacks(ctx) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val d = exporting; exporting = null
        if (uri != null && d != null) scope.launch {
            msg = "Exporting…"
            val ok = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openOutputStream(uri)?.use { Prepared.exportPack(ctx, d, it) } ?: false } catch (_: Exception) { false }
            }
            msg = if (ok) "Exported ✓" else "Export failed"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            msg = "Importing…"
            val ok = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)?.use { Prepared.importPack(ctx, it) } ?: false } catch (_: Exception) { false }
            }
            msg = if (ok) "Imported ✓" else "Not a valid .mxs file"
            refresh++
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PadButton("‹ FOLDERS", Modifier.width(84.dp).height(28.dp), active = true, color = Accent, fontSize = 10) { onBack() }
            Spacer(Modifier.width(6.dp))
            Text(
                if (msg.isNotEmpty()) msg else "${packs.size} prepared packs · they play even if the original song is deleted",
                color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            PadButton("IMPORT .mxs", Modifier.width(100.dp).height(28.dp), fontSize = 10) { importLauncher.launch(arrayOf("*/*")) }
        }
        if (packs.isEmpty()) {
            Text(
                "No prepared stems yet. Open a folder and tap P on a track (or PREP ALL). To keep a pack safe or move it to another phone, EXPORT it as a .mxs file.",
                color = TextGray, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp)
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                items(packs, key = { it.dirName }) { pk ->
                    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 6.dp)) {
                            Text(pk.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${pk.bytes / 1_048_576} MB · " + when (pk.engine) {
                                    "AI", "AI MAX" -> "${pk.engine} stems ✓"
                                    "" -> "older pack"
                                    else -> "${pk.engine} (not AI: some bleed)"
                                },
                                color = if (pk.engine.startsWith("AI")) Accent else TextGray, fontSize = 9.sp
                            )
                        }
                        Text(if (pk.bpm > 0f) "%.1f".format(pk.bpm) else "–", color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(54.dp))
                        Text(pk.key.ifEmpty { "–" }, color = Accent, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(64.dp))
                        Text("%d:%02d".format(pk.seconds / 60, pk.seconds % 60), color = TextGray, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
                        PadButton("EXPORT", Modifier.width(62.dp).height(30.dp), fontSize = 9) {
                            exporting = pk.dirName
                            exportLauncher.launch(pk.title.replace(Regex("[^A-Za-z0-9 _.-]"), "_") + ".mxs")
                        }
                        Spacer(Modifier.width(4.dp))
                        PadButton("A", Modifier.width(34.dp).height(30.dp), active = true, color = DeckALight) { onLoadPack(0, pk.dirName) }
                        Spacer(Modifier.width(4.dp))
                        PadButton("B", Modifier.width(34.dp).height(30.dp), active = true, color = DeckB) { onLoadPack(1, pk.dirName) }
                        Spacer(Modifier.width(4.dp))
                        PadButton("✕", Modifier.width(30.dp).height(30.dp), fontSize = 11) {
                            scope.launch { withContext(Dispatchers.IO) { Prepared.deletePack(ctx, pk.dirName) }; refresh++ }
                        }
                    }
                }
            }
        }
    }
}

private fun folderName(dir: String?) = if (dir.isNullOrEmpty()) "Other" else dir.substringAfterLast('/')

/** Where the folder is, without the long storage prefix. */
private fun folderHint(dir: String) = dir.substringBeforeLast('/', "")
    .removePrefix("/storage/emulated/0").removePrefix("/storage").ifEmpty { "Internal storage" }

@Composable
private fun TrackRow(t: Track, index: Int, onLoad: (Int, Uri) -> Unit, ctx: android.content.Context) {
    val pending = if (t.analysed) "–" else "···"
    // Table look: alternate rows, and the track that is on a deck is highlighted in that deck's colour.
    val onDeck = StemSwap.decks.indexOfFirst { it.loaded && it.curUri == t.uri }
    val rowBg = when {
        onDeck == 0 -> DeckA.copy(alpha = 0.30f)
        onDeck == 1 -> DeckB.copy(alpha = 0.30f)
        index % 2 == 1 -> Color.White.copy(alpha = 0.035f)
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().height(46.dp).background(rowBg).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text((if (t.isVideo) "▶ " else "") + t.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.artist.isNotEmpty())
                Text(t.artist, color = TextGray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (t.bpm > 0f) "%.1f".format(t.bpm) else pending,
            color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(54.dp)
        )
        Text(
            if (t.camelot.isNotEmpty()) "${t.camelot} ${t.key}" else pending,
            color = Accent, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(64.dp)
        )
        Text(
            "%d:%02d".format(t.durationMs / 60000, t.durationMs / 1000 % 60),
            color = TextGray, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp)
        )
        val prepared = Prepared.has(t.uri)
        val queued = PrepQueue.queue.any { it.uri == t.uri }
        PadButton(
            if (prepared) "●" else if (queued) "…" else "P", Modifier.width(30.dp).height(30.dp),
            active = prepared, color = Accent, enabled = !prepared && !queued
        ) { if (Pro.gate(Pro.STEMS)) PrepQueue.add(ctx, t.uri, t.title) }
        Spacer(Modifier.width(4.dp))
        PadButton("A", Modifier.width(34.dp).height(30.dp), active = true, color = DeckALight) { onLoad(0, t.uri) }
        Spacer(Modifier.width(4.dp))
        PadButton("B", Modifier.width(34.dp).height(30.dp), active = true, color = DeckB) { onLoad(1, t.uri) }
    }
}
