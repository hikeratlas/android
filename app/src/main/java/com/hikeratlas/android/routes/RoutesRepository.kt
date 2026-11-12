package com.hikeratlas.android.routes

import com.hikeratlas.android.pmtiles.Offline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * The curated routes of a build: each park's JSON gzipped, end to end, in one
 * bundle file, located by index.json. As the website reads them.
 */
class RoutesRepository(
    private val client: OkHttpClient,
    private val root: String = "https://public.hikeratlas.com/routes/north-america",
) {
    private val mutex = Mutex()
    private var index: BundleIndex? = null
    private val parks = object : LinkedHashMap<String, ParkDoc>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParkDoc>) = size > 20
    }

    suspend fun index(): BundleIndex = mutex.withLock {
        index ?: withContext(Dispatchers.IO) {
            JSON.decodeFromString(BundleIndex.serializer(), kept("$root/index.json").decodeToString())
        }.also { index = it }
    }

    private var regions: Regions? = null

    suspend fun regions(): Regions = mutex.withLock {
        regions ?: withContext(Dispatchers.IO) {
            JSON.decodeFromString(Regions.serializer(), kept("$root/regions.json").decodeToString())
        }.also { regions = it }
    }

    /**
     * The routes id of a park named as search names it: shortened ("Jasper
     * NP"), in a state. Null if it has no curated hikes.
     */
    suspend fun parkIdFor(state: String?, shortName: String): String? {
        val st = regions().countries.flatMap { it.states }.firstOrNull { it.name == state } ?: return null
        return st.areas.firstOrNull { shortPark(it.name) == shortName }?.id
    }

    /** Whether a park has curated hikes. */
    suspend fun hasHikes(parkId: String): Boolean = index().parks.containsKey(parkId)

    /** The park's routes if they're already loaded, without waiting; else null. */
    fun cachedPark(parkId: String): ParkDoc? = if (mutex.tryLock()) {
        try { parks[parkId] } finally { mutex.unlock() }
    } else {
        null
    }

    /** The park's routes, or null if it has none. */
    suspend fun park(parkId: String): ParkDoc? {
        mutex.withLock { parks[parkId] }?.let { return it }
        // Downloaded with an offline area: the routes its tiles were drawn from.
        val stored = withContext(Dispatchers.IO) { Offline.resources?.resource(parkKey(parkId)) }
        val doc = if (stored != null) {
            decodePark(stored)
        } else {
            val idx = index()
            val (offset, length) = idx.parks[parkId] ?: return null
            withContext(Dispatchers.IO) { decodePark(get("$root/${idx.bundle}", offset until offset + length)) }
        }
        mutex.withLock { parks[parkId] = doc }
        return doc
    }

    private val reports = object : LinkedHashMap<String, Map<String, RouteReports>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, RouteReports>>) = size > 20
    }

    /**
     * A park's pages about its routes, by route key (or id, in builds
     * without keys), with thumbnails as URLs; empty where the build has none
     * for it. From the reports bundle, one range read a park.
     */
    suspend fun parkReports(parkId: String): Map<String, RouteReports> {
        mutex.withLock { reports[parkId] }?.let { return it }
        val rep = index().reports ?: return emptyMap()
        val (offset, length) = rep.parks[parkId] ?: return emptyMap()
        val gz = withContext(Dispatchers.IO) { get("$root/${rep.bundle}", offset until offset + length) }
        val raw = JSON.decodeFromString(
            kotlinx.serialization.builtins.MapSerializer(String.serializer(), RouteReports.serializer()),
            GZIPInputStream(gz.inputStream()).readBytes().decodeToString(),
        )
        fun abs(p: ReportPage) = p.thumb?.takeIf { !it.startsWith("http") }?.let { p.copy(thumb = "$root/$it") } ?: p
        val out = raw.mapValues { (_, r) -> RouteReports(r.reports.map(::abs), r.about.map(::abs)) }
        mutex.withLock { reports[parkId] = out }
        return out
    }

    /** Forgets the index and parks, e.g. after a rebuild moved the bundle. */
    suspend fun refresh() = mutex.withLock { index = null; regions = null; parks.clear(); reports.clear() }

    /** The park's routes as the bundle has them (gzipped JSON), for keeping offline; null if it has none. */
    suspend fun parkBytes(parkId: String): ByteArray? {
        val idx = index()
        val (offset, length) = idx.parks[parkId] ?: return null
        return withContext(Dispatchers.IO) { get("$root/${idx.bundle}", offset until offset + length) }
    }

    /** The park's gzipped JSON in the offline store. */
    fun parkKey(parkId: String) = "$root/parks/$parkId.json.gz"

    private fun decodePark(gz: ByteArray) =
        JSON.decodeFromString(ParkDoc.serializer(), GZIPInputStream(gz.inputStream()).readBytes().decodeToString())

    /**
     * A small file that's kept on the phone each time it's fetched, so it's
     * there offline: fetched if it can be, else the copy kept last time.
     */
    private fun kept(url: String): ByteArray {
        val store = Offline.resources
        return try {
            get(url, null).also { store?.putResource(url, it) }
        } catch (e: IOException) {
            store?.resource(url) ?: throw e
        }
    }

    private fun get(url: String, range: LongRange?): ByteArray {
        val req = Request.Builder().url(url)
        if (range != null) req.header("Range", "bytes=${range.first}-${range.last}")
        client.newCall(req.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} fetching $url")
            return r.body.bytes()
        }
    }

    companion object {
        /** A park's name shortened as the basemap's export/zindex.lua does for search. */
        fun shortPark(name: String) = name.replace("National Park", "NP").replace("State Park", "SP")
            .replace("Provincial Park", "PP").replace("National Forest", "NF")

        val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
