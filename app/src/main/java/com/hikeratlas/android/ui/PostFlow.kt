package com.hikeratlas.android.ui

import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Xrpc
import com.hikeratlas.android.track.TrackLibrary
import com.hikeratlas.android.track.TrackStore

/**
 * Posts what the form holds, as [did]. A track is marked posted on the
 * phone as soon as its record is made, so it's never posted twice: if the
 * review then fails, trying again points the review at the track already
 * posted.
 */
internal suspend fun postDraft(
    xrpc: Xrpc, did: String, library: TrackLibrary, d: PostDraft, progress: (String) -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    val id = d.trackId
    val already = id?.let { library.posted(it) }
    if (already != null && d.text.isBlank()) return
    val gpx = if (id != null && already == null) library.gpxText(id) ?: throw IllegalStateException("That track is no longer on this phone") else null
    var mark = already
    val published = Posts.publish(
        xrpc, did,
        Posts.Post(
            text = d.text, hikedAt = Posts.noonOf(d.day), tags = d.tags, routeKey = d.hike?.key, title = d.title, gpx = gpx,
            track = already?.let { Posts.StrongRef(it.trackUri, it.trackCid) },
        ),
        progress,
        onTrack = { ref ->
            if (id != null) {
                mark = TrackStore.Posted(ref.uri, ref.cid, null, now())
                library.setPosted(id, mark)
            }
        },
    )
    val m = mark
    if (id != null && m != null && published.review != null) library.setPosted(id, m.copy(reviewUri = published.review.uri))
}

/** A posted track whose track record is gone (deleted, on the website say) can be posted again. */
internal suspend fun verifyPosted(xrpc: Xrpc, did: String, library: TrackLibrary, id: Long) {
    val posted = library.posted(id) ?: return
    if (Posts.exists(xrpc, did, posted.trackUri) == false) library.setPosted(id, null)
}
