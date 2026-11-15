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
 * $handle._index.tsx): their reviews and, apart, every track, whatever
 * posted it, each newest first. Records are other
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
        /**
         * The record as read, every field: an edit keeps what it doesn't
         * change, fields this app doesn't know included (see [Posts.editedReview]).
         */
        val value: JsonObject = JsonObject(emptyMap()),
    )

    data class Profile(
        val did: String,
        val handle: String?,
        val pds: String,
        /** Newest hike first: by hikedAt, else when posted. */
        val reviews: List<Review>,
        /** Every track, posted alone or with a review, newest first: by startedAt, else when posted. */
        val tracks: List<Track>,
        val faves: List<Faves.Fave>,
    ) {
        /** The track [r]'s strongRef points at, if it's among this account's tracks (not gone, not someone else's). */
        fun trackOf(r: Review): Track? = r.trackUri?.let { u -> tracks.firstOrNull { it.uri == u } }

        /**
         * One post deletes as one: a review, its track, and every other
         * review pointing at that track. The track goes by its URI even when
         * it isn't among the loaded tracks (past the first 200), as long as
         * the URI names this account's own track collection; a strongRef to
         * anyone else's record is never followed.
         */
        fun deletion(r: Review): Deletion {
            val t = r.trackUri?.takeIf { it.startsWith("at://$did/${Posts.TRACK}/") }
                ?: return Deletion(listOf(r), emptyList())
            val loaded = tracks.firstOrNull { it.uri == t }
            val others = reviews.filter { it.trackUri == t && it.uri != r.uri }
            return Deletion(listOf(r) + others, listOfNotNull(loaded), if (loaded == null) listOf(t) else emptyList())
        }

        /** Deleting a track deletes the reviews pointing at it too, so none is left pointing at nothing. */
        fun deletion(t: Track) = Deletion(reviews.filter { it.trackUri == t.uri }, listOf(t))

        /** This profile with [r] in place of the review with its URI (edited), in order again. */
        fun with(r: Review): Profile =
            copy(reviews = reviews.map { if (it.uri == r.uri) r else it }.sortedByDescending { it.hikedAt ?: it.createdAt })

        /** This profile once [d] has gone. */
        fun without(d: Deletion): Profile {
            val gone = (d.reviews.map { it.uri } + d.tracks.map { it.uri } + d.unloadedTracks).toSet()
            return copy(reviews = reviews.filter { it.uri !in gone }, tracks = tracks.filter { it.uri !in gone })
        }
    }

    /**
     * What one Delete deletes: a review and its track, or a track and the
     * reviews pointing at it. [unloadedTracks]: a review's own track that
     * isn't among the loaded tracks, by URI.
     */
    data class Deletion(val reviews: List<Review>, val tracks: List<Track>, val unloadedTracks: List<String> = emptyList()) {
        val hasTrack get() = tracks.isNotEmpty() || unloadedTracks.isNotEmpty()
    }

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
        return Profile(
            did, handle, pds,
            reviews.sortedByDescending { it.hikedAt ?: it.createdAt },
            tracks.sortedByDescending { it.startedAt ?: it.createdAt },
            faves,
        )
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
            value = v,
        )
    }

    /**
     * Deletes records of [did]'s own: the reviews first, then the tracks, so
     * a failure halfway leaves no review pointing at a track that's gone.
     * Every record is checked to be [did]'s before any is deleted.
     * [onDeleted] hears each record as it goes, so a failure halfway still
     * leaves the phone knowing what's gone.
     */
    /** Deletes [d]: reviews first, so none is left pointing at a track that's gone. [onDeleted] hears each (review URI, track URI). */
    suspend fun delete(xrpc: Xrpc, did: String, d: Deletion, onDeleted: suspend (String?, String?) -> Unit = { _, _ -> }) {
        val trackUris = d.tracks.map { it.uri } + d.unloadedTracks
        (d.reviews.map { it.uri } + trackUris).forEach { rkeyOf(did, it) }
        for (r in d.reviews) { deleteRecord(xrpc, did, r.uri, r.routeKey); onDeleted(r.uri, null) }
        for (t in d.tracks) { deleteRecord(xrpc, did, t.uri, t.routeKey); onDeleted(null, t.uri) }
        // Not loaded, so its route isn't known: the review's, which it was posted with.
        for (t in d.unloadedTracks) { deleteRecord(xrpc, did, t, d.reviews.firstOrNull()?.routeKey); onDeleted(null, t) }
    }

    /** [uri]'s collection and rkey, if it's [did]'s record. */
    private fun rkeyOf(did: String, uri: String): Pair<String, String> {
        val parts = uri.removePrefix("at://").split('/')
        require(parts.size == 3 && parts[0] == did) { "Not your post" }
        return parts[1] to parts[2]
    }

    private suspend fun deleteRecord(xrpc: Xrpc, did: String, uri: String, routeKey: String?) {
        val (collection, rkey) = rkeyOf(did, uri)
        xrpc.procedure(
            "com.atproto.repo.deleteRecord",
            buildJsonObject { put("repo", did); put("collection", collection); put("rkey", rkey) },
        )
        Indexer.notify(uri, routeKey)
    }

    /**
     * The track record at [uri], read from [xrpc] (its author's PDS): a
     * review's track that isn't among those loaded. Null if it isn't a track
     * URI, or it's gone, unreadable or malformed.
     */
    suspend fun getTrack(xrpc: Xrpc, uri: String): Track? {
        val (repo, collection, rkey) = IndexerClient.parseAtUri(uri) ?: return null
        if (collection != Posts.TRACK) return null
        return try {
            val r = xrpc.query("com.atproto.repo.getRecord", mapOf("repo" to repo, "collection" to collection, "rkey" to rkey))
            parseTrack(r)?.takeIf { it.uri == uri }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
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
