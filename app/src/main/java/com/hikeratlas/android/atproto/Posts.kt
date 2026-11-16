package com.hikeratlas.android.atproto

import com.hikeratlas.android.track.GpxStats
import com.hikeratlas.android.track.TrackMeasure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.BreakIterator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/** Posting didn't work: [message] says what failed, as the website words it. */
class PostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Tracks and reviews (com.hikeratlas.temp.track and .review, in
 * hikeratlas/atproto-lexicon), as the website's app/atproto/posts.ts:
 * records in the user's own repository, keyed by TIDs the PDS makes
 * (createRecord with no rkey), so someone can post any number for the same
 * hike, tied to it by its route key. A track's GPX is a blob uploaded
 * gzipped to the same PDS, as are its photos (on the review, or on the
 * track posted alone: see [Photos]). Each post records the licence it was
 * made under, from the profile's setting at the time.
 */
object Posts {
    const val TRACK = "com.hikeratlas.temp.track"
    const val REVIEW = "com.hikeratlas.temp.review"

    val LICENSES = listOf("CC0-1.0", "CC-BY-4.0", "hikeratlas-only")
    const val DEFAULT_LICENSE = "CC0-1.0"

    /** As the post form names them (the website's LICENSE_LABEL). */
    val LICENSE_LABEL = mapOf(
        "CC0-1.0" to "public domain (CC0)",
        "CC-BY-4.0" to "CC BY 4.0",
        "hikeratlas-only" to "Hiker Atlas only",
    )

    /** As the licence setting words them (the website's Settings tab). */
    val LICENSE_CHOICE = mapOf(
        "CC0-1.0" to "Anything: public domain (CC0)",
        "CC-BY-4.0" to "Anything, crediting you (CC BY 4.0)",
        "hikeratlas-only" to "Nothing beyond Hiker Atlas showing them",
    )

    /** What the review form offers (the lexicon's knownValues), labelled as the website's TAGS, in its order. */
    val TAGS = listOf(
        "views" to "Views", "quiet" to "Quiet", "crowded" to "Crowded", "wildflowers" to "Wildflowers",
        "fall-colours" to "Fall colours", "wildlife" to "Wildlife", "bugs" to "Bugs", "mud" to "Mud",
        "snow" to "Snow", "ice" to "Ice", "water-crossing" to "Water crossing", "blowdown" to "Blowdown",
        "washout" to "Washout", "closed" to "Closed",
    )

    // The lexicons' limits. A review's text is at most 3000 graphemes; the
    // form counts UTF-16 units, as the website does, which is never fewer.
    const val MAX_TEXT = 3000
    const val MAX_TAGS = 10
    const val MAX_TITLE = 200
    const val MAX_GPX_BYTES = 10_000_000

    /** A record's address and version: a review's `track`. */
    @Serializable
    data class StrongRef(val uri: String, val cid: String)

    /**
     * One post, as the form fills it in: a review ([text], if any), a track
     * ([gpx], if any), or both. Everything needed to publish it later is
     * here, so one made without signal can wait on the phone (#60).
     */
    @Serializable
    data class Post(
        val text: String = "",
        /** When they hiked it (a datetime: [noonOf] the day chosen). */
        val hikedAt: String? = null,
        val tags: List<String> = emptyList(),
        /** The hike's key from the build; null for a track posted without a hike. */
        val routeKey: String? = null,
        /** The track's title: the hike's, or the track's own name. */
        val title: String? = null,
        /** The track as GPX, as the app exports it. */
        val gpx: String? = null,
        /**
         * The track, already posted (a review that failed after its track
         * went up, tried again): the review points at it, and it isn't
         * posted twice. Takes the place of [gpx].
         */
        val track: StrongRef? = null,
        /**
         * On the review, or on the track when it's posted alone, as the
         * website's PostDialog: placed already ([Photos.placed]).
         */
        val photos: List<Photo> = emptyList(),
    )

    /**
     * A photo for a post, as [Photos.Store] keeps it: the prepared JPEG's
     * [file] on the phone (uploaded as it is), where and when it was
     * taken, from its EXIF or, for [lat] and [lon], its time on the track,
     * and its size as displayed.
     */
    @Serializable
    data class Photo(
        val file: String,
        val alt: String? = null,
        /** As JavaScript's toISOString. */
        val takenAt: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        /** Whether [takenAt] came with its time zone (EXIF often has local time only). */
        val offsetKnown: Boolean = false,
        /**
         * [file]'s size as displayed, measured when it was prepared; null in
         * a post queued before the app recorded it, measured as it goes up.
         */
        val aspectRatio: Photos.AspectRatio? = null,
    )

