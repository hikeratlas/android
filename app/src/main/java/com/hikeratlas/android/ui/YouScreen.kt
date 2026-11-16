package com.hikeratlas.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import com.hikeratlas.android.offline.megabytes
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.BuildConfig
import com.hikeratlas.android.map.openUrl
import com.hikeratlas.android.routes.SITE
import com.hikeratlas.android.units.DistanceUnit
import com.hikeratlas.android.units.ElevationUnit
import com.hikeratlas.android.units.Units
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.hikeratlas.android.atproto.AccountState

/** What the Storage card shows and does: offline areas, and MapLibre's cache of what's been looked at. */
class StorageActions(
    val areaCount: () -> Int,
    val offlineBytes: suspend () -> Long,
    val mapCacheBytes: suspend () -> Long,
    val deleteAll: suspend () -> Unit,
    val clearMapCache: suspend () -> Unit,
)

/**
 * The You tab: signed in, your profile (Overview, and Settings gathering
 * the rest); otherwise, as before signing in, [YouScreen].
 * [seePost]: a post to land on, from "See post".
 */
@Composable
fun YouTab(
    account: AccountActions,
    profile: ProfileActions,
    units: UnitsActions,
    storage: StorageActions? = null,
    /** Null while signed out (the licence is the profile's). */
    license: LicenseActions? = null,
    waiting: WaitingActions? = null,
    seePost: PostRef? = null,
    refreshKey: Int = 0,
) {
    val s = account.state
    if (s is AccountState.In) {
        ProfileScreen(
            ProfileTarget(s.did, s.handle, seePost), profile, onBack = null, avatar = s.avatar,
            settings = { YouSettings(account, units, license, storage) },
            waiting = waiting, refreshKey = refreshKey,
        )
    } else {
        YouScreen(storage, account, units, waiting)
    }
}

/**
 * Settings, signed in: what the You tab had, under small headings. Units
 * and the licence are the profile's (as on the website's Settings tab);
 * storage is this phone's.
 */
@Composable
internal fun YouSettings(account: AccountActions, units: UnitsActions, license: LicenseActions?, storage: StorageActions?) {
    SettingsGroup("Posting") {
        UnitsCard(units)
        if (license != null) LicenseCard(license)
    }
    if (storage != null) SettingsGroup("On this phone") { StorageCard(storage) }
    SettingsGroup("Account") { AccountCard(account, title = null) }
    SettingsGroup("About") { AboutCard(title = null) }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
        )
        content()
    }
}

/**
 * The You tab's icon: your picture in a circle signed in (so the way to
 * your profile is plain to see), else the person icon.
 */
@Composable
internal fun YouTabIcon(state: AccountState, selected: Boolean) {
    if (state is AccountState.In) {
        // Not an Icon: the bar would tint the photo.
        Box(
            Modifier.semantics(mergeDescendants = true) { contentDescription = "You, ${state.handle?.let { "@$it" } ?: state.did}" }
                .testTag("you-avatar"),
        ) {
            Avatar(state.avatar, state.handle ?: "", 26)
        }
    } else {
        Icon(if (selected) Icons.Filled.Person else Icons.Outlined.Person, contentDescription = null, modifier = Modifier.testTag("you-person"))
    }
}

/** Signed out (or signing in): Sign in, units on this phone, storage and About. */
@Composable
fun YouScreen(
    storage: StorageActions? = null,
    account: AccountActions? = null,
    /**
     * Units on this phone, shown signed out. Signed in, units and the
     * licence are your profile's settings, and live in its Settings tab
     * (as the website's), so there's one place to change them.
     */
    units: UnitsActions? = null,
    /** Posts waiting to go up (signed in, they're on your profile's Overview). */
    waiting: WaitingActions? = null,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("You", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        if (account != null) AccountCard(account)
        if (waiting != null && waiting.entries.isNotEmpty()) WaitingPosts(waiting)
        if (units != null && !units.signedIn) UnitsCard(units)
        if (storage != null) StorageCard(storage)
        AboutCard()
    }
}

