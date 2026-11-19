package com.hikeratlas.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.map.RemoteThumb
import com.hikeratlas.android.map.Thumbs
import com.hikeratlas.android.routes.KeyedHike
import com.hikeratlas.android.routes.KeyedSearch
import com.hikeratlas.android.track.TrackMeasure
import com.hikeratlas.android.track.TrackStore
import com.hikeratlas.android.track.stepsText
import com.hikeratlas.android.units.dist
import com.hikeratlas.android.units.elev
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A post to show, from "See post" on a posted track: the profile lands on
 * its track, in Tracks (every posted track is listed there, with a review
 * or not).
 */
data class PostRef(val trackUri: String?, val reviewUri: String?) {
    fun matches(t: Profiles.Track) = trackUri != null && t.uri == trackUri

    companion object {
        fun of(posted: TrackStore.Posted) = PostRef(posted.trackUri, posted.reviewUri)
    }
}

/** Whose profile is open (the app opens only your own for now), and a post to scroll to. */
data class ProfileTarget(val did: String, val handle: String?, val highlight: PostRef? = null)

/** A posted track, read from its GPX, to show on the map as the app's own tracks are. */
data class PostedTrack(val title: String, val facts: String, val segments: List<List<List<Double>>>, val gpx: String)

/** What the profile reads and does. */
class ProfileActions(
    val load: suspend (ProfileTarget) -> Profiles.Profile,
    /** The hikes route keys name (as faves are found). */
    val findHikes: suspend (Collection<String>) -> KeyedSearch,
    /** False where keys can't be searched at all (no H3 here): nothing is then said to be gone. */
    val canFind: Boolean,
    val gpx: suspend (Profiles.Profile, Profiles.Track) -> String,
    /** Deletes one of your own posts (a review and its track, or a track and its reviews); null where the profile isn't yours (no Delete). */
    val delete: (suspend (Profiles.Deletion) -> Unit)?,
    val openHike: (KeyedHike) -> Unit,
    val showTrack: (PostedTrack) -> Unit,
    val shareGpx: (title: String, gpx: String) -> Unit,
    /** Opens a post's photos in the viewer. */
    val openPhotos: (PhotoSet) -> Unit,
    /**
     * Saves an edit of one of your own reviews over it (putRecord, swapping
     * the CID read), giving its new address and the record written; null
     * where the profile isn't yours (no Edit).
     */
    val edit: (suspend (Profiles.Review, Posts.ReviewEdit) -> Posts.Edited)? = null,
)

internal sealed interface ProfileLoad {
    data object Loading : ProfileLoad
    data class Failed(val message: String) : ProfileLoad
    data class Ready(val profile: Profiles.Profile) : ProfileLoad
}

/** The hikes a profile's keys name: [search] null while looking, or if it couldn't be done. */
internal data class HikesFound(val search: KeyedSearch?, val done: Boolean, val canFind: Boolean)

/**
 * A track's numbers, from its record, in the viewer's units: "7.3 km · ↑ 612 m
 * · 2 h 5 min · 3,120 steps", as the website's TrackView.
 */
internal fun trackFacts(t: Profiles.Track): String = listOfNotNull(
    t.distance?.let { dist(it / 1000.0) },
    t.gain?.let { "↑ ${elev(it)}" },
    t.duration?.let { durationText(it.toLong()) },
    t.steps?.let(::stepsText),
).joinToString(" · ")

/** A GPX's line for a thumbnail: each segment's points, thinned to about [n] in all, as the website's `lines`. */
internal fun gpxLines(text: String, n: Int = 400): List<List<List<Double>>> {
    val segs = TrackMeasure.parse(text)
    val total = segs.sumOf { it.size }
    val step = maxOf(1, total / n)
    return segs.map { s -> s.filterIndexed { i, _ -> i % step == 0 || i == s.size - 1 }.map { listOf(it.lon, it.lat) } }
}

