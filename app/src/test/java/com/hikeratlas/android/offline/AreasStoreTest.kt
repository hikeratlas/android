package com.hikeratlas.android.offline

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.pmtiles.Compression
import com.hikeratlas.android.pmtiles.StoredTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AreasStoreTest {
    private fun t(n: Int) = StoredTile(Compression.ZSTD, ByteArray(n))

    @Test
    fun deletingAnAreaKeepsWhatAnotherUses() {
        OfflineStore(ApplicationProvider.getApplicationContext(), name = null).use { s ->
            val a = s.area(OfflineStore.PARK, "1", "Garibaldi").id
            val b = s.area(OfflineStore.HIKE, "1/day/5", "Black Tusk").id
            // The same area again is the same row.
            assertEquals(a, s.area(OfflineStore.PARK, "1", "Garibaldi").id)
            s.putAreaTiles(a, "basemap", 14, mapOf((1 to 1) to t(10), (2 to 2) to t(20)))
            s.useTiles(b, "basemap", 14, listOf(2 to 2))
            s.putAreaTiles(b, "basemap", 14, mapOf((3 to 3) to t(30)))
            s.putAreaResource(a, "park/1", ByteArray(5))
            s.putAreaResource(b, "park/1", ByteArray(5))
            s.finishArea(a)
            assertEquals(35, s.areaById(a)!!.bytes)
            assertEquals(OfflineStore.READY, s.areaById(a)!!.state)

            s.deleteArea(a)
            assertNull(s.areaById(a))
            assertNull(s.tile("basemap", 14, 1, 1)) // only a's
            assertNotNull(s.tile("basemap", 14, 2, 2)) // b's too
            assertNotNull(s.tile("basemap", 14, 3, 3))
            assertNotNull(s.resource("park/1"))

            s.deleteArea(b)
            assertNull(s.tile("basemap", 14, 2, 2))
            assertNull(s.resource("park/1"))
            assertEquals(0, s.bytes())
        }
    }

    /** A phone with the first version's store keeps what's in it. */
    @Test
    fun upgradesTheFirstVersion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("offline-v1.db").apply { parentFile?.mkdirs(); delete() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE tiles (source TEXT NOT NULL, z INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, compression INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (source, z, x, y)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE resources (key TEXT PRIMARY KEY NOT NULL, data BLOB NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("INSERT INTO resources VALUES ('https://x/index.json', x'00', 1)")
            db.version = 1
        }
        OfflineStore(context, "offline-v1.db").use { s ->
            assertNotNull(s.resource("https://x/index.json"))
            assertEquals(emptyList<OfflineStore.Area>(), s.areas())
            s.area(OfflineStore.PARK, "1", "Garibaldi")
            assertEquals(1, s.areas().size)
        }
    }

    /**
     * A download's progress says it's downloading: a stopped run's clean-up
     * landing after the next run began left it "queued" (the Saved list said
     * "Waiting to start") while the download went on, its notification moving.
     */
    @Test
    fun progressSaysItsDownloadingWhateverWasWrittenBefore() {
        OfflineStore(ApplicationProvider.getApplicationContext(), name = null).use { s ->
            val a = s.area(OfflineStore.PARK, "1", "Garibaldi").id
            s.setAreaState(a, OfflineStore.DOWNLOADING)
            // The old run's clean-up, late.
            s.setAreaState(a, OfflineStore.QUEUED)
            s.setAreaProgress(a, 120, 800)
            val now = s.areaById(a)!!
            assertEquals(OfflineStore.DOWNLOADING, now.state)
            assertEquals(120, now.done)
            assertEquals(800, now.total)
            assertEquals("Downloading · 15%", areaStatus(now, wifiOnly = true))
        }
    }
}
