package com.hikeratlas.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.Photos
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.SignedOutException
import com.hikeratlas.android.track.GpxStats
import com.hikeratlas.android.track.NearbyHike
import com.hikeratlas.android.track.TrackStore
import com.hikeratlas.android.track.statsLine
import com.hikeratlas.android.units.dist
import com.hikeratlas.android.units.elev
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A hike a post is about: its key from the build, its title, and its line (for the tracks near it). */
data class PostHike(val key: String, val title: String, val line: List<List<Double>> = emptyList())

/** What the form posts: a review ([text]), a recorded track ([trackId]), or both. */
data class PostDraft(
    /** Null for a track posted without a hike. */
    val hike: PostHike?,
    /** The track record's title: the hike's, else the track's name. */
    val title: String,
    val text: String,
    val tags: List<String>,
    val day: LocalDate,
    val trackId: Long?,
    /** Prepared, with their descriptions; placed on the track as they're posted. */
    val photos: List<Posts.Photo> = emptyList(),
)

/** Photos for the form: prepared into the app's storage as they're added, dropped when removed or done with. */
class PhotoActions(
    /** Reads a picked or taken photo, prepares it (within 2 MB, EXIF kept) and keeps it on the phone. Throws if it can't. */
    val prepare: suspend (PhotoInput) -> Posts.Photo,
    val discard: (Posts.Photo) -> Unit,
    /** A small upright copy, for the form; null if there's none. */
    val thumbnail: suspend (Posts.Photo) -> ImageBitmap? = { null },
)

/** A photo in the form: being read ([photo] and [failed] null), ready, or unreadable. */
internal data class PhotoItem(val key: Long, val photo: Posts.Photo? = null, val failed: String? = null, val alt: String = "")

/**
 * Where a photo's location comes from, as the website's form says it: the
 * photo's own GPS, else its time on the track ([gpx], the track attached,
 * if any).
 */
internal fun whereText(p: PhotoItem, gpx: String?): String {
    val photo = p.photo
    return when {
        p.failed != null -> "Can't read this photo"
        photo == null -> "Reading…"
        photo.lat != null -> "Location from the photo"
        photo.takenAt != null && gpx != null && Photos.locate(gpx, photo.takenAt) != null -> "Placed on your track by its time"
        photo.takenAt != null && gpx != null -> "No location (not taken during your track)"
        photo.takenAt != null -> "No location (add your track to place it)"
        else -> "No location"
    }
}

/** Posting reviews and tracks, from a hike's sheet or a recorded track. */
class PostActions(
    /** Who's signed in, and signing in (offered when signed out). */
    val account: AccountActions,
    /** The recorded tracks one can attach, newest first. */
    val tracks: List<TrackStore.Track>,
    /** The licence new posts are shared under (the profile's), for the form's note. */
    val license: suspend () -> String,
    /** What a recorded track will post: its numbers, as the website measures its GPX. */
    val trackStats: suspend (Long) -> GpxStats?,
    /** The curated hikes near a recorded track, closest first. */
    val hikesNear: suspend (Long) -> List<NearbyHike>,
    /** The recorded tracks passing near a hike's line. */
    val tracksNear: suspend (List<List<Double>>) -> Set<Long> = { emptySet() },
    /**
     * Posts it, or keeps it on the phone to go up later (no signal);
     * [progress] hears what's under way. Throws saying what failed.
     */
    val post: suspend (draft: PostDraft, progress: (String) -> Unit) -> PostOutcome,
    /** Opens your profile in the app; at the post a posted track went up as, when given ("See post"). */
    val openProfile: (TrackStore.Posted?) -> Unit,
    /** Checks a posted track's post is still there, and clears the mark if it was deleted. */
    val verifyPosted: suspend (Long) -> Unit = {},
    /** Photos, if the form offers them. */
    val photos: PhotoActions? = null,
    /** A recorded track's GPX, for placing photos on it by their time. */
    val trackGpx: suspend (Long) -> String? = { null },
    val today: () -> LocalDate = { LocalDate.now() },
    /** The recorded tracks in a post waiting to go up: not offered again. */
    val waitingTracks: Set<Long> = emptySet(),
)

/** "2 h 5 min", as the website shows a track's duration. */
internal fun durationText(s: Long): String {
    val h = s / 3600
    val m = Math.round((s % 3600) / 60.0)
    if (h >= 48) return "${Math.round(s / 86400.0)} days"
    return if (h > 0) "$h h $m min" else "$m min"
}