/** The site, the terms, map credits and the version. [title] null where a heading above says it. */
@Composable
private fun AboutCard(title: String? = "About") {
    val context = LocalContext.current
    var credits by remember { mutableStateOf(false) }
    Card(title) {
        Item("hikeratlas.com", external = true, divider = title != null) { openUrl(context, SITE) }
        // The source is a tap away on the version's commit hash, below.
        Item("Terms and privacy", external = true) { openUrl(context, "$SITE/terms") }
        Item("Map credits", "OpenStreetMap contributors, Mapterhorn, ESA WorldCover and others") { credits = true }
        VersionRow(BuildConfig.COMMIT, BuildConfig.COMMIT_DATE) { openUrl(context, it) }
    }
    if (credits) {
        AlertDialog(
            onDismissRequest = { credits = false },
            confirmButton = { TextButton(onClick = { credits = false }) { Text("Close") } },
            title = { Text("Map credits") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Map data © OpenStreetMap contributors, under the Open Database License.")
                    Text("Elevation and hillshade: © Mapterhorn; Newfoundland: hikeratlas/nl-dem.")
                    Text("Hikes' land cover: OpenStreetMap, and ESA WorldCover 2021 (CC BY 4.0) where it has none.")
                    TextButton(onClick = { openUrl(context, "https://www.openstreetmap.org/copyright") }) { Text("openstreetmap.org/copyright") }
                    TextButton(onClick = { openUrl(context, "https://mapterhorn.com/attribution") }) { Text("mapterhorn.com/attribution") }
                    TextButton(onClick = { openUrl(context, "https://esa-worldcover.org/") }) { Text("esa-worldcover.org") }
                }
            },
        )
    }
}

/** What the Units card shows and does: [save] goes to the profile record when [signedIn], else to this phone. */
class UnitsActions(
    val current: Units,
    val signedIn: Boolean,
    val save: suspend (Units) -> Unit,
)

