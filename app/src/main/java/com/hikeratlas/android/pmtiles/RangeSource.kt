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
) : RangeSource {
    override val key: String = url

    // The basemap is rebuilt in place, so a range read after a rebuild would
    // mix two archives. Pin the first ETag and notice when it changes.
    @Volatile private var etag: String? = null

    override fun read(offset: Long, length: Int): ByteArray {
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
            val bytes = response.body.bytes()
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
