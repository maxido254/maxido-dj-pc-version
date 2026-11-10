package com.example.djapp

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Its own low-priority thread, so preparing a library never competes with live stem work or the audio thread. */
private val prepQueueDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "prep-queue")
}.asCoroutineDispatcher()

class PrepInfo(
    val dir: File, val rate: Int, val bpm: Float, val offset: Double, val key: String, val title: String, val wave: WaveData?,
    val engine: String = "", val bpmVer: Int = 0, val src: String = ""
) {
    val stemPaths: Array<String> get() = STEM_NAMES.map { File(dir, "$it.pcm").absolutePath }.toTypedArray()
}

/**
 * A "prepared" track = one folder in the app's own storage holding the 4 finished stems, the beat grid, the key
 * and the waveform. Loading one needs no decoding, no analysis and no separation: it is instant even on a weak phone,
 * and uses no CPU while you play. (Same idea as VirtualDJ's pre-made stem files.)
 */
object Prepared {
    private val keys = mutableStateMapOf<String, Boolean>()
    private var inited = false

    fun root(ctx: Context) = File(ctx.filesDir, "prep").apply { mkdirs() }

    fun keyOf(uri: Uri): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        // Only cheap checks here (this runs on the main thread at start-up); deleting big leftovers can take
        // seconds on a slow phone and made the app look frozen, so it happens in the background.
        val junk = ArrayList<File>()
        root(ctx).listFiles()?.forEach { d ->
            if (d.isDirectory && !d.name.endsWith(".tmp")) {
                if (File(d, "meta.json").exists()) keys[d.name] = true else junk.add(d)
            } else if (d.name.endsWith(".tmp")) junk.add(d)    // interrupted job
        }
        if (junk.isNotEmpty()) Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            junk.forEach { try { it.deleteRecursively() } catch (_: Exception) { } }
        }, "prep-cleanup").start()
    }

    fun has(uri: Uri): Boolean = keys[keyOf(uri)] == true

    fun count(): Int = keys.size

    // ------------------------------------------------------------------ .mxs stem packs
    /** One prepared track as shown in the library; it plays without the original song file. */
    class Pack(val dirName: String, val title: String, val bpm: Float, val key: String, val seconds: Int, val bytes: Long, val engine: String)

    fun listPacks(ctx: Context): List<Pack> =
        (root(ctx).listFiles { f -> f.isDirectory && !f.name.endsWith(".tmp") } ?: emptyArray()).mapNotNull { d ->
            try {
                val m = File(d, "meta.json")
                if (!m.exists() || !STEM_NAMES.all { File(d, "$it.pcm").length() > 0 }) null
                else {
                    val j = JSONObject(m.readText())
                    Pack(
                        d.name, j.optString("title", d.name), j.optDouble("bpm", 0.0).toFloat(), j.optString("key", ""),
                        (j.optLong("frames", 0) / maxOf(j.optInt("rate", 44100), 1)).toInt(),
                        d.walkTopDown().filter { it.isFile }.sumOf { it.length() }, j.optString("engine", "")
                    )
                }
            } catch (_: Exception) { null }
        }.sortedBy { it.title.lowercase() }

    fun findPack(ctx: Context, dirName: String): PrepInfo? = try {
        val dir = File(root(ctx), dirName)
        val meta = File(dir, "meta.json")
        if (!meta.exists() || !STEM_NAMES.all { File(dir, "$it.pcm").length() > 0 }) null
        else {
            val j = JSONObject(meta.readText())
            dir.setLastModified(System.currentTimeMillis())
            PrepInfo(dir, j.getInt("rate"), j.optDouble("bpm", 0.0).toFloat(), j.optDouble("offset", 0.0), j.optString("key", ""), j.optString("title", ""), readWave(File(dir, "wave.bin")), j.optString("engine", ""), j.optInt("bv", 0), j.optString("src", ""))
        }
    } catch (_: Exception) { null }

    fun deletePack(ctx: Context, dirName: String) { File(root(ctx), dirName).deleteRecursively(); keys.remove(dirName) }

    /** Writes the pack as ONE file (.mxs): magic, meta json, waveform, then the 4 raw stems. It needs nothing else to play. */
    fun exportPack(ctx: Context, dirName: String, out: OutputStream): Boolean = try {
        val dir = File(root(ctx), dirName)
        DataOutputStream(out.buffered(256 * 1024)).use { s ->
            s.writeBytes("MXS1")
            val meta = File(dir, "meta.json").readBytes(); s.writeInt(meta.size); s.write(meta)
            val wf = File(dir, "wave.bin"); val wb = if (wf.exists()) wf.readBytes() else ByteArray(0)
            s.writeInt(wb.size); s.write(wb)
            for (n in STEM_NAMES) {
                val f = File(dir, "$n.pcm"); s.writeLong(f.length())
                f.inputStream().use { it.copyTo(s, 256 * 1024) }
            }
        }
        true
    } catch (_: Exception) { false }

    /** Reads a .mxs file into the prepared store (one copy now, instant loading afterwards). */
    fun importPack(ctx: Context, inp: InputStream): Boolean {
        val tmp = File(root(ctx), "imp_${System.nanoTime()}.tmp").also { it.mkdirs() }
        try {
            val s = DataInputStream(inp.buffered(256 * 1024))
            val magic = ByteArray(4); s.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "MXS1") { tmp.deleteRecursively(); return false }
            val ml = s.readInt()
            if (ml <= 0 || ml > 1_000_000) { tmp.deleteRecursively(); return false }
            val mb = ByteArray(ml); s.readFully(mb); File(tmp, "meta.json").writeBytes(mb)
            val wl = s.readInt()
            if (wl > 0) { val wb = ByteArray(wl); s.readFully(wb); File(tmp, "wave.bin").writeBytes(wb) }
            val buf = ByteArray(256 * 1024)
            for (n in STEM_NAMES) {
                var left = s.readLong()
                File(tmp, "$n.pcm").outputStream().buffered(256 * 1024).use { o ->
                    while (left > 0) {
                        val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) throw java.io.EOFException()
                        o.write(buf, 0, r); left -= r
                    }
                }
            }
            val j = JSONObject(String(mb, Charsets.UTF_8))
            val id = "pk" + MessageDigest.getInstance("SHA-1")
                .digest((j.optString("title") + "|" + j.optLong("frames")).toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            val dir = File(root(ctx), id)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) { tmp.deleteRecursively(); return false }
            keys[id] = true
            return true
        } catch (_: Exception) { tmp.deleteRecursively(); return false }
    }

    fun find(ctx: Context, uri: Uri): PrepInfo? = try {
        val dir = File(root(ctx), keyOf(uri))
        val meta = File(dir, "meta.json")
        if (!meta.exists() || !STEM_NAMES.all { File(dir, "$it.pcm").length() > 0 }) null
        else {
            val j = JSONObject(meta.readText())
            dir.setLastModified(System.currentTimeMillis())
            PrepInfo(dir, j.getInt("rate"), j.optDouble("bpm", 0.0).toFloat(), j.optDouble("offset", 0.0), j.optString("key", ""), j.optString("title", ""), readWave(File(dir, "wave.bin")), j.optString("engine", ""), j.optInt("bv", 0), j.optString("src", ""))
        }
    } catch (_: Exception) { null }

    fun readWave(f: File): WaveData? = try {
        DataInputStream(f.inputStream().buffered()).use { s ->
            val n = s.readInt(); val per = s.readInt(); val mx = s.readFloat()
            val pk = FloatArray(n) { s.readFloat() }; val lw = FloatArray(n) { s.readFloat() }
            WaveData(pk, lw, per, mx)
        }
    } catch (_: Exception) { null }

    private fun writeWave(f: File, w: WaveData) {
        DataOutputStream(f.outputStream().buffered()).use { s ->
            s.writeInt(w.peak.size); s.writeInt(w.perSec); s.writeFloat(w.maxPeak)
            for (v in w.peak) s.writeFloat(v); for (v in w.low) s.writeFloat(v)
        }
    }

    /**
     * Packs made before the accurate detector hold a wrong BPM. Re-detects it from the stems (they add up to the mix)
     * and rewrites the pack's meta, once; returns the new grid (null if it could not be done).
     */
    fun upgradeBpm(info: PrepInfo): BeatGrid? {
        if (info.bpmVer >= BpmDetector.VERSION) return null
        val g = BpmDetector.gridFromStems(info.stemPaths, info.rate) ?: return null
        try {
            val m = File(info.dir, "meta.json")
            val j = JSONObject(m.readText())
            j.put("bpm", g.bpm.toDouble()).put("offset", g.offsetSec).put("bv", BpmDetector.VERSION)
            val tmp = File(info.dir, "meta.json.tmp"); tmp.writeText(j.toString()); tmp.renameTo(m)
        } catch (_: Exception) {}
        return g
    }

    /** The user corrected the tempo by hand (half / double time): keep it, and never let the automatic upgrade replace it. */
    fun setBpm(ctx: Context, dirName: String, bpm: Float) {
        try {
            val m = File(File(root(ctx), dirName), "meta.json")
            val j = JSONObject(m.readText())
            j.put("bpm", bpm.toDouble()).put("bv", BpmDetector.VERSION).put("manual", true)
            val tmp = File(m.parentFile, "meta.json.tmp"); tmp.writeText(j.toString()); tmp.renameTo(m)
        } catch (_: Exception) {}
    }

    fun usageBytes(ctx: Context): Long = root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll(ctx: Context) { root(ctx).listFiles()?.forEach { it.deleteRecursively() }; keys.clear() }

    /** Keeps the store under [capMb]: oldest-used tracks go first. Never deletes [keep]. */
    fun prune(ctx: Context, capMb: Int, keep: String) {
        val dirs = root(ctx).listFiles { f -> f.isDirectory }?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (d in dirs) {
            if (total <= capMb * 1_048_576L) break
            if (d.name == keep) continue
            val sz = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively(); keys.remove(d.name); total -= sz
        }
    }

    fun commit(ctx: Context, tmp: File, uri: Uri): Boolean {
        val dir = File(root(ctx), keyOf(uri))
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) return false
        keys[dir.name] = true
        prune(ctx, AppSettings.prepCapMb, dir.name)
        return true
    }

    fun writeMeta(dir: File, rate: Int, frames: Long, grid: BeatGrid?, key: String, title: String, wave: WaveData?, engine: String = "", src: String = "") {
        File(dir, "meta.json").writeText(
            JSONObject().put("v", 1).put("bv", BpmDetector.VERSION).put("rate", rate).put("frames", frames)
                .put("bpm", (grid?.bpm ?: 0f).toDouble()).put("offset", grid?.offsetSec ?: 0.0)
                .put("key", key).put("title", title).put("engine", engine).put("src", src).toString()
        )
        if (wave != null) writeWave(File(dir, "wave.bin"), wave)
    }
}

