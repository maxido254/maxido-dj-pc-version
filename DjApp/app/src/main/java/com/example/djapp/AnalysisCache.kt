package com.example.djapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything learned about a song is kept on disk, so quitting the app and loading the same song again later
 * needs NO decoding and NO analysis:
 *  - analysis/<id>.json   BPM, first-beat offset, key (+ which algorithm version made them)
 *  - analysis/<id>.wave   the waveform
 *  - decoded/<id>.pcm     the decoded audio, ready to be mapped by the engine
 * An entry is only trusted while the song file's size / modified time are unchanged and the BPM algorithm
 * version matches (so results of the old, inaccurate detector are never reused).
 */
object AnalysisCache {
    class Entry(val bpm: Float, val offset: Double, val key: String, val keyDone: Boolean, val full: Boolean, val rate: Int)

    private const val CAP_MB = 20000         // practically never evicts: a song is decoded once, ever (only a runaway store is trimmed)
    private val lock = Any()
    private val pinned = ConcurrentHashMap<Int, String>()      // deck id -> decoded file in use (never pruned)

    private fun adir(ctx: Context) = File(ctx.filesDir, "analysis").apply { mkdirs() }
    private fun ddir(ctx: Context) = File(ctx.filesDir, "decoded").apply { mkdirs() }
    private fun id(uri: Uri) = Prepared.keyOf(uri)
    private fun jsonFile(ctx: Context, uri: Uri) = File(adir(ctx), id(uri) + ".json")

    fun pin(deck: Int, f: File?) { if (f == null) pinned.remove(deck) else pinned[deck] = f.absolutePath }

