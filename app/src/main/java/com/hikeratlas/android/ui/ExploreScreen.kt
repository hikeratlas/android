package com.hikeratlas.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.routes.Regions
import com.hikeratlas.android.routes.StateCard
import com.hikeratlas.android.routes.parkLabel
import kotlinx.coroutines.CancellationException
import java.util.Locale

private val MAGENTA = Color(0xFF86198F)
private val GREEN = Color(0xFF14532D)
private val THUMB_BG = Color(0xFFEEF2EA)

/** Where Explore is: the countries (empty), a country ([country]) or a state ([country, state]), by slug. */
typealias ExplorePath = List<String>

private fun count(n: Int, one: String, many: String = "${one}s") = "${String.format(Locale.US, "%,d", n)} ${if (n == 1) one else many}"

/**
 * Browsing the hikes by place, as the website's /hikes pages: the countries
 * and a country's states as tiles comparing their hikes, then a state's
 * parks as cards (their outline, kind and shorter name, best hike and what
 * their hikes pass). A park opens on the map.
 */
@Composable
fun ExploreScreen(
    regions: suspend () -> Regions,
    cards: suspend (String) -> Map<String, StateCard>,
    path: ExplorePath,
    onPath: (ExplorePath) -> Unit,
    onPark: (id: String, name: String) -> Unit,
) {
    var loaded by remember { mutableStateOf<Regions?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        try {
            loaded = regions()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't load the parks: ${e.message ?: e}"
        }
    }
    BackHandler(enabled = path.isNotEmpty()) { onPath(path.dropLast(1)) }
    val r = loaded
    val country = r?.countries?.firstOrNull { it.slug == path.getOrNull(0) }
    val state = country?.states?.firstOrNull { it.slug == path.getOrNull(1) }
    val heading = when {
        state != null -> "Hikes in ${state.name}"
        country != null -> "Hikes in ${country.name}"
        else -> "Hikes in North America"
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path.isNotEmpty()) {
                IconButton(onClick = { onPath(path.dropLast(1)) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            } else {
                Box(Modifier.size(12.dp))
            }
            Text(heading, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        when {
            error != null -> Column(Modifier.padding(20.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { attempt++ }) { Text("Try again") }
            }
            r == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
            state != null && country != null -> ParkCards(state, "${country.slug}/${state.slug}", cards, onPark)
            country != null -> Tiles(
                country.states.map { Tile(it.name, it.hikes, it.parks, it.areas.firstOrNull()?.name) { onPath(listOf(country.slug, it.slug)) } },
            )
            else -> Tiles(
                r.countries.map { c -> Tile(c.name, c.hikes, c.parks, c.states.maxByOrNull { it.hikes }?.name) { onPath(listOf(c.slug)) } },
            )
        }
    }
}

private class Tile(val name: String, val hikes: Int, val parks: Int, val top: String?, val open: () -> Unit)

/** Countries or states, two to a row: their hikes big, in how many parks, a bar against the most, and the one with the most. */
@Composable
private fun Tiles(tiles: List<Tile>) {
    val most = maxOf(1, tiles.maxOfOrNull { it.hikes } ?: 1)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(tiles.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (t in row) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable(onClick = t.open).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(String.format(Locale.US, "%,d", t.hikes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MAGENTA)
                        Text("${if (t.hikes == 1) "hike" else "hikes"} in ${count(t.parks, "park")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color(0xFFF5F5F4))) {
                            Box(Modifier.fillMaxWidth(maxOf(0.02f, t.hikes.toFloat() / most)).height(6.dp).background(Color(0xFFC026D3), RoundedCornerShape(50)))
                        }
                        t.top?.let {
                            Text("Most: ${parkLabel(it).name}", style = MaterialTheme.typography.labelSmall, color = GREEN, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** A state's parks as cards, their extras from the build's cards (until they come, or without them, just regions.json's). */
@Composable
private fun ParkCards(state: Regions.State, key: String, cards: suspend (String) -> Map<String, StateCard>, onPark: (String, String) -> Unit) {
    var extra by remember(key) { mutableStateOf<Map<String, StateCard>>(emptyMap()) }
    LaunchedEffect(key) {
        extra = try { cards(key) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyMap() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.areas, key = { it.id }) { a ->
            val c = extra[a.id]
            val l = parkLabel(a.name, a.id)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { onPark(a.id, a.name) }.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                ParkOutline(c?.outline)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    l.kind?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(l.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    l.near?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        count(a.hikes, "hike") + (c?.top?.let { " · top pick: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (c != null && c.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                            for (t in c.tags.take(3)) {
                                Text(
                                    t, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.background(Color(0xFFF5F5F4), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The park's outline (an SVG path in a 100×100 box), tinted and edged; a mountain where there's none. */
@Composable
private fun ParkOutline(d: String?) {
    val path = remember(d) { d?.let { runCatching { PathParser().parsePathString(it).toPath() }.getOrNull() } }
    Box(Modifier.size(64.dp).background(THUMB_BG, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(56.dp)) {
            if (path != null) {
                // The website's viewBox: -6 -6 112 112.
                val k = size.width / 112f
                translate(6f * k, 6f * k) {
                    scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                        drawPath(path, Color(0x2E15803D))
                        drawPath(path, GREEN, style = Stroke(2.5f))
                    }
                }
            } else {
                val w = size.width
                val m = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.15f, w * 0.75f); lineTo(w * 0.4f, w * 0.35f); lineTo(w * 0.55f, w * 0.6f)
                    lineTo(w * 0.63f, w * 0.48f); lineTo(w * 0.87f, w * 0.75f); close()
                }
                drawPath(m, GREEN.copy(alpha = 0.6f), style = Stroke(1.6.dp.toPx()))
            }
        }
    }
}