/** A review's or track's title: its hike's, or why there's none; without a hike, [own] (a track's title). */
internal fun postTitle(routeKey: String?, own: String?, found: HikesFound): String {
    val key = routeKey ?: return own?.takeIf { it.isNotBlank() } ?: "A hike"
    found.search?.found?.get(key)?.let { return it.title }
    return when {
        !found.done -> "Finding the hike…"
        // Gone only after an exact search said so.
        found.canFind && found.search != null && key !in found.search.unsure && Profiles.isRouteKey(key) -> "A hike no longer on Hiker Atlas"
        else -> "A hike that couldn't be looked up"
    }
}

/**
 * Your profile, as the website's /@handle: Overview (reviews, every track,
 * and faved hikes; your reviews can be edited, your posts deleted) and,
 * when [settings] is given, Settings.
 * [onBack] null: it's a tab of its own (the You tab), headed by [avatar]
 * rather than a back arrow.
 * [waiting]: posts waiting on the phone to go up, listed above the posts;
 * [refreshKey] changes when one has, so the posts are read again.
 */
@Composable
fun ProfileScreen(
    target: ProfileTarget,
    actions: ProfileActions,
    onBack: (() -> Unit)?,
    settings: (@Composable ColumnScope.() -> Unit)? = null,
    waiting: WaitingActions? = null,
    refreshKey: Int = 0,
    avatar: String? = null,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    // A "See post" lands on Overview, wherever the profile was left.
    var tab by remember(target.did, target.highlight) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).statusBarsPadding()) {
        val header = if (onBack != null) Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)
        else Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        Row(Modifier.fillMaxWidth().then(header), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            } else {
                Avatar(avatar, target.handle ?: "", 48)
            }
            Text(
                target.handle?.let { "@$it" } ?: target.did, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = if (onBack != null) Modifier else Modifier.padding(start = 12.dp),
            )
        }
        if (settings != null) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Overview") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
            }
        }
        if (tab == 1 && settings != null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = settings,
            )
        } else {
            Overview(target, actions, waiting, refreshKey)
        }
    }
}

