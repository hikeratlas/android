package com.hikeratlas.android.offline

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.pmtiles.Compression
import com.hikeratlas.android.pmtiles.StoredTile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OfflineStoreTest {
    @Test
    fun keepsTilesBySourceAndPosition() {
        OfflineStore(ApplicationProvider.getApplicationContext(), name = null).use { s ->
            s.putTile("basemap", 14, 10, 20, StoredTile(Compression.ZSTD, byteArrayOf(1)))
            s.putTile("basemap", 14, 11, 20, StoredTile(Compression.ZSTD, byteArrayOf(2)))
            s.putTile("routes", 14, 10, 20, StoredTile(Compression.GZIP, byteArrayOf(3)))
            s.putTile("basemap", 14, 12, 22, StoredTile(Compression.ZSTD, byteArrayOf(4)))
            val t = s.tile("basemap", 14, 10, 20)!!
            assertEquals(Compression.ZSTD, t.compression)
            assertArrayEquals(byteArrayOf(1), t.data)
            assertArrayEquals(byteArrayOf(3), s.tile("routes", 14, 10, 20)!!.data)
            assertNull(s.tile("basemap", 13, 10, 20))
            // A batch: only those asked for, though (12, 22) is in the block they span.
            val got = s.tiles("basemap", 14, listOf(10 to 20, 11 to 20, 12 to 20))
            assertEquals(setOf(10 to 20, 11 to 20), got.keys)
            // Replaced, not duplicated.
            s.putTile("basemap", 14, 10, 20, StoredTile(Compression.ZSTD, byteArrayOf(9)))
            assertArrayEquals(byteArrayOf(9), s.tile("basemap", 14, 10, 20)!!.data)
        }
    }

    @Test
    fun keepsResourcesAndCountsBytes() {
        OfflineStore(ApplicationProvider.getApplicationContext(), name = null).use { s ->
            assertEquals(0, s.bytes())
            s.putResource("https://x/index.json", ByteArray(100))
            s.putTile("basemap", 0, 0, 0, StoredTile(Compression.ZSTD, ByteArray(50)))
            assertEquals(100, s.resource("https://x/index.json")!!.size)
            assertNull(s.resource("https://x/other.json"))
            assertEquals(150, s.bytes())
        }
    }
}
