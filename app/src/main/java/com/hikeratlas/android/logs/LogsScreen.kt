package com.hikeratlas.android.logs

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hikeratlas.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the Logs screen shows and does. */
class LogsActions(
    /** The app version and commit, Android version and phone model. */
    val header: String,
    /** Every entry, newest first. */
    val load: suspend () -> List<LogEntry>,
    val detailed: Boolean,
    val setDetailed: (Boolean) -> Unit,
    val share: () -> Unit,
    val clear: suspend () -> Unit,
)

/** The app version (commit), Android version and phone model, at the top of the screen and of a shared file. */
fun deviceHeader(): String {
    val commit = BuildConfig.COMMIT.take(7).ifEmpty { "unknown commit" }
    val date = BuildConfig.COMMIT_DATE.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""
    return "Hiker Atlas ${BuildConfig.VERSION_NAME} ($commit$date)\n" +
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "${Build.MANUFACTURER} ${Build.MODEL}"
}

/** The Logs screen's actions on the app's own log ([Log]). */
fun logsActions(context: Context, scope: kotlinx.coroutines.CoroutineScope): LogsActions {
    val header = deviceHeader()
    return LogsActions(
        header = header,
        load = {
            withContext(Dispatchers.IO) {
                Log.flush()
                Log.files?.read().orEmpty()
            }
        },
        detailed = Log.detailed,
        setDetailed = { on ->
            Log.detailed = on
            Log.i("logs", if (on) "Detailed logging on" else "Detailed logging off")
        },
        share = { scope.launch { shareLogs(context, header) } },
        clear = {
            withContext(Dispatchers.IO) {
                Log.flush()
                Log.files?.clear()
            }
        },
    )
}

/**
 * The log as a .txt file, through Android's share sheet (to attach to an
 * issue): the header, then every day kept, oldest first. A file, not
 * EXTRA_TEXT: 14 days can be megabytes, too big for an intent.
 */
suspend fun shareLogs(context: Context, header: String) {
    val file = withContext(Dispatchers.IO) {
        Log.flush()
        logsFile(context.cacheDir, header, Log.files?.text().orEmpty())
    } ?: return
    try {
        context.startActivity(logsShareIntent(context, file))
    } catch (e: android.content.ActivityNotFoundException) {
        Log.w("logs", "No app to share the log with")
    }
}

/** Writes the file to share into [cache]'s logs/ (only the latest is kept). */
internal fun logsFile(cache: File, header: String, text: String): File? = try {
    val dir = File(cache, "logs").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    File(dir, "hiker-atlas-logs.txt").apply { writeText("$header\n\n$text") }
} catch (e: java.io.IOException) {
    null
}

/** Android's share sheet for the log file, as text/plain, readable by the app picked (FileProvider). */
fun logsShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Hiker Atlas logs")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // So the grant reaches the app picked through the chooser.
    send.clipData = ClipData.newRawUri(file.name, uri)
    return Intent.createChooser(send, "Share logs")
}

/** Entries at [min] or above, as the filter shows them. */
internal fun filtered(entries: List<LogEntry>, min: Level) = entries.filter { it.level >= min }

/** The log, newest first, over everything: time · level · message, a level filter, Share and Clear. */
@Composable
fun LogsScreen(actions: LogsActions, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<LogEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var min by remember { mutableStateOf(Level.DEBUG) }
    var detailed by remember { mutableStateOf(actions.detailed) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { entries = actions.load() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = actions.share) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, contentDescription = "Clear") }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("log-list")) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(actions.header, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Kept on this phone only, for the last 14 days. Share sends it where you choose.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Detailed logging")
                                Text("Also keeps debug lines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = detailed,
                                onCheckedChange = {
                                    detailed = it
                                    actions.setDetailed(it)
                                },
                                modifier = Modifier.testTag("detailed"),
                            )
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (l in Level.entries) {
                                FilterChip(
                                    selected = min == l,
                                    onClick = { min = l },
                                    label = { Text(if (l == Level.DEBUG) "All" else "${l.label}+") },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
                val shown = entries?.let { filtered(it, min) }
                when {
                    shown == null -> item { Text("Reading…", Modifier.padding(16.dp)) }
                    shown.isEmpty() -> item { Text("Nothing logged", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> {
                        var day: String? = null
                        val rows = buildList {
                            for (e in shown) {
                                if (e.day != day) {
                                    day = e.day
                                    add(e.day)
                                }
                                add(e)
                            }
                        }
                        items(rows) { r ->
                            if (r is String) {
                                Text(
                                    r, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            } else {
                                LogRow(r as LogEntry)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the log?") },
            text = { Text("Everything logged on this phone is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        actions.clear()
                        reload++
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val color = when (e.level) {
        Level.ERROR -> MaterialTheme.colorScheme.error
        Level.WARN -> Color(0xFFB26A00)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        "${e.time.substring(11, 19)} · ${e.level.letter} · ${e.tag}: ${e.message}",
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
    )
}
