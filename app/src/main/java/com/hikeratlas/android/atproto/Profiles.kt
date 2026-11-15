package com.hikeratlas.android.atproto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Someone's posts and faves, read straight from their PDS (public records,
 * no session), as the website's profile page (app/routes/$handle.tsx and
 * $handle._index.tsx): reviews joined with the tracks they point at, tracks
 * no review uses on their own, newest hike first. Records are other
 * people's data, so malformed ones are skipped, as the website's filters
 * skip them, rather than failing the page.
 */
object Profiles {
    /** Records read per collection, as the website's listRecords. */
    const val MAX = 200
    private const val PAGE = 100
    /** Photos shown per post, as the website's Photos. */
    const val MAX_PHOTOS = 70
    /** A GPX blob, unzipped, is at most this: the lexicon's 10 MB compressed, with room, and no zip bomb. */
    private const val MAX_GPX_TEXT = 100_000_000

    /** A blob as a record refers to it: its CID, and its type. */
    data class Blob(val cid: String, val mimeType: String?)

    data class Photo(val image: Blob, val alt: String?)

    data class Track(
        val uri: String,
        val cid: String,
        val createdAt: String,
        val gpx: Blob,
        val startedAt: String? = null,
        /** Seconds. */
        val duration: Double? = null,
        /** Metres. */
        val distance: Double? = null,
        /** Metres climbed. */
        val gain: Double? = null,
        val title: String? = null,
        val routeKey: String? = null,
        val photos: List<Photo> = emptyList(),
        val license: String? = null,
    )

    data class Review(
        val uri: String,
        val cid: String,
        val text: String,
        val createdAt: String,
        val hikedAt: String? = null,
        val routeKey: String? = null,
        /** The track it points at (its strongRef's URI): only shown if it's one of the same account's tracks. */
        val trackUri: String? = null,
        val tags: List<String> = emptyList(),
        val photos: List<Photo> = emptyList(),
        val license: String? = null,
    )

    /** One entry in the list: a review (with its track, if it has one), or a track on its own. */
    data class Post(val at: String, val review: Review?, val track: Track?) {
        val uri: String get() = review?.uri ?: track!!.uri
        val routeKey: String? get() = review?.routeKey ?: track?.routeKey
        val license: String? get() = review?.license ?: track?.license
        val photos: List<Photo> get() = (review?.photos.orEmpty() + track?.photos.orEmpty()).take(MAX_PHOTOS)
    }

    data class Profile(
        val did: String,
        val handle: String?,
        val pds: String,
        val posts: List<Post>,
        val faves: List<Faves.Fave>,
    )

    /**
     * [did]'s profile, from [pds]. Throws (IOException, [XrpcException]) if a
     * collection can't be read, rather than show a page with posts missing:
     * the caller offers Try again.
     */
    suspend fun load(xrpc: Xrpc, did: String, handle: String?, pds: String): Profile {
        val tracks = listRecords(xrpc, did, Posts.TRACK).mapNotNull(::parseTrack)
        val reviews = listRecords(xrpc, did, Posts.REVIEW).mapNotNull(::parseReview)
        // The website shows only faves whose key is a route key (the spike's aren't routes).
        val faves = Faves.list(xrpc, did).filter { isRouteKey(it.key) }
        return Profile(did, handle, pds, join(reviews, tracks), faves)
    }

    /**
     * Anyone's profile by DID: their PDS from their DID document, then their
     * records from it, without a session. [handle] is shown if given, else
     * the one the DID document claims.
     */
    suspend fun loadDid(http: okhttp3.OkHttpClient, identity: Identity, did: String, handle: String? = null): Profile {
        val doc = identity.describeDid(did) ?: throw IOException("Couldn't find the account's server")
        return load(Xrpc.public(http, doc.pds), did, handle ?: doc.handle, doc.pds)
    }

