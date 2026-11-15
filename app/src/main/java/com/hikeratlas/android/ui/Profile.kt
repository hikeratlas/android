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
import com.hikeratlas.android.routes.KeyedHike
import com.hikeratlas.android.routes.KeyedSearch
import com.hikeratlas.android.track.TrackMeasure
import com.hikeratlas.android.track.TrackStore
import com.hikeratlas.android.units.dist
import com.hikeratlas.android.units.elev
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A post to show, from "See post" on a posted track: the one whose track
 * or review is this.
 */
data class PostRef(val trackUri: String?, val reviewUri: String?) {
    fun matches(p: Profiles.Post) =
        (trackUri != null && p.track?.uri == trackUri) || (reviewUri != null && p.review?.uri == reviewUri)

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
    /** Deletes one of your own posts; null where the profile isn't yours (no Delete). */
    val delete: (suspend (Profiles.Post) -> Unit)?,
    val openHike: (KeyedHike) -> Unit,
    val showTrack: (PostedTrack) -> Unit,
    val shareGpx: (title: String, gpx: String) -> Unit,
    /** A photo at full size (in the browser, as the website links it). */
    val openPhoto: (String) -> Unit,
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
 * · 2 h 5 min", as the website's TrackView.
 */
internal fun trackFacts(t: Profiles.Track): String = listOfNotNull(
    t.distance?.let { dist(it / 1000.0) },
    t.gain?.let { "↑ ${elev(it)}" },
    t.duration?.let { durationText(it.toLong()) },
).joinToString(" · ")

/** A GPX's line for a thumbnail: each segment's points, thinned to about [n] in all, as the website's `lines`. */
internal fun gpxLines(text: String, n: Int = 400): List<List<List<Double>>> {
    val segs = TrackMeasure.parse(text)
    val total = segs.sumOf { it.size }
    val step = maxOf(1, total / n)
    return segs.map { s -> s.filterIndexed { i, _ -> i % step == 0 || i == s.size - 1 }.map { listOf(it.lon, it.lat) } }
}

/** A post's title: its hike's, or why there's none. */
internal fun postTitle(p: Profiles.Post, found: HikesFound): String {
    val key = p.routeKey ?: return p.track?.title?.takeIf { it.isNotBlank() } ?: "A hike"
    found.search?.found?.get(key)?.let { return it.title }
    return when {
        !found.done -> "Finding the hike…"
        // Gone only after an exact search said so.
        found.canFind && found.search != null && key !in found.search.unsure && Profiles.isRouteKey(key) -> "A hike no longer on Hiker Atlas"
        else -> "A hike that couldn't be looked up"
    }
}

/**
 * Your profile, as the website's /@handle: Overview (posts, newest hike
 * first, and faved hikes) and, when [settings] is given, Settings.
 */
@Composable
fun ProfileScreen(
    target: ProfileTarget,
    actions: ProfileActions,
    onBack: () -> Unit,
    settings: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    var tab by remember(target.did) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                target.handle?.let { "@$it" } ?: target.did, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, maxLines = 1,
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
            Overview(target, actions)
        }
    }
}

@Composable
private fun Overview(target: ProfileTarget, actions: ProfileActions) {
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember(target.did) { mutableStateOf<ProfileLoad>(ProfileLoad.Loading) }
    LaunchedEffect(target.did, attempt) {
        load = ProfileLoad.Loading
        load = try {
            ProfileLoad.Ready(actions.load(target))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProfileLoad.Failed(e.message ?: e.toString())
        }
    }
    when (val l = load) {
        ProfileLoad.Loading -> Text(
            "Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        is ProfileLoad.Failed -> Column(Modifier.padding(20.dp)) {
            Text("Couldn't load the profile: ${l.message}", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { attempt++ }) { Text("Try again") }
        }
        is ProfileLoad.Ready -> PostList(l.profile, target.highlight, actions) { load = ProfileLoad.Ready(it) }
    }
}

