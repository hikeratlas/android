package com.hikeratlas.android.pmtiles

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** PMTiles v3 compression codes. */
object Compression {
    const val UNKNOWN = 0
    const val NONE = 1
    const val GZIP = 2
    const val BROTLI = 3
    const val ZSTD = 4

    fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
        NONE, UNKNOWN -> bytes
        // GZIPInputStream reads concatenated members, and ZstdInputStream
        // concatenated frames. `pmtiles merge` joins the frames of the layer
        // archives that share a tile without recompressing them, so the
        // whole stream is the merged tile.
        GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        ZSTD -> ZstdInputStreamNoFinalizer(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else -> throw IOException("unsupported PMTiles compression $compression")
    }
}
