package com.hikeratlas.android.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
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
        addAreas(db)
        db.execSQL("ALTER TABLE areas ADD COLUMN build TEXT")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addAreas(db)
        if (oldVersion < 3) db.execSQL("ALTER TABLE areas ADD COLUMN build TEXT")
    }

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    // Version 2: downloaded areas, and which tiles and files each uses, so
    // deleting one removes only what no other uses.
    private fun addAreas(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE areas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                ref TEXT NOT NULL,
                name TEXT NOT NULL,
                created INTEGER NOT NULL,
                state TEXT NOT NULL,
                done INTEGER NOT NULL DEFAULT 0,
                total INTEGER NOT NULL DEFAULT 0,
                bytes INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                UNIQUE (kind, ref)
            )""",
        )
        db.execSQL(
            """CREATE TABLE area_tiles (
                area INTEGER NOT NULL REFERENCES areas(id) ON DELETE CASCADE,
                source TEXT NOT NULL,
                z INTEGER NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                PRIMARY KEY (area, source, z, x, y)
            ) WITHOUT ROWID""",
        )
        db.execSQL("CREATE INDEX area_tiles_tile ON area_tiles(source, z, x, y)")
        db.execSQL(
            """CREATE TABLE area_resources (
                area INTEGER NOT NULL REFERENCES areas(id) ON DELETE CASCADE,
                key TEXT NOT NULL,
                PRIMARY KEY (area, key)
            ) WITHOUT ROWID""",
        )
    }

    /** A downloaded (or downloading) area: a park, a hike, or the overview. */
    data class Area(
        val id: Long,
        val kind: String,
        /** The park's id, the hike's route id, or "" for the overview. */
        val ref: String,
        val name: String,
        val created: Long,
        val state: String,
        val done: Int,
        val total: Int,
        val bytes: Long,
        val error: String?,
        /** The archives' builds its tiles came from ([OfflineAreas.Build.key]); null before it's known. */
        val build: String? = null,
    ) {
        val ready get() = state == READY
    }

    /** The area for [kind]/[ref], made (queued) if there's none. */
    fun area(kind: String, ref: String, name: String): Area {
        writableDatabase.insertWithOnConflict(
            "areas", null,
            ContentValues().apply {
                put("kind", kind); put("ref", ref); put("name", name)
                put("created", System.currentTimeMillis()); put("state", QUEUED)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return findArea(kind, ref)!!
    }

    fun findArea(kind: String, ref: String): Area? =
        readableDatabase.rawQuery("SELECT * FROM areas WHERE kind = ? AND ref = ?", arrayOf(kind, ref)).use { c ->
            if (c.moveToFirst()) c.area() else null
        }

    fun areaById(id: Long): Area? =
        readableDatabase.rawQuery("SELECT * FROM areas WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.area() else null
        }

    /** Every area, newest first. */
    fun areas(): List<Area> = readableDatabase.rawQuery("SELECT * FROM areas ORDER BY created DESC, id DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.area()) }
    }

    fun setAreaState(id: Long, state: String, error: String? = null) {
        writableDatabase.update(
            "areas", ContentValues().apply { put("state", state); put("error", error) },
            "id = ?", arrayOf(id.toString()),
        )
    }

    fun setAreaProgress(id: Long, done: Int, total: Int) {
        writableDatabase.update(
            "areas", ContentValues().apply { put("done", done); put("total", total) },
            "id = ?", arrayOf(id.toString()),
        )
    }

    /** Kept tiles, in one transaction, each counted as used by [area]. */
    fun putAreaTiles(area: Long, source: String, z: Int, tiles: Map<Pair<Int, Int>, StoredTile>) {
        val db = writableDatabase
        db.transaction {
            for ((c, t) in tiles) {
                putTile(source, z, c.first, c.second, t)
                useTile(db, area, source, z, c.first, c.second)
            }
        }
    }

    /** Tiles already kept (for another area), counted as used by [area] too. */
    fun useTiles(area: Long, source: String, z: Int, coords: Collection<Pair<Int, Int>>) {
        val db = writableDatabase
        db.transaction {
            for (c in coords) useTile(db, area, source, z, c.first, c.second)
        }
    }

    private fun useTile(db: SQLiteDatabase, area: Long, source: String, z: Int, x: Int, y: Int) {
        db.insertWithOnConflict(
            "area_tiles", null,
            ContentValues().apply { put("area", area); put("source", source); put("z", z); put("x", x); put("y", y) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** A kept file, used by [area]. */
    fun putAreaResource(area: Long, key: String, data: ByteArray) {
        putResource(key, data)
        writableDatabase.insertWithOnConflict(
            "area_resources", null, ContentValues().apply { put("area", area); put("key", key) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** Marks [area] ready, with the bytes its tiles and files take. */
    fun finishArea(area: Long) {
        val bytes = readableDatabase.rawQuery(
            """SELECT
                (SELECT COALESCE(SUM(LENGTH(t.data)), 0) FROM area_tiles a JOIN tiles t
                    ON t.source = a.source AND t.z = a.z AND t.x = a.x AND t.y = a.y WHERE a.area = ?1)
              + (SELECT COALESCE(SUM(LENGTH(r.data)), 0) FROM area_resources a JOIN resources r
                    ON r.key = a.key WHERE a.area = ?1)""",
            arrayOf(area.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
        writableDatabase.update(
            "areas", ContentValues().apply { put("state", READY); put("bytes", bytes); putNull("error") },
            "id = ?", arrayOf(area.toString()),
        )
    }

    fun setAreaBuild(id: Long, build: String) {
        writableDatabase.update("areas", ContentValues().apply { put("build", build) }, "id = ?", arrayOf(id.toString()))
    }

    /** Gives the disk back after deleting (SQLite otherwise keeps the freed pages). */
    fun vacuum() {
        writableDatabase.execSQL("VACUUM")
    }

    /** Deletes an area, and the tiles and files no other area uses. */
    fun deleteArea(area: Long) {
        val db = writableDatabase
        val id = area.toString()
        db.transaction {
            db.execSQL(
                """DELETE FROM tiles WHERE EXISTS (
                    SELECT 1 FROM area_tiles a WHERE a.area = ?1
                      AND a.source = tiles.source AND a.z = tiles.z AND a.x = tiles.x AND a.y = tiles.y
                ) AND NOT EXISTS (
                    SELECT 1 FROM area_tiles o WHERE o.area != ?1
                      AND o.source = tiles.source AND o.z = tiles.z AND o.x = tiles.x AND o.y = tiles.y
                )""",
                arrayOf(id),
            )
            db.execSQL(
                """DELETE FROM resources WHERE key IN (SELECT key FROM area_resources WHERE area = ?1)
                    AND key NOT IN (SELECT key FROM area_resources WHERE area != ?1)""",
                arrayOf(id),
            )
            db.delete("areas", "id = ?", arrayOf(id))
        }
    }

    private fun android.database.Cursor.area(): Area {
        fun col(n: String) = getColumnIndexOrThrow(n)
        return Area(
            getLong(col("id")), getString(col("kind")), getString(col("ref")), getString(col("name")), getLong(col("created")),
            getString(col("state")), getInt(col("done")), getInt(col("total")), getLong(col("bytes")),
            col("error").let { if (isNull(it)) null else getString(it) },
            col("build").let { if (isNull(it)) null else getString(it) },
        )
    }

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

    companion object {
        private const val VERSION = 3

        const val PARK = "park"
        const val HIKE = "hike"
        const val OVERVIEW = "overview"

        const val QUEUED = "queued"
        const val DOWNLOADING = "downloading"
        /** A network error: WorkManager tries again soon (error says what went wrong). */
        const val RETRYING = "retrying"
        const val READY = "ready"
        const val FAILED = "failed"
    }
}
