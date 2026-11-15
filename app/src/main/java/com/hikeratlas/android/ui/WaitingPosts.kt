package com.hikeratlas.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.outbox.Outbox

/** The posts waiting on the phone to go up, and what can be done with them. */
class WaitingActions(
    val entries: List<Outbox.Entry>,
    /** The one going up now. */
    val active: String?,
    val retry: (String) -> Unit,
    val discard: (String) -> Unit,
)

/** What a waiting post is: "Review, track and 2 photos". */
internal fun waitingWhat(e: Outbox.Entry): String {
    val parts = listOfNotNull(
        "review".takeIf { e.review },
        "track".takeIf { e.hasGpx && e.post.track == null },
        e.post.photos.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "photo" else "photos"}" },
    )
    val s = when (parts.size) {
        0 -> "post"
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
    return s.replaceFirstChar { it.uppercase() }
}

/** Where a waiting post is at, in words. */
internal fun waitingState(e: Outbox.Entry, active: String?): String = when {
    e.id == active -> "Uploading…"
    e.state == Outbox.State.WAITING -> if (e.error != null) "Waiting for signal (last try: ${e.error})" else "Waiting for signal"
    e.state == Outbox.State.NEEDS_SIGN_IN -> "Needs you to sign in again: your session ended"
    e.state == Outbox.State.OTHER_ACCOUNT ->
        "Held: made as ${e.handle?.let { "@$it" } ?: "another account"}, which isn't signed in. It goes up once it signs in again."
    else -> "Failed: ${e.error ?: "refused"}"
}

/** The waiting posts, for a lazy list (your profile's Overview, above your posts). */
fun LazyListScope.waitingPostsSection(w: WaitingActions) {
    if (w.entries.isEmpty()) return
    item(key = "waiting-header") { WaitingHeader() }
    items(w.entries, key = { "waiting-${it.id}" }) { e -> WaitingRow(e, w, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
}

/** How many items [waitingPostsSection] adds. */
internal fun waitingItemCount(w: WaitingActions?): Int = if (w == null || w.entries.isEmpty()) 0 else 1 + w.entries.size

/** The waiting posts, not in a lazy list (the profile still loading, or the You tab). */
@Composable
fun WaitingPosts(w: WaitingActions, modifier: Modifier = Modifier) {
    if (w.entries.isEmpty()) return
    Column(modifier) {
        WaitingHeader()
        for (e in w.entries) WaitingRow(e, w, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

@Composable
private fun WaitingHeader() {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
        Text("Waiting to post", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Made without signal: they go up by themselves once there is.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WaitingRow(e: Outbox.Entry, w: WaitingActions, modifier: Modifier) {
    var confirming by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val going = e.id == w.active
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp),
    ) {
        Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(waitingWhat(e), style = MaterialTheme.typography.bodyMedium)
        Text(
            waitingState(e, w.active), style = MaterialTheme.typography.bodySmall,
            color = if (e.state == Outbox.State.FAILED && !going) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { confirming = true }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { w.retry(e.id) }, enabled = !going) { Text("Retry") }
        }
    }
    if (confirming) {
        val upAlready = e.progress.track != null && e.post.track == null
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Discard this post?") },
            text = {
                Text(
                    if (upAlready) "It's deleted from this phone. Its track went up already, and stays on your profile."
                    else "It's deleted from this phone, and won't be posted.",
                )
            },
            confirmButton = { TextButton(onClick = { confirming = false; w.discard(e.id) }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep") } },
        )
    }
}
