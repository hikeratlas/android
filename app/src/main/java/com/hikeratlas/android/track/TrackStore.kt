package com.hikeratlas.android.track

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Recorded tracks, on the phone only. Each kept fix is written as it comes,
 * so a crash loses at most the fix in hand.
 */
class TrackStore(context: Context, name: String? = "tracks.db") :
    SQLiteOpenHelper(context, name, null, VERSION) {

    data class Track(
        val id: Long,
        val started: Long,
        val ended: Long?,
        val name: String?,
        /** Time spent paused before [pausedAt]. */
        val pausedMs: Long,
        /** When it was paused, if it is. */
        val pausedAt: Long?,
        /** Worked out when it's stopped (null before, or if that didn't finish). */
        val stats: Stats? = null,
    )

    /** A finished track's numbers, kept for the list. */
    data class Stats(
        val metres: Double,
        /** Recording, not counting pauses. */
        val elapsedMs: Long,
        /** Of that, moving. */
        val movingMs: Long,
        /** From the DEM; null where there was none. */
        val gain: Double?,
        val loss: Double?,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started INTEGER NOT NULL,
                ended INTEGER,
                name TEXT,
                paused_ms INTEGER NOT NULL DEFAULT 0,
                paused_at INTEGER
            )""",
        )
        addStats(db)
        // alt: above the WGS84 ellipsoid; msl_alt: above sea level; both GPS's own.
        db.execSQL(
            """CREATE TABLE points (
                track INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                seg INTEGER NOT NULL,
                t INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                acc REAL,
                alt REAL,
                msl_alt REAL,
                v_acc REAL,
                speed REAL
            )""",
        )
        db.execSQL("CREATE INDEX points_track ON points(track, t)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addStats(db)
    }

    // Version 2: a finished track's numbers.
    private fun addStats(db: SQLiteDatabase) {
        for (col in listOf("metres REAL", "elapsed_ms INTEGER", "moving_ms INTEGER", "gain REAL", "loss REAL")) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN $col")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    fun start(now: Long): Long = writableDatabase.insertOrThrow("tracks", null, ContentValues().apply { put("started", now) })

    fun add(track: Long, seg: Int, f: Fix) {
        writableDatabase.insertOrThrow(
            "points", null,
            ContentValues().apply {
                put("track", track); put("seg", seg); put("t", f.t); put("lat", f.lat); put("lon", f.lon)
                put("acc", f.accuracy); put("alt", f.altitude); put("msl_alt", f.mslAltitude)
                put("v_acc", f.verticalAccuracy); put("speed", f.speed)
            },
        )
    }

    fun setPaused(track: Long, pausedMs: Long, pausedAt: Long?) {
        writableDatabase.update(
            "tracks", ContentValues().apply { put("paused_ms", pausedMs); put("paused_at", pausedAt) },
            "id = ?", arrayOf(track.toString()),
        )
    }

    fun end(track: Long, now: Long) {
        writableDatabase.update("tracks", ContentValues().apply { put("ended", now) }, "id = ?", arrayOf(track.toString()))
    }

    fun setStats(track: Long, st: Stats) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply {
                put("metres", st.metres); put("elapsed_ms", st.elapsedMs); put("moving_ms", st.movingMs)
                put("gain", st.gain); put("loss", st.loss)
            },
            "id = ?", arrayOf(track.toString()),
        )
    }

    fun rename(track: Long, name: String) {
        writableDatabase.update("tracks", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(track.toString()))
    }

    /** Deletes a track and its fixes. */
    fun delete(track: Long) {
        writableDatabase.delete("tracks", "id = ?", arrayOf(track.toString()))
    }

    /** Makes a stopped track unfinished again, paused at [pausedAt], to record on; its numbers are worked out again when it stops. */
    fun reopen(track: Long, pausedAt: Long) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply {
                putNull("ended"); put("paused_at", pausedAt)
                for (c in listOf("metres", "elapsed_ms", "moving_ms", "gain", "loss")) putNull(c)
            },
            "id = ?", arrayOf(track.toString()),
        )
    }

    /** Finished tracks, newest first. */
    fun saved(): List<Track> = readableDatabase.query("tracks", null, "ended IS NOT NULL", null, null, null, "started DESC").use { c ->
        buildList { while (c.moveToNext()) add(c.track()) }
    }

    fun track(id: Long): Track? = readableDatabase.query("tracks", null, "id = ?", arrayOf(id.toString()), null, null, null).use {
        if (it.moveToFirst()) it.track() else null
    }

    /** The track being recorded, if one was left unfinished (the app was killed). */
    fun unfinished(): Track? = readableDatabase.query("tracks", null, "ended IS NULL", null, null, null, "started DESC", "1").use {
        if (it.moveToFirst()) it.track() else null
    }

    /** A track's kept fixes, in order, with the segment of each. */
    fun points(track: Long): List<Pair<Int, Fix>> =
        readableDatabase.query("points", null, "track = ?", arrayOf(track.toString()), null, null, "t, rowid").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        c.int("seg") to Fix(
                            c.long("t"), c.double("lat")!!, c.double("lon")!!, c.double("acc")?.toFloat(),
                            c.double("alt"), c.double("msl_alt"), c.double("v_acc")?.toFloat(), c.double("speed")?.toFloat(),
                        ),
                    )
                }
            }
        }

    private fun Cursor.track() = Track(
        long("id"), long("started"), longOrNull("ended"), string("name"), long("paused_ms"), longOrNull("paused_at"),
        double("metres")?.let { m ->
            Stats(m, longOrNull("elapsed_ms") ?: 0, longOrNull("moving_ms") ?: 0, double("gain"), double("loss"))
        },
    )
    private fun Cursor.col(n: String) = getColumnIndexOrThrow(n)
    private fun Cursor.long(n: String) = getLong(col(n))
    private fun Cursor.int(n: String) = getInt(col(n))
    private fun Cursor.longOrNull(n: String) = col(n).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.double(n: String) = col(n).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.string(n: String) = col(n).let { if (isNull(it)) null else getString(it) }

    private companion object {
        const val VERSION = 2
    }
}
