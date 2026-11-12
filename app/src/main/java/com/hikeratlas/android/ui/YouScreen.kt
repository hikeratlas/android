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
import androidx.compose.material3.AlertDialog
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

/** What the Storage card shows and does: offline areas, and MapLibre's cache of what's been looked at. */
class StorageActions(
    val areaCount: () -> Int,
    val offlineBytes: suspend () -> Long,
    val mapCacheBytes: suspend () -> Long,
    val deleteAll: suspend () -> Unit,
    val clearMapCache: suspend () -> Unit,
)

@Composable
fun YouScreen(storage: StorageActions? = null) {
    val context = LocalContext.current
    var credits by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("You", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        if (storage != null) StorageCard(storage)
        Card("About") {
            Item("Blog") { openUrl(context, "$SITE/blog") }
            Item("Hiker Atlas on the web") { openUrl(context, SITE) }
            Item("Source code") { openUrl(context, "https://github.com/hikeratlas") }
            Item("Map credits", "OpenStreetMap contributors, Mapterhorn and others") { credits = true }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Version", modifier = Modifier.weight(1f))
                Text(BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
                    TextButton(onClick = { openUrl(context, "https://www.openstreetmap.org/copyright") }) { Text("openstreetmap.org/copyright") }
                    TextButton(onClick = { openUrl(context, "https://mapterhorn.com/attribution") }) { Text("mapterhorn.com/attribution") }
                }
            },
        )
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

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun Item(title: String, subtitle: String? = null, onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