    /** What was posted. */
    data class Published(val track: StrongRef?, val review: StrongRef?)

    /** The licence new posts are shared under: the profile record's, [DEFAULT_LICENSE] when unset or unknown (the website's licenseFor). */
    suspend fun licenseFor(xrpc: Xrpc, did: String): String {
        val l = try {
            (ProfileRecord.read(xrpc, did)?.get("license") as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        return if (l != null && l in LICENSES) l else DEFAULT_LICENSE
    }

    /**
     * Publishes [post]: the track first (its GPX uploaded, then its record),
     * then the review, pointing at the track just posted. [progress] hears
     * what's under way, in the website's words; [onTrack], the track's
     * record as soon as it's made (before the review, which may yet fail),
     * so the phone never posts it again. Throws [PostException] saying what
     * failed; [SignedOutException] if there's no session.
     */
    suspend fun publish(
        xrpc: Xrpc, did: String, post: Post, progress: (String) -> Unit = {}, onTrack: suspend (StrongRef) -> Unit = {},
    ): Published {
        val review = post.text.trim().isNotEmpty()
        require(review || (post.gpx != null && post.track == null)) { "Nothing to post" }
        progress("Posting…")
        val license = licenseFor(xrpc, did)
        val track = post.track ?: post.gpx?.let { gpx ->
            progress("Uploading your track…")
            val stats = try {
                TrackMeasure.ofGpx(gpx)
            } catch (e: IllegalArgumentException) {
                throw PostException(e.message ?: "Not a GPX file", e)
            }
            publishTrack(xrpc, did, gpx, stats, post.routeKey, post.title, license, if (review) emptyList() else post.photos).also { onTrack(it) }
        }
        val reviewRef = if (review) {
            progress("Posting your review…")
            publishReview(xrpc, did, post.text.trim(), post.hikedAt, post.routeKey, track, post.tags, license, post.photos)
        } else {
            null
        }
        return Published(track, reviewRef)
    }

    /**
     * Whether the record at [uri] is still in [did]'s repository: false only
     * when the PDS says there's no such record (400, as the website takes
     * it); null when it can't be told (another account's record, no
     * signal, the PDS down), which changes nothing.
     */
    suspend fun exists(xrpc: Xrpc, did: String, uri: String): Boolean? {
        val parts = uri.removePrefix("at://").split('/')
        if (parts.size != 3 || parts[0] != did) return null
        return try {
            xrpc.query("com.atproto.repo.getRecord", mapOf("repo" to did, "collection" to parts[1], "rkey" to parts[2]))
            true
        } catch (e: XrpcException) {
            if (e.status == 400) false else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /** A track record, field for field as the website's publishTrack writes it. */
    suspend fun publishTrack(
        xrpc: Xrpc, did: String, gpx: String, s: GpxStats, routeKey: String?, title: String?, license: String,
        photos: List<Photo> = emptyList(),
    ): StrongRef {
        val bytes = gzip(gpx)
        if (bytes.size > MAX_GPX_BYTES) throw PostException(TOO_BIG)
        val blob = uploadBlob(xrpc, bytes, "application/gzip")
        val photoEntries = uploadPhotos(xrpc, photos)
        return create(xrpc, did, TRACK, trackRecord(blob, s, routeKey, title, license, photoEntries))
    }

    const val TOO_BIG = "The track is too big to post (over 10 MB, compressed)"

    /** A track record's fields, with its GPX's [blob] and photos' entries already uploaded. [createdAt]: now, unless given. */
    fun trackRecord(
        blob: JsonObject, s: GpxStats, routeKey: String?, title: String?, license: String, photoEntries: List<JsonObject>,
        createdAt: String? = null,
    ): JsonObject = buildJsonObject {
        put("\$type", TRACK)
        put("gpx", blob)
        put("createdAt", createdAt ?: now())
        put("license", license)
        s.startedAt?.let { put("startedAt", it) }
        s.duration?.let { put("duration", it) }
        put("distance", s.distance)
        s.gain?.let { put("gain", it) }
        // Only where the track was counted: absent means not counted, never 0.
        s.steps?.let { put("steps", it) }
        title?.let { clip(it) }?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
        routeKey?.takeIf { it.isNotEmpty() }?.let { put("routeKey", it) }
        if (photoEntries.isNotEmpty()) put("photos", JsonArray(photoEntries))
    }

    /** A review record, field for field as the website's publishReview writes it. */
    suspend fun publishReview(
        xrpc: Xrpc, did: String, text: String, hikedAt: String?, routeKey: String?, track: StrongRef?, tags: List<String>, license: String,
        photos: List<Photo> = emptyList(),
    ): StrongRef {
        val photoEntries = uploadPhotos(xrpc, photos)
        return create(xrpc, did, REVIEW, reviewRecord(text, hikedAt, routeKey, track, tags, license, photoEntries))
    }

    /** A review record's fields, its photos' entries already uploaded. [createdAt]: now, unless given. */
    fun reviewRecord(
        text: String, hikedAt: String?, routeKey: String?, track: StrongRef?, tags: List<String>, license: String,
        photoEntries: List<JsonObject>, createdAt: String? = null,
    ): JsonObject = buildJsonObject {
        put("\$type", REVIEW)
        put("text", text)
        put("createdAt", createdAt ?: now())
        put("license", license)
        hikedAt?.takeIf { it.isNotEmpty() }?.let { put("hikedAt", it) }
        routeKey?.takeIf { it.isNotEmpty() }?.let { put("routeKey", it) }
        track?.let { put("track", buildJsonObject { put("uri", it.uri); put("cid", it.cid) }) }
        if (tags.isNotEmpty()) put("tags", JsonArray(tags.take(MAX_TAGS).map(::JsonPrimitive)))
        if (photoEntries.isNotEmpty()) put("photos", JsonArray(photoEntries))
    }

    /**
     * What editing a review can change, as the website's ReviewEdit: its
     * licence included (from then on: copies already made keep the licence
     * they came with). [photos] are the record's own photo entries, some
     * gone or with their alt changed ([withAlt]), so what's in them stays.
     * When it was posted, its hike and its track stay as they were.
     */
    data class ReviewEdit(
        val text: String,
        val hikedAt: String? = null,
        val tags: List<String> = emptyList(),
        val photos: List<JsonObject> = emptyList(),
        val license: String? = null,
    )

    /**
     * The edited record, as the website's editedReview: [edit]'s fields and
     * everything else in [value] as it was. The licence is written only when
     * [edit] gives one, so a review without one stays without; the date,
     * tags and photos are left out when the edit has none.
     */
    fun editedReview(value: JsonObject, edit: ReviewEdit): JsonObject = buildJsonObject {
        for ((k, v) in value) if (k != "hikedAt" && k != "tags" && k != "photos") put(k, v)
        put("\$type", REVIEW)
        put("text", edit.text)
        value["createdAt"]?.let { put("createdAt", it) }
        edit.license?.takeIf { it.isNotEmpty() }?.let { put("license", it) }
        edit.hikedAt?.takeIf { it.isNotEmpty() }?.let { put("hikedAt", it) }
        if (edit.tags.isNotEmpty()) put("tags", JsonArray(edit.tags.map(::JsonPrimitive)))
        if (edit.photos.isNotEmpty()) put("photos", JsonArray(edit.photos))
    }

    /** A review record's photo entries, as they are (the edit form's starting point). */
    fun photoEntries(value: JsonObject): List<JsonObject> = (value["photos"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    /** A photo entry with [alt] as its description (trimmed, at most [Photos.MAX_ALT]), or none when blank, as the website's dialog saves it. */
    fun withAlt(entry: JsonObject, alt: String): JsonObject {
        val a = alt.trim()
        return if (a.isEmpty()) JsonObject(entry - "alt")
        else JsonObject(entry + ("alt" to JsonPrimitive(clip(a, Photos.MAX_ALT))))
    }

    /** The day a datetime falls on in [zone], as the website's localDay; null if it isn't one. */
    fun localDay(iso: String, zone: ZoneId = ZoneId.systemDefault()): LocalDate? = try {
        java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDate()
    } catch (_: java.time.format.DateTimeParseException) {
        null
    }

    /**
     * An edited review's hikedAt, as the website's dialog: the one it had
     * while its day is unchanged (so its time isn't moved to noon), [noonOf]
     * a new day, or none when the day is cleared.
     */
    fun editedHikedAt(was: String?, day: LocalDate?, zone: ZoneId = ZoneId.systemDefault()): String? = when {
        day == null -> null
        was != null && localDay(was, zone) == day -> was
        else -> noonOf(day, zone)
    }

    /** What [editReview] wrote: the review's new version, and the record as saved. */
    data class Edited(val ref: StrongRef, val record: JsonObject)

    /**
     * [edit] with an aspectRatio on each photo, as the website's dialog
     * saves it (#177): a photo posted before it was required has none, and
     * the record wouldn't validate without it. Those are measured from their
     * blobs, read from [did]'s PDS. One that can't be read (gone, not an
     * image) throws [PostException] rather than saving a record the
     * indexer would leave out.
     */
    suspend fun measured(xrpc: Xrpc, did: String, edit: ReviewEdit): ReviewEdit {
        if (edit.photos.all { Photos.ratioOf(it) != null }) return edit
        val photos = edit.photos.mapIndexed { i, entry ->
            if (Photos.ratioOf(entry) != null) return@mapIndexed entry
            val cid = ((entry["image"] as? JsonObject)?.get("ref") as? JsonObject)?.get("\$link")
                ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
            val bytes = try {
                cid?.let { xrpc.queryBytes("com.atproto.sync.getBlob", mapOf("did" to did, "cid" to it)) }
            } catch (e: XrpcException) {
                null
            } catch (e: IOException) {
                throw PostException("Couldn't reach your account's server, and an edit needs it. What you typed is kept here: try again when you have signal.", e)
            }
            val ratio = bytes?.let { withContext(Dispatchers.Default) { Photos.measure(it) } }
                ?: throw PostException("Photo ${i + 1} couldn't be read from your account to record its size, which photos now need. Remove it and save again.")
            JsonObject(entry + ("aspectRatio" to Photos.ratioJson(ratio)))
        }
        return edit.copy(photos = photos)
    }

    const val EDIT_CHANGED = "This review changed since you opened it: your profile has been read again. Close this and edit the review again."

    /**
     * Saves [edit] over [review] with putRecord at the same record key, so
     * its at:// URI (and the indexer's entry, and anything pointing at it)
     * stays the same. swapRecord is the CID it was read with, so it fails
     * ([EDIT_CHANGED]) rather than overwrite a review that changed since.
     * Online only: an edit needs the current CID. Throws [PostException]
     * saying what failed; [SignedOutException] if there's no session.
     * Photos posted before aspectRatio was required get it first ([measured]).
     */
    suspend fun editReview(xrpc: Xrpc, did: String, review: Profiles.Review, edit: ReviewEdit): Edited {
        val parts = review.uri.removePrefix("at://").split('/')
        require(parts.size == 3 && parts[0] == did && parts[1] == REVIEW) { "Not your review" }
        // Without the record as read, the edit would drop what it doesn't change.
        require(review.value["createdAt"] != null && review.cid.isNotEmpty()) { "The review wasn't read whole" }
        val record = editedReview(review.value, measured(xrpc, did, edit))
        val r = try {
            xrpc.procedure(
                "com.atproto.repo.putRecord",
                buildJsonObject {
                    put("repo", did); put("collection", REVIEW); put("rkey", parts[2])
                    put("record", record)
                    put("swapRecord", review.cid)
                },
            )
        } catch (e: XrpcException) {
            if (e.error == "InvalidSwap") throw PostException(EDIT_CHANGED, e)
            throw PostException("Saving failed (${e.status})" + (e.message?.takeIf { it != e.error && it != "HTTP ${e.status}" }?.let { ": $it" } ?: ""), e)
        } catch (e: IOException) {
            throw PostException("Couldn't reach your account's server, and an edit needs it. What you typed is kept here: try again when you have signal.", e)
        }
        val uri = r["uri"]?.jsonPrimitive?.contentOrNull
        val cid = r["cid"]?.jsonPrimitive?.contentOrNull
        if (uri == null || cid == null) throw PostException("Saving failed (no uri and cid in the answer)")
        Indexer.notify(uri, routeKeyOf(record))
        return Edited(StrongRef(uri, cid), record)
    }

    /** Each photo's JPEG uploaded, in order, and its entry for the record (the website's uploadPhotos). */
    private suspend fun uploadPhotos(xrpc: Xrpc, photos: List<Photo>): List<JsonObject> {
        if (photos.size > Photos.MAX_PHOTOS) throw PostException("Up to ${Photos.MAX_PHOTOS} photos can go with a post")
        return photos.map { p ->
            val bytes = try {
                withContext(Dispatchers.IO) { File(p.file).readBytes() }
            } catch (e: IOException) {
                throw PostException("A photo is no longer on this phone", e)
            }
            // Prepared to fit already; a PDS would refuse one that doesn't.
            if (bytes.size > Photos.MAX_BYTES) throw PostException("A photo is too big to post (over 2 MB)")
            val ratio = Photos.ratioFor(p, bytes)
            Photos.record(uploadBlob(xrpc, bytes, "image/jpeg"), p, ratio)
        }
    }

    /** uploadBlob: the blob reference the PDS gives, for the record. */
    suspend fun uploadBlob(xrpc: Xrpc, bytes: ByteArray, mimeType: String): JsonObject {
        val r = try {
            xrpc.upload("com.atproto.repo.uploadBlob", bytes, mimeType)
        } catch (e: XrpcException) {
            throw PostException("Uploading a file failed (${e.status})", e)
        } catch (e: IOException) {
            throw unreachable(e)
        }
        return r["blob"] as? JsonObject ?: throw PostException("Uploading a file failed (no blob in the answer)")
    }

    /**
     * createRecord: under [rkey] when given (the phone's own TID, so a
     * retry can find what an earlier try made: see [find]), else under a
     * TID the PDS makes, as the website does.
     */
    suspend fun create(xrpc: Xrpc, did: String, collection: String, record: JsonObject, rkey: String? = null): StrongRef {
        val r = try {
            xrpc.procedure(
                "com.atproto.repo.createRecord",
                buildJsonObject {
                    put("repo", did); put("collection", collection)
                    rkey?.let { put("rkey", it) }
                    put("record", record)
                },
            )
        } catch (e: XrpcException) {
            // As the website's: "createRecord failed (400): <the PDS's message>".
            throw PostException("createRecord failed (${e.status})" + (e.message?.takeIf { it != e.error && it != "HTTP ${e.status}" }?.let { ": $it" } ?: ""), e)
        } catch (e: IOException) {
            throw unreachable(e)
        }
        val uri = r["uri"]?.jsonPrimitive?.contentOrNull
        val cid = r["cid"]?.jsonPrimitive?.contentOrNull
        if (uri == null || cid == null) throw PostException("createRecord failed (no uri and cid in the answer)")
        Indexer.notify(uri, routeKeyOf(record))
        return StrongRef(uri, cid)
    }

    /**
     * The record at [collection]/[rkey] in [did]'s repository, if there is
     * one: null when the PDS says there's no such record. Network trouble
     * is an IOException, as anything else the PDS says is an XrpcException;
     * an answer without the record's uri and cid, a [PostException].
     */
    suspend fun find(xrpc: Xrpc, did: String, collection: String, rkey: String): StrongRef? {
        val r = try {
            xrpc.query("com.atproto.repo.getRecord", mapOf("repo" to did, "collection" to collection, "rkey" to rkey))
        } catch (e: XrpcException) {
            if (e.status == 400) return null
            throw e
        }
        // A bad answer, not the network: no use waiting for signal over it.
        val uri = r["uri"]?.jsonPrimitive?.contentOrNull
        val cid = r["cid"]?.jsonPrimitive?.contentOrNull
        if (uri == null || cid == null) throw PostException("getRecord failed (no uri and cid in the answer)")
        // Made by an earlier try whose answer was lost: that try couldn't tell the indexer.
        Indexer.notify(uri, (r["value"] as? JsonObject)?.let(::routeKeyOf))
        return StrongRef(uri, cid)
    }

    private fun routeKeyOf(record: JsonObject) = (record["routeKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /**
     * The licence new posts are shared under, as [licenseFor], but a profile
     * that can't be read for want of signal (or a session) throws, rather
     * than passing for unset: a post waiting on the phone mustn't go up
     * under the wrong licence.
     */
    suspend fun licenseStrict(xrpc: Xrpc, did: String): String {
        val l = try {
            (ProfileRecord.read(xrpc, did)?.get("license") as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (e: XrpcException) {
            // The PDS answered, and won't give it: as the website, the default.
            null
        }
        return if (l != null && l in LICENSES) l else DEFAULT_LICENSE
    }

    private fun unreachable(e: IOException) = if (e is ServerException) {
        PostException("Your account's server had a problem (HTTP ${e.status}). Try again in a while.", e)
    } else {
        PostException("Couldn't reach your account's server" + (e.message?.let { " ($it)" } ?: "") + ". Check your connection and try again.", e)
    }

    fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /**
     * A day as a datetime: noon local time, so it reads as the same day
     * anywhere near (AT Protocol has no date-only format), as the website's
     * noonOf.
     */
    fun noonOf(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String =
        ISO.format(day.atTime(LocalTime.NOON).atZone(zone).toInstant())

    /** At most [max] graphemes (a title's [MAX_TITLE]), as the lexicon allows. */
    internal fun clip(title: String, max: Int = MAX_TITLE): String {
        val chars = BreakIterator.getCharacterInstance().apply { setText(title) }
        var end = 0
        repeat(max) {
            val next = chars.next()
            if (next == BreakIterator.DONE) return title
            end = next
        }
        return if (chars.next() == BreakIterator.DONE) title else title.substring(0, end)
    }

    // As JavaScript's toISOString, which the website writes: milliseconds, UTC.
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private fun now() = ISO.format(Instant.now())
}