/**
 * Prepares tracks one after another in the background (decode -> beat grid + key -> stems -> waveform) and stores
 * them with [Prepared]. Runs at the lowest priority and, by default, pauses while a deck is playing.
 */
object PrepQueue {
    class Item(val uri: Uri, val title: String)

    val queue = mutableStateListOf<Item>()
    var current by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var failed by mutableStateOf("")
    var running by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    private var stopFlag = false
    private val scope = CoroutineScope(SupervisorJob() + prepQueueDispatcher)

    /** Called (on the prep thread) when a track has been prepared and stored. */
    val listeners = mutableListOf<(Context, Uri) -> Unit>()

    /** [urgent]: a track that is loaded on a deck right now goes to the front of the waiting list (behind the one being made). */
    fun add(ctx: Context, uri: Uri, title: String, urgent: Boolean = false) {
        if (!Pro.has(Pro.STEMS)) return          // PRO feature (buttons call Pro.gate first, so the paywall shows)
        if (Prepared.has(uri) || current == title && running) return
        val waiting = queue.indexOfFirst { it.uri == uri }
        if (waiting >= 0 && !urgent) return
        if (urgent) {
            val first = if (running) 1 else 0
            if (waiting >= first) { val it = queue.removeAt(waiting); queue.add(first.coerceAtMost(queue.size), it) }
            else if (waiting < 0) queue.add(first.coerceAtMost(queue.size), Item(uri, title))
        } else queue.add(Item(uri, title))
        start(ctx.applicationContext)
    }