/** A track's numbers as the post form shows them (the website's): 7.3 km · ↑ 612 m · 2 h 5 min. */
internal fun postStatsLine(s: GpxStats): String =
    listOfNotNull(dist(s.distance / 1000.0, 1), s.gain?.let { "↑ ${elev(it.toDouble())}" }, s.duration?.let(::durationText)).joinToString(" · ")

private val TRACK_DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
internal val FORM_DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

internal fun trackLabel(t: TrackStore.Track): String =
    listOf(t.name ?: "Track", TRACK_DAY.format(Instant.ofEpochMilli(t.started).atZone(ZoneId.systemDefault()))).joinToString(" · ")

/**
 * The hike sheet's "Hiked it?", as the website's PostButton: opens the
 * post form, or signed out, offers to sign in first.
 */
@Composable
fun PostSection(hike: PostHike, posts: PostActions) {
    var open by remember { mutableStateOf(false) }
    val state = posts.account.state
    Row(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Hiked it?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Post a review, photos or your track.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(
            onClick = { posts.account.clearError(); open = true },
            enabled = state !is AccountState.Loading && state !is AccountState.SigningIn,
        ) { Text("Post") }
    }
    if (open) {
        if (state is AccountState.In) PostDialog(hike, null, posts, onClose = { open = false })
        else SignInDialog(posts.account, onClose = { open = false }, why = "Sign in to post about ${hike.title}.")
    }
}

/**
 * "Post this track", from a recorded track: which curated hike it was (those
 * it walked), or none; then the post form with the track attached.
 */
@Composable
fun TrackPostDialog(trackId: Long, trackName: String, posts: PostActions, onClose: () -> Unit) {
    if (posts.account.state !is AccountState.In) {
        SignInDialog(posts.account, onClose = onClose, why = "Sign in to post your track.")
        return
    }
    // Whether it was waiting when the dialog opened: posting it from here
    // puts it in the outbox (waiting) before it goes up, and switching to
    // this message then took the form away and cancelled the upload (#99).
    val waiting = remember(trackId) { trackId in posts.waitingTracks }
    if (waiting) {
        // Posted twice it mustn't be: it's going up already.
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Waiting to post") },
            text = { Text("This track is in a post waiting to go up. See Waiting posts on your profile.") },
            confirmButton = { TextButton(onClick = { onClose(); posts.openProfile(null) }) { Text("See it") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        )
        return
    }
    var chosen by remember(trackId) { mutableStateOf(false) }
    // The hike chosen, or none.
    var hike by remember(trackId) { mutableStateOf<PostHike?>(null) }
    if (!chosen) {
        var matches by remember(trackId) { mutableStateOf<MatchState>(MatchState.Loading) }
        LaunchedEffect(trackId) {
            matches = try {
                MatchState.Ready(posts.hikesNear(trackId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MatchState.Failed(e.message ?: e.toString())
            }
        }
        Dialog(onDismissRequest = onClose) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                HikeChooser(matches, onNext = { hike = it; chosen = true }, onCancel = onClose)
            }
        }
    } else {
        PostDialog(hike, trackId, posts, onClose, trackName = trackName)
    }
}

/** The hikes a track walked, being looked for. */
sealed interface MatchState {
    data object Loading : MatchState
    data class Failed(val message: String) : MatchState
    data class Ready(val matches: List<NearbyHike>) : MatchState
}

/** "On your track", or how far from it: "400 m from your track". */
internal fun nearText(metres: Double): String =
    if (metres < 50) "on your track" else "${com.hikeratlas.android.units.elev(Math.round(metres / 50.0) * 50.0)} from your track"

/**
 * Which hike a track was: every curated hike near it, closest first, or
 * none. No guessing which it was: the closest is chosen to start with
 * only when the track is on it. (The dialog's content, apart, to test.)
 */
@Composable
internal fun HikeChooser(state: MatchState, onNext: (PostHike?) -> Unit, onCancel: () -> Unit) {
    // "None" is the empty key.
    var picked by remember(state) {
        mutableStateOf((state as? MatchState.Ready)?.matches?.firstOrNull()?.takeIf { it.metres < 50 }?.key ?: "")
    }
    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Which hike was it?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        // The choices scroll; the buttons stay below them.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when (state) {
                MatchState.Loading -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Finding the curated hikes near your track…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is MatchState.Failed -> Text(
                    "Couldn't look for the hikes near your track (${state.message}). You can post it on its own.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
                )
                is MatchState.Ready -> {
                    if (state.matches.isEmpty()) Text(
                        "No curated hike comes within ${com.hikeratlas.android.units.dist(com.hikeratlas.android.track.NEAR_METRES / 1000, 0)} of your track: post it on its own.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    for (m in state.matches) {
                        Choice(
                            m.hike.title, listOf(m.hike.parkName, nearText(m.metres)).filter { it.isNotEmpty() }.joinToString(" · "),
                            picked == m.key,
                        ) { picked = m.key }
                    }
                }
            }
            if (state !is MatchState.Loading) Choice("None of these", "Post the track on its own, not tied to a hike", picked == "") { picked = "" }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val m = (state as? MatchState.Ready)?.matches?.firstOrNull { it.key == picked }
                    onNext(m?.let { PostHike(it.key, it.hike.title) })
                },
                enabled = state !is MatchState.Loading,
            ) { Text("Next") }
        }
    }
}

