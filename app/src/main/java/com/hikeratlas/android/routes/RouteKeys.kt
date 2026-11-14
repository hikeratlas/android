package com.hikeratlas.android.routes

import com.uber.h3core.H3Core

/**
 * Where a route key's route is: the centre of its centroid cell. Only the
 * decoding of that cell is ported from hikeratlas/route-keys (SPEC.md,
 * "Decoding"; ts/src/index.ts's decode and centroidOf). The app never makes
 * keys: they come from the build (Route.route_key).
 */
object RouteKeys {
    private const val VERSION = '1'
    private const val RESOLUTION = 10
    private const val HASH_CHARS = 11
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * The key's centroid cell (an H3 index, resolution 10), or null for
     * anything not a v1 key. Bit arithmetic only: H3 itself isn't needed
     * until the cell is turned into a place.
     */
    fun centroidCell(key: String): Long? {
        val dot = key.indexOf('.')
        if (dot < 0) return null
        val cells = key.substring(0, dot)
        val hash = key.substring(dot + 1)
        if (hash.length != HASH_CHARS || hash.any { ALPHABET.indexOf(it) < 0 }) return null
        if (cells.length < 11 || cells[0] != VERSION || cells[8] !in "bpw") return null
        if (cells.any { ALPHABET.indexOf(it) < 0 }) return null
        // Characters 1-7: 42 bits, ending in 5 zero bits; the 37 above them
        // are the base cell (7 bits) and digits 1-10 (3 bits each).
        var v = 0L
        for (ch in cells.substring(1, 8)) v = (v shl 6) or ALPHABET.indexOf(ch).toLong()
        if (v and 31L != 0L) return null
        v = v shr 5
        // Mode 1 (cell), resolution 10, the base cell; digits 11-15 unused (7).
        var n = (1L shl 59) or (RESOLUTION.toLong() shl 52) or ((v shr 30) shl 45)
        for (r in 1..15) {
            val d = if (r <= RESOLUTION) (v shr (3 * (RESOLUTION - r))) and 7L else 7L
            n = n or (d shl ((15 - r) * 3))
        }
        return n
    }

    /** The centre of the key's centroid cell, [lon, lat]; null for anything not a v1 key, or without H3. */
    fun centroid(key: String): LngLat? {
        val h3 = h3 ?: return null
        val cell = centroidCell(key) ?: return null
        if (!h3.isValidCell(cell)) return null
        val p = h3.cellToLatLng(cell)
        return listOf(p.lng, p.lat)
    }

    /** Whether keys can be placed here: H3's native library loaded (h3-java has none for x86 or x86_64 Android). */
    val available: Boolean get() = h3 != null

    private val h3: H3Core? by lazy {
        // On a phone, the library the app installs (the build unpacks it into
        // jniLibs); on a desktop JVM (unit tests), the one h3-java copies out
        // of its jar. Neither on an x86 or x86_64 emulator: keys just can't
        // be placed there.
        try {
            H3Core.newSystemInstance()
        } catch (_: Throwable) {
            try {
                H3Core.newInstance()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