    /** Size + modified time of the song file: changes when the file is replaced or edited. */
    fun signature(ctx: Context, uri: Uri): String {
        var size = -1L; var mod = -1L
        try { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) size = it.getLong(0) } } catch (_: Exception) {}
        for (col in arrayOf("last_modified", "date_modified")) {
            if (mod > 0 || size > 0) break      // size alone is stable; modified times can change without the audio changing
            try { ctx.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) mod = it.getLong(0) } } catch (_: Exception) {}
        }
        return "$size:$mod"
    }

    private fun readJson(ctx: Context, uri: Uri): JSONObject? = try {
        val f = jsonFile(ctx, uri)
        if (!f.exists()) null else JSONObject(f.readText())
    } catch (_: Exception) { null }

    private fun sigOk(ctx: Context, uri: Uri, j: JSONObject?): Boolean = j != null && j.optString("sig") == signature(ctx, uri)

    private fun valid(ctx: Context, uri: Uri, j: JSONObject?): Boolean =
        j != null && j.optInt("v", 0) == BpmDetector.VERSION && j.optString("sig") == signature(ctx, uri)

    /** The saved analysis of this song, or null when there is none (or it is stale). */
    fun get(ctx: Context, uri: Uri): Entry? = synchronized(lock) {
        val j = readJson(ctx, uri)
        if (!valid(ctx, uri, j)) return null
        j!!
        if (!j.has("bpm")) return null
        Entry(j.optDouble("bpm", 0.0).toFloat(), j.optDouble("off", 0.0), j.optString("key", ""), j.optBoolean("kd", false), j.optBoolean("full", false), j.optInt("rate", 0))
    }

    /** Merges new facts into the song's entry (a stale entry is replaced). */
    fun update(ctx: Context, uri: Uri, block: (JSONObject) -> Unit) {
        synchronized(lock) { try {
            val sig = signature(ctx, uri)
            val existing = readJson(ctx, uri)
            val j: JSONObject
            if (existing == null || existing.optString("sig") != sig) {
                if (existing != null) purge(ctx, uri)             // the song file itself changed
                j = JSONObject().put("v", BpmDetector.VERSION).put("sig", sig)
            } else {
                j = existing
            }
            if (j.optInt("v", 0) != BpmDetector.VERSION) {
                // New BPM algorithm: old BPM / grid dropped, but the decoded audio, key and waveform stay valid.
                j.remove("bpm"); j.remove("off"); j.remove("full"); j.put("v", BpmDetector.VERSION)
            }
            block(j)
            val tmp = File(adir(ctx), id(uri) + ".json.tmp")
            tmp.writeText(j.toString()); tmp.renameTo(jsonFile(ctx, uri))
        } catch (_: Exception) {} }
    }

    fun putGrid(ctx: Context, uri: Uri, g: BeatGrid, full: Boolean) { update(ctx, uri) {
        // a slice-based result never replaces a full-track one
        if (full || !it.optBoolean("full", false)) it.put("bpm", g.bpm.toDouble()).put("off", g.offsetSec).put("full", full)
    } }

    fun putKey(ctx: Context, uri: Uri, key: String) { update(ctx, uri) { it.put("key", key).put("kd", true) } }

    // ------------------------------------------------------------------ waveform
    private fun waveFile(ctx: Context, uri: Uri) = File(adir(ctx), id(uri) + ".wave")

    fun getWave(ctx: Context, uri: Uri): WaveData? =
        if (!synchronized(lock) { sigOk(ctx, uri, readJson(ctx, uri)) }) null else Prepared.readWave(waveFile(ctx, uri))

    fun putWave(ctx: Context, uri: Uri, w: WaveData) {
        try {
            update(ctx, uri) { }
            val tmp = File(adir(ctx), id(uri) + ".wave.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.writeInt(w.peak.size); s.writeInt(w.perSec); s.writeFloat(w.maxPeak)
                for (v in w.peak) s.writeFloat(v); for (v in w.low) s.writeFloat(v)
            }
            tmp.renameTo(waveFile(ctx, uri))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ decoded audio
    fun pcmFile(ctx: Context, uri: Uri) = File(ddir(ctx), id(uri) + ".pcm")
    fun pcmTemp(ctx: Context, uri: Uri) = File(ddir(ctx), id(uri) + "_${System.nanoTime()}.tmp")

    /** Sample rate of the saved decode of this song, or null if there is no valid one. */
    fun pcmRate(ctx: Context, uri: Uri): Int? = synchronized(lock) {
        val j = readJson(ctx, uri)
        val f = pcmFile(ctx, uri)
        if (!sigOk(ctx, uri, j) || !f.exists() || f.length() <= 0L) return null
        if (j!!.optLong("pcmBytes", -1L) != f.length()) return null      // interrupted write
        val r = j.optInt("rate", 0)
        if (r <= 0) null else { f.setLastModified(System.currentTimeMillis()); r }
    }

    /** Moves a freshly decoded temp file into the store and remembers its rate. Returns the final file. */
    fun commitPcm(ctx: Context, uri: Uri, tmp: File, rate: Int): File = synchronized(lock) {
        val dst = pcmFile(ctx, uri)
        val len = tmp.length()
        // Entry first (it clears any stale data of an older version of this song), then the file moves into place.
        update(ctx, uri) { it.put("rate", rate).put("pcmBytes", len) }
        if (!tmp.renameTo(dst)) {
            update(ctx, uri) { it.remove("rate"); it.remove("pcmBytes") }
            return tmp
        }
        prune(ctx, CAP_MB, dst)
        dst
    }

    private fun purge(ctx: Context, uri: Uri) {
        jsonFile(ctx, uri).delete(); waveFile(ctx, uri).delete()
        val p = pcmFile(ctx, uri); if (!pinned.containsValue(p.absolutePath)) p.delete()
    }

    fun usageBytes(ctx: Context): Long = ddir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearDecoded(ctx: Context) {
        ddir(ctx).listFiles()?.forEach { if (!pinned.containsValue(it.absolutePath)) it.delete() }
    }

    /** Keeps decoded audio under [capMb]: least recently used first; songs on a deck are never removed. */
    private fun prune(ctx: Context, capMb: Int, keep: File) {
        // Half-written decodes left by an interrupted load (app closed or killed while a song was decoding).
        ddir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".tmp") && System.currentTimeMillis() - f.lastModified() > 3_600_000L }?.forEach { it.delete() }
        val files = ddir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".pcm") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= capMb * 1_048_576L) break
            if (f == keep || f.name.endsWith(".tmp") || pinned.containsValue(f.absolutePath)) continue
            total -= f.length(); f.delete()
        }
    }
}