@Composable
private fun PostList(profile: Profiles.Profile, highlight: PostRef?, actions: ProfileActions, onChange: (Profiles.Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    var found by remember(profile.did) { mutableStateOf(HikesFound(null, false, actions.canFind)) }
    val keys = remember(profile) {
        (profile.faves.map { it.key } + profile.posts.mapNotNull { it.routeKey }).filter(Profiles::isRouteKey).distinct()
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
    // Thumbnails' lines, by track URI, read once.
    val lines = remember(profile.did) { mutableStateMapOf<String, List<List<List<Double>>>>() }
    var confirming by remember { mutableStateOf<Profiles.Post?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<Pair<String, String>?>(null) }
    val list = rememberLazyListState()
    val mine = actions.delete != null
    // "See post": scrolled to it once it's listed. Items before the posts: the header and the count.
    val shown = highlight?.let { h -> profile.posts.indexOfFirst(h::matches).takeIf { it >= 0 } }
    LaunchedEffect(profile.did, shown) { if (shown != null) list.scrollToItem(2 + shown) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        item(key = "posts-header") {
            Text(
                "Hikes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        item(key = "posts-count") {
            val n = profile.posts.size
            Text(
                when {
                    n > 0 -> "$n ${if (n == 1) "post" else "posts"}"
                    mine -> "Nothing posted yet. Open a hike and choose Post to add a review or your track."
                    else -> "Nothing posted yet."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        itemsIndexed(profile.posts, key = { _, p -> "post-${p.uri}" }) { i, p ->
            PostCard(
                profile, p, found, actions, lines,
                highlighted = i == shown,
                deleting = deleting == p.uri,
                error = failed?.takeIf { it.first == p.uri }?.second,
                onDelete = { confirming = p },
            )
        }
        favedHikesSection(
            FavedState.Ready(
                profile.faves, if (found.done) found.search?.found.orEmpty() else null, found.canFind, found.search?.unsure.orEmpty(),
            ),
            onOpen = actions.openHike, onRetry = {},
        )
        item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
    }
    confirming?.let { p ->
        val what = when {
            p.review != null && p.track != null -> "this review and its track"
            p.review != null -> "this review"
            else -> "this track"
        }
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Delete $what?") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    val del = actions.delete ?: return@TextButton
                    deleting = p.uri
                    failed = null
                    scope.launch {
                        try {
                            del(p)
                            onChange(profile.copy(posts = profile.posts.filter { it.uri != p.uri }))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = p.uri to "Deleting didn't work: ${e.message ?: e}"
                        } finally {
                            deleting = null
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostCard(
    profile: Profiles.Profile,
    p: Profiles.Post,
    found: HikesFound,
    actions: ProfileActions,
    lines: MutableMap<String, List<List<List<Double>>>>,
    highlighted: Boolean,
    deleting: Boolean,
    error: String?,
    onDelete: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val hike = p.routeKey?.let { found.search?.found?.get(it) }
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
                postTitle(p, found), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (hike != null || p.routeKey == null) MaterialTheme.colorScheme.onSurface else muted,
                modifier = if (hike != null) Modifier.clickable { actions.openHike(hike) } else Modifier,
            )
            val sub = listOfNotNull(hike?.parkName?.takeIf { it.isNotEmpty() }, faveDay(p.at).takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        p.review?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (g in tags) {
                    Text(
                        TAG_LABEL[g] ?: g, style = MaterialTheme.typography.labelMedium, color = Color(0xFF14532D),
                        modifier = Modifier.background(Color(0xFFF0FDF4), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
        p.review?.let { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
        val photos = p.photos
        if (photos.isNotEmpty()) PhotoGrid(profile, photos, actions)
        p.track?.let { TrackBox(profile, it, actions, lines) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val lic = p.license
            Text(
                "Shared under ${Posts.LICENSE_LABEL[lic ?: "hikeratlas-only"] ?: lic}${if (lic == null) " (no licence given)" else ""}",
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f),
            )
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

@Composable
private fun PhotoGrid(profile: Profiles.Profile, photos: List<Profiles.Photo>, actions: ProfileActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in photos.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (ph in row) {
                    val url = Profiles.blobUrl(profile.pds, profile.did, ph.image.cid)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable { actions.openPhoto(url) }
                            .semantics { contentDescription = ph.alt?.takeIf { it.isNotBlank() } ?: "Photo" },
                    ) {
                        RemoteThumb(url, Modifier.fillMaxSize())
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** A track's line, its numbers, and its GPX: on the map, or saved or shared. */
@Composable
private fun TrackBox(profile: Profiles.Profile, t: Profiles.Track, actions: ProfileActions, lines: MutableMap<String, List<List<List<Double>>>>) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(t.uri) {
        if (t.uri in lines) return@LaunchedEffect
        try {
            val text = actions.gpx(profile, t)
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
                f(actions.gpx(profile, t))
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
                        actions.showTrack(PostedTrack(title, facts, segs, gpx))
                    }
                }, enabled = busy == null) { Text(if (busy == "map") "Loading…" else "Show on map") }
                TextButton(onClick = { withGpx("share") { actions.shareGpx(title, it) } }, enabled = busy == null) {
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

/** The account card's way to your profile. */
@Composable
internal fun ProfileLink(onOpen: () -> Unit) {
    Item("Your profile", "Your posts, faved hikes and settings", onClick = onOpen)
}

/** What "See post" opens: your profile at that post; null if signed out (or another account's post). */
internal fun seePostTarget(did: String?, handle: String?, posted: TrackStore.Posted?): ProfileTarget? {
    did ?: return null
    // A track posted from another account isn't on this profile: open it without scrolling.
    val ref = posted?.let(PostRef::of)?.takeIf { r -> listOfNotNull(r.trackUri, r.reviewUri).all { it.startsWith("at://$did/") } }
    return ProfileTarget(did, handle, ref)
}
