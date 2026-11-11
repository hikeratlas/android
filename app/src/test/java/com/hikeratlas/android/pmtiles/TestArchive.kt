package com.hikeratlas.android.pmtiles

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/** Builds small PMTiles v3 archives in memory, for tests. */
object TestArchive {
    fun varints(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (x >= 0x80) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
    }

    fun encodeDirectory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        varints(out, entries.size.toLong())
        var last = 0L
        for (e in entries) { varints(out, e.tileId - last); last = e.tileId }
        for (e in entries) varints(out, e.runLength.toLong())
        for (e in entries) varints(out, e.length.toLong())
        for ((i, e) in entries.withIndex()) {
            val contiguous = i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length
            varints(out, if (contiguous) 0 else e.offset + 1)
        }
        return out.toByteArray()
    }

    fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /**
     * An archive of [tiles] (tile ID to already-compressed bytes). With
     * [leafSize], the root directory points at leaf directories of that many
     * entries each.
     */
    fun build(
        tiles: Map<Long, ByteArray>,
        tileCompression: Int,
        minZoom: Int,
        maxZoom: Int,
        leafSize: Int? = null,
    ): ByteArray {
        val data = ByteArrayOutputStream()
        val entries = tiles.toSortedMap().map { (id, bytes) ->
            Entry(id, data.size().toLong(), bytes.size, 1).also { data.write(bytes) }
        }
        val leaves = ByteArrayOutputStream()
        val root: List<Entry> = if (leafSize == null) entries else entries.chunked(leafSize).map { chunk ->
            val leaf = gzip(encodeDirectory(chunk))
            Entry(chunk.first().tileId, leaves.size().toLong(), leaf.size, 0).also { leaves.write(leaf) }
        }
        val rootBytes = gzip(encodeDirectory(root))
        val rootOffset = Header.LENGTH.toLong()
        val leafOffset = rootOffset + rootBytes.size
        val dataOffset = leafOffset + leaves.size()

        val h = ByteBuffer.allocate(Header.LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()); h.put(3)
        h.putLong(rootOffset); h.putLong(rootBytes.size.toLong())
        h.putLong(0); h.putLong(0) // metadata
        h.putLong(leafOffset); h.putLong(leaves.size().toLong())
        h.putLong(dataOffset); h.putLong(data.size().toLong())
        h.putLong(tiles.size.toLong()); h.putLong(tiles.size.toLong()); h.putLong(tiles.size.toLong())
        h.put(1) // clustered
        h.put(Compression.GZIP.toByte()); h.put(tileCompression.toByte()); h.put(1) // mvt
        h.put(minZoom.toByte()); h.put(maxZoom.toByte())
        repeat(4) { h.putInt(0) } // bounds
        h.put(0); h.putInt(0); h.putInt(0) // center

        return ByteArrayOutputStream().apply {
            write(h.array()); write(rootBytes); write(leaves.toByteArray()); write(data.toByteArray())
        }.toByteArray()
    }
}

class BytesSource(private val bytes: ByteArray, override val key: String = "test") : RangeSource {
    var reads = 0
    override fun read(offset: Long, length: Int): ByteArray {
        reads++
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, (offset + length).toInt()))
    }
}
