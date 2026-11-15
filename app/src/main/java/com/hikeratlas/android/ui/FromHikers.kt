package com.hikeratlas.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.atproto.Indexer
import com.hikeratlas.android.atproto.IndexerClient
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What other hikers posted about a route, from the indexer, as the
 * website's FromHikers.tsx: a line of counts near the top of a hike's sheet,
 * and "From hikers", their reviews and tracks. Without the indexer (offline,
 * down, slow), neither shows: the sheet is as it was.
 */
class HikerActions(
    val client: IndexerClient,
    /** An author's PDS when the indexer didn't give one: from their DID document; null if it can't be found. */
    val pdsOf: suspend (did: String) -> String?,
    /** A review's track that isn't on the pages loaded, from its author's [pds]; null if gone or unreadable. */
    val getTrack: suspend (pds: String, uri: String) -> Profiles.Track?,
    /** A track's GPX, from its author's PDS. */
    val gpx: suspend (pds: String, did: String, track: Profiles.Track) -> String,
    /** Opens a page in the browser: an author's profile, a report form, a photo. */
    val openUrl: (String) -> Unit,
    /** Shows a track on the map; [hikeId] and [hikeTitle], the hike to come back to. */
    val showTrack: (track: PostedTrack, hikeId: String, hikeTitle: String) -> Unit,
    val shareGpx: (title: String, gpx: String) -> Unit,
    /** The route keys of the app's own writes, once the indexer is told (null: route not known). */
    val writes: Flow<String?> = Indexer.written,
    val now: () -> Long = System::currentTimeMillis,
)

/** Posts shown before "More", and how many each "More" adds, as the website's. */
internal const val FIRST = 3
internal const val PAGE = 10
/** Recent tags shown, as the website's. */
private const val RECENT_TAGS = 6

/** A list that shows [FIRST] posts, then pages through the rest, as the website's usePosts. */
@Stable
internal class PostPages<T>(private val load: suspend (limit: Int, cursor: String?, fresh: Long) -> IndexerClient.Page<IndexerClient.Post<T>>?) {
    var items by mutableStateOf<List<IndexerClient.Post<T>>>(emptyList())
        private set
    var cursor by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The first page, if [count] says there's anything; a failure keeps what's shown. */
    suspend fun first(count: Int, fresh: Long) {
        if (count <= 0) {
            items = emptyList()
            cursor = null
            return
        }
        load(FIRST, null, fresh)?.let { items = addNew(emptyList(), it.items); cursor = it.cursor }
    }

    suspend fun more() {
        if (busy) return
        busy = true
        try {
            load(PAGE, cursor, 0)?.let { items = addNew(items, it.items); cursor = it.cursor }
        } finally {
            busy = false
        }
    }

    /** One added out of turn: a review's track that isn't on the pages loaded. */
    fun add(p: IndexerClient.Post<T>) {
        items = addNew(items, listOf(p))
    }
}

/** [list] with [more] added, none twice (by URI): the next page may bring one added out of turn, as the website's addNew. */
internal fun <T> addNew(list: List<IndexerClient.Post<T>>, more: List<IndexerClient.Post<T>>): List<IndexerClient.Post<T>> {
    val have = list.mapTo(HashSet()) { it.uri }
    return list + more.filter { have.add(it.uri) }
}

/** "12 faves", "1 review". */
internal fun plural(n: Int, one: String) = "$n ${if (n == 1) one else one + "s"}"

/** Everything the sheet shows of a route from the indexer, for one route key. */
@Stable
class RouteHikers internal constructor(val key: String, internal val actions: HikerActions, private val scope: CoroutineScope) {
    var stats by mutableStateOf<IndexerClient.RouteStats?>(null)
        private set
    internal val reviews = PostPages { limit, cursor, fresh -> actions.client.reviews(key, limit, cursor, fresh) }
    internal val tracks = PostPages { limit, cursor, fresh -> actions.client.tracks(key, limit, cursor, fresh) }
    /** "From hikers", for the counts to scroll to. */
    internal val section = BringIntoViewRequester()
    /** A track a "Has a track" was tapped for, to scroll to once it's in Tracks. */
    internal var goTo by mutableStateOf<String?>(null)
    /** Thumbnails' lines, by track URI: a track shown twice is read once. */
    internal val lines = mutableStateMapOf<String, List<List<List<Double>>>>()
    private val pds = HashMap<String, Deferred<String?>>()

