package com.hikeratlas.android.pmtiles

/** Tiles and files "kept on the phone", in memory, for tests. */
class MemoryOffline : LocalTiles, LocalResources {
    val tiles = HashMap<String, StoredTile>()
    val resources = HashMap<String, ByteArray>()
    var tileLookups = 0

    fun put(source: String, z: Int, x: Int, y: Int, t: StoredTile) { tiles["$source/$z/$x/$y"] = t }

    override fun tile(source: String, z: Int, x: Int, y: Int): StoredTile? {
        tileLookups++
        return tiles["$source/$z/$x/$y"]
    }

    override fun resource(key: String) = resources[key]
    override fun putResource(key: String, data: ByteArray) { resources[key] = data }
}

/** A source that's offline: any read fails as a dropped connection would. */
class OfflineSource(override val key: String) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray = throw java.io.IOException("offline")
}
