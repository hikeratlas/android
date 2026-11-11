package com.hikeratlas.android.track

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.routes.km
import com.hikeratlas.android.routes.metres
import kotlinx.coroutines.delay
import java.util.Locale

/** 1:04:09, or 4:09 under an hour. */
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s / 60) % 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

/** The track's time, distance and climb so far: 1:04:09 · 3.2 km · ↑ 240 m. */
fun summary(s: TrackRecorder.State, now: Long): String =
    listOfNotNull(duration(s.elapsed(now)), "${km(s.metres / 1000)} km", s.gain?.let { "↑ ${metres(it)} m" }).joinToString(" · ")

/**
 * The recording bar, under search: a dot (red recording, grey paused), the
 * track so far, and pause or resume and stop. Stopping asks first.
 */
@Composable
fun RecordingBar(
    state: TrackRecorder.State,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
) {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(state.paused) {
        now = clock()
        while (!state.paused) {
            delay(1000 - now % 1000)
            now = clock()
        }
    }
    var confirming by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(26.dp), shadowElevation = 4.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (state.paused) Color(0xFF9AA5A0) else Color(0xFFDC2626), CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                (if (state.paused) "Paused · " else "") + summary(state, now),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (state.paused) {
                IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, contentDescription = "Resume recording") }
            } else {
                IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, contentDescription = "Pause recording") }
            }
            IconButton(onClick = { confirming = true }) { Icon(Icons.Filled.Stop, contentDescription = "Stop recording") }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Stop recording?") },
            text = { Text("The track is kept on this phone.") },
            confirmButton = { TextButton(onClick = { confirming = false; onStop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep recording") } },
        )
    }
}
