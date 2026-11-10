package com.example.djapp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer

object Decoder {
    /**
     * Decodes any Android-supported audio file to interleaved 16-bit stereo PCM,
     * streaming it to [outFile] so RAM use stays tiny (important on 3-4 GB phones).
     * Returns the sample rate.
     * [startSec] / [maxSec] decode only a slice (maxSec <= 0 means "to the end"); used by the library analyser.
     *
     * Progressive loading: when [onChunk] is given it is called as soon as the first [firstChunkSec] seconds are on disk, and
     * again each time the decoded part has grown a lot (the file keeps growing; every call may map what exists so far), so
     * a deck can start playing after a fraction of a second instead of waiting for the whole song.
     * [isCancelled] is polled so a load that was replaced by a newer one stops at once.
     */
    fun decode(
        ctx: Context, uri: Uri, outFile: File, startSec: Double = 0.0, maxSec: Double = 0.0,
        isCancelled: () -> Boolean = { false }, firstChunkSec: Double = 5.0,
        onChunk: ((rate: Int, durationSec: Double?) -> Unit)? = null
    ): Int {
        val ex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
        val idx = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
        }
        ex.selectTrack(idx)
        if (startSec > 0.0) ex.seekTo((startSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val fmt = ex.getTrackFormat(idx)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()

        val durSec: Double? = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else null
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        var written = 0L
        var nextChunkAt = (firstChunkSec * rate).toLong()
        var bytes = ByteArray(64 * 1024)            // reused for every buffer (the old code allocated 3 arrays per buffer)
        try {
        BufferedOutputStream(outFile.outputStream(), 256 * 1024).use { os ->
            while (!outDone) {
                if (isCancelled()) break
                // Keep the decoder fed: queue as many input buffers as are free right now.
                while (!inDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val frames = info.size / 2 / ch
                    if (ch == 2) {
                        if (bytes.size < info.size) bytes = ByteArray(info.size)
                        buf.get(bytes, 0, info.size)
                        os.write(bytes, 0, info.size)
                    } else if (frames > 0) {
                        if (bytes.size < frames * 4) bytes = ByteArray(frames * 4)
                        val src = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val dst = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
                        for (f in 0 until frames) {
                            val l = src.get(f * ch); val r = if (ch > 1) src.get(f * ch + 1) else l
                            dst.put(l); dst.put(r)
                        }
                        os.write(bytes, 0, frames * 4)
                    }
                    codec.releaseOutputBuffer(o, false)
                    written += frames
                    if (onChunk != null && maxSec <= 0.0 && written >= nextChunkAt) {
                        os.flush()                                  // everything decoded so far is now readable from the file
                        onChunk(rate, durSec)
                        nextChunkAt = maxOf(written + 20L * rate, written * 2)
                    }
                    if (maxSec > 0.0 && written >= (maxSec * rate).toLong()) outDone = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        } finally {
            // Always give the decoder back (budget phones only have a few): an error used to leave it allocated.
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
        return rate
    }

    /** Length of the audio track in seconds straight from the file header (instant), or null if unknown. */
    fun durationSec(ctx: Context, uri: Uri): Double? = try {
        val ex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
        try {
            val idx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            val f = ex.getTrackFormat(idx)
            if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else null
        } finally { ex.release() }
    } catch (_: Exception) { null }
}
