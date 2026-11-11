package com.hikeratlas.android.pmtiles

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PmtilesTest {
    @Test
    fun tileIdsFollowTheSpec() {
        assertEquals(0L, TileId.fromZxy(0, 0, 0))
        assertEquals(1L, TileId.fromZxy(1, 0, 0))
        assertEquals(2L, TileId.fromZxy(1, 0, 1))
        assertEquals(3L, TileId.fromZxy(1, 1, 1))
        assertEquals(4L, TileId.fromZxy(1, 1, 0))
        assertEquals(5L, TileId.fromZxy(2, 0, 0))
        assertEquals(19078479L, TileId.fromZxy(12, 3423, 1763))
    }

    @Test
    fun tileIdsAreUniqueAndDenseWithinAZoom() {
        val z = 4
        val ids = (0 until 16).flatMap { x -> (0 until 16).map { y -> TileId.fromZxy(z, x, y) } }.toSet()
        val base = TileId.fromZxy(z, 0, 0)
        assertEquals((base until base + 256).toSet(), ids)
    }

    @Test
    fun directoriesRoundTrip() {
        val entries = listOf(
            Entry(5, 0, 100, 1),
            Entry(6, 100, 50, 3), // contiguous: encoded as offset 0
            Entry(1000, 10, 20, 0),
        )
        assertEquals(entries, Directory.parse(TestArchive.encodeDirectory(entries)))
    }

    @Test
    fun findTileHandlesRunsAndLeaves() {
        val dir = listOf(Entry(10, 0, 1, 3), Entry(20, 0, 1, 0))
        assertEquals(dir[0], Pmtiles.findTile(dir, 12))
        assertNull(Pmtiles.findTile(dir, 13))
        assertNull(Pmtiles.findTile(dir, 9))
        assertEquals(dir[1], Pmtiles.findTile(dir, 999))
    }

    @Test
    fun readsGzipTilesThroughLeafDirectories() {
        val tiles = (0 until 4).flatMap { x -> (0 until 4).map { y -> Triple(2, x, y) } }
            .associate { (z, x, y) -> TileId.fromZxy(z, x, y) to "tile $z/$x/$y".toByteArray() }
        val archive = TestArchive.build(
            tiles.mapValues { TestArchive.gzip(it.value) }, Compression.GZIP, 2, 2, leafSize = 5,
        )
        val source = BytesSource(archive)
        val p = Pmtiles(source)
        assertArrayEquals("tile 2/3/1".toByteArray(), p.tile(2, 3, 1))
        assertArrayEquals("tile 2/0/0".toByteArray(), p.tile(2, 0, 0))
        assertNull(p.tile(3, 0, 0)) // outside the zoom range
        val reads = source.reads
        p.tile(2, 3, 1)
        assertEquals("a cached leaf costs one read", reads + 1, source.reads)
    }

    @Test
    fun readsMergedZstdTiles() {
        // `pmtiles merge` joins each layer archive's zstd frame for a tile.
        val a = "layer-a".toByteArray()
        val b = "layer-b".toByteArray()
        val merged = Zstd.compress(a) + Zstd.compress(b)
        val id = TileId.fromZxy(0, 0, 0)
        val p = Pmtiles(BytesSource(TestArchive.build(mapOf(id to merged), Compression.ZSTD, 0, 0)))
        assertArrayEquals(a + b, p.tile(0, 0, 0))
    }

    @Test
    fun rereadsAnArchiveThatChanged() {
        val id = TileId.fromZxy(0, 0, 0)
        val v1 = TestArchive.build(mapOf(id to "one".toByteArray()), Compression.NONE, 0, 0)
        val v2 = TestArchive.build(mapOf(id to "two!".toByteArray()), Compression.NONE, 0, 0)
        var current = v1
        var changed = false
        val source = object : RangeSource {
            override val key = "changing"
            override fun read(offset: Long, length: Int): ByteArray {
                if (changed) { changed = false; throw ArchiveChangedException("rebuilt") }
                return current.copyOfRange(offset.toInt(), minOf(current.size, (offset + length).toInt()))
            }
        }
        val p = Pmtiles(source)
        assertArrayEquals("one".toByteArray(), p.tile(0, 0, 0))
        current = v2
        changed = true
        assertArrayEquals("two!".toByteArray(), p.tile(0, 0, 0))
    }
}
