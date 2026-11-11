package com.hikeratlas.android.pmtiles

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask

/** The fixed-size header of a PMTiles v3 archive. */
data class Header(
    val rootDirOffset: Long,
    val rootDirLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirsOffset: Long,
    val leafDirsLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val internalCompression: Int,
    val tileCompression: Int,
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
) {
    companion object {
        const val LENGTH = 127

        fun parse(bytes: ByteArray): Header {
            if (bytes.size < LENGTH || String(bytes, 0, 7, Charsets.US_ASCII) != "PMTiles") {
                throw IOException("not a PMTiles archive")
            }
            if (bytes[7].toInt() != 3) throw IOException("unsupported PMTiles version ${bytes[7]}")
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun e7(i: Int) = b.getInt(i) / 10_000_000.0
            return Header(
                rootDirOffset = b.getLong(8), rootDirLength = b.getLong(16),
                metadataOffset = b.getLong(24), metadataLength = b.getLong(32),
                leafDirsOffset = b.getLong(40), leafDirsLength = b.getLong(48),
                tileDataOffset = b.getLong(56), tileDataLength = b.getLong(64),
                internalCompression = bytes[97].toInt(), tileCompression = bytes[98].toInt(),
                tileType = bytes[99].toInt(),
                minZoom = bytes[100].toInt() and 0xff, maxZoom = bytes[101].toInt() and 0xff,
                minLon = e7(102), minLat = e7(106), maxLon = e7(110), maxLat = e7(114),
                centerZoom = bytes[118].toInt() and 0xff, centerLon = e7(119), centerLat = e7(123),
            )
        }
    }
}

/** A directory entry: a run of tiles, or (runLength 0) a leaf directory. */
data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

/**
 * Reads tiles from a PMTiles v3 archive.
 *
 * The header and root directory come from one read; leaf directories are
 * cached, so a tile costs one range read once its leaf has been seen. Safe to
 * use from many threads.
 */
class Pmtiles(private val source: RangeSource) {
    val key: String get() = source.key

    private class State(val header: Header, val root: List<Entry>)

    @Volatile private var state: State? = null
    private val stateLock = Any()

    // Leaf directories by offset. FutureTask so concurrent requests for the
    // same leaf share one read.
    private val leaves = object : LinkedHashMap<Long, FutureTask<List<Entry>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FutureTask<List<Entry>>>) =
            size > MAX_CACHED_LEAVES
    }

    fun header(): Header = load().header

    /** The tile's bytes, decompressed, or null if the archive has no such tile. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        return try {
            readTile(z, x, y)
        } catch (e: ArchiveChangedException) {
            reset()
            readTile(z, x, y)
        }
    }

    private fun readTile(z: Int, x: Int, y: Int): ByteArray? {
        val s = load()
        val h = s.header
        if (z < h.minZoom || z > h.maxZoom) return null
        val id = TileId.fromZxy(z, x, y)
        var dir = s.root
        for (depth in 0..3) {
            val entry = findTile(dir, id) ?: return null
            if (entry.runLength > 0) {
                val data = source.read(h.tileDataOffset + entry.offset, entry.length)
                return Compression.decompress(data, h.tileCompression)
            }
            dir = leaf(h, h.leafDirsOffset + entry.offset, entry.length)
        }
        throw IOException("PMTiles directories nested too deep")
    }

    private fun load(): State {
        state?.let { return it }
        synchronized(stateLock) {
            state?.let { return it }
            val first = source.read(0, INITIAL_READ)
            val header = Header.parse(first)
            val rootEnd = header.rootDirOffset + header.rootDirLength
            val rootBytes = if (rootEnd <= first.size) {
                first.copyOfRange(header.rootDirOffset.toInt(), rootEnd.toInt())
            } else {
                source.read(header.rootDirOffset, header.rootDirLength.toInt())
            }
            val root = Directory.parse(Compression.decompress(rootBytes, header.internalCompression))
            return State(header, root).also { state = it }
        }
    }

    private fun leaf(h: Header, offset: Long, length: Int): List<Entry> {
        val task = synchronized(leaves) {
            leaves.getOrPut(offset) {
                FutureTask { Directory.parse(Compression.decompress(source.read(offset, length), h.internalCompression)) }
            }
        }
        task.run() // no-op if another thread ran it
        return try {
            task.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            synchronized(leaves) { leaves.remove(offset) }
            throw e.cause ?: e
        }
    }

    private fun reset() {
        synchronized(stateLock) { state = null }
        synchronized(leaves) { leaves.clear() }
    }

    companion object {
        // The spec puts the header and root directory in the first 16 KiB.
        const val INITIAL_READ = 16384
        const val MAX_CACHED_LEAVES = 256

        fun findTile(entries: List<Entry>, tileId: Long): Entry? {
            var lo = 0
            var hi = entries.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = tileId.compareTo(entries[mid].tileId)
                when {
                    c > 0 -> lo = mid + 1
                    c < 0 -> hi = mid - 1
                    else -> return entries[mid]
                }
            }
            // hi is now the entry before tileId: a leaf directory covering
            // it, or a run of identical tiles reaching it.
            if (hi >= 0) {
                val e = entries[hi]
                if (e.runLength == 0) return e
                if (tileId - e.tileId < e.runLength) return e
            }
            return null
        }
    }
}

/** PMTiles directory encoding. */
object Directory {
    fun parse(bytes: ByteArray): List<Entry> {
        val r = VarintReader(bytes)
        val n = r.next().toInt()
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += r.next(); ids[i] = last }
        val runs = IntArray(n) { r.next().toInt() }
        val lengths = IntArray(n) { r.next().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = r.next()
            // 0 means "right after the previous entry's data".
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    private class VarintReader(private val b: ByteArray) {
        private var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= b.size) throw IOException("truncated PMTiles directory")
                val byte = b[pos++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("bad varint in PMTiles directory")
            }
        }
    }
}

/** Hilbert-curve tile IDs, as PMTiles numbers tiles. */
object TileId {
    fun fromZxy(z: Int, x: Int, y: Int): Long {
        require(z in 0..26) { "zoom $z out of range" }
        val n = 1L shl z
        require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y out of range" }
        // Tiles at lower zooms come first: 1 + 4 + 16 + ... + 4^(z-1).
        val base = ((1L shl (2 * z)) - 1) / 3
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (tx and s != 0L) 1L else 0L
            val ry = if (ty and s != 0L) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return base + d
    }
}
