package com.example.djapp

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The library "dock" under the decks (pro-DJ-software look): a crate list on the left and a sortable track table on the right,
 * with a search box and A / B load buttons (A = blue deck, B = red deck). It lives on the main screen, so tracks can be
 * browsed and loaded without leaving the decks. The full-screen LIB page (analyse, prepare stems, ...) is still available.
 */

private const val PREPARED = "::prepared"

/** Lets keys / MIDI move through the dock's track list (see Mappings). */
object DockNav {
    var cursor by androidx.compose.runtime.mutableIntStateOf(0)
    var rows: List<Track> = emptyList()
    var load: ((Int, Uri) -> Unit)? = null
    var typing = false        // the search box has the keyboard focus: arrows / Enter belong to it
    var blocked = false       // a full-screen page (Settings / full Library) is open on top of the dock
    fun move(step: Int) { if (rows.isNotEmpty()) cursor = (cursor + step).coerceIn(0, rows.size - 1) }
    fun scrub(v: Float) { if (rows.isNotEmpty()) cursor = (v * (rows.size - 1)).toInt().coerceIn(0, rows.size - 1) }
    fun loadTo(deck: Int) { rows.getOrNull(cursor)?.let { load?.invoke(deck, it.uri) } }
}

@Composable
fun LibraryDock(
    onLoad: (Int, Uri) -> Unit, onLoadPack: (Int, String) -> Unit, onCollapse: () -> Unit, modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
               else android.Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { Library.loadFolders(ctx) }
    LaunchedEffect(granted) { if (granted) Library.scan(ctx) }

    // "" = automatic: the first folder you added, or Prepared stems when you have not added any.
    var sel by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val folders = Library.folders
    val effective = when {
        sel == PREPARED -> PREPARED
        sel.isNotEmpty() && folders.any { it.rel == sel } -> sel
        else -> folders.firstOrNull()?.rel ?: PREPARED
    }
    val folder = folders.firstOrNull { it.rel == effective }

    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (Library.addFolder(ctx, uri)) sel = Library.folders.getOrNull(Library.activeFolder)?.rel ?: ""
            else android.widget.Toast.makeText(ctx, "Pick a folder inside the phone storage, not the storage root", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    var packs by remember { mutableStateOf<List<Prepared.Pack>>(emptyList()) }
    LaunchedEffect(effective) { if (effective == PREPARED) packs = withContext(Dispatchers.IO) { Prepared.listPacks(ctx) } }

    val all = Library.tracks
    val shown = remember(all, folder, query, Library.sortBy, Library.ascending) {
        if (folder == null) emptyList() else {
            val q = query.trim()
            val inF = all.filter { Library.inFolder(it, folder) }
            Library.sorted(if (q.isEmpty()) inF else inF.filter { it.title.contains(q, true) || it.artist.contains(q, true) }, Library.sortBy, Library.ascending)
        }
    }

    androidx.compose.runtime.SideEffect { DockNav.rows = shown; DockNav.load = onLoad }
    LaunchedEffect(DockNav.cursor) { if (shown.isNotEmpty()) listState.animateScrollToItem(maxOf(0, DockNav.cursor - 1)) }

    Column(modifier.background(BgDark)) {
        // ---- toolbar
        Row(
            Modifier.fillMaxWidth().height(26.dp).background(PanelBg).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LIBRARY", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(1f).height(19.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                    .border(1.dp, LineGray, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) Text("Search", color = TextGray, fontSize = 10.sp)
                BasicTextField(
                    query, { query = it }, singleLine = true, cursorBrush = SolidColor(Color.White),
                    textStyle = TextStyle(color = Color.White, fontSize = 10.sp),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { DockNav.typing = it.isFocused }
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    effective == PREPARED -> "${packs.size} packs"
                    Library.scanning && all.isEmpty() -> "scanning…"
                    Library.analysing -> "analysing…"
                    else -> "${shown.size} tracks"
                },
                color = TextGray, fontSize = 9.sp, maxLines = 1
            )
            Spacer(Modifier.width(8.dp))
            if (folder != null) {
                DockButton(if (Library.analysing) "STOP" else "ANALYSE", Library.analysing, Accent, 58.dp) {
                    if (Library.analysing) Library.stopAnalysis() else Library.startAnalysis(ctx) { t -> Library.inFolder(t, folder) }
                }
                Spacer(Modifier.width(4.dp))
            }
            DockButton("▾", false, Accent, 24.dp) { onCollapse() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))

        if (!granted && effective != PREPARED) {
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Allow access to the audio on this device to see your folders. Nothing is uploaded.", color = TextGray, fontSize = 10.sp, modifier = Modifier.weight(1f))
                DockButton("ALLOW ACCESS", true, Accent, 110.dp) { ask.launch(perm) }
            }
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            // ---- your folders (only the ones you added) and the prepared stems
            Column(Modifier.width(120.dp).fillMaxHeight().background(PanelBg)) {
                Box(Modifier.fillMaxWidth().padding(4.dp)) { DockButton("+ ADD FOLDER", true, Accent, 112.dp) { addFolder.launch(null) } }
                LazyColumn(Modifier.fillMaxSize()) {
                    item { CrateRow("💾", "Prepared stems", Prepared.count(), effective == PREPARED, null) { sel = PREPARED } }
                    itemsIndexed(folders.sortedBy { it.name.lowercase() }, key = { _, f -> f.rel }) { _, f ->
                        val on = effective == f.rel
                        val i = folders.indexOf(f)
                        CrateRow("▪", f.name, all.count { Library.inFolder(it, f) }, on, if (on) ({
                            Library.selectFolder(ctx, i); Library.removeActiveFolder(ctx); sel = ""
                        }) else null) { sel = f.rel }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color.Black))

            if (effective == PREPARED) {
                // ---- prepared stem packs
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(17.dp).background(Color(0xFF1C1C20)), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(22.dp))
                        Text("PREPARED PACK", color = TextGray, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("BPM", color = TextGray, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
                        Text("KEY", color = TextGray, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
                        Text("TIME", color = TextGray, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
                        Spacer(Modifier.width(66.dp))
                    }
                    val q = query.trim()
                    val list = if (q.isEmpty()) packs else packs.filter { it.title.contains(q, true) }
                    if (list.isEmpty()) Text("No prepared stems yet. Open the full LIB page, open a folder and tap P on a track.", color = TextGray, fontSize = 10.sp, modifier = Modifier.padding(10.dp))
                    else LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(list, key = { _, pk -> pk.dirName }) { i, pk ->
                            Row(Modifier.fillMaxWidth().height(25.dp).background(if (i % 2 == 1) Color.White.copy(alpha = 0.04f) else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = TextGray, fontSize = 8.sp, textAlign = TextAlign.Center, modifier = Modifier.width(22.dp))
                                Text(pk.title, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 2.dp))
                                Text(if (pk.bpm > 0f) "%.1f".format(pk.bpm) else "–", color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
                                Text(pk.key.ifEmpty { "–" }, color = Accent, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(44.dp))
                                Text("%d:%02d".format(pk.seconds / 60, pk.seconds % 60), color = TextGray, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
                                LoadChip("A", DeckA) { onLoadPack(0, pk.dirName) }
                                Spacer(Modifier.width(4.dp))
                                LoadChip("B", DeckB) { onLoadPack(1, pk.dirName) }
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                }
            } else {
                // ---- track table of the chosen folder
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(17.dp).background(Color(0xFF1C1C20)), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(22.dp))
                        HeaderCell("SONG", SortBy.TITLE, Modifier.weight(1.3f), TextAlign.Start)
                        HeaderCell("ARTIST", SortBy.ARTIST, Modifier.weight(1f), TextAlign.Start)
                        HeaderCell("BPM", SortBy.BPM, Modifier.width(44.dp), TextAlign.Center)
                        HeaderCell("KEY", SortBy.KEY, Modifier.width(44.dp), TextAlign.Center)
                        HeaderCell("TIME", SortBy.LENGTH, Modifier.width(40.dp), TextAlign.Center)
                        Spacer(Modifier.width(66.dp))
                    }
                    if (shown.isEmpty()) {
                        Text(
                            if (Library.scanning) "Scanning…" else if (query.isNotEmpty()) "No match." else "No audio found in this folder.",
                            color = TextGray, fontSize = 10.sp, modifier = Modifier.padding(10.dp)
                        )
                    } else LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        itemsIndexed(shown, key = { _, t -> t.id }) { i, t -> DockRow(t, i, i == DockNav.cursor, onLoad) }
                    }
                }
            }
        }
    }
}

