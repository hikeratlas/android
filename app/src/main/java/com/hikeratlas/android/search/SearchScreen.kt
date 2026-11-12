package com.hikeratlas.android.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Water
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Full-screen search, over the map. Results are near [nearLat]/[nearLon] (the
 * map's centre) when given.
 */
@Composable
fun SearchScreen(
    api: SearchApi,
    recents: RecentSearches,
    nearLat: Double?,
    nearLon: Double?,
    onPick: (SearchItem) -> Unit,
    onClose: () -> Unit,
    /** When search can't be reached (offline): the downloaded parks and hikes instead. */
    offline: (suspend (String, Double?, Double?) -> List<SearchItem>)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // The query [results] are for: "No matches" only once its search is back,
    // not while a newer one's out.
    var answered by remember { mutableStateOf<String?>(null) }
    // Whether [results] came from the downloaded areas, search being out of reach.
    var local by remember { mutableStateOf(false) }
    val recent = remember { recents.load() }
    val focus = remember { FocusRequester() }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); error = null; answered = null; local = false; return@LaunchedEffect }
        delay(200) // wait for a pause in typing
        val q = query.trim()
        try {
            results = api.search(q, nearLat, nearLon)
            answered = q
            error = null
            local = false
        } catch (e: CancellationException) {
            // A newer query took over (typing on): not a failure.
            throw e
        } catch (e: Exception) {
            try {
                results = offline?.invoke(q, nearLat, nearLon) ?: throw e
                answered = q
                error = null
                local = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Search isn't available right now."
            }
        }
    }

    val showingRecent = query.isBlank()
    val items = if (showingRecent) recent else results
    val lines = if (showingRecent) items.map(SearchApi::describe) else SearchApi.secondLines(items, nearLat, nearLon)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to map") }
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search hikes, parks, peaks, trails") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { recents.add(it); onPick(it) } }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
            }
        }
        HorizontalDivider()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (!showingRecent && error == null && local) {
            Text(
                "Offline: searching your downloaded parks and hikes",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
        if (!showingRecent && error == null && results.isEmpty() && answered == query.trim()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (local) "No downloaded park or hike matches \u201c${query.trim()}\u201d" else "No matches for \u201c${query.trim()}\u201d",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (local) "Peaks, trails and places need a connection." else "Try a hike, park, peak or trail's name, or a town near it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (showingRecent && items.isNotEmpty()) {
                item {
                    Text(
                        "RECENT",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
            }
            itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { recents.add(item); onPick(item) }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    KindIcon(if (showingRecent) null else item.kind)
                    Column(Modifier.weight(1f)) {
                        Text(if (showingRecent) AnnotatedString(item.name) else highlight(item.name, query), style = MaterialTheme.typography.bodyLarge)
                        Text(lines[i], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun KindIcon(kind: String?) {
    val (icon, tint, bg) = when (kind) {
        null -> Triple(Icons.Filled.History, Color(0xFF4F5D55), Color.Transparent)
        "hike" -> Triple(Icons.Filled.Hiking, Color(0xFFA21CAF), Color(0xFFF6E3F8))
        "peak" -> Triple(Icons.Filled.Landscape, Color(0xFF6B4A2E), Color(0xFFEFE6DC))
        "trail" -> Triple(Icons.Filled.Route, Color(0xFF6B4A2E), Color(0xFFEFE6DC))
        "park" -> Triple(Icons.Filled.Park, Color(0xFF1F4D2C), Color(0xFFD6EBDC))
        "lake", "waterfall" -> Triple(Icons.Filled.Water, Color(0xFF1D5F86), Color(0xFFDDEFF9))
        else -> Triple(Icons.Filled.Place, Color(0xFF4F5D55), Color(0xFFEEF2EF))
    }
    Box(Modifier.size(40.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** The name with each word's start that a query word begins in bold, as search matches them. */
private fun highlight(name: String, query: String): AnnotatedString {
    val tokens = fold(query).split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.sortedByDescending { it.length }
    return buildAnnotatedString {
        for (m in Regex("[\\p{L}\\p{N}'’]+|[^\\p{L}\\p{N}'’]+").findAll(name)) {
            val word = m.value
            val t = tokens.firstOrNull { fold(word).startsWith(it) }
            if (t == null || t.length > word.length) {
                append(word)
            } else {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word.substring(0, t.length)) }
                append(word.substring(t.length))
            }
        }
    }
}
