package com.hikeratlas.android.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
            JSON.decodeFromString(BundleIndex.serializer(), get("$root/index.json", null).decodeToString())
        }.also { index = it }
    }

    /** Whether a park has curated hikes. */
    suspend fun hasHikes(parkId: String): Boolean = index().parks.containsKey(parkId)

    /** The park's routes, or null if it has none. */
    suspend fun park(parkId: String): ParkDoc? {
        mutex.withLock { parks[parkId] }?.let { return it }
        val idx = index()
        val (offset, length) = idx.parks[parkId] ?: return null
        val doc = withContext(Dispatchers.IO) {
            val bytes = get("$root/${idx.bundle}", offset until offset + length)
            JSON.decodeFromString(ParkDoc.serializer(), GZIPInputStream(bytes.inputStream()).readBytes().decodeToString())
        }
        mutex.withLock { parks[parkId] = doc }
        return doc
    }

    /** Forgets the index and parks, e.g. after a rebuild moved the bundle. */
    suspend fun refresh() = mutex.withLock { index = null; parks.clear() }

    private fun get(url: String, range: LongRange?): ByteArray {
        val req = Request.Builder().url(url)
        if (range != null) req.header("Range", "bytes=${range.first}-${range.last}")
        client.newCall(req.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} fetching $url")
            return r.body.bytes()
        }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