@Composable
private fun Overview(target: ProfileTarget, actions: ProfileActions, waiting: WaitingActions?, refreshKey: Int) {
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember(target.did) { mutableStateOf<ProfileLoad>(ProfileLoad.Loading) }
    LaunchedEffect(target.did, attempt, refreshKey) {
        // Read again as a waiting post went up: the posts shown stay meanwhile, and if it fails.
        val shown = load as? ProfileLoad.Ready
        if (shown == null) load = ProfileLoad.Loading
        load = try {
            ProfileLoad.Ready(actions.load(target))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            shown ?: ProfileLoad.Failed(e.message ?: e.toString())
        }
    }
    when (val l = load) {
        is ProfileLoad.Ready -> PostList(l.profile, target.highlight, actions, waiting, onReload = { attempt++ }) { load = ProfileLoad.Ready(it) }
        // Waiting posts show without the profile: it's often without signal that they're looked at.
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            waiting?.let { WaitingPosts(it) }
            if (l is ProfileLoad.Failed) {
                Column(Modifier.padding(20.dp)) {
                    Text("Couldn't load the profile: ${l.message}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text("Try again") }
                }
            } else {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
        }
    }
}

/** What a Delete asks before deleting: what goes with the post. */
internal data class Confirming(val uri: String, val isReview: Boolean, val deletion: Profiles.Deletion) {
    val title get() = if (isReview) "Delete this review?" else "Delete this track?"
    /** Said only when more goes than the post itself. */
    val also: String?
        get() = when {
            isReview && deletion.hasTrack -> "Deleting this review also deletes its track."
            !isReview && deletion.reviews.size == 1 -> "Deleting this track also deletes its review."
            !isReview && deletion.reviews.size > 1 -> "Deleting this track also deletes its reviews."
            else -> null
        }
}

@Composable
private fun PostList(
    profile: Profiles.Profile,
    highlight: PostRef?,
    actions: ProfileActions,
    waiting: WaitingActions?,
    onReload: () -> Unit = {},
    onChange: (Profiles.Profile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var found by remember(profile.did) { mutableStateOf(HikesFound(null, false, actions.canFind)) }
    val keys = remember(profile) {
        (profile.faves.map { it.key } + profile.reviews.mapNotNull { it.routeKey } + profile.tracks.mapNotNull { it.routeKey })
            .filter(Profiles::isRouteKey).distinct()
    }
    LaunchedEffect(keys) {
        if (keys.isEmpty() || !actions.canFind) {
            found = HikesFound(KeyedSearch(emptyMap(), emptySet()), true, actions.canFind)
            return@LaunchedEffect
        }
        found = try {
            HikesFound(actions.findHikes(keys), true, true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HikesFound(null, true, false)
        }
    }
    // Thumbnails' lines, by track URI, read once: a review's track and the same track in Tracks share one.
    val lines = remember(profile.did) { mutableStateMapOf<String, List<List<List<Double>>>>() }
    var confirming by remember { mutableStateOf<Confirming?>(null) }
    // The URIs of the records being deleted, so the review and its track both say so.
    var deleting by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failed by remember { mutableStateOf<Pair<String, String>?>(null) }
    // The review being edited, as it was when Edit was pressed: its CID is
    // what the save swaps, so a profile read again meanwhile can't make it
    // overwrite a change it hasn't shown.
    var editing by remember { mutableStateOf<Profiles.Review?>(null) }
    val list = rememberLazyListState()
    val mine = actions.delete != null
    val empty = profile.reviews.isEmpty() && profile.tracks.isEmpty()
    // "See post": scrolled to its track once it's listed. Items before the
    // tracks: the waiting posts, Reviews' header and count, the reviews, and
    // Tracks' header and count.
    val shown = highlight?.let { h -> profile.tracks.indexOfFirst(h::matches).takeIf { it >= 0 } }
    LaunchedEffect(profile.did, shown) {
        if (shown != null) list.scrollToItem(waitingItemCount(waiting) + 2 + profile.reviews.size + 2 + shown)
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        waiting?.let { waitingPostsSection(it) }
        if (empty) {
            sectionHeader("posts", "Hikes")
            sectionNote(
                "posts",
                if (mine) "Nothing posted yet. Open a hike and choose Post to add a review or your track." else "Nothing posted yet.",
            )
        } else {
            sectionHeader("reviews", "Reviews")
            sectionNote("reviews", count(profile.reviews.size, "review", "No reviews yet."))
            items(profile.reviews, key = { "review-${it.uri}" }) { r ->
                // Titled as the website does: its hike (its own, or its track's), else its track's title.
                val track = profile.trackOf(r)
                PostCard(
                    profile, r.routeKey ?: track?.routeKey, track?.title, r.hikedAt ?: r.createdAt, r.license, found, actions,
                    highlighted = false,
                    deleting = r.uri in deleting,
                    error = failed?.takeIf { it.first == r.uri }?.second,
                    onDelete = { confirming = Confirming(r.uri, true, profile.deletion(r)) },
                    onEdit = actions.edit?.let { { editing = r } },
                ) {
                    r.tags.takeIf { it.isNotEmpty() }?.let { Tags(it) }
                    Text(r.text, style = MaterialTheme.typography.bodyMedium)
                    if (r.photos.isNotEmpty()) PhotoGrid(PhotoSet(profile.pds, profile.did, profile.handle, r.license, r.photos), actions.openPhotos)
                    // Its track drawn here too: the profile is a full page, as the website's.
                    track?.let { TrackBox(profile, it, actions, lines) }
                }
            }
            sectionHeader("tracks", "Tracks")
            sectionNote("tracks", count(profile.tracks.size, "track", "No tracks yet."))
            itemsIndexed(profile.tracks, key = { _, t -> "track-${t.uri}" }) { i, t ->
                PostCard(
                    profile, t.routeKey, t.title, t.startedAt ?: t.createdAt, t.license, found, actions,
                    highlighted = i == shown,
                    deleting = t.uri in deleting,
                    error = failed?.takeIf { it.first == t.uri }?.second,
                    onDelete = { confirming = Confirming(t.uri, false, profile.deletion(t)) },
                ) {
                    if (t.photos.isNotEmpty()) PhotoGrid(PhotoSet(profile.pds, profile.did, profile.handle, t.license, t.photos), actions.openPhotos)
                    TrackBox(profile, t, actions, lines)
                }
            }
        }
        favedHikesSection(
            FavedState.Ready(
                profile.faves, if (found.done) found.search?.found.orEmpty() else null, found.canFind, found.search?.unsure.orEmpty(),
            ),
            onOpen = actions.openHike, onRetry = {},
        )
        item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
    }
    val edit = actions.edit
    val edited = editing
    if (edit != null && edited != null) {
        EditReviewDialog(edited, profile.pds, profile.did, save = { e ->
            try {
                val saved = edit(edited, e)
                // Shown as saved, from what was written (photos' sizes measured included), at once.
                val ref = saved.ref
                Profiles.parseReview(buildJsonObject { put("uri", ref.uri); put("cid", ref.cid); put("value", saved.record) })
                    ?.let { onChange(profile.with(it)) }
            } catch (x: com.hikeratlas.android.atproto.PostException) {
                // Changed meanwhile: read it again, so the next edit starts from what's there now.
                if ((x.cause as? com.hikeratlas.android.atproto.XrpcException)?.error == "InvalidSwap") onReload()
                throw x
            }
        }, onClose = { editing = null })
    }
    confirming?.let { c ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(c.title) },
            text = c.also?.let { { Text(it) } },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    val del = actions.delete ?: return@TextButton
                    val uris = (c.deletion.reviews.map { it.uri } + c.deletion.tracks.map { it.uri }).toSet()
                    deleting = uris
                    failed = null
                    scope.launch {
                        try {
                            del(c.deletion)
                            onChange(profile.without(c.deletion))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = c.uri to "Deleting didn't work: ${e.message ?: e}"
                        } finally {
                            deleting = emptySet()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Keep") } },
        )
    }
}

private fun count(n: Int, one: String, none: String) = if (n == 0) none else "$n ${if (n == 1) one else one + "s"}"

private fun LazyListScope.sectionHeader(key: String, title: String) = item(key = "$key-header") {
    Text(
        title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun LazyListScope.sectionNote(key: String, text: String) = item(key = "$key-count") {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

/** A review's tags, as the website's green pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Tags(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in tags) {
            Text(
                TAG_LABEL[g] ?: g, style = MaterialTheme.typography.labelMedium, color = Color(0xFF14532D),
                modifier = Modifier.background(Color(0xFFF0FDF4), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** A review or a track: its hike and day, [body], its licence, Edit (your reviews) and Delete. */
@Composable
private fun PostCard(
    profile: Profiles.Profile,
    routeKey: String?,
    ownTitle: String?,
    at: String,
    license: String?,
    found: HikesFound,
    actions: ProfileActions,
    highlighted: Boolean,
    deleting: Boolean,
    error: String?,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val hike = routeKey?.let { found.search?.found?.get(it) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .let { if (highlighted) it.testTag("highlighted-post") else it }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                postTitle(routeKey, ownTitle, found), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (hike != null || routeKey == null) MaterialTheme.colorScheme.onSurface else muted,
                modifier = if (hike != null) Modifier.clickable { actions.openHike(hike) } else Modifier,
            )
            val sub = listOfNotNull(hike?.parkName?.takeIf { it.isNotEmpty() }, faveDay(at).takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        body()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Shared under ${Posts.LICENSE_LABEL[license ?: "hikeratlas-only"] ?: license}${if (license == null) " (no licence given)" else ""}",
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f),
            )
            if (onEdit != null) TextButton(onClick = onEdit, enabled = !deleting) { Text("Edit") }
            if (actions.delete != null) {
                TextButton(onClick = onDelete, enabled = !deleting) {
                    Text(if (deleting) "Deleting…" else "Delete", color = if (deleting) muted else MaterialTheme.colorScheme.error)
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private val TAG_LABEL = Posts.TAGS.toMap()

/**
 * A post's photos, three to a row, square and cropped as the website's:
 * thumbnails through the indexer, else from its author's PDS; tapped, in
 * the viewer from that one.
 */
@Composable
internal fun PhotoGrid(set: PhotoSet, openPhotos: (PhotoSet) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((r, row) in set.photos.chunked(3).withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((i, ph) in row.withIndex()) {
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable { openPhotos(set.copy(start = r * 3 + i)) }
                            .semantics { contentDescription = ph.alt?.takeIf { it.isNotBlank() } ?: "Photo" },
                    ) {
                        RemoteThumb(set.thumb(ph), Modifier.fillMaxSize(), Thumbs.GRID_PX, fallback = set.url(ph))
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TrackBox(profile: Profiles.Profile, t: Profiles.Track, actions: ProfileActions, lines: MutableMap<String, List<List<List<Double>>>>) =
    TrackBox(t, { actions.gpx(profile, t) }, actions.showTrack, actions.shareGpx, lines)

/**
 * A track's line, its numbers, and its GPX: on the map, or saved or shared.
 * [gpx] reads it from its author's PDS; [lines] keeps thumbnails by URI.
 */
@Composable
internal fun TrackBox(
    t: Profiles.Track,
    gpx: suspend () -> String,
    showTrack: (PostedTrack) -> Unit,
    shareGpx: (title: String, gpx: String) -> Unit,
    lines: MutableMap<String, List<List<List<Double>>>>,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(t.uri) {
        if (t.uri in lines) return@LaunchedEffect
        try {
            val text = gpx()
            lines[t.uri] = withContext(Dispatchers.Default) { gpxLines(text) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Just no thumbnail: the buttons say what's wrong if pressed.
        }
    }
    val facts = trackFacts(t)
    val title = t.title?.takeIf { it.isNotBlank() } ?: "Track"
    fun withGpx(what: String, f: (String) -> Unit) {
        busy = what
        error = null
        scope.launch {
            try {
                f(gpx())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't get the track: ${e.message ?: e}"
            } finally {
                busy = null
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackLine(lines[t.uri])
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("GPX track", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = {
                    withGpx("map") { gpx ->
                        val segs = TrackMeasure.parse(gpx).map { s -> s.map { listOf(it.lon, it.lat) } }
                        showTrack(PostedTrack(title, facts, segs, gpx))
                    }
                }, enabled = busy == null) { Text(if (busy == "map") "Loading…" else "Show on map") }
                TextButton(onClick = { withGpx("share") { shareGpx(title, it) } }, enabled = busy == null) {
                    Text(if (busy == "share") "Loading…" else "Share GPX")
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** The track's shape, as the website's TrackLine: fitted in a square, longitudes scaled by latitude. */
@Composable
private fun TrackLine(segs: List<List<List<Double>>>?) {
    val m = Modifier.size(72.dp)
    val pts = segs?.flatten().orEmpty()
    if (pts.size < 2) {
        Box(m.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)))
        return
    }
    Canvas(m.background(Color.White, RoundedCornerShape(4.dp))) {
        val lat0 = pts.sumOf { it[1] } / pts.size
        val k = Math.cos(Math.toRadians(lat0))
        val xs = pts.map { it[0] * k }
        val ys = pts.map { it[1] }
        val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
        val span = maxOf(x1 - x0, y1 - y0).takeIf { it > 0 } ?: 1e-6
        val pad = size.width / 12
        val s = (size.width - 2 * pad) / span
        val ox = pad + ((size.width - 2 * pad) - (x1 - x0) * s) / 2
        val oy = pad + ((size.height - 2 * pad) - (y1 - y0) * s) / 2
        val path = Path()
        for (seg in segs.orEmpty()) seg.forEachIndexed { i, p ->
            val o = Offset((ox + (p[0] * k - x0) * s).toFloat(), (oy + (y1 - p[1]) * s).toFloat())
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, Color(0xFFC026D3), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** What "See post" opens: your profile at that post; null if signed out (or another account's post). */
internal fun seePostTarget(did: String?, handle: String?, posted: TrackStore.Posted?): ProfileTarget? {
    did ?: return null
    // A track posted from another account isn't on this profile: open it without scrolling.
    val ref = posted?.let(PostRef::of)?.takeIf { r -> listOfNotNull(r.trackUri, r.reviewUri).all { it.startsWith("at://$did/") } }
    return ProfileTarget(did, handle, ref)
}