    /** A collection's records, newest first (TIDs sort by time): up to [max], paged by 100. */
    suspend fun listRecords(xrpc: Xrpc, did: String, collection: String, max: Int = MAX): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var cursor: String? = null
        while (out.size < max) {
            val r = xrpc.query(
                "com.atproto.repo.listRecords",
                mapOf("repo" to did, "collection" to collection, "limit" to PAGE, "cursor" to cursor),
            )
            val records = (r["records"] as? JsonArray).orEmpty()
            records.forEach { (it as? JsonObject)?.let(out::add) }
            cursor = (r["cursor"] as? JsonPrimitive)?.contentOrNull
            if (cursor == null || records.size < PAGE) break
        }
        return out.take(max)
    }

    /**
     * Reviews with their tracks (by the strongRef's URI, among this
     * account's tracks), then the tracks no review uses; sorted by when
     * hiked (the review's hikedAt, else the track's startedAt, else when
     * posted), newest first, as the website's Overview.
     */
    fun join(reviews: List<Review>, tracks: List<Track>): List<Post> {
        val byUri = tracks.associateBy { it.uri }
        val used = mutableSetOf<String>()
        val out = reviews.map { r ->
            val t = r.trackUri?.let(byUri::get)
            if (t != null) used += t.uri
            Post(r.hikedAt ?: t?.startedAt ?: r.createdAt, r, t)
        }.toMutableList()
        for (t in tracks) if (t.uri !in used) out += Post(t.startedAt ?: t.createdAt, null, t)
        return out.sortedByDescending { it.at }
    }

    /** A track record, or null where it isn't one the website would show (no createdAt, no GPX blob). */
    fun parseTrack(rec: JsonObject): Track? {
        val uri = rec.str("uri") ?: return null
        val v = rec["value"] as? JsonObject ?: return null
        val createdAt = v.str("createdAt") ?: return null
        val gpx = blob(v["gpx"]) ?: return null
        return Track(
            uri, rec.str("cid") ?: "", createdAt, gpx,
            startedAt = v.str("startedAt"), duration = v.num("duration"), distance = v.num("distance"), gain = v.num("gain"),
            title = v.str("title"), routeKey = v.str("routeKey")?.takeIf { it.isNotEmpty() },
            photos = photos(v["photos"]), license = v.str("license"),
        )
    }

    /** A review record, or null where it isn't one the website would show (no text, no createdAt). */
    fun parseReview(rec: JsonObject): Review? {
        val uri = rec.str("uri") ?: return null
        val v = rec["value"] as? JsonObject ?: return null
        val text = v.str("text") ?: return null
        val createdAt = v.str("createdAt") ?: return null
        val tags = (v["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return Review(
            uri, rec.str("cid") ?: "", text.take(Posts.MAX_TEXT), createdAt,
            hikedAt = v.str("hikedAt"), routeKey = v.str("routeKey")?.takeIf { it.isNotEmpty() },
            trackUri = (v["track"] as? JsonObject)?.str("uri"),
            tags = tags.take(Posts.MAX_TAGS), photos = photos(v["photos"]), license = v.str("license"),
        )
    }

    /**
     * Deletes a post of [did]'s own: the review, then its track (a review
     * and its track were posted together, and go together, as the website's).
     * [onDeleted] hears each record as it goes, so a failure halfway still
     * leaves the phone knowing what's gone.
     */
    suspend fun delete(xrpc: Xrpc, did: String, post: Post, onDeleted: suspend (Review?, Track?) -> Unit = { _, _ -> }) {
        post.review?.let { deleteRecord(xrpc, did, it.uri); onDeleted(it, null) }
        post.track?.let { deleteRecord(xrpc, did, it.uri); onDeleted(null, it) }
    }

    private suspend fun deleteRecord(xrpc: Xrpc, did: String, uri: String) {
        val parts = uri.removePrefix("at://").split('/')
        require(parts.size == 3 && parts[0] == did) { "Not your post" }
        xrpc.procedure(
            "com.atproto.repo.deleteRecord",
            buildJsonObject { put("repo", did); put("collection", parts[1]); put("rkey", parts[2]) },
        )
    }

    /** A blob's public URL on its PDS (com.atproto.sync.getBlob), as the website's blobUrl: for photos. */
    fun blobUrl(pds: String, did: String, cid: String): String =
        "${pds.trimEnd('/')}/xrpc/com.atproto.sync.getBlob".toHttpUrl().newBuilder()
            .addQueryParameter("did", did).addQueryParameter("cid", cid).build().toString()

    /** A track's GPX, from its blob: gzipped (as posted) or plain, as the website's readBlob. */
    suspend fun gpx(xrpc: Xrpc, did: String, blob: Blob): String {
        val bytes = xrpc.queryBytes("com.atproto.sync.getBlob", mapOf("did" to did, "cid" to blob.cid))
        return gpxText(bytes, blob.mimeType)
    }

    internal fun gpxText(bytes: ByteArray, mimeType: String?): String {
        val gz = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val zst = bytes.size >= 4 && bytes[0] == 0x28.toByte() && bytes[1] == 0xb5.toByte() && bytes[2] == 0x2f.toByte() && bytes[3] == 0xfd.toByte()
        val raw = when {
            gz || mimeType == "application/gzip" -> capped(GZIPInputStream(ByteArrayInputStream(bytes)))
            zst || mimeType == "application/zstd" ->
                capped(com.github.luben.zstd.ZstdInputStreamNoFinalizer(ByteArrayInputStream(bytes)))
            else -> bytes
        }
        return String(raw, Charsets.UTF_8)
    }

    private fun capped(input: java.io.InputStream): ByteArray = input.use { s ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_GPX_TEXT) throw IOException("The track is too big to read")
        }
        out.toByteArray()
    }

    // Route keys are recipe v1's (hikeratlas/route-keys), as the website's isRouteKey.
    private val ROUTE_KEY = Regex("^1[A-Za-z0-9_-]{7}[bpw][A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{11}$")
    fun isRouteKey(k: String) = ROUTE_KEY.matches(k)

    private fun photos(e: kotlinx.serialization.json.JsonElement?): List<Photo> =
        (e as? JsonArray).orEmpty().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            Photo(blob(o["image"]) ?: return@mapNotNull null, o.str("alt"))
        }.take(MAX_PHOTOS)

    /** A blob ref: {$type: blob, ref: {$link}, mimeType}; null without a link. */
    private fun blob(e: kotlinx.serialization.json.JsonElement?): Blob? {
        val o = e as? JsonObject ?: return null
        val link = (o["ref"] as? JsonObject)?.str("\$link")?.takeIf { it.isNotEmpty() } ?: return null
        return Blob(link, o.str("mimeType"))
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
}
