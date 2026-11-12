package com.hikeratlas.android.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hikeratlas.android.pmtiles.LocalResources
import com.hikeratlas.android.pmtiles.LocalTiles
import com.hikeratlas.android.pmtiles.StoredTile

/**
 * What's kept on the phone for offline use, in one SQLite database:
 *
 * - tiles: by source (an archive's URL, or "mapterhorn") and z/x/y, as their
 *   archive stores them (compressed), so overlapping areas share them;
 * - resources: files by key (a URL): routes data, fonts, icons.
 *
 * Every tile reader checks it before the network (see [com.hikeratlas.android.pmtiles.Offline]),
 * so what's kept is used online too, and loads at once.
 */
class OfflineStore(context: Context, name: String? = "offline.db") :
    SQLiteOpenHelper(context, name, null, VERSION), LocalTiles, LocalResources {

    init {
        // Readers (MapLibre's tile requests) aren't blocked by a download's writes.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tiles (
                source TEXT NOT NULL,
                z INTEGER NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                compression INTEGER NOT NULL,
                data BLOB NOT NULL,
                PRIMARY KEY (source, z, x, y)
            ) WITHOUT ROWID""",
        )
        db.execSQL(
            """CREATE TABLE resources (
                key TEXT PRIMARY KEY NOT NULL,
                data BLOB NOT NULL,
                updated INTEGER NOT NULL
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun tile(source: String, z: Int, x: Int, y: Int): StoredTile? =
        readableDatabase.rawQuery(
            "SELECT compression, data FROM tiles WHERE source = ? AND z = ? AND x = ? AND y = ?",
            arrayOf(source, z.toString(), x.toString(), y.toString()),
        ).use { c -> if (c.moveToFirst()) StoredTile(c.getInt(0), c.getBlob(1)) else null }

    override fun tiles(source: String, z: Int, coords: Collection<Pair<Int, Int>>): Map<Pair<Int, Int>, StoredTile> {
        if (coords.isEmpty()) return emptyMap()
        val want = coords.toHashSet()
        val xs = coords.map { it.first }
        val ys = coords.map { it.second }
        // One query over the block they're in, rather than one per tile.
        return readableDatabase.rawQuery(
            "SELECT x, y, compression, data FROM tiles WHERE source = ? AND z = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ?",
            arrayOf(source, z.toString(), xs.min().toString(), xs.max().toString(), ys.min().toString(), ys.max().toString()),
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val k = c.getInt(0) to c.getInt(1)
                    if (k in want) put(k, StoredTile(c.getInt(2), c.getBlob(3)))
                }
            }
        }
    }

    fun putTile(source: String, z: Int, x: Int, y: Int, tile: StoredTile) {
        writableDatabase.insertWithOnConflict(
            "tiles", null,
            ContentValues().apply {
                put("source", source); put("z", z); put("x", x); put("y", y)
                put("compression", tile.compression); put("data", tile.data)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun resource(key: String): ByteArray? =
        readableDatabase.rawQuery("SELECT data FROM resources WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }

    override fun putResource(key: String, data: ByteArray) {
        writableDatabase.insertWithOnConflict(
            "resources", null,
            ContentValues().apply { put("key", key); put("data", data); put("updated", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Bytes kept: tiles and resources, for the storage summary. */
    fun bytes(): Long = readableDatabase.rawQuery(
        "SELECT (SELECT COALESCE(SUM(LENGTH(data)), 0) FROM tiles) + (SELECT COALESCE(SUM(LENGTH(data)), 0) FROM resources)",
        null,
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    private companion object {
        const val VERSION = 1
    }
}
