package com.hikeratlas.android.track

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.map.ElevationChart
import com.hikeratlas.android.routes.km
import com.hikeratlas.android.routes.metres
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CHART_HEIGHT = 110.dp

/** "7.3 km · 1:04:09 · ↑ 612 m", the climb where the DEM had it. */
fun statsLine(s: TrackStore.Stats): String =
    listOfNotNull("${km(s.metres / 1000)} km", duration(s.elapsedMs), s.gain?.let { "↑ ${metres(it)} m" }).joinToString(" · ")

/** "Moving 52:10 · ↓ 598 m". */
private fun moreLine(s: TrackStore.Stats): String =
    listOfNotNull("Moving ${duration(s.movingMs)}", s.loss?.let { "↓ ${metres(it)} m" }).joinToString(" · ")

@Composable
private fun Chart(profile: ElevationProfiler.Profile?, onScrub: (DoubleArray?) -> Unit) {
    if (profile == null) {
        Text(
            "No elevation here: the terrain didn't load.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ElevationChart(profile, emptyList(), onScrub = { i -> onScrub(i?.let { profile.coords[it] }) }, height = CHART_HEIGHT)
    }
}

/**
 * Just stopped: the track's numbers and profile, its name to change, and
 * Save, Resume (record on) or Discard. Dismissing it saves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSummarySheet(
    detail: TrackLibrary.Detail,
    onSave: (name: String) -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onScrub: (DoubleArray?) -> Unit,
) {
    var name by remember(detail.track.id) { mutableStateOf(detail.name) }
    var discarding by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { onSave(name) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Track recorded", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column {
                Text(statsLine(detail.stats), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(moreLine(detail.stats), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Chart(detail.profile, onScrub)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { discarding = true }) { Text("Discard") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onResume) { Text("Resume") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(name) }) { Text("Save") }
            }
        }
    }
    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text("Discard this track?") },
            text = { Text("It's deleted from this phone.") },
            confirmButton = { TextButton(onClick = { discarding = false; onDiscard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text("Keep") } },
        )
    }
}

/** A saved track on the map: its name and numbers, its profile, and Delete. */
@Composable
fun TrackPanel(
    detail: TrackLibrary.Detail,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onScrub: (DoubleArray?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), shadowElevation = 8.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(detail.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(statsLine(detail.stats), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Box(Modifier.padding(end = 12.dp)) { Chart(detail.profile, onScrub) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(moreLine(detail.stats), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { deleting = true }) { Text("Delete") }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this track?") },
            text = { Text("It's deleted from this phone.") },
            confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } },
        )
    }
}

/** The Saved tab: recorded tracks, newest first. Tapping one shows it on the map. */
@Composable
fun SavedScreen(tracks: List<TrackStore.Track>, onOpen: (Long) -> Unit) {
    val date = remember { SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Saved", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        Text(
            "Tracks you've recorded", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(tracks, key = { it.id }) { t ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(t.id) }.heightIn(min = 56.dp)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(t.name ?: "Track", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(date.format(Date(t.started)), t.stats?.let(::statsLine)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