/** Thin bar shown instead of the dock when it is collapsed. */
@Composable
fun LibraryDockHandle(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(PanelBg).clickable(onClick = onOpen).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) { Text("▴  LIBRARY", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

@Composable
private fun DockButton(label: String, active: Boolean, color: Color, w: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.width(w).height(19.dp).clip(RoundedCornerShape(5.dp)).background(if (active) color else BtnBottom).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = if (active) Color.Black else Color(0xFFE6E7EA)) }
}

@Composable
private fun CrateRow(icon: String, name: String, count: Int, selected: Boolean, onRemove: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(if (selected) Accent.copy(alpha = 0.30f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = if (selected) Color.White else TextGray, fontSize = 10.sp, modifier = Modifier.width(14.dp))
        Text(name, color = if (selected) Color.White else Color(0xFFC9CAD0), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$count", color = TextGray, fontSize = 8.sp, maxLines = 1)
        if (onRemove != null) Text("✕", color = Color.White, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onRemove).padding(start = 6.dp))
    }
}

@Composable
private fun HeaderCell(label: String, by: SortBy, modifier: Modifier, align: TextAlign) {
    val on = Library.sortBy == by
    Text(
        label + if (on) (if (Library.ascending) " ▲" else " ▼") else "",
        color = if (on) Color.White else TextGray, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = align,
        modifier = modifier.fillMaxHeight().clickable {
            if (on) Library.ascending = !Library.ascending else { Library.sortBy = by; Library.ascending = true }
        }.padding(top = 4.dp, start = 2.dp, end = 2.dp)
    )
}

