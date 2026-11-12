package com.hikeratlas.android.pmtiles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class PmtilesOfflineTest {
    private val gz = { s: String -> StoredTile(Compression.GZIP, TestArchive.gzip(s.toByteArray())) }

    @Test
    fun aKeptTileIsReadWithoutTheArchive() {
        val kept = MemoryOffline().apply { put("https://x/basemap.pmtiles", 14, 2600, 5600, gz("kept")) }
        // Offline: the archive can't be reached, not even its header.
        val p = Pmtiles(OfflineSource("https://x/basemap.pmtiles")) { kept }
        assertArrayEquals("kept".toByteArray(), p.tile(14, 2600, 5600))
        // Not kept: it tries the archive, and fails as the connection does.
        try {
            p.tile(14, 2601, 5600)
            throw AssertionError("expected the archive to be tried")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test
    fun aBatchReadsOnlyWhatIsntKept() = runBlocking {
        val tiles = (0 until 4).flatMap { x -> (0 until 4).map { y -> Triple(2, x, y) } }
            .associate { (z, x, y) -> TileId.fromZxy(z, x, y) to TestArchive.gzip("archive $z/$x/$y".toByteArray()) }
        val source = BytesSource(TestArchive.build(tiles, Compression.GZIP, 2, 2), key = "a")
        val kept = MemoryOffline().apply {
            put("a", 2, 0, 0, gz("kept 2/0/0"))
            put("a", 2, 1, 1, gz("kept 2/1/1"))
        }
        val p = Pmtiles(source) { kept }
        val got = p.tiles(2, listOf(0 to 0, 1 to 1, 3 to 3))
        assertArrayEquals("kept 2/0/0".toByteArray(), got[0 to 0])
        assertArrayEquals("kept 2/1/1".toByteArray(), got[1 to 1])
        assertArrayEquals("archive 2/3/3".toByteArray(), got[3 to 3])

        // All kept: the archive isn't read at all.
        val reads = source.reads
        val again = p.tiles(2, listOf(0 to 0, 1 to 1))
        assertEquals(reads, source.reads)
        assertEquals(2, again.size)
    }

    @Test
    fun withNothingKeptItReadsTheArchiveAsBefore() {
        val tiles = mapOf(TileId.fromZxy(0, 0, 0) to TestArchive.gzip("root".toByteArray()))
        val p = Pmtiles(BytesSource(TestArchive.build(tiles, Compression.GZIP, 0, 0))) { null }
        assertArrayEquals("root".toByteArray(), p.tile(0, 0, 0))
        assertNull(p.tile(1, 0, 0))
    }
}
