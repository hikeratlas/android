package com.hikeratlas.android.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.Faves
import com.hikeratlas.android.atproto.SignedOutException
import com.hikeratlas.android.routes.KeyedHike
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Faving hikes, for the hike sheet's heart. */
class FaveActions(
    /** Who's signed in, and signing in (the dialog offered when signed out). */
    val account: AccountActions,
    /** Whether the route with this key is faved. */
    val isFaved: suspend (key: String) -> Boolean,
    /** Faves or unfaves it. */
    val setFave: suspend (key: String, on: Boolean) -> Unit,
)

/** The website's heart colour (Tailwind's rose-700). */
private val FAVED = Color(0xFFBE123C)

/**
 * The hike sheet's heart, as the website's FaveButton: saves the hike to
 * your faves, or signed out, offers to sign in. Only for a route with a key
 * from the build.
 */
@Composable
fun FaveButton(key: String, title: String, faves: FaveActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = faves.account.state
    val did = (state as? AccountState.In)?.did
    // Null: not known (signed out, still asking, or the asking failed).
    var faved by remember(key, did) { mutableStateOf<Boolean?>(null) }
    var busy by remember(key) { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(key, did) {
        if (did == null) return@LaunchedEffect
        faved = try {
            faves.isFaved(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val on = faved == true
    IconButton(
        onClick = {
            if (did == null) {
                faves.account.clearError()
                asking = true
                return@IconButton
            }
            val want = !on
            busy = true
            faved = want
            scope.launch {
                try {
                    faves.setFave(key, want)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SignedOutException) {
                    faved = null
                    asking = true
                } catch (e: Exception) {
                    faved = !want
                    val what = if (want) "Saving" else "Removing"
                    Toast.makeText(context, "$what the fave failed: ${e.message ?: e}", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy && state !is AccountState.Loading && state !is AccountState.SigningIn,
    ) {
        Icon(
            if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (on) "Remove from your faves" else "Save to your faves",
            tint = if (on) FAVED else LocalContentColor.current,
        )
    }
    if (asking) SignInDialog(faves.account, onClose = { asking = false }, why = "Sign in to save $title to your faves.")
}

/** The Saved tab's faved hikes. */
sealed interface FavedState {
    data object Loading : FavedState
    data class Failed(val message: String) : FavedState
    /**
     * The faves, newest first, and the hikes they are: [found] is null
     * while they're being looked for. [canFind] is false where they
     * couldn't be (no network for the build's index, or no H3 here).
     */
    data class Ready(
        val faves: List<Faves.Fave>,
        val found: Map<String, KeyedHike>? = null,
        val canFind: Boolean = true,
    ) : FavedState
}

/**
 * The faves ([list]), then the hikes they are ([find], for the keys not
 * already in [known]), each step given to [emit] as it's done.
 */
suspend fun loadFaved(
    list: suspend () -> List<Faves.Fave>,
    find: suspend (Collection<String>) -> Map<String, KeyedHike>,
    known: Map<String, KeyedHike>,
    canFind: Boolean,
    emit: (FavedState) -> Unit,
) {
    val faves = try {
        list()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emit(FavedState.Failed(e.message ?: e.toString()))
        return
    }
    val missing = faves.map { it.key }.filter { it !in known }.distinct()
    if (missing.isEmpty()) {
        emit(FavedState.Ready(faves, known))
        return
    }
    if (!canFind) {
        emit(FavedState.Ready(faves, known, false))
        return
    }
    emit(FavedState.Ready(faves, null))
    try {
        emit(FavedState.Ready(faves, known + find(missing), true))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emit(FavedState.Ready(faves, known, false))
    }
}

private val DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())

/** "Nov 14, 2026", as the website shows a fave's date; "" if it can't be read. */
internal fun faveDay(createdAt: String): String = try {
    DAY.format(Instant.parse(createdAt))
} catch (_: Exception) {
    ""
}

/**
 * The Saved tab's "Faved hikes", as the website's profile page has them:
 * newest first, each opening its hike; one that's no current hike says so.
 */
fun LazyListScope.favedHikesSection(state: FavedState, onOpen: (KeyedHike) -> Unit, onRetry: () -> Unit) {
    item(key = "faves-header") {
        Text(
            "Faved hikes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
    }
    when (state) {
        FavedState.Loading -> item(key = "faves-loading") {
            Text(
                "Loading your faves…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        is FavedState.Failed -> item(key = "faves-failed") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Couldn't load your faves: ${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
        is FavedState.Ready -> {
            if (state.faves.isEmpty()) {
                item(key = "faves-none") {
                    Text(
                        "No faved hikes yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            items(state.faves, key = { "fave-${it.key}" }) { f ->
                val h = state.found?.get(f.key)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(
                    Modifier.fillMaxWidth().let { if (h != null) it.clickable { onOpen(h) } else it }.heightIn(min = 56.dp)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    when {
                        h != null -> Text(h.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        state.found == null -> Text("Finding the hike…", style = MaterialTheme.typography.titleSmall, color = muted)
                        state.canFind -> Text("A hike no longer on Hiker Atlas", style = MaterialTheme.typography.titleSmall, color = muted)
                        else -> Text("A hike that couldn't be looked up", style = MaterialTheme.typography.titleSmall, color = muted)
                    }
                    val sub = listOfNotNull(h?.parkName?.takeIf { it.isNotEmpty() }, faveDay(f.createdAt).takeIf { it.isNotEmpty() }).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}
