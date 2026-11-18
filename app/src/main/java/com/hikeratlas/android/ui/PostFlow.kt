package com.hikeratlas.android.ui

import com.hikeratlas.android.atproto.Photos
import com.hikeratlas.android.atproto.PostException
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Xrpc
import com.hikeratlas.android.outbox.Outbox
import com.hikeratlas.android.track.TrackLibrary
import kotlinx.coroutines.CancellationException

/** What became of a post sent from the form. */
sealed interface PostOutcome {
    data object Posted : PostOutcome
    /** Kept on the phone to go up later: [message] says when. */
    data class Queued(val message: String) : PostOutcome
}

const val WILL_POST = "Will post when you're online. Until then it's under Waiting posts on your profile."
const val WILL_POST_SIGNED_IN = "Will post once you sign in again (You tab). Until then it's under Waiting posts on your profile."
private const val UNTIL_THEN = "It'll try again. Until then it's under Waiting posts on your profile."

/** A post kept on the phone after the server had a problem ([error]: its HTTP status). */
fun willPostServer(error: String) = "Your account's server had a problem ($error). $UNTIL_THEN"

/** A post kept on the phone that didn't get through, though the phone has a network ([error]: why not). */
fun willPostUnreachable(error: String) = "Couldn't reach your account's server just now ($error). $UNTIL_THEN"

/**
 * Posts what the form holds, as [did], by way of the [outbox]: saved on the
 * phone first, then up at once. Every post takes this one way, signal or
 * not, so a post cut off halfway (signal lost, the app killed) is never
 * lost or posted twice: it carries on from where it got to. A network
 * error or the server's 5xx leaves it waiting (said as waiting for signal
 * only when [online] says the phone has no network); a refusal (a 4xx, or
 * a bad answer) takes it back out of the outbox and throws, so the form,
 * which kept its contents, can say why and let it be tried again. A track
 * is marked posted on the phone as soon as its record is made, so it's
 * never posted twice: if the review then fails, trying again points the
 * review at the track already posted.
 */
internal suspend fun sendDraft(
    outbox: Outbox, did: String, handle: String?, library: TrackLibrary, d: PostDraft, progress: (String) -> Unit,
    online: () -> Boolean = { false },
): PostOutcome {
    val id = d.trackId
    val already = id?.let { library.posted(it) }
    if (already != null && d.text.isBlank()) return PostOutcome.Posted
    val gpx = if (id != null && already == null) library.gpxText(id) ?: throw IllegalStateException("That track is no longer on this phone") else null
    // Photos without GPS placed by their time on the track, as the website
    // does as it posts; the track's GPX is read for that even when it went
    // up already (a review tried again).
    val photos = if (d.photos.isEmpty()) emptyList() else {
        val track = gpx ?: id?.let { library.gpxText(it) }
        d.photos.map { Photos.placed(it, track) }
    }
    progress("Posting…")
    val entry = outbox.add(
        did, handle, d.title,
        Posts.Post(
            text = d.text, hikedAt = Posts.noonOf(d.day), tags = d.tags, routeKey = d.hike?.key, title = d.title, gpx = gpx,
            track = already?.let { Posts.StrongRef(it.trackUri, it.trackCid) }, photos = photos,
        ),
        trackId = id,
    )
    val outcome = try {
        outbox.upload(entry.id, progress = progress)
    } catch (e: CancellationException) {
        // The form went (closed, or the app stopped it) as it posted: it's
        // saved waiting, so the background goes on with it rather than
        // leaving it for a Retry (#99).
        outbox.schedule(entry.id)
        throw e
    }
    return when (val o = outcome) {
        Outbox.Outcome.Posted, Outbox.Outcome.Gone -> PostOutcome.Posted
        is Outbox.Outcome.Waiting -> {
            outbox.schedule(entry.id)
            PostOutcome.Queued(
                when {
                    o.server -> willPostServer(o.error)
                    online() -> willPostUnreachable(o.error)
                    else -> WILL_POST
                },
            )
        }
        Outbox.Outcome.NeedsSignIn -> PostOutcome.Queued(WILL_POST_SIGNED_IN)
        Outbox.Outcome.OtherAccount -> PostOutcome.Queued("Will post once ${handle?.let { "@$it" } ?: "that account"} signs in again.")
        is Outbox.Outcome.Failed -> {
            outbox.discard(entry.id)
            throw PostException(o.message)
        }
    }
}

/** A posted track whose track record is gone (deleted, on the website say) can be posted again. */
internal suspend fun verifyPosted(xrpc: Xrpc, did: String, library: TrackLibrary, id: Long) {
    val posted = library.posted(id) ?: return
    if (Posts.exists(xrpc, did, posted.trackUri) == false) library.setPosted(id, null)
}

/**
 * Deletes one of your posts (a review and its track, or a track and its
 * reviews), and the phone's mark on a track posted as it, so that track can
 * be posted again.
 */
internal suspend fun deletePost(xrpc: Xrpc, did: String, library: TrackLibrary, d: com.hikeratlas.android.atproto.Profiles.Deletion) {
    com.hikeratlas.android.atproto.Profiles.delete(xrpc, did, d) { review, track -> library.postDeleted(track, review) }
}
