package com.hikeratlas.android.offline

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.elevation.MapterhornSource
import com.hikeratlas.android.pmtiles.BytesSource
import com.hikeratlas.android.pmtiles.Compression
import com.hikeratlas.android.pmtiles.Pmtiles
import com.hikeratlas.android.pmtiles.TestArchive
import com.hikeratlas.android.pmtiles.TileId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two small archives (z0-2, every tile) and a terrain source with some tiles missing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AreaDownloaderTest {
    private val store = OfflineStore(ApplicationProvider.getApplicationContext(), name = null)

    @After
    fun tearDown() = store.close()

    private fun archive(name: String): BytesSource {
        val tiles = (0..2).flatMap { z -> (0 until (1 shl z)).flatMap { x -> (0 until (1 shl z)).map { y -> Triple(z, x, y) } } }
            .associate { (z, x, y) -> TileId.fromZxy(z, x, y) to TestArchive.gzip("$name $z/$x/$y".toByteArray()) }
        return BytesSource(TestArchive.build(tiles, Compression.GZIP, 0, 2), key = "https://x/$name.pmtiles")
    }

    private val basemapSrc = archive("basemap")
    private val routesSrc = archive("routes")
    private val demCalls = mutableListOf<Triple<Int, Int, Int>>()
    // Terrain: everything to z1; at z2 only x=0 (the rest 404).
    private val dem = { z: Int, x: Int, y: Int ->
        synchronized(demCalls) { demCalls += Triple(z, x, y) }
        if (z <= 1 || x == 0) "dem $z/$x/$y".toByteArray() else null
    }
    private val downloader = AreaDownloader(store, Pmtiles(basemapSrc) { null }, Pmtiles(routesSrc) { null }, dem)

    private val plan = AreaPlan(
        vector = mapOf(0 to setOf(0 to 0), 2 to setOf(0 to 0, 1 to 1, 3 to 3)),
        dem = mapOf(1 to setOf(0 to 0), 2 to setOf(0 to 0, 1 to 0)),
    )

    @Test
    fun keepsAnAreasTilesAsTheArchivesHaveThem() = runBlocking {
        val area = store.area(OfflineStore.PARK, "1", "Test").id
        val seen = mutableListOf<Pair<Int, Int>>()
        downloader.download(area, plan) { d, t -> seen += d to t }
        val total = 4 + 4 + 3
        assertEquals(total to total, seen.last())

        // Raw, as the archive stores them: compressed, with the compression.
        val t = store.tile(basemapSrc.key, 2, 1, 1)!!
        assertEquals(Compression.GZIP, t.compression)
        assertArrayEquals("basemap 2/1/1".toByteArray(), Compression.decompress(t.data, t.compression))
        assertArrayEquals("routes 2/3/3".toByteArray(), Compression.decompress(store.tile(routesSrc.key, 2, 3, 3)!!.data, Compression.GZIP))
        assertArrayEquals("dem 2/0/0".toByteArray(), store.tile(MapterhornSource.KEY, 2, 0, 0)!!.data)
        // Mapterhorn's 404s are left out.
        assertNull(store.tile(MapterhornSource.KEY, 2, 1, 0))

        store.finishArea(area)
        assertTrue(store.areaById(area)!!.bytes > 0)
    }

    @Test
    fun aSecondAreaOrARetryFetchesOnlyWhatIsntKept() = runBlocking {
        val a = store.area(OfflineStore.PARK, "1", "A").id
        downloader.download(a, plan)
        val reads = basemapSrc.reads + routesSrc.reads
        val demRequests = demCalls.size

        // Another area over the same tiles: the archives aren't read, and of
        // the terrain only the tile Mapterhorn hasn't got is asked for again.
        val b = store.area(OfflineStore.HIKE, "1/day/0", "B").id
        downloader.download(b, plan)
        assertEquals(reads, basemapSrc.reads + routesSrc.reads)
        assertEquals(listOf(Triple(2, 1, 0)), demCalls.drop(demRequests))
        // Deleting the first keeps the second's tiles.
        store.deleteArea(a)
        assertArrayEquals("basemap 0/0/0".toByteArray(), Compression.decompress(store.tile(basemapSrc.key, 0, 0, 0)!!.data, Compression.GZIP))
    }

    @Test
    fun estimatesFromTheArchivesDirectories() = runBlocking {
        val e = downloader.estimate(plan)
        // Basemap and routes exactly; terrain guessed per tile.
        val vectorBytes = listOf(0 to (0 to 0), 2 to (0 to 0), 2 to (1 to 1), 2 to (3 to 3)).sumOf { (z, c) ->
            TestArchive.gzip("basemap $z/${c.first}/${c.second}".toByteArray()).size + TestArchive.gzip("routes $z/${c.first}/${c.second}".toByteArray()).size
        }
        assertEquals(vectorBytes + 3 * AreaDownloader.DEM_TILE_BYTES, e)
        // Once it's all kept, nothing's left to download but the missing terrain.
        downloader.download(store.area(OfflineStore.PARK, "1", "T").id, plan)
        assertEquals(1 * AreaDownloader.DEM_TILE_BYTES, downloader.estimate(plan))
    }
}