    fun stop() { stopFlag = true; queue.clear() }

    private fun anyDeckPlaying() = NativeEngine.isPlaying(0) || NativeEngine.isPlaying(1)

    private fun start(app: Context) {
        if (running) return
        running = true; stopFlag = false
        try { app.startForegroundService(android.content.Intent(app, PrepService::class.java)) } catch (_: Exception) {}
        scope.launch {
            try {
                while (!stopFlag) {
                    val item = queue.firstOrNull() ?: break
                    current = item.title; progress = 0f; failed = ""
                    try { prepareOne(app, item) } catch (_: Exception) {}
                    queue.remove(item)
                }
            } finally { running = false; current = ""; progress = 0f; paused = false }
        }
    }

    private fun checkpoint(): Boolean {
        // Called very often by the separator: wait here while music is playing so the audio thread keeps the CPU.
        while (!stopFlag && AppSettings.prepPause && anyDeckPlaying()) { paused = true; Thread.sleep(300) }
        paused = false
        return stopFlag
    }

    private fun prepareOne(app: Context, item: Item) {
        val root = Prepared.root(app)
        val tmpDir = File(root, Prepared.keyOf(item.uri) + ".tmp").also { it.deleteRecursively(); it.mkdirs() }
        val cachedRate = AnalysisCache.pcmRate(app, item.uri)
        val mix = if (cachedRate != null) AnalysisCache.pcmFile(app, item.uri) else File(app.cacheDir, "prep_mix.pcm")
        try {
            if (checkpoint()) return
            val rate = cachedRate ?: Decoder.decode(app, item.uri, mix)
            progress = 0.05f
            if (checkpoint()) return
            val saved = AnalysisCache.get(app, item.uri)
            val grid = if (saved != null && saved.full) BeatGrid(saved.bpm, saved.offset)
                       else BpmDetector.grid(mix, rate)?.also { AnalysisCache.putGrid(app, item.uri, it, true) }
            val k = KeyDetector.detect(mix, rate)
            progress = 0.1f
            val prog = { v: Float -> progress = 0.1f + 0.85f * v.coerceIn(0f, 1f) }
            var ok = false
            var engine = ""
            // AI (SCNet neural network, in its own process) = the clean result; PRO/BETTER only if the AI cannot run.
            if (AppSettings.prepQuality >= 2) {
                val pauseWhen = { AppSettings.prepPause && anyDeckPlaying() }
                val ai: StemSeparator =
                    if (AppSettings.prepQuality == 3) ShiftedAiSeparator(app, 5, 2, pauseWhen, { paused = it }, { anyDeckPlaying() })
                    else AiSeparator(app, pauseWhen, { paused = it }, 4, persistent = true, audioBusy = { anyDeckPlaying() })
                AiStatus.text = ""
                ok = ai.separate(mix, rate, tmpDir, prog, { stopFlag })
                if (ok) AiStatus.text = ""
                if (ok) engine = if (AppSettings.prepQuality == 3) "AI MAX" else "AI"
            }
            if (!ok && !stopFlag && AppSettings.prepQuality == 3) {
                // AI MAX was chosen: never hand back weaker stems silently.
                failed = "${item.title}: AI MAX could not finish (phone out of memory or storage). Free some space/close apps and press PREP again."
                return
            }
            if (!ok && !stopFlag) {
                val sep: StemSeparator = if (AppSettings.prepQuality == 0) HpssSeparator() else ProSeparator()
                ok = sep.separate(mix, rate, tmpDir, prog, { checkpoint() })
                engine = if (AppSettings.prepQuality == 0) "BETTER" else "PRO"
            }
            if (!ok || stopFlag) return
            progress = 0.96f
            // Only for the classic separators: neural-network stems are already clean and must stay untouched.
            if (!engine.startsWith("AI")) VocalCleaner.clean(tmpDir, rate) { checkpoint() }
            if (stopFlag) return
            val wave = WaveAnalyzer.analyze(mix, rate)
            Prepared.writeMeta(tmpDir, rate, mix.length() / 4, grid, if (k != null) "${k.camelot} ${k.name}" else "", item.title, wave, engine, item.uri.toString())
            Prepared.commit(app, tmpDir, item.uri)
            progress = 1f
            for (l in listeners.toList()) try { l(app, item.uri) } catch (_: Exception) {}
        } finally {
            if (cachedRate == null) mix.delete()          // a saved decode stays for next time
            if (tmpDir.exists()) tmpDir.deleteRecursively()
        }
    }
}