@Composable
private fun DockRow(t: Track, index: Int, cursor: Boolean, onLoad: (Int, Uri) -> Unit) {
    // The track on a deck is highlighted in that deck's colour (blue = A, red = B).
    val onDeck = StemSwap.decks.indexOfFirst { it.loaded && it.curUri == t.uri }
    val bg = when {
        onDeck == 0 -> DeckA.copy(alpha = 0.35f)
        onDeck == 1 -> DeckB.copy(alpha = 0.35f)
        index % 2 == 1 -> Color.White.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    val pending = if (t.analysed) "–" else "···"
    Row(Modifier.fillMaxWidth().height(25.dp).background(bg).then(if (cursor) Modifier.border(1.dp, Color.White.copy(alpha = 0.7f)) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", color = TextGray, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(22.dp))
        Text((if (t.isVideo) "▶ " else "") + t.title, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f).padding(horizontal = 2.dp))
        Text(t.artist, color = Color(0xFFB4B5BC), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 2.dp))
        Text(if (t.bpm > 0f) "%.1f".format(t.bpm) else pending, color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(44.dp))
        Text(
            if (t.camelot.isNotEmpty()) t.camelot else pending, color = keyColor(t.camelot), fontSize = 10.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(44.dp)
        )
        Text("%d:%02d".format(t.durationMs / 60000, t.durationMs / 1000 % 60), color = TextGray, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(40.dp))
        LoadChip("A", DeckA) { onLoad(0, t.uri) }
        Spacer(Modifier.width(4.dp))
        LoadChip("B", DeckB) { onLoad(1, t.uri) }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun LoadChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.width(30.dp).height(21.dp).clip(RoundedCornerShape(5.dp)).background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.75f)))).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

/** Camelot keys get a colour from the wheel position (1..12), like the key colours in pro DJ software. */
private fun keyColor(camelot: String): Color {
    val n = camelot.takeWhile { it.isDigit() }.toIntOrNull() ?: return TextGray
    return Color.hsv(((n - 1) * 30f + 200f) % 360f, 0.55f, 1f)
}

