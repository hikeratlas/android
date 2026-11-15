package com.hikeratlas.android.ui

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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hikeratlas.android.atproto.AccountState
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
)

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
    /** Posts it; [progress] hears what's under way. Throws saying what failed. */
    val post: suspend (draft: PostDraft, progress: (String) -> Unit) -> Unit,
    /** Opens your profile in the app; at the post a posted track went up as, when given ("See post"). */
    val openProfile: (TrackStore.Posted?) -> Unit,
    /** Checks a posted track's post is still there, and clears the mark if it was deleted. */
    val verifyPosted: suspend (Long) -> Unit = {},
    val today: () -> LocalDate = { LocalDate.now() },
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
private val FORM_DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

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
            Text("Post a review or your track.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var picking by remember { mutableStateOf(false) }
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
        if (id == null) return@LaunchedEffect
        val s = try { posts.trackStats(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        stats = s
        // The day it started, as the website takes it from a GPX file.
        s?.startedAt?.let { day = Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    }

    if (done) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Posted", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Thanks! It's on your profile, and in your own account for any app to show.")
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
    val canPost = (review || trackId != null) && text.length <= Posts.MAX_TEXT && busy == null
    fun post() {
        error = null
        busy = "Posting…"
        onBusy(true)
        scope.launch {
            try {
                posts.post(PostDraft(hike, hike?.title ?: trackName0 ?: "Track", text, tags.toList(), day, trackId)) { busy = it }
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

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Your track")
                if (fixedTrack != null) {
                    Text(trackName0 ?: "Track", style = MaterialTheme.typography.bodyLarge)
                } else {
                    TrackPicker(posts.tracks, near, trackId, enabled = busy == null) { trackId = it }
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

/**
 * The tracks to offer with a hike's post: those passing near it first, then
 * the others, each newest first (as [tracks] come).
 */
internal fun trackGroups(tracks: List<TrackStore.Track>, near: Set<Long>): Pair<List<TrackStore.Track>, List<TrackStore.Track>> =
    tracks.partition { it.id in near }

/** No track, or one of the phone's recorded tracks, chosen from a list. */
@Composable
private fun TrackPicker(tracks: List<TrackStore.Track>, near: Set<Long>, chosen: Long?, enabled: Boolean, onChoose: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled && tracks.isNotEmpty()) {
        Text(tracks.firstOrNull { it.id == chosen }?.let(::trackLabel) ?: if (tracks.isEmpty()) "No recorded tracks" else "No track")
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    if (tracks.isEmpty()) Hint("Tracks you record with Hiker Atlas can go with a post.")
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                TrackChoices(tracks, near, chosen, onChoose = { open = false; onChoose(it) })
            }
        }
    }
}

/**
 * The tracks to choose from (the picker's content, apart, to test): near
 * the hike first, then "Other tracks", newest first in each; one already
 * posted can't be posted again.
 */
@Composable
internal fun TrackChoices(tracks: List<TrackStore.Track>, near: Set<Long>, chosen: Long?, onChoose: (Long?) -> Unit) {
    val (close, other) = trackGroups(tracks, near)
    androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(vertical = 12.dp)) {
        item { Choice("No track", "A review alone", chosen == null) { onChoose(null) } }
        if (close.isNotEmpty()) item { GroupLabel("Near this hike") }
        items(close.size) { i -> TrackChoice(close[i], chosen, onChoose) }
        if (other.isNotEmpty()) item { GroupLabel(if (close.isEmpty()) "Your tracks" else "Other tracks") }
        items(other.size) { i -> TrackChoice(other[i], chosen, onChoose) }
    }
}

@Composable
private fun GroupLabel(s: String) = Text(
    s, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
)

@Composable
private fun TrackChoice(t: TrackStore.Track, chosen: Long?, onChoose: (Long?) -> Unit) {
    val posted = t.posted != null
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
                if (posted) "Already posted" else t.stats?.let(::statsLine) ?: "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Label(s: String) = Text(s, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

@Composable
private fun Hint(s: String) = Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