@Composable
private fun Choice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The post form, full screen: as the website's PostDialog, with the
 * phone's recorded tracks in place of a GPX file. [fixedTrack]: posted from
 * that track (it's attached, and can't be changed). Can't be dismissed
 * while posting, which would stop it halfway.
 */
@Composable
fun PostDialog(hike: PostHike?, fixedTrack: Long?, posts: PostActions, onClose: () -> Unit, trackName: String? = null) {
    var busy by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            PostForm(hike, fixedTrack, posts, onClose, trackName, onBusy = { busy = it })
        }
    }
}

/** The form itself (apart, to test: Robolectric never lets a text field in a dialog go idle). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PostForm(
    hike: PostHike?,
    fixedTrack: Long?,
    posts: PostActions,
    onClose: () -> Unit,
    trackName: String? = null,
    onBusy: (Boolean) -> Unit = {},
    /** Choosing and taking photos (apart, so a test can stand in for the system's pickers). */
    photoButtons: @Composable (room: Int, enabled: Boolean, onPicked: (List<PhotoInput>) -> Unit) -> Unit =
        { room, enabled, onPicked -> PhotoButtons(room, enabled, onPicked) },
) {
    val scope = rememberCoroutineScope()
    val today = remember { posts.today() }
    var day by remember { mutableStateOf(today) }
    var text by remember { mutableStateOf("") }
    val tags = remember { mutableStateListOf<String>() }
    var trackId by remember { mutableStateOf(fixedTrack) }
    var stats by remember { mutableStateOf<GpxStats?>(null) }
    var license by remember { mutableStateOf(Posts.DEFAULT_LICENSE) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    // Kept on the phone to go up later: what to say about it.
    var queued by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val photos = remember { mutableStateListOf<PhotoItem>() }
    var nextPhoto by remember { mutableLongStateOf(0L) }
    // The track's GPX, for placing photos on it.
    var gpx by remember { mutableStateOf<String?>(null) }
    // Photos still on the phone when the form goes (cancelled, or posted:
    // uploaded) aren't needed any more.
    DisposableEffect(Unit) {
        onDispose { photos.forEach { p -> p.photo?.let { posts.photos?.discard?.invoke(it) } } }
    }
    // The tracks passing near the hike, offered first.
    var near by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(hike) {
        val line = hike?.line.orEmpty()
        if (fixedTrack == null && line.isNotEmpty()) {
            near = try { posts.tracksNear(line) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptySet() }
        }
    }
    LaunchedEffect(Unit) {
        license = try { posts.license() } catch (e: CancellationException) { throw e } catch (_: Exception) { Posts.DEFAULT_LICENSE }
    }
    LaunchedEffect(trackId) {
        val id = trackId
        stats = null
        gpx = null
        if (id == null) return@LaunchedEffect
        val s = try { posts.trackStats(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        stats = s
        if (posts.photos != null) gpx = try { posts.trackGpx(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        // The day it started, as the website takes it from a GPX file.
        s?.startedAt?.let { day = Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    }

    if (done) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val later = queued
            Text(if (later != null) "Saved on your phone" else "Posted", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(later ?: "Thanks! It's on your profile, and in your own account for any app to show.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Close") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onClose(); posts.openProfile(null) }) { Text("See your profile") }
            }
        }
        return
    }

    val trackName0 = trackName ?: posts.tracks.firstOrNull { it.id == trackId }?.name
    val title = hike?.title ?: trackName0 ?: "Your track"
    val review = text.trim().isNotEmpty()
    // Every photo ready: one still being read, or unreadable, holds the post.
    val canPost = (review || trackId != null) && text.length <= Posts.MAX_TEXT && busy == null && photos.all { it.photo != null }
    fun addPhotos(inputs: List<PhotoInput>) {
        val actions = posts.photos ?: return
        for (input in inputs.take(Photos.MAX_PHOTOS - photos.size)) {
            val key = nextPhoto++
            photos.add(PhotoItem(key))
            scope.launch {
                val result = try {
                    Result.success(actions.prepare(input))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                val i = photos.indexOfFirst { it.key == key }
                when {
                    // Removed while it was being read.
                    i < 0 -> result.getOrNull()?.let(actions.discard)
                    else -> photos[i] = result.fold({ photos[i].copy(photo = it) }, { photos[i].copy(failed = it.message ?: it.toString()) })
                }
            }
        }
    }
    fun post() {
        error = null
        busy = "Posting…"
        onBusy(true)
        scope.launch {
            try {
                val ready = photos.mapNotNull { p -> p.photo?.copy(alt = p.alt.trim().take(Photos.MAX_ALT).ifEmpty { null }) }
                val outcome = posts.post(PostDraft(hike, hike?.title ?: trackName0 ?: "Track", text, tags.toList(), day, trackId, ready)) { busy = it }
                queued = (outcome as? PostOutcome.Queued)?.message
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: SignedOutException) {
                error = "You're signed out. Sign in again (You tab) to post; what you wrote is kept here."
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = null
                onBusy(false)
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // The fields scroll; the buttons stay below them.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (hike == null) "Your track, with a review if you like. It isn't tied to a curated hike." else "A review, your track, or both.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("When you hiked it")
                OutlinedButton(onClick = { picking = true }, enabled = busy == null) { Text(FORM_DAY.format(day)) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Review")
                OutlinedTextField(
                    text, { text = it.take(Posts.MAX_TEXT + 500) },
                    placeholder = { Text("How was it? Trail conditions, highlights, tips.") },
                    minLines = 5, enabled = busy == null, isError = text.length > Posts.MAX_TEXT,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.length > Posts.MAX_TEXT * 0.9) {
                    Text(
                        "${text.length} / ${Posts.MAX_TEXT}", style = MaterialTheme.typography.bodySmall,
                        color = if (text.length > Posts.MAX_TEXT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Conditions and highlights")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, name) in Posts.TAGS) {
                        val on = k in tags
                        FilterChip(
                            selected = on,
                            onClick = { if (on) tags.remove(k) else tags.add(k) },
                            label = { Text(name) },
                            // The lexicon takes at most 10.
                            enabled = busy == null && (on || tags.size < Posts.MAX_TAGS),
                        )
                    }
                }
                when {
                    tags.size >= Posts.MAX_TAGS -> Hint("Up to ${Posts.MAX_TAGS} tags.")
                    tags.isNotEmpty() && !review -> Hint("Tags go with a review: write a few words to post them.")
                }
            }

            posts.photos?.let { actions ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label("Photos")
                    PhotoList(
                        photos, gpx, actions, enabled = busy == null,
                        onAlt = { key, alt -> photos.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { photos[it] = photos[it].copy(alt = alt) } },
                        onRemove = { key ->
                            val i = photos.indexOfFirst { it.key == key }
                            if (i >= 0) photos.removeAt(i).photo?.let(actions.discard)
                        },
                    )
                    photoButtons(Photos.MAX_PHOTOS - photos.size, busy == null, ::addPhotos)
                    if (photos.size >= Photos.MAX_PHOTOS) Hint("Up to ${Photos.MAX_PHOTOS} photos.")
                    Hint(
                        "Photos keep where and when they were taken (their EXIF), so others can see the place. " +
                            "Photos without a location are placed by their time on your track.",
                    )
                    // Which record they go on, as the website's: the review, or the track posted alone.
                    if (photos.isNotEmpty()) Hint(
                        when {
                            review -> "They go on your review."
                            trackId != null -> "They go on your track."
                            else -> "Photos go with a review or a track: write a few words, or choose your track."
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Your track")
                if (fixedTrack != null) {
                    Text(trackName0 ?: "Track", style = MaterialTheme.typography.bodyLarge)
                } else {
                    TrackPicker(posts.tracks, near, trackId, enabled = busy == null, waiting = posts.waitingTracks) { trackId = it }
                }
                stats?.let { Text(postStatsLine(it), style = MaterialTheme.typography.bodyMedium) }
                if (trackId != null) Hint("Your track shows where you went, and when, to anyone.")
            }

            Text(
                buildAnnotatedString {
                    append("Posts are public, and stay in your own account. You keep ownership; this one is shared under ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(Posts.LICENSE_LABEL[license] ?: license) }
                    append(". To change that, see Licence in the You tab.")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            error?.let { ErrorBox(it) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose, enabled = busy == null) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::post, enabled = canPost) { Text(busy ?: "Post") }
        }
    }

    if (picking) {
        val zoneToday = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= zoneToday
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** The form's photos: each a thumbnail, its description, where its location comes from, and Remove. */
@Composable
internal fun PhotoList(
    photos: List<PhotoItem>, gpx: String?, actions: PhotoActions, enabled: Boolean,
    onAlt: (Long, String) -> Unit, onRemove: (Long) -> Unit,
) {
    photos.forEachIndexed { i, p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            var thumb by remember(p.photo?.file) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(p.photo?.file) {
                val photo = p.photo ?: return@LaunchedEffect
                thumb = try { actions.thumbnail(photo) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
            val shape = RoundedCornerShape(8.dp)
            val t = thumb
            if (t != null) {
                Image(t, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(shape))
            } else {
                Spacer(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, shape))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                OutlinedTextField(
                    p.alt, { onAlt(p.key, it.take(Photos.MAX_ALT)) },
                    placeholder = { Text("Describe it (for people who can't see it)") },
                    singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Description of photo ${i + 1}" },
                )
                // Worked out again only when the photo or the track changes (it reads the GPX), not as its description is typed.
                val where = remember(p.photo, p.failed, gpx) { whereText(p, gpx) }
                Text(
                    where, style = MaterialTheme.typography.bodySmall,
                    color = if (p.failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRemove(p.key) }, enabled = enabled) {
                Icon(Icons.Filled.Close, contentDescription = "Remove photo ${i + 1}")
            }
        }
    }
}

/**
 * The tracks to offer with a hike's post: those passing near it first, then
 * the others, each newest first (as [tracks] come).
 */
internal fun trackGroups(tracks: List<TrackStore.Track>, near: Set<Long>): Pair<List<TrackStore.Track>, List<TrackStore.Track>> =
    tracks.partition { it.id in near }

/** No track, or one of the phone's recorded tracks, chosen from a list. */
@Composable
private fun TrackPicker(
    tracks: List<TrackStore.Track>, near: Set<Long>, chosen: Long?, enabled: Boolean, waiting: Set<Long>, onChoose: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled && tracks.isNotEmpty()) {
        Text(tracks.firstOrNull { it.id == chosen }?.let(::trackLabel) ?: if (tracks.isEmpty()) "No recorded tracks" else "No track")
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    if (tracks.isEmpty()) Hint("Tracks you record with Hiker Atlas can go with a post.")
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                TrackChoices(tracks, near, chosen, waiting = waiting, onChoose = { open = false; onChoose(it) })
            }
        }
    }
}

/**
 * The tracks to choose from (the picker's content, apart, to test): near
 * the hike first, then "Other tracks", newest first in each; one already
 * posted, or [waiting] to post, can't be posted again.
 */
@Composable
internal fun TrackChoices(
    tracks: List<TrackStore.Track>, near: Set<Long>, chosen: Long?, waiting: Set<Long> = emptySet(), onChoose: (Long?) -> Unit,
) {
    val (close, other) = trackGroups(tracks, near)
    androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(vertical = 12.dp)) {
        item { Choice("No track", "A review alone", chosen == null) { onChoose(null) } }
        if (close.isNotEmpty()) item { GroupLabel("Near this hike") }
        items(close.size) { i -> TrackChoice(close[i], chosen, close[i].id in waiting, onChoose) }
        if (other.isNotEmpty()) item { GroupLabel(if (close.isEmpty()) "Your tracks" else "Other tracks") }
        items(other.size) { i -> TrackChoice(other[i], chosen, other[i].id in waiting, onChoose) }
    }
}

@Composable
private fun GroupLabel(s: String) = Text(
    s, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
)

@Composable
private fun TrackChoice(t: TrackStore.Track, chosen: Long?, waiting: Boolean, onChoose: (Long?) -> Unit) {
    val posted = t.posted != null || waiting
    Row(
        Modifier.fillMaxWidth().selectable(t.id == chosen, enabled = !posted, role = Role.RadioButton) { onChoose(t.id) }
            .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = t.id == chosen, onClick = null, enabled = !posted, modifier = Modifier.padding(horizontal = 8.dp))
        Column {
            val dim = if (posted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
            Text(trackLabel(t), style = MaterialTheme.typography.bodyLarge, color = dim)
            Text(
                when {
                    t.posted != null -> "Already posted"
                    waiting -> "Waiting to post"
                    else -> t.stats?.let(::statsLine) ?: ""
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun Label(s: String) = Text(s, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

@Composable
internal fun Hint(s: String) = Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A failure, said loudly (as the Units card's). */
@Composable
internal fun ErrorBox(msg: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(
            msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 10.dp),
        )
    }
}
