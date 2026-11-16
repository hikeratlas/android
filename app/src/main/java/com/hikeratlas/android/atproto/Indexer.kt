package com.hikeratlas.android.atproto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Hiker Atlas's indexer (hikeratlas/atproto-indexer), told about each
 * record right after the app writes or deletes it, as the website does
 * (website#160): POST /notify with the record's at:// URI, and it applies
 * that one record from the author's PDS at once, rather than at its next
 * Jetstream run a minute or two later. So what someone posts, faves or
 * deletes from the phone shows on hike sheets right away.
 *
 * Fire and forget: on OkHttp's own threads, a short timeout, every failure
 * ignored (offline, the indexer down, its per-account rate limit). It never
 * delays or fails the write; without it, the record still arrives through
 * Jetstream.
 *
 * Once the indexer has answered (or not), [written] hears the record's
 * route key, so a hike sheet showing that route reads it again, past the
 * indexer's cache, as the website's useFresh.
 */
object Indexer {
    const val BASE = "https://indexer.hikeratlas.com"
    const val URL = "$BASE/notify"
    private val JSON = "application/json".toMediaType()

    /**
     * Sends a notify, calling `done` once it's answered or failed; set by the
     * app at start (and by tests). Unset, notifies are dropped.
     */
    @Volatile var notifier: (uri: String, done: () -> Unit) -> Unit = { _, done -> done() }

    private val writes = MutableSharedFlow<String?>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The route keys of the app's writes, each once the indexer has been told; null for a write whose route isn't known. */
    val written: SharedFlow<String?> = writes.asSharedFlow()

    /** Tells the indexer about [uri], a record just written or deleted, about [routeKey]'s route. */
    fun notify(uri: String, routeKey: String? = null) {
        val done = { writes.tryEmit(routeKey); Unit }
        try {
            notifier(uri, done)
        } catch (e: Exception) {
            // Never the write's problem; the sheet may as well look again.
            com.hikeratlas.android.logs.Log.w("indexer", "Notify failed for $uri", e)
            done()
        }
    }

    /** A notifier posting to [url] with [http], quickly giving up. */
    fun http(http: OkHttpClient, url: String = URL): (String, () -> Unit) -> Unit {
        val client = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
        return { uri, done ->
            val body = buildJsonObject { put("uri", uri) }.toString().toRequestBody(JSON)
            client.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    com.hikeratlas.android.logs.Log.w("indexer", "Notify failed for $uri", e)
                    done()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    if (response.isSuccessful) com.hikeratlas.android.logs.Log.i("indexer", "Notified $uri")
                    else com.hikeratlas.android.logs.Log.w("indexer", "Notify for $uri: HTTP ${response.code}")
                    done()
                }
            })
        }
    }

    /**
     * A post's "Report": hikeratlas/feedback's report form with the
     * record's at:// URI filled in, as the website's reportUrl.
     */
    fun reportUrl(uri: String): String =
        "https://github.com/hikeratlas/feedback/issues/new".toHttpUrl().newBuilder()
            .addQueryParameter("template", "report.yml").addQueryParameter("link", uri).build().toString()

    /** Someone's profile on the website: the app has no view of other people's. */
    fun profileUrl(handle: String): String = "https://www.hikeratlas.com/@$handle"
}

/**
 * The indexer's queries, as the website's app/atproto/indexer.ts: everyone's
 * faves, reviews and tracks of a route, by its route key
 * (com.hikeratlas.temp.getRouteStats, getRouteReviews, getRouteTracks).
 *
 * Every call gives up after [timeoutMs] and returns null rather than throw:
 * a sheet without them is the sheet as it was. What comes back is other
 * people's records, so each is checked and a malformed one skipped, and
 * text, tags and photos are capped as the website caps them. Photos and
 * GPX aren't here: they're blobs on each author's PDS.
 */
class IndexerClient(http: OkHttpClient, private val base: String = Indexer.BASE, timeoutMs: Long = 5_000) {
    private val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    /** An account as the indexer knows it: [handle] only where it resolves back to the DID, [pds] only once it's seen it. */
    data class Author(val did: String, val handle: String?, val pds: String?)

    data class Post<T>(val uri: String, val cid: String, val author: Author, val record: T)

    data class Page<T>(val items: List<T>, val cursor: String?)

    data class RouteStats(
        val routeKey: String,
        val faves: Int,
        val reviews: Int,
        val tracks: Int,
        /** Tags from reviews of hikes in the last month, the most recent first. */
        val recentTags: List<String> = emptyList(),
    ) {
        val any get() = faves > 0 || reviews > 0 || tracks > 0
    }

    /** The counts for [keys] (up to 100), by key; null without the indexer. */
    suspend fun stats(keys: Collection<String>, fresh: Long = 0): Map<String, RouteStats>? {
        val asked = keys.distinct().take(MAX_KEYS)
        if (asked.isEmpty()) return emptyMap()
        val r = query("com.hikeratlas.temp.getRouteStats", asked.map { "keys" to it }, fresh) ?: return null
        val routes = r["routes"] as? JsonArray ?: return null
        return routes.mapNotNull { (it as? JsonObject)?.let(::parseStats) }.associateBy { it.routeKey }
    }