/** Km or mi, m or ft, worded as the website's Settings tab. */
@Composable
internal fun UnitsCard(units: UnitsActions) {
    val scope = rememberCoroutineScope()
    // What's chosen, ahead of a save finishing; back to what's in effect if it fails.
    var chosen by remember(units.current) { mutableStateOf(units.current) }
    var saving by remember { mutableStateOf(false) }
    var status by remember(units.signedIn) { mutableStateOf<String?>(null) }
    // A save that failed: said loudly, as the units haven't changed.
    var failed by remember(units.signedIn) { mutableStateOf<String?>(null) }
    fun choose(u: Units) {
        if (u == chosen || saving) return
        chosen = u
        saving = true
        failed = null
        if (units.signedIn) status = "Saving…"
        scope.launch {
            status = try {
                units.save(u)
                if (units.signedIn) "Saved." else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chosen = units.current
                failed = "Couldn't save your units to your Hiker Atlas profile, so they haven't changed." +
                    (e.message?.let { " ($it)" } ?: "")
                null
            }
            saving = false
        }
    }
    Card("Units") {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Column(Modifier.weight(1f)) {
                UnitGroup("Distance")
                UnitChoice("Kilometres (km)", chosen.distance == DistanceUnit.KM, !saving) { choose(chosen.copy(distance = DistanceUnit.KM)) }
                UnitChoice("Miles (mi)", chosen.distance == DistanceUnit.MI, !saving) { choose(chosen.copy(distance = DistanceUnit.MI)) }
            }
            Column(Modifier.weight(1f)) {
                UnitGroup("Elevation")
                UnitChoice("Metres (m)", chosen.elevation == ElevationUnit.M, !saving) { choose(chosen.copy(elevation = ElevationUnit.M)) }
                UnitChoice("Feet (ft)", chosen.elevation == ElevationUnit.FT, !saving) { choose(chosen.copy(elevation = ElevationUnit.FT)) }
            }
        }
        failed?.let { msg ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (failed == null) Text(
            status ?: if (units.signedIn) "For distances, climbs and heights across Hiker Atlas."
            else "For distances, climbs and heights, on this phone. Signed in, your Hiker Atlas profile's apply, as on the website.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        )
    }
}

/**
 * The licence for new posts, from the profile record: [current] is null
 * while it's being read, or if reading failed ([readError]).
 */
class LicenseActions(
    val current: String?,
    val readError: String? = null,
    val save: suspend (String) -> Unit,
)

/** What others may do with your new posts, worded as the website's Settings tab. */
@Composable
internal fun LicenseCard(license: LicenseActions) {
    val scope = rememberCoroutineScope()
    var chosen by remember(license.current) { mutableStateOf(license.current) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // A save that failed: said loudly, as the licence hasn't changed.
    var failed by remember { mutableStateOf<String?>(null) }
    fun choose(l: String) {
        if (l == chosen || saving) return
        val before = chosen
        chosen = l
        saving = true
        failed = null
        status = "Saving…"
        scope.launch {
            status = try {
                license.save(l)
                "Saved. Posts you've already made keep theirs; edit a post to change its own."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chosen = before
                failed = "Couldn't save your licence to your Hiker Atlas profile, so it hasn't changed." +
                    (e.message?.let { " ($it)" } ?: "")
                null
            }
            saving = false
        }
    }
    // Unknown (not read yet): what a post would use, the default, can't be changed until it's read...
    // unless reading failed, when choosing is still worth trying (saving reads it again).
    val shown = chosen ?: com.hikeratlas.android.atproto.Posts.DEFAULT_LICENSE.takeIf { license.readError != null }
    val enabled = !saving && (chosen != null || license.readError != null)
    Card("Licence") {
        Text(
            "What others may do with your new posts (you keep ownership):",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
        for (l in com.hikeratlas.android.atproto.Posts.LICENSES) {
            UnitChoice(com.hikeratlas.android.atproto.Posts.LICENSE_CHOICE.getValue(l), shown == l, enabled) { choose(l) }
        }
        val error = failed ?: license.readError?.takeIf { license.current == null }?.let {
            "Couldn't read your licence from your Hiker Atlas profile. ($it)"
        }
        error?.let { ErrorBox(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) }
        if (failed == null) Text(
            status ?: if (license.current == null && license.readError == null) "Reading your profile…"
            else "Posts you've already made keep theirs; edit a post to change its own.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun UnitGroup(name: String) {
    Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp))
}

@Composable
private fun UnitChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(horizontal = 8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Offline areas (Delete all, after asking) and the map cache (Clear), each with its size. */
@Composable
private fun StorageCard(storage: StorageActions) {
    val scope = rememberCoroutineScope()
    var offline by remember { mutableStateOf<Long?>(null) }
    var cache by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    val areas = storage.areaCount()
    LaunchedEffect(round, areas) {
        offline = try { storage.offlineBytes() } catch (e: Exception) { null }
        cache = try { storage.mapCacheBytes() } catch (e: Exception) { null }
    }
    fun run(what: String, f: suspend () -> Unit) {
        busy = what
        scope.launch {
            try { f() } catch (e: Exception) { }
            busy = null
            round++
        }
    }
    Card("Storage") {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("Offline areas", style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(offline?.let(::megabytes), "$areas ${if (areas == 1) "area" else "areas"}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirming = true }, enabled = areas > 0 && busy == null) {
                Text(if (busy == "areas") "Deleting…" else "Delete all")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("Map cache", style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(cache?.let(::megabytes), "places you've looked at, kept to load faster").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { run("cache", storage.clearMapCache) }, enabled = busy == null) {
                Text(if (busy == "cache") "Clearing…" else "Clear")
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete all offline areas?") },
            text = { Text("Their maps, terrain and routes are deleted from this phone. Your tracks are kept.") },
            confirmButton = { TextButton(onClick = { confirming = false; run("areas", storage.deleteAll) }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep") } },
        )
    }
}

/** A card of rows; [title] null for one with a heading above it instead. */
@Composable
internal fun Card(title: String?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(top = if (title == null) 4.dp else 0.dp),
    ) {
        if (title != null) Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        content()
    }
}

/** The open-in-browser icon, after a link that leaves the app. */
@Composable
private fun ExternalIcon() {
    Icon(
        Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Opens in browser",
        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(18.dp),
    )
}

/**
 * The build: its commit's date and short hash, the hash linking to the
 * commit. [commit] is empty for a build made outside git.
 */
@Composable
internal fun VersionRow(commit: String, date: String, open: (String) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Version", modifier = Modifier.weight(1f))
        if (commit.isEmpty()) {
            Text("Unknown", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (date.isNotEmpty()) Text("$date · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.clickable { open("$SOURCE/commit/$commit") }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(commit.take(7), color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                ExternalIcon()
            }
        }
    }
}

private const val SOURCE = "https://github.com/hikeratlas/android"

@Composable
internal fun Item(title: String, subtitle: String? = null, external: Boolean = false, divider: Boolean = true, onClick: () -> Unit) {
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (external) ExternalIcon()
    }
}
