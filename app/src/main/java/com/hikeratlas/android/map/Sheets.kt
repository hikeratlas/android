package com.hikeratlas.android.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import com.hikeratlas.android.offline.OfflineActions
import com.hikeratlas.android.offline.OfflineAreas
import com.hikeratlas.android.offline.OfflineButton
import com.hikeratlas.android.offline.OfflineStore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.routes.RouteItem
import com.hikeratlas.android.routes.km
import com.hikeratlas.android.routes.metres
import java.net.URI
import java.util.Locale

private val MUTED @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** The sheet's title row: a title (one line unless [full]) and a close button. */
@Composable
private fun TitleRow(title: String, full: Boolean, onClose: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = if (full) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
    }
}

/** Header and body together, as one column: for tests and simple hosts. */
@Composable
fun PanelContent(model: PanelModel, onScrub: (DoubleArray?) -> Unit) {
    Column {
        PanelHeader(model, full = true, onScrub)
        PanelBody(model, onScrub)
    }
}

/**
 * The part of the sheet that stays put: what it is, the key numbers and the
 * actions. At the sheet's lowest height, it's all that shows.
 */
@Composable
fun PanelHeader(
    model: PanelModel,
    full: Boolean,
    onScrub: (DoubleArray?) -> Unit = {},
    /** Downloading a park or hike for offline use, from its sheet; none where it's not offered. */
    offline: OfflineActions? = null,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        when (val p = model.panel) {
            Panel.None -> {}
            is Panel.Loading -> {
                TitleRow(p.heading, full, model::close)
                if (p.hike != null) {
                    // Laid out as the hike's header will be, so the sheet
                    // doesn't change height when it arrives.
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.hike.meta ?: "", style = MaterialTheme.typography.bodyMedium, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.fillMaxWidth().height(CHART_HEIGHT), contentAlignment = Alignment.Center) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
            is Panel.Failed -> {
                TitleRow(p.heading, full, model::close)
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Couldn't load: ${p.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = p.retry) { Text("Try again") }
                }
            }
            is Panel.RouteList -> {
                TitleRow(p.heading, full, model::close, actions = {
                    if (offline != null && p.parkId != null) {
                        OfflineButton(OfflineAreas.Target(OfflineStore.PARK, p.parkId, p.heading), offline)
                    }
                    if (p.shareUrl != null) {
                        IconButton(onClick = { share(context, p.heading, p.shareUrl) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                })
                Column(Modifier.padding(horizontal = 20.dp)) { AboutLine(p.about) }
                val sections = p.items.mapNotNull { it.section }.distinct()
                if (sections.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (sec in sections) {
                            FilterChip(
                                selected = sec == model.section,
                                onClick = { model.section = sec },
                                label = { Text("$sec ${p.items.count { it.section == sec }}") },
                            )
                        }
                    }
                } else if (p.items.isNotEmpty()) {
                    Text(
                        "${p.items.size} ${if (p.items.size == 1) "route" else "routes"}",
                        style = MaterialTheme.typography.bodyMedium, color = MUTED,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            is Panel.Trail -> {
                TitleRow(p.name, full, model::close)
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.subtitle, style = MaterialTheme.typography.bodyMedium, color = MUTED)
                    val m = { x: Double -> "${metres(x)} m" }
                    val prof = p.profile
                    val range = p.range
                    when {
                        prof != null && range != null -> Text(
                            "↑ ${m(prof.gain)} · ↓ ${m(prof.loss)} " +
                                (p.along?.let { "along ${km(it / 1000)} km of it" } ?: "end to end") +
                                " · high ${m(range.second)} · low ${m(range.first)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        range != null -> Text("High ${m(range.second)} · low ${m(range.first)}", style = MaterialTheme.typography.bodyMedium)
                        p.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                }
            }
            is Panel.Detail -> {
                val r = p.selected.route
                // The actions are icons beside the title, not a row of their
                // own: the peek is the title, one line of numbers and the chart.
                TitleRow(p.selected.title, full, model::close, actions = {
                    val start = r.line.firstOrNull()
                    if (start != null) {
                        IconButton(onClick = { directions(context, start[1], start[0], p.selected.title) }) {
                            Icon(Icons.Filled.Directions, contentDescription = "Directions to the start", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (offline != null) OfflineButton(OfflineAreas.Target(OfflineStore.HIKE, p.selected.id, p.selected.title), offline)
                    if (p.shareUrl != null) {
                        IconButton(onClick = { share(context, p.selected.title, p.shareUrl) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                })
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val type = if (full) p.selected.label else p.selected.label.substringBefore(" · ")
                    Text(
                        "$type · ${km(r.km)} km · ↑ ${metres(r.gain)} m · high ${metres(r.max_ele)} m" + if (r.scramble) " · scramble" else "",
                        style = MaterialTheme.typography.bodyMedium, color = MUTED,
                        // Two lines at most: a scramble's note can need the second.
                        maxLines = if (full) 3 else 2, overflow = TextOverflow.Ellipsis,
                    )
                    // The same height loading or not, so the peek doesn't jump.
                    Box(Modifier.fillMaxWidth().height(CHART_HEIGHT), contentAlignment = Alignment.CenterStart) {
                        when (val prof = p.profile) {
                            ProfileState.Loading -> Text("Measuring elevation…", color = MUTED, style = MaterialTheme.typography.bodySmall)
                            is ProfileState.Failed -> Text("Couldn't measure elevation", color = MUTED, style = MaterialTheme.typography.bodySmall)
                            is ProfileState.Ready -> ElevationChart(
                                prof.profile,
                                r.stopovers.map { Mark(it.km * 1000, it.name) },
                                onScrub = { i -> onScrub(i?.let { prof.profile.coords[it] }) },
                                height = CHART_HEIGHT,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The hike chart's height in the sheet's peek: the route stays in view above it. */
private val CHART_HEIGHT = 96.dp

/** Whether a hike has anything below its peek: a scramble warning, or a base camp's hikes. */
fun hasDetailBody(d: Panel.Detail) = d.selected.route.scramble || d.selected.base != null

/** The rest of the sheet: lists, the elevation chart, details. Scrolls. */
@Composable
fun PanelBody(model: PanelModel, onScrub: (DoubleArray?) -> Unit) {
    when (val p = model.panel) {
        is Panel.RouteList -> {
            if (p.items.isEmpty()) {
                Text("No curated hikes here yet.", color = MUTED, modifier = Modifier.padding(20.dp))
                return
            }
            val sections = p.items.mapNotNull { it.section }.distinct()
            val shown = if (sections.size > 1) p.items.filter { it.section == model.section } else p.items
            Column(Modifier.padding(bottom = 16.dp)) {
                for (item in shown) RouteRow(item) { model.openRoute(item.park, item.id, hint = HikeHint(item.title, item.meta)) }
            }
        }
        is Panel.Detail -> DetailBody(p, model)
        is Panel.Trail -> TrailBody(p, model, onScrub)
        else -> {}
    }
}

/** A trail's elevation, and the park's curated hikes that walk it. */
@Composable
private fun TrailBody(t: Panel.Trail, model: PanelModel, onScrub: (DoubleArray?) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val prof = t.profile
        when {
            prof != null -> ElevationChart(prof, emptyList(), onScrub = { i -> onScrub(i?.let { prof.coords[it] }) })
            t.loading -> Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.CenterStart) {
                Text("Measuring elevation…", color = MUTED)
            }
            t.tooLong -> Text("Too long to measure here.", color = MUTED, style = MaterialTheme.typography.bodySmall)
        }
        val hikes = t.hikes
        if (!hikes.isNullOrEmpty()) {
            Column {
                Text("Hikes on this trail", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                for (h in hikes) LinkRow(h.title, h.meta) { model.openRoute(h.park, h.id, hint = HikeHint(h.title, h.meta)) }
            }
        } else if (!t.loading && hikes != null) {
            Column {
                Text("No curated hikes use this trail yet.", color = MUTED)
                if (t.parkId != null) {
                    LinkRow("Hikes in ${t.parkName ?: "the park"}", null) { model.openPark(t.parkId, t.parkName ?: "") }
                }
            }
        }
    }
}

@Composable
private fun AboutLine(about: ParkAbout?) {
    if (about == null) return
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        about.operator?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MUTED) }
        if (about.operator != null && about.website != null) Text(" · ", color = MUTED)
        about.website?.let { url ->
            Text(
                "${hostOf(url)} ↗",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl(context, url) }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RouteRow(item: RouteItem, onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(item.meta, style = MaterialTheme.typography.bodySmall, color = MUTED)
    }
}

@Composable
private fun DetailBody(d: Panel.Detail, model: PanelModel) {
    val r = d.selected.route
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (r.scramble) {
            Text(
                "Includes scrambling (SAC T4, alpine hiking): expect to use your hands on rock, exposed sections, and possibly snow. Some sections may need route-finding.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78350F),
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFFFFFBEB), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
        }
        d.selected.base?.let { b ->
            val park = d.selected.park
            val i = d.selected.baseIndex
            Column {
                Text("From ${b.camp}:", color = MUTED, style = MaterialTheme.typography.bodyMedium)
                b.hikes.forEachIndexed { j, h ->
                    val title = h.name?.takeIf { it.isNotEmpty() } ?: h.dest
                    val meta = "${km(h.route.km)} km · ↑ ${metres(h.route.gain)} m" + if (h.route.scramble) " · scramble" else ""
                    LinkRow(title, meta) { model.openRoute(park, "$park/base/$i/$j", hint = HikeHint(title, meta)) }
                }
                LinkRow("The walk in", null) { model.openRoute(park, "$park/base/$i") }
            }
        }
    }
}


@Composable
private fun LinkRow(title: String, meta: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(vertical = 6.dp)) {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
        if (meta != null) Text(meta, color = MUTED, style = MaterialTheme.typography.bodySmall)
    }
}

/** The peek row: how many curated hikes are in view. */
@Composable
fun InViewPeek(count: Int, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "$count curated ${if (count == 1) "hike" else "hikes"} in view",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("Tap to list them, or tap a magenta route on the map", style = MaterialTheme.typography.bodySmall, color = MUTED)
    }
}

fun hostOf(url: String): String = try {
    URI(url).host?.removePrefix("www.") ?: url
} catch (e: Exception) {
    url
}

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}

private fun share(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** Hands the trailhead to whichever maps app the user has. */
private fun directions(context: Context, lat: Double, lon: Double, label: String) {
    val q = String.format(Locale.US, "%.6f,%.6f", lat, lon)
    val uri = "geo:$q?q=$q(${Uri.encode(label)})".toUri()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: android.content.ActivityNotFoundException) {
        openUrl(context, "https://www.openstreetmap.org/directions?to=${Uri.encode(q)}")
    }
}
