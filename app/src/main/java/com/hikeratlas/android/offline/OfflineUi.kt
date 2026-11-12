package com.hikeratlas.android.offline

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** "37 MB", "0.4 MB". */
fun megabytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb < 10) String.format(Locale.ROOT, "%.1f MB", mb) else String.format(Locale.ROOT, "%,d MB", Math.round(mb))
}

/** What a sheet needs to offer downloading what it shows. */
class OfflineActions(
    /** The area already downloaded (or on its way) for a target, if any. */
    val areaFor: (OfflineAreas.Target) -> OfflineStore.Area?,
    val onDownload: (OfflineAreas.Target) -> Unit,
)

/**
 * A sheet's download button: Download, or (pinned) downloaded, or downloading.
 * Once there's an area, tapping says where it's at.
 */
@Composable
fun OfflineButton(target: OfflineAreas.Target, actions: OfflineActions) {
    val context = LocalContext.current
    val area = actions.areaFor(target)
    when {
        area == null -> IconButton(onClick = { actions.onDownload(target) }) {
            Icon(Icons.Filled.Download, contentDescription = "Download for offline use")
        }
        area.ready -> IconButton(onClick = { Toast.makeText(context, "Downloaded for offline use", Toast.LENGTH_SHORT).show() }) {
            Icon(Icons.Filled.OfflinePin, contentDescription = "Downloaded for offline use", tint = MaterialTheme.colorScheme.primary)
        }
        else -> IconButton(onClick = { Toast.makeText(context, "Downloading for offline use: see Saved", Toast.LENGTH_SHORT).show() }) {
            Icon(Icons.Filled.Downloading, contentDescription = "Downloading for offline use")
        }
    }
}

/**
 * Before downloading: how much it'll take (worked out from the archives),
 * that the first also brings the overview, and that it waits for Wi-Fi.
 */
@Composable
fun DownloadDialog(
    target: OfflineAreas.Target,
    wifiOnly: Boolean,
    estimate: suspend () -> Pair<Long, Long>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var size by remember(target) { mutableStateOf<Pair<Long, Long>?>(null) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    LaunchedEffect(target) {
        try {
            size = estimate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download for offline use?") },
        text = {
            Column {
                Text(target.name, fontWeight = FontWeight.SemiBold)
                val s = size
                when {
                    error != null -> Text("Couldn't work out its size: $error", color = MaterialTheme.colorScheme.error)
                    s == null -> {
                        Text("Working out its size…")
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    else -> {
                        Text("About ${megabytes(s.first)}: the map, terrain and routes.")
                        if (s.second > 0) Text("The first download also gets a ${megabytes(s.second)} map of North America at small scale.")
                    }
                }
                Text(
                    if (wifiOnly) "It downloads on Wi-Fi, in the background." else "It downloads in the background, on any network.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = size != null) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** An area's state, as the Saved list says it. */
fun areaStatus(a: OfflineStore.Area, wifiOnly: Boolean): String = when (a.state) {
    OfflineStore.READY -> megabytes(a.bytes)
    OfflineStore.DOWNLOADING -> if (a.total > 0) "Downloading · ${a.done * 100 / a.total}%" else "Downloading"
    OfflineStore.FAILED -> "Couldn't download" + (a.error?.let { ": $it" } ?: "")
    else -> if (wifiOnly) "Waiting for Wi-Fi" else "Waiting to download"
}

/** The Saved tab's offline areas: each with its size or progress, Retry and Delete; and Wi-Fi only. */
fun LazyListScope.offlineAreasSection(
    areas: List<OfflineStore.Area>,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    /** Whether an area's from an older build of the map, and offers Update. */
    updateAvailable: (OfflineStore.Area) -> Boolean = { false },
    onUpdate: (Long) -> Unit = {},
) {
    if (areas.isEmpty()) return
    item(key = "offline-header") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Offline areas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Wi-Fi only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(wifiOnly, onWifiOnly, modifier = Modifier.padding(start = 8.dp))
        }
    }
    items(areas, key = { "area-${it.id}" }) { a -> AreaRow(a, wifiOnly, updateAvailable(a), onRetry, onUpdate, onDelete) }
}

@Composable
private fun AreaRow(
    a: OfflineStore.Area,
    wifiOnly: Boolean,
    outdated: Boolean,
    onRetry: (Long) -> Unit,
    onUpdate: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                areaStatus(a, wifiOnly) + if (outdated) " · Map updated since" else "", style = MaterialTheme.typography.bodySmall,
                color = if (a.state == OfflineStore.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (a.state == OfflineStore.DOWNLOADING && a.total > 0) {
                LinearProgressIndicator({ a.done.toFloat() / a.total }, Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp))
            }
        }
        if (a.state == OfflineStore.FAILED) TextButton(onClick = { onRetry(a.id) }) { Text("Retry") }
        if (outdated) TextButton(onClick = { onUpdate(a.id) }) { Text("Update") }
        IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${a.name}") }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${a.name}?") },
            text = { Text("Its map, terrain and routes are deleted from this phone, unless another area uses them.") },
            confirmButton = { TextButton(onClick = { deleting = false; onDelete(a.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } },
        )
    }
}
