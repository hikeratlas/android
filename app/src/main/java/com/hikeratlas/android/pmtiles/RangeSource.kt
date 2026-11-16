package com.hikeratlas.android.pmtiles

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Somewhere a PMTiles archive's bytes can be read from, a range at a time. */
interface RangeSource {
    /** A key for the archive, stable across launches (used in tile URLs). */
    val key: String

    /**
     * Reads [length] bytes at [offset]. May return fewer at the end of the
     * archive. Throws [ArchiveChangedException] if the archive was replaced
     * since the first read.
     */
    fun read(offset: Long, length: Int): ByteArray
}

/** The archive behind a source was rebuilt: cached directories are stale. */
class ArchiveChangedException(message: String) : IOException(message)

/** An archive on the web, read with HTTP range requests. */
class HttpRangeSource(
    private val url: String,
    private val client: OkHttpClient,
    // Pauses before each retry of a read the network broke off.
    private val retryDelaysMs: List<Long> = listOf(500, 1500),
) : RangeSource {
    override val key: String = url

    // The basemap is rebuilt in place, so a range read after a rebuild would
    // mix two archives. Pin the first ETag and notice when it changes.
    @Volatile private var etag: String? = null

    /** The response started, then the connection broke or stalled. */
    private class BrokenBody(cause: IOException) : IOException(cause.message, cause)

    override fun read(offset: Long, length: Int): ByteArray {
        // Offline downloads read up to 4 MB a request, and a connection can
        // reset or stall partway through one (android#95). OkHttp only
        // retries before a response starts, so a broken body would fail the
        // whole area download: read it again instead. Only then: with no
        // network at all, retrying would just make every read slower to fail.
        var attempt = 0
        while (true) {
            try {
                return readOnce(offset, length)
            } catch (e: BrokenBody) {
                val cause = e.cause as IOException
                if (attempt >= retryDelaysMs.size || Thread.currentThread().isInterrupted) throw cause
                Thread.sleep(retryDelaysMs[attempt++])
            }
        }
    }

    private fun readOnce(offset: Long, length: Int): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} reading $url")
            }
            val got = response.header("ETag")
            val pinned = etag
            if (pinned == null) {
                etag = got
            } else if (got != null && got != pinned) {
                etag = got
                throw ArchiveChangedException("$url changed ($pinned -> $got)")
            }
            val bytes = try {
                response.body.bytes()
            } catch (e: IOException) {
                throw BrokenBody(e)
            }
            // A server that ignores Range sends the whole file.
            if (response.code == 200 && bytes.size > length) {
                return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, (offset + length).toInt()))
            }
            return bytes
        }
    }
}

/** An archive on disk, e.g. a downloaded offline area. */
class FileRangeSource(private val file: File) : RangeSource {
    override val key: String = file.absolutePath

    override fun read(offset: Long, length: Int): ByteArray =
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(length.toLong(), raf.length() - offset).toInt().coerceAtLeast(0)
            ByteArray(n).also { raf.seek(offset); raf.readFully(it) }
        }
}
