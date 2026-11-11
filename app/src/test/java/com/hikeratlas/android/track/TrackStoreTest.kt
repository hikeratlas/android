package com.hikeratlas.android.track

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackStoreTest {
    @Test
    fun keepsTracksAndTheirFixes() {
        TrackStore(ApplicationProvider.getApplicationContext(), name = null).use { store ->
            val id = store.start(100)
            val f = Fix(200, 49.1, -123.2, 4.5f, altitude = 780.0, mslAltitude = 797.5, verticalAccuracy = 7f, speed = 1.4f)
            store.add(id, 0, f)
            store.add(id, 1, Fix(300, 49.2, -123.3, 3f))
            assertEquals(listOf(0 to f, 1 to Fix(300, 49.2, -123.3, 3f)), store.points(id))
            store.setPaused(id, 5_000, 250)
            assertEquals(TrackStore.Track(id, 100, null, null, 5_000, 250), store.unfinished())
            store.end(id, 400)
            assertNull(store.unfinished())
            assertEquals(400L, store.track(id)!!.ended)
        }
    }

    @Test
    fun keepsStatsNamesAndDeletes() {
        TrackStore(ApplicationProvider.getApplicationContext(), name = null).use { store ->
            val old = store.start(100)
            store.add(old, 0, Fix(150, 49.0, -123.0, 4f))
            store.end(old, 200)
            val new = store.start(300)
            store.end(new, 400)
            val unfinished = store.start(500)
            assertEquals(listOf(new, old), store.saved().map { it.id })
            assertEquals(unfinished, store.unfinished()!!.id)

            val st = TrackStore.Stats(1234.5, 60_000, 50_000, 210.0, 190.0)
            store.setStats(old, st)
            store.rename(old, "Upward Mobility")
            assertEquals(st, store.track(old)!!.stats)
            assertEquals("Upward Mobility", store.track(old)!!.name)

            // Reopened to record on: unfinished, paused when it stopped, its numbers to work out again.
            store.reopen(old, 200)
            val again = store.track(old)!!
            assertEquals(null, again.ended)
            assertEquals(200L, again.pausedAt)
            assertEquals(null, again.stats)

            store.delete(old)
            assertNull(store.track(old))
            assertEquals(emptyList<Pair<Int, Fix>>(), store.points(old))
        }
    }

    /** A phone with the first version's database keeps its tracks. */
    @Test
    fun upgradesTheFirstVersionsDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("v1.db").apply { parentFile?.mkdirs(); delete() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, ended INTEGER, name TEXT, paused_ms INTEGER NOT NULL DEFAULT 0, paused_at INTEGER)")
            db.execSQL("CREATE TABLE points (track INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE, seg INTEGER NOT NULL, t INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, alt REAL, msl_alt REAL, v_acc REAL, speed REAL)")
            db.execSQL("INSERT INTO tracks (started, ended) VALUES (100, 900)")
            db.execSQL("INSERT INTO points (track, seg, t, lat, lon, acc, alt) VALUES (1, 0, 150, 49.0, -123.0, 4, 812.5)")
            db.version = 1
        }
        TrackStore(context, "v1.db").use { store ->
            val t = store.saved().single()
            assertEquals(900L, t.ended)
            assertNull(t.stats)
            assertEquals(812.5, store.points(t.id).single().second.altitude!!, 0.0)
            store.setStats(t.id, TrackStore.Stats(10.0, 800, 700, null, null))
            assertEquals(10.0, store.track(t.id)!!.stats!!.metres, 0.0)
        }
    }
}