    /** A page of [key]'s reviews, newest first; null without the indexer. */
    suspend fun reviews(key: String, limit: Int, cursor: String? = null, fresh: Long = 0): Page<Post<Profiles.Review>>? =
        page("com.hikeratlas.temp.getRouteReviews", "reviews", key, limit, cursor, fresh) { uri, rec ->
            if (collectionOf(uri) != Posts.REVIEW) null
            else Profiles.parseReview(rec)?.let { r -> r.copy(tags = r.tags.map { it.take(MAX_TAG) }, license = r.license?.take(MAX_LICENSE)) }
        }

    /** A page of [key]'s tracks, newest first; null without the indexer. */
    suspend fun tracks(key: String, limit: Int, cursor: String? = null, fresh: Long = 0): Page<Post<Profiles.Track>>? =
        page("com.hikeratlas.temp.getRouteTracks", "tracks", key, limit, cursor, fresh) { uri, rec ->
            if (collectionOf(uri) != Posts.TRACK) null
            else Profiles.parseTrack(rec)?.let { t -> t.copy(title = t.title?.let { Posts.clip(it) }, license = t.license?.take(MAX_LICENSE)) }
        }

    private suspend fun <T : Any> page(
        nsid: String, field: String, key: String, limit: Int, cursor: String?, fresh: Long,
        parse: (String, JsonObject) -> T?,
    ): Page<Post<T>>? {
        val params = listOfNotNull("key" to key, "limit" to limit.coerceIn(1, 50).toString(), cursor?.let { "cursor" to it })
        val r = query(nsid, params, fresh) ?: return null
        val views = r["$field"] as? JsonArray ?: return null
        val seen = HashSet<String>()
        val items = views.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val uri = o.str("uri") ?: return@mapNotNull null
            val author = (o["author"] as? JsonObject)?.let(::parseAuthor) ?: return@mapNotNull null
            // A record's blobs are in its own repository: an author who isn't the record's would point at the wrong ones.
            if (repoOf(uri) != author.did || !seen.add(uri)) return@mapNotNull null
            val record = o["record"] as? JsonObject ?: return@mapNotNull null
            val cid = o.str("cid") ?: ""
            val value = parse(uri, buildJsonObject { put("uri", uri); put("cid", cid); put("value", record) }) ?: return@mapNotNull null
            Post(uri, cid, author, value)
        }
        return Page(items, r.str("cursor")?.takeIf { it.isNotEmpty() })
    }

    private suspend fun query(nsid: String, params: List<Pair<String, String>>, fresh: Long): JsonObject? {
        val url = try {
            "${base.trimEnd('/')}/xrpc/$nsid".toHttpUrl().newBuilder().apply {
                for ((k, v) in params) addQueryParameter(k, v)
                if (fresh > 0) addQueryParameter("fresh", fresh.toString())
            }.build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val text = get(Request.Builder().url(url).build()) ?: return null
        return try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            // Absurdly nested JSON: as good as none.
            null
        }
    }

    /** A body's text, or null: offline, timed out, not 2xx, or too big. Cancelling the coroutine cancels the call. */
    private suspend fun get(req: Request): String? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val text = try {
                    response.use { r ->
                        if (!r.isSuccessful) null
                        else {
                            val src = r.body.source()
                            if (src.request(MAX_BODY + 1)) null else src.buffer.readUtf8()
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                if (cont.isActive) cont.resume(text)
            }
        })
    }

    private fun parseStats(o: JsonObject): RouteStats? {
        val key = o.str("routeKey") ?: return null
        fun count(k: String) = ((o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 0).coerceAtLeast(0)
        val tags = (o["recentTags"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.take(MAX_TAG)?.takeIf { t -> t.isNotBlank() } }
            .distinct().take(MAX_RECENT_TAGS)
        return RouteStats(key, count("faves"), count("reviews"), count("tracks"), tags)
    }

    private fun parseAuthor(o: JsonObject): Author? {
        val did = o.str("did")?.takeIf(::isDid) ?: return null
        return Author(
            did,
            // It goes in a URL (their profile page): only a real handle.
            o.str("handle")?.takeIf(::isHandle),
            o.str("pds")?.takeIf { it.startsWith("https://") && it.toHttpUrlOrNull() != null }?.trimEnd('/'),
        )
    }

    private fun String.toHttpUrlOrNull() = try { toHttpUrl() } catch (_: IllegalArgumentException) { null }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val MAX_KEYS = 100
        private const val MAX_BODY = 4L * 1024 * 1024
        private const val MAX_TAG = 64
        private const val MAX_LICENSE = 64
        private const val MAX_RECENT_TAGS = 20

        /** A record's at:// URI as its parts, or null if it isn't one, as the website's parseAtUri. */
        fun parseAtUri(uri: String): Triple<String, String, String>? =
            Regex("^at://([^/]+)/([^/]+)/([^/]+)$").find(uri)?.destructured?.let { (a, b, c) -> Triple(a, b, c) }

        private fun repoOf(uri: String) = parseAtUri(uri)?.first
        private fun collectionOf(uri: String) = parseAtUri(uri)?.second
    }
}