    /** The counts, then the lists' first pages; [fresh] non-zero after one of our writes, past the indexer's cache. */
    internal suspend fun load(fresh: Long) {
        // A failure keeps what's shown (nothing, the first time).
        val s = actions.client.stats(listOf(key), fresh)?.let { it[key] ?: IndexerClient.RouteStats(key, 0, 0, 0) } ?: return
        stats = s
        coroutineScope {
            launch { reviews.first(s.reviews, fresh) }
            launch { tracks.first(s.tracks, fresh) }
        }
    }

    /** An author's PDS: the indexer's, else their DID document's, asked once per sheet. */
    internal suspend fun pdsOf(author: IndexerClient.Author): String? {
        author.pds?.let { return it }
        val d = pds.getOrPut(author.did) {
            scope.async {
                try { actions.pdsOf(author.did) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
        }
        // Not kept if it failed: a later post by them asks again.
        return d.await().also { if (it == null) pds.remove(author.did) }
    }
}

/**
 * The indexer's view of [key]'s route, read when the sheet opens and again,
 * past the cache, after one of the app's writes to it.
 */
@Composable
fun rememberRouteHikers(key: String, actions: HikerActions): RouteHikers {
    val scope = rememberCoroutineScope()
    val hikers = remember(key, actions.client) { RouteHikers(key, actions, scope) }
    var fresh by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(hikers) {
        actions.writes.collect { k -> if (k == null || k == key) fresh = actions.now() }
    }
    LaunchedEffect(hikers, fresh) { hikers.load(fresh) }
    return hikers
}

/** The website's heart colour (Tailwind's rose-600, as its counts line). */
private val HEART = Color(0xFFE11D48)

/** Scrolls so this lands at the top of what's in view, as the website's scrollIntoView({block: 'start'}). */
@OptIn(ExperimentalFoundationApi::class)
private suspend fun BringIntoViewRequester.toTop() = bringIntoView(Rect(0f, 0f, 1f, 100_000f))

/** "♥ 12 faves · 3 reviews · 2 tracks"; nothing until someone has posted. Reviews and tracks scroll to "From hikers". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteCounts(hikers: RouteHikers) {
    val s = hikers.stats?.takeIf { it.any } ?: return
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val parts = buildList<Pair<String, Boolean>> {
        if (s.faves > 0) add(plural(s.faves, "fave") to false)
        if (s.reviews > 0) add(plural(s.reviews, "review") to true)
        if (s.tracks > 0) add(plural(s.tracks, "track") to true)
    }
    FlowRow(Modifier.testTag("route-counts"), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = HEART, modifier = Modifier.size(14.dp).align(androidx.compose.ui.Alignment.CenterVertically))
        parts.forEachIndexed { i, (text, link) ->
            if (i > 0) Text(" · ", style = MaterialTheme.typography.bodyMedium, color = muted)
            else Text(" ", style = MaterialTheme.typography.bodyMedium)
            Text(
                text, style = MaterialTheme.typography.bodyMedium,
                color = if (link) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = if (link) Modifier.clickable { scope.launch { hikers.section.toTop() } } else Modifier,
            )
        }
    }
}

/**
 * "From hikers", as the website's: tags reported in the last month, then
 * reviews and tracks, [FIRST] of each and "More" pages of [PAGE]. A review
 * with a track says "Has a track", which scrolls to it in Tracks: the sheet
 * is too tight to draw it in the review too. [showTrack] shows one on the map.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FromHikers(hikers: RouteHikers, showTrack: (PostedTrack) -> Unit) {
    val reviews = hikers.reviews.items
    val tracks = hikers.tracks.items
    if (reviews.isEmpty() && tracks.isEmpty()) return
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(hikers.section).testTag("from-hikers"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("From hikers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        hikers.stats?.recentTags?.takeIf { it.isNotEmpty() }?.let { tags ->
            Text(
                "Reported in the last month: " + tags.take(RECENT_TAGS).joinToString(", ") { TAG_LABELS[it] ?: it },
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
        }
        for (p in reviews) {
            androidx.compose.runtime.key(p.uri) { ReviewCard(hikers, p) }
        }
        More(hikers.reviews, "reviews") { scope.launch { hikers.reviews.more() } }
        if (tracks.isNotEmpty()) {
            Text("Tracks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            for (p in tracks) {
                androidx.compose.runtime.key(p.uri) { TrackCard(hikers, p, showTrack) }
            }
        }
        More(hikers.tracks, "tracks") { scope.launch { hikers.tracks.more() } }
    }
}

private val TAG_LABELS = Posts.TAGS.toMap()

@Composable
private fun More(pages: PostPages<*>, what: String, onMore: () -> Unit) {
    if (pages.cursor == null) return
    TextButton(onClick = onMore, enabled = !pages.busy) { Text(if (pages.busy) "Loading…" else "More $what") }
}

/** A post's card: who and when, [body], and its licence and Report. */
@Composable
private fun PostCard(
    hikers: RouteHikers, post: IndexerClient.Post<*>, at: String, title: String?, license: String?,
    modifier: Modifier = Modifier, body: @Composable () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            val a = post.author
            if (a.handle != null) {
                Text(
                    "@${a.handle}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false).clickable { hikers.actions.openUrl(Indexer.profileUrl(a.handle)) },
                )
            } else {
                // No handle that resolves back to them: nothing to link to.
                Text("${a.did.take(16)}…", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            val rest = listOfNotNull(faveDay(at).takeIf { it.isNotEmpty() }, title?.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (rest.isNotEmpty()) Text(" · $rest", style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        }
        body()
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "Shared under ${Posts.LICENSE_LABEL[license ?: "hikeratlas-only"] ?: license}",
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { hikers.actions.openUrl(Indexer.reportUrl(post.uri)) }) {
                Text("Report", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable
private fun ReviewCard(hikers: RouteHikers, post: IndexerClient.Post<Profiles.Review>) {
    val r = post.record
    val pds by produceState(post.author.pds, post.author) { value = hikers.pdsOf(post.author) }
    // Only a track in the author's own repository: its GPX is a blob on their PDS.
    val uri = r.trackUri?.takeIf { u ->
        IndexerClient.parseAtUri(u)?.let { it.first == post.author.did && it.second == Posts.TRACK } == true
    }
    val loaded = uri?.let { u -> hikers.tracks.items.firstOrNull { it.uri == u } }
    var fetched by remember(uri) { mutableStateOf<IndexerClient.Post<Profiles.Track>?>(null) }
    LaunchedEffect(uri, pds, loaded != null) {
        val p = pds
        if (loaded != null || uri == null || p == null || fetched != null) return@LaunchedEffect
        hikers.actions.getTrack(p, uri)?.let { fetched = IndexerClient.Post(it.uri, it.cid, post.author, it) }
    }
    val track = loaded ?: fetched
    PostCard(hikers, post, r.hikedAt ?: r.createdAt, null, r.license) {
        if (r.tags.isNotEmpty()) Tags(r.tags)
        if (r.text.isNotBlank()) Text(r.text, style = MaterialTheme.typography.bodyMedium)
        pds?.let { if (r.photos.isNotEmpty()) PhotoGrid(it, post.author.did, r.photos, hikers.actions.openUrl) }
        if (track != null) {
            Text(
                "Has a track", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    hikers.tracks.add(track)
                    hikers.goTo = track.uri
                }.padding(vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackCard(hikers: RouteHikers, post: IndexerClient.Post<Profiles.Track>, showTrack: (PostedTrack) -> Unit) {
    val t = post.record
    val pds by produceState(post.author.pds, post.author) { value = hikers.pdsOf(post.author) }
    val here = remember { BringIntoViewRequester() }
    if (hikers.goTo == post.uri) {
        LaunchedEffect(Unit) {
            // Laid out first: it may have just been added.
            withFrameNanos {}
            here.toTop()
            hikers.goTo = null
        }
    }
    PostCard(hikers, post, t.startedAt ?: t.createdAt, t.title, t.license, Modifier.bringIntoViewRequester(here).testTag("track-${post.uri}")) {
        pds?.let { p ->
            TrackBox(t, { hikers.actions.gpx(p, post.author.did, t) }, showTrack, hikers.actions.shareGpx, hikers.lines)
            if (t.photos.isNotEmpty()) PhotoGrid(p, post.author.did, t.photos, hikers.actions.openUrl)
        }
    }
}
