package com.hikeratlas.android.pmtiles

/**
 * A tile kept on the phone (an offline area's), as its archive stores it:
 * [data] compressed with [compression] (a [Compression] code), so it reads
 * without the archive's header, which may be out of reach.
 */
class StoredTile(val compression: Int, val data: ByteArray)

/** Tiles kept on the phone, by source (an archive's URL, or "mapterhorn") and z/x/y. */
interface LocalTiles {
    fun tile(source: String, z: Int, x: Int, y: Int): StoredTile?

    /** The ones of [coords] (at [z]) that are kept; the rest are left out. */
    fun tiles(source: String, z: Int, coords: Collection<Pair<Int, Int>>): Map<Pair<Int, Int>, StoredTile> =
        coords.mapNotNull { c -> tile(source, z, c.first, c.second)?.let { c to it } }.toMap()
}

/** Files kept on the phone (routes data, fonts, icons), by key: a URL. */
interface LocalResources {
    fun resource(key: String): ByteArray?
    fun putResource(key: String, data: ByteArray)
}

/**
 * What's kept on the phone, which every reader checks before the network:
 * the app sets these at start (OfflineStore); tests set their own, or none.
 */
object Offline {
    @Volatile var tiles: LocalTiles? = null
    @Volatile var resources: LocalResources? = null
}
