package com.hikeratlas.android.outbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hikeratlas.android.atproto.Account
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.NetworkException
import com.hikeratlas.android.atproto.OtherAccountException
import com.hikeratlas.android.atproto.Photos
import com.hikeratlas.android.atproto.PostException
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.ServerException
import com.hikeratlas.android.atproto.SignedOutException
import com.hikeratlas.android.atproto.Tid
import com.hikeratlas.android.atproto.Xrpc
import com.hikeratlas.android.atproto.XrpcException
import com.hikeratlas.android.logs.Log
import com.hikeratlas.android.track.TrackLibrary
import com.hikeratlas.android.track.TrackMeasure
import com.hikeratlas.android.track.TrackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Posts waiting on the phone to go up (#60): every post the form makes goes
 * here first, then up, at once if there's signal, else by a [PostWorker]
 * once there is.
 *
 * Each post is a folder in [dir]: post.json (an [Entry]: what to post, its
 * state, and how far it got), its track's GPX, and copies of its photos,
 * which it owns until it's posted or discarded. Progress is saved after
 * every step (each blob uploaded, each record made), so a post stopped
 * halfway (no signal, the app killed, the phone off) carries on from where
 * it was. Records go under keys the phone chose and saved before making
 * them, so a create whose answer was lost is found on the next try, not
 * made again: a track is never posted twice.
 *
 * One post goes up at a time ([lock]): two posts of the same recorded track
 * then never both make its track record (the second finds the first's mark).
 */
class Outbox(
    private val context: Context,
    private val account: Account,
    private val library: TrackLibrary,
    /** The form's photos, for [cleanOrphans]. */
    private val photos: Photos.Store? = null,
    private val dir: File = File(context.filesDir, "outbox"),
    private val now: () -> Long = System::currentTimeMillis,
    private val notifications: PostNotifications = PostNotifications(context),
) {
    enum class State {
        /** For signal, or to try again after a network error or a server's 5xx ([Entry.error] says which). */
        WAITING,
        /** The session ended: waits for the account to sign in again. */
        NEEDS_SIGN_IN,
        /** Another account is signed in: waits for its own. */
        OTHER_ACCOUNT,
        /** Refused (a 4xx, a bad answer, or something wrong with the post itself): waits for Retry or Discard. */
        FAILED,
    }

    /** How far a post got: saved after each step. */
    @Serializable
    data class Progress(
        /** Read once, so every record of the post has the same. */
        val license: String? = null,
        val gpxBlob: JsonObject? = null,
        /** Each photo's blob, by its place in the post's photos. */
        val photoBlobs: Map<Int, JsonObject> = emptyMap(),
        val trackRkey: String? = null,
        /** A create was sent under [trackRkey]: look for it before sending another. */
        val trackTried: Boolean = false,
        val track: Posts.StrongRef? = null,
        val reviewRkey: String? = null,
        val reviewTried: Boolean = false,
        val review: Posts.StrongRef? = null,
    )

    @Serializable
    data class Entry(
        val id: String,
        /** The account it was made as: it goes up as that account only. */
        val did: String,
        val handle: String? = null,
        val queuedAt: Long,
        /** The records' createdAt: when it was posted on the phone, not when it went up. */
        val createdAt: String,
        /** What to call it: the hike's title, or the track's. */
        val title: String,
        /** The post, its photos in this post's folder; its GPX is in the folder too, if [hasGpx]. */
        val post: Posts.Post,
        val hasGpx: Boolean = false,
        /** The recorded track it posts, if any: marked posted (TrackStore) as its record is made. */
        val trackId: Long? = null,
        val state: State = State.WAITING,
        val error: String? = null,
        /** Waiting because the server had a problem (a 5xx, or busy), not for signal. */
        val serverError: Boolean = false,
        val progress: Progress = Progress(),
        /** Asked to sign in (a notification) once: not again each try. */
        val signInNotified: Boolean = false,
    ) {
        val review get() = post.text.isNotBlank()
    }

    /** What an upload came to. */
    sealed interface Outcome {
        data object Posted : Outcome
        /** No such post (posted or discarded meanwhile). */
        data object Gone : Outcome
        /**
         * To try again later: the request didn't get through ([error] is
         * OkHttp's why), or with [server], the server had a problem
         * ([error] is its HTTP status).
         */
        data class Waiting(val error: String, val server: Boolean = false) : Outcome
        data object NeedsSignIn : Outcome
        data object OtherAccount : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val lock = Mutex()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** The waiting posts, oldest first. */
    val entries: StateFlow<List<Entry>> = _entries
    private val _active = MutableStateFlow<String?>(null)
    /** The post going up now, if one is. */
    val active: StateFlow<String?> = _active
    private val _posted = MutableStateFlow(0)
    /** Counts the posts that went up, so a profile showing can read its posts again. */
    val posted: StateFlow<Int> = _posted

    /**
     * Cleans up, makes sure every waiting post has its worker, and resumes
     * the posts held for an account when it signs in. Called once the app
     * is set up (not from a constructor: it reads the account).
     */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            cleanOrphans()
            // Their workers survive reboots, but one that was going up from
            // the form when the app was killed has none.
            for (e in _entries.value) if (e.state == State.WAITING) schedule(e.id)
            account.state.collect { s -> if (s is AccountState.In) resume(s.did) }
        }
    }

    /** The post [id], as saved. */
    suspend fun entry(id: String): Entry? = withContext(Dispatchers.IO) { read(id) }

    /**
     * Saves a post to go up as [did]: [post]'s GPX and photos are copied
     * into its folder (the form keeps and drops its own). Throws
     * [PostException] if a photo is gone. Nothing is sent yet: see [upload]
     * and [schedule].
     */
    suspend fun add(did: String, handle: String?, title: String, post: Posts.Post, trackId: Long? = null): Entry {
        val review = post.text.isNotBlank()
        require(review || (post.gpx != null && post.track == null)) { "Nothing to post" }
        val e = lock.withLock {
            withContext(Dispatchers.IO) {
                val id = UUID.randomUUID().toString()
                val folder = File(dir, id).apply { mkdirs() }
                try {
                    post.gpx?.let { File(folder, GPX).writeText(it) }
                    val photos = post.photos.mapIndexed { i, p ->
                        val to = File(folder, "photo-$i.jpg")
                        try {
                            File(p.file).copyTo(to, overwrite = true)
                        } catch (x: IOException) {
                            throw PostException("A photo is no longer on this phone", x)
                        }
                        p.copy(file = to.path)
                    }
                    val t = now()
                    val e = Entry(
                        id = id, did = did, handle = handle, queuedAt = t, createdAt = TrackMeasure.iso(t), title = title,
                        post = post.copy(gpx = null, photos = photos), hasGpx = post.gpx != null, trackId = trackId,
                    )
                    // Last: a folder without post.json is a post never saved (see cleanOrphans).
                    write(e)
                    Log.i(LOG, "Queued ${short(id)} as $did: track ${post.gpx != null}, review $review, ${photos.size} photos, recorded ${trackId != null}")
                    e
                } catch (x: Exception) {
                    folder.deleteRecursively()
                    throw x
                }
            }
        }
        refresh()
        return e
    }

    /** Has a [PostWorker] post [id] once there's signal; [replace]: start again now (Retry), not after a backoff. */
    fun schedule(id: String, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PostWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(PostWorker.POST to id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        Log.d(LOG, "Scheduled ${short(id)}${if (replace) " (now)" else ""}")
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Tries a post again now (a failed one, or one waiting): it starts from where it got to. */
    suspend fun retry(id: String) {
        val found = lock.withLock {
            withContext(Dispatchers.IO) {
                read(id)?.let { write(it.copy(state = State.WAITING, error = null, serverError = false, signInNotified = false)) } != null
            }
        }
        Log.i(LOG, if (found) "Retry ${short(id)}" else "Retry ${short(id)}: gone")
        if (found) schedule(id, replace = true)
        refresh()
    }

    /** Deletes a post that hasn't gone up, and its files. What went up already (a track, say) stays up. */
    suspend fun discard(id: String) {
        // Stops it if it's going up: the lock is then free.
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        lock.withLock { withContext(Dispatchers.IO) { File(dir, id).deleteRecursively() } }
        notifications.cancel(id)
        Log.i(LOG, "Discarded ${short(id)}")
        refresh()
    }

    /**
     * Posts [id], from where it got to. [notify]: say how it went in a
     * notification (it went up in the background). The post's state is
     * saved as it ends: [Outcome.Waiting] wants another try later.
     */
    suspend fun upload(id: String, notify: Boolean = false, progress: (String) -> Unit = {}): Outcome {
        val outcome = lock.withLock {
            val first = withContext(Dispatchers.IO) { read(id) } ?: return@withLock Outcome.Gone.also { Log.i(LOG, "Upload ${short(id)}: gone") }
            Log.i(LOG, "Upload ${short(id)} (${if (notify) "background" else "from the form"}), was ${first.state}")
            _active.value = id
            refresh()
            val run = Run(first, progress)
            try {
                run.go()
                withContext(Dispatchers.IO) { File(dir, id).deleteRecursively() }
                _posted.value++
                Log.i(LOG, "Posted ${short(id)}")
                if (notify) notifications.posted(run.e)
                Outcome.Posted
            } catch (x: CancellationException) {
                Log.i(LOG, "Upload ${short(id)} stopped (cancelled)")
                throw x
            } catch (x: Exception) {
                stopped(run.e, x, notify)
            } finally {
                _active.value = null
            }
        }
        refresh()
        return outcome
    }

    /** Saves why [e] stopped, and says so if it's for good or needs the user. */
    private suspend fun stopped(e: Entry, x: Exception, notify: Boolean): Outcome {
        val (state, outcome) = when (val c = classify(x)) {
            is Outcome.Waiting -> State.WAITING to c
            Outcome.NeedsSignIn -> State.NEEDS_SIGN_IN to c
            Outcome.OtherAccount -> State.OTHER_ACCOUNT to c
            is Outcome.Failed -> State.FAILED to c
            else -> State.FAILED to Outcome.Failed(x.toString())
        }
        val error = when (outcome) {
            is Outcome.Waiting -> outcome.error
            is Outcome.Failed -> outcome.message
            else -> null
        }
        val how = "Upload ${short(e.id)} stopped: $state ${outcomeText(outcome)}"
        if (state == State.FAILED) Log.e(LOG, how, x) else Log.w(LOG, how, x)
        val askSignIn = notify && state == State.NEEDS_SIGN_IN && !e.signInNotified
        val saved = e.copy(
            state = state, error = error, serverError = (outcome as? Outcome.Waiting)?.server == true,
            signInNotified = e.signInNotified || askSignIn,
        )
        withContext(Dispatchers.IO) { if (File(dir, e.id).isDirectory) write(saved) }
        if (notify && state == State.FAILED) notifications.failed(saved)
        if (askSignIn) notifications.signIn(saved)
        return outcome
    }

    /** One try at posting an entry; [e] is the entry as last saved. */
    private inner class Run(var e: Entry, val progress: (String) -> Unit) {
        private lateinit var xrpc: Xrpc
        private val folder = File(dir, e.id)

        private suspend fun save(p: Progress) {
            e = e.copy(progress = p)
            withContext(Dispatchers.IO) { write(e) }
        }

        suspend fun go() {
            val did = account.currentDid() ?: throw SignedOutException()
            if (did != e.did) throw OtherAccountException(e.did, did)
            xrpc = account.xrpcAs(e.did)
            e = e.copy(state = State.WAITING, error = null, serverError = false)
            // A blob kept from an earlier try may have been dropped by the PDS
            // since (unused blobs are, after a while): a record refused
            // then is tried once more with every file uploaded again.
            val reused = e.progress.gpxBlob != null || e.progress.photoBlobs.isNotEmpty()
            try {
                steps()
            } catch (x: Exception) {
                if (!reused || x is CancellationException || cause<XrpcException>(x)?.status != 400) throw x
                Log.w(LOG, "${short(e.id)}: refused with blobs from an earlier try; uploading them again", x)
                save(e.progress.copy(gpxBlob = null, photoBlobs = emptyMap()))
                steps()
            }
        }

        private suspend fun steps() {
            progress("Posting…")
            Log.d(LOG, "${short(e.id)}: steps from ${done(e.progress)}")
            val license = e.progress.license ?: Posts.licenseStrict(xrpc, e.did).also { save(e.progress.copy(license = it)) }
            val review = e.review
            var track = e.progress.track ?: e.post.track
            // Posted meanwhile (by another post of the same track): pointed at, not posted again.
            if (track == null && e.trackId != null) library.posted(e.trackId!!)?.let { track = Posts.StrongRef(it.trackUri, it.trackCid) }
            if (track == null && e.hasGpx) {
                progress("Uploading your track…")
                val gpx = try {
                    withContext(Dispatchers.IO) { File(folder, GPX).readText() }
                } catch (x: IOException) {
                    throw PostException("The track is no longer on this phone", x)
                }
                val stats = try {
                    TrackMeasure.ofGpx(gpx)
                } catch (x: IllegalArgumentException) {
                    throw PostException(x.message ?: "Not a GPX file", x)
                }
                val blob = e.progress.gpxBlob ?: run {
                    val bytes = Posts.gzip(gpx)
                    if (bytes.size > Posts.MAX_GPX_BYTES) throw PostException(Posts.TOO_BIG)
                    Posts.uploadBlob(xrpc, bytes, "application/gzip").also {
                        save(e.progress.copy(gpxBlob = it))
                        Log.d(LOG, "${short(e.id)}: GPX uploaded (${bytes.size} bytes)")
                    }
                }
                val photoEntries = if (review) emptyList() else photoEntries()
                val record = Posts.trackRecord(blob, stats, e.post.routeKey, e.post.title, license, photoEntries, e.createdAt)
                track = createOnce(Posts.TRACK, record, review = false)
            }
            if (track != null && e.progress.track == null) save(e.progress.copy(track = track))
            mark(track, null)
            if (review && e.progress.review == null) {
                progress("Posting your review…")
                val photoEntries = photoEntries()
                val record = Posts.reviewRecord(
                    e.post.text.trim(), e.post.hikedAt, e.post.routeKey, track, e.post.tags, license, photoEntries, e.createdAt,
                )
                save(e.progress.copy(review = createOnce(Posts.REVIEW, record, review = true)))
            }
            mark(track, e.progress.review)
        }

        /** The recorded track marked posted (TrackStore), with its review once there is one. */
        private suspend fun mark(track: Posts.StrongRef?, review: Posts.StrongRef?) {
            val id = e.trackId ?: return
            track ?: return
            val m = library.posted(id)
            when {
                m == null -> library.setPosted(id, TrackStore.Posted(track.uri, track.cid, review?.uri, now()))
                review != null && m.trackUri == track.uri && m.reviewUri == null -> library.setPosted(id, m.copy(reviewUri = review.uri))
            }
        }

        /** Each photo uploaded (those not already), and its entry for the record, in order. */
        private suspend fun photoEntries(): List<JsonObject> {
            val photos = e.post.photos
            if (photos.size > Photos.MAX_PHOTOS) throw PostException("Up to ${Photos.MAX_PHOTOS} photos can go with a post")
            return photos.mapIndexed { i, p ->
                val blob = e.progress.photoBlobs[i] ?: run {
                    val bytes = try {
                        withContext(Dispatchers.IO) { File(p.file).readBytes() }
                    } catch (x: IOException) {
                        throw PostException("A photo is no longer on this phone", x)
                    }
                    if (bytes.size > Photos.MAX_BYTES) throw PostException("A photo is too big to post (over 2 MB)")
                    // Measured before it goes up, so one that can't be isn't uploaded for nothing.
                    ratio(i, p, bytes)
                    Posts.uploadBlob(xrpc, bytes, "image/jpeg").also {
                        save(e.progress.copy(photoBlobs = e.progress.photoBlobs + (i to it)))
                        Log.d(LOG, "${short(e.id)}: photo ${i + 1} uploaded (${bytes.size} bytes)")
                    }
                }
                Photos.record(blob, p, ratio(i, p, null))
            }
        }

        /**
         * Photo [i]'s aspectRatio: as it was prepared, or for a post queued
         * before the app recorded it, measured from its file ([bytes], if
         * read already) and saved with the post, so it's measured once.
         */
        private suspend fun ratio(i: Int, p: Posts.Photo, bytes: ByteArray?): Photos.AspectRatio {
            e.post.photos[i].aspectRatio?.let { return it }
            val b = bytes ?: try {
                withContext(Dispatchers.IO) { File(p.file).readBytes() }
            } catch (x: IOException) {
                throw PostException("A photo is no longer on this phone", x)
            }
            val r = Photos.ratioFor(p, b)
            e = e.copy(post = e.post.copy(photos = e.post.photos.mapIndexed { j, q -> if (j == i) q.copy(aspectRatio = r) else q }))
            withContext(Dispatchers.IO) { write(e) }
            Log.d(LOG, "${short(e.id)}: photo ${i + 1} measured (${r.width} × ${r.height}), queued without its size")
            return r
        }

        /**
         * Makes a record under the key chosen for it, once: the key is saved
         * before the first create, and after any try (one whose answer may
         * have been lost) the record is looked for before another is sent.
         */
        private suspend fun createOnce(collection: String, record: JsonObject, review: Boolean): Posts.StrongRef {
            val p = e.progress
            val rkey = (if (review) p.reviewRkey else p.trackRkey) ?: Tid.next().also {
                save(if (review) e.progress.copy(reviewRkey = it) else e.progress.copy(trackRkey = it))
            }
            val tried = if (review) e.progress.reviewTried else e.progress.trackTried
            if (tried) Posts.find(xrpc, e.did, collection, rkey)?.let {
                Log.i(LOG, "${short(e.id)}: $collection/$rkey found, made by an earlier try")
                return it
            }
            save(if (review) e.progress.copy(reviewTried = true) else e.progress.copy(trackTried = true))
            return try {
                Posts.create(xrpc, e.did, collection, record, rkey).also { Log.i(LOG, "${short(e.id)}: made $collection/$rkey") }
            } catch (x: PostException) {
                // Already made after all (by a try just before this one).
                if (cause<XrpcException>(x)?.status == 400) Posts.find(xrpc, e.did, collection, rkey)?.let {
                    Log.i(LOG, "${short(e.id)}: $collection/$rkey refused but there, made by an earlier try")
                    return it
                }
                throw x
            }
        }
    }

    /**
     * What stopping on [x] means: try again later, wait for a sign-in, or
     * fail for good. Only the network and the server's own trouble wait:
     * any other IOException (a bad answer, a file that couldn't be saved)
     * would fail the same way next time, so it fails now, where it can be
     * seen, rather than waiting for a signal it already has (#83).
     */
    internal fun classify(x: Throwable): Outcome {
        cause<OtherAccountException>(x)?.let { return Outcome.OtherAccount }
        cause<SignedOutException>(x)?.let { return Outcome.NeedsSignIn }
        cause<XrpcException>(x)?.let { xe ->
            return when (xe.status) {
                // Refused even after a refresh: the session's no good.
                401 -> Outcome.NeedsSignIn
                // Timed out, or too many requests: later.
                408, 429 -> Outcome.Waiting("HTTP ${xe.status}", server = true)
                else -> Outcome.Failed(failure(x))
            }
        }
        cause<ServerException>(x)?.let { return Outcome.Waiting("HTTP ${it.status}", server = true) }
        causeWhere(x, NetworkException::isTransport)?.let { return Outcome.Waiting(it.message ?: it.javaClass.simpleName) }
        return Outcome.Failed(failure(x))
    }

    private fun failure(x: Throwable): String = (cause<PostException>(x) ?: x).let { it.message ?: it.toString() }

    private inline fun <reified T : Throwable> cause(x: Throwable): T? = causeWhere(x) { it is T } as T?

    private inline fun causeWhere(x: Throwable, ok: (Throwable) -> Boolean): Throwable? {
        var c: Throwable? = x
        while (c != null) {
            if (ok(c)) return c
            c = c.cause
        }
        return null
    }

    /** The posts held for [did] (it signed in again, or back in), waiting again and tried at once. */
    private suspend fun resume(did: String) {
        val ids = lock.withLock {
            // Only while that account is signed in now: a sign-in seen late
            // (the watcher starting, or a value it took before a refresh was
            // refused) mustn't undo a "needs sign-in" just written, which
            // always follows the sign-out.
            if ((account.state.value as? AccountState.In)?.did != did) return
            withContext(Dispatchers.IO) {
                list().filter { it.did == did && (it.state == State.NEEDS_SIGN_IN || it.state == State.OTHER_ACCOUNT) }.map {
                    write(it.copy(state = State.WAITING, error = null, serverError = false, signInNotified = false))
                    it.id
                }
            }
        }
        if (ids.isNotEmpty()) Log.i(LOG, "Signed in as $did: ${ids.size} held posts waiting again")
        for (id in ids) schedule(id, replace = true)
        refresh()
    }

    /**
     * Deletes files no post or form needs: folders here left without their
     * post.json (the app killed as one was saved), the form's photos left by
     * a form open when the app was killed, and camera files the camera never
     * gave back. A post.json that can't be read is kept: it may be a post.
     */
    suspend fun cleanOrphans() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                dir.listFiles()?.forEach { f -> if (!File(f, POST).isFile) f.deleteRecursively() }
                photos?.cleanOrphans(now())
                File(context.filesDir, "camera").listFiles()?.forEach { f -> if (now() - f.lastModified() > DAY_MS) f.delete() }
            }
        }
        refresh()
    }

    /** Reads the list again. */
    suspend fun refresh() {
        _entries.value = withContext(Dispatchers.IO) { list() }
    }

    private fun list(): List<Entry> = dir.listFiles()?.mapNotNull { read(it.name) }?.sortedBy { it.queuedAt }.orEmpty()

    private fun read(id: String): Entry? {
        val f = File(File(dir, id), POST)
        return try {
            JSON.decodeFromString<Entry>(f.readText())
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // Written whole and renamed into place, so a post.json is never half written.
    private fun write(e: Entry) {
        val folder = File(dir, e.id)
        val tmp = File(folder, "$POST.tmp")
        tmp.writeText(JSON.encodeToString(e))
        if (!tmp.renameTo(File(folder, POST))) throw IOException("Couldn't save the post")
    }

    /** What a post's progress has done so far, for the log. */
    private fun done(p: Progress) = listOfNotNull(
        "licence".takeIf { p.license != null },
        "GPX".takeIf { p.gpxBlob != null },
        "${p.photoBlobs.size} photos".takeIf { p.photoBlobs.isNotEmpty() },
        "track".takeIf { p.track != null },
        "review".takeIf { p.review != null },
    ).joinToString(", ").ifEmpty { "the start" }

    private fun outcomeText(o: Outcome) = when (o) {
        is Outcome.Waiting -> if (o.server) "(server: ${o.error})" else "(network: ${o.error})"
        is Outcome.Failed -> "(${o.message})"
        else -> ""
    }

    companion object {
        const val TAG = "post"
        private const val LOG = "outbox"
        /** A post's id, short: enough to follow one through the log. */
        private fun short(id: String) = id.take(8)
        fun workName(id: String) = "post-$id"
        private const val POST = "post.json"
        private const val GPX = "track.gpx"
        private const val DAY_MS = 86_400_000L
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
