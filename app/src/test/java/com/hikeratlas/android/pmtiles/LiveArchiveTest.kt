package com.hikeratlas.android.pmtiles

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads the published basemap. Opt in with HIKERATLAS_LIVE=1, since it needs
 * the network and the archive changes daily.
 */
class LiveArchiveTest {
    @Test
    fun readsAGaribaldiTile() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val source = HttpRangeSource("https://public.hikeratlas.com/north-america.pmtiles", OkHttpClient())
        val p = Pmtiles(source)
        val h = p.header()
        assertEquals(Compression.ZSTD, h.tileCompression)
        // Garibaldi Lake, BC, at z14.
        val tile = requireNotNull(p.tile(14, 2592, 5561))
        val layers = layerNames(tile)
        assertTrue("layers: $layers", layers.containsAll(listOf("path", "water_poly", "park")))
    }

    /** The names of a vector tile's layers (field 3, each with its name in field 1). */
    private fun layerNames(tile: ByteArray): Set<String> {
        var p = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val b = tile[p++].toInt() and 0xff
                r = r or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return r
                shift += 7
            }
        }
        // Skips a field's value, returning the bounds of a length-delimited one.
        fun skip(wireType: Int): IntRange? = when (wireType) {
            0 -> { varint(); null }
            1 -> { p += 8; null }
            2 -> { val n = varint().toInt(); (p until p + n).also { p += n } }
            5 -> { p += 4; null }
            else -> error("wire type $wireType")
        }
        val names = mutableSetOf<String>()
        while (p < tile.size) {
            val key = varint().toInt()
            val layer = skip(key and 7)
            if (key shr 3 != 3 || layer == null) continue
            val after = p
            p = layer.first
            while (p <= layer.last) {
                val k = varint().toInt()
                val v = skip(k and 7)
                if (k shr 3 == 1 && v != null) names += String(tile, v.first, v.last - v.first + 1)
            }
            p = after
        }
        return names
    }
}
