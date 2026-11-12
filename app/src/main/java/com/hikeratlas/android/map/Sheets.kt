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
import com.hikeratlas.android.routes.Difficulty
import com.hikeratlas.android.routes.Route
import com.hikeratlas.android.routes.ReportPage
import com.hikeratlas.android.routes.RouteItem
import com.hikeratlas.android.routes.RouteReports
import com.hikeratlas.android.routes.SAC_WORDS
import com.hikeratlas.android.routes.TERRAIN_COLORS
import com.hikeratlas.android.routes.TERRAIN_OTHER_STRIPE
import com.hikeratlas.android.routes.WORLDCOVER_URL
import com.hikeratlas.android.routes.alongOf
import com.hikeratlas.android.routes.difficulty
import com.hikeratlas.android.routes.hours
import com.hikeratlas.android.routes.kindName
import com.hikeratlas.android.routes.listWords
import com.hikeratlas.android.routes.routeGpx
import com.hikeratlas.android.routes.timeLabel
import com.hikeratlas.android.routes.whyWords
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.roundToInt
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
    /** A park's country or state, tapped in its crumbs. */
    onCrumb: (Crumb) -> Unit = {},
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
                        // Where the numbers and difficulty will be.
                        Text("", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = 20.dp))
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
            is Panel.RouteList -> if (p.doc != null) {
                // A park: its crumbs and actions on one row, its name below at the sheet's width.
                CrumbsRow(p.crumbs, onCrumb) {
                    if (offline != null && p.parkId != null) {
                        OfflineButton(OfflineAreas.Target(OfflineStore.PARK, p.parkId, p.heading), offline)
                    }
                    if (p.shareUrl != null) {
                        IconButton(onClick = { share(context, p.heading, p.shareUrl) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                    IconButton(onClick = model::close) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                Text(
                    p.heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = if (full) 3 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Column(Modifier.padding(horizontal = 20.dp)) { AboutLine(p.about) }
                Box(Modifier.padding(top = 6.dp)) { ParkTabs(p.doc, model) }
            } else {
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
                    Text(
                        p.selected.label, style = MaterialTheme.typography.bodyMedium, color = MUTED,
                        maxLines = if (full) 3 else 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DifficultyPill(difficulty(r))
                        Text(
                            "${km(r.km)} km · ↑ ${metres(r.gain)} m · ${timeLabel(hours(r), r).removePrefix("about ").let { if (it.endsWith("min")) "~$it" else it }}",
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                                pins = alongOf(r).mapNotNull { f -> f.km?.let { Pin(it * 1000, f.n) } },
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

/** Whether a hike has anything below its peek: it always has its numbers, terrain and actions now. */
@Suppress("UNUSED_PARAMETER")
fun hasDetailBody(d: Panel.Detail) = true

/** The rest of the sheet: lists, the elevation chart, details. Scrolls. */
@Composable
fun PanelBody(model: PanelModel, onScrub: (DoubleArray?) -> Unit) {
    when (val p = model.panel) {
        is Panel.RouteList -> if (p.doc != null) {
            ParkBody(p, p.doc, model)
        } else {
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
        is Panel.Detail -> DetailBody(p, model, onScrub)
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
private fun DetailBody(d: Panel.Detail, model: PanelModel, onScrub: (DoubleArray?) -> Unit) {
    val context = LocalContext.current
    val r = d.selected.route
    val title = d.selected.title
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatTiles(r, d.selected.kind)
        val sac = r.max_sac ?: 0
        if (sac > 0) {
            Text(SAC_WORDS[sac] ?: "SAC T$sac", style = MaterialTheme.typography.bodySmall)
        }
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
        if (r.stopovers.isNotEmpty()) {
            Section("Campsites") {
                for (c in r.stopovers) {
                    ListRow(c.name, "km ${km(c.km)}") { onScrub(doubleArrayOf(c.at[0], c.at[1])) }
                }
            }
        }
        val feats = alongOf(r)
        if (feats.isNotEmpty()) {
            Section("Along the way") {
                for (f in feats) {
                    ListRow(
                        f.name, kindName(f.kind) + (f.km?.let { " · km ${km(it)}" } ?: ""),
                        number = f.n.takeIf { f.km != null },
                    ) { f.at?.let { onScrub(doubleArrayOf(it[0], it[1])) } }
                }
            }
        }
        val terrain = r.terrain.filter { it.second >= 0.03 }
        if (terrain.isNotEmpty()) {
            Section("Terrain") {
                TerrainBar(r.terrain, Modifier.fillMaxWidth().height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                    for ((k, share) in terrain) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TerrainSwatch(k, Modifier.size(10.dp))
                            Text(" $k ${(share * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    "From OpenStreetMap, and ESA WorldCover 2021 (CC BY 4.0) where it has none.",
                    style = MaterialTheme.typography.labelSmall, color = MUTED,
                    modifier = Modifier.padding(top = 6.dp).clickable { openUrl(context, WORLDCOVER_URL) },
                )
            }
        }
        val words = whyWords(d.selected.why, r)
        if (words.isNotEmpty() || r.via.isNotEmpty()) {
            Section("Why it made the list") {
                if (words.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (w in words) {
                            Text(
                                w, style = MaterialTheme.typography.bodySmall, color = Color(0xFF14532D),
                                modifier = Modifier.background(Color(0xFFF0FDF4), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (r.via.isNotEmpty()) {
                    Text(
                        "Follows ${listWords(r.via.take(6))}${if (r.via.size > 6) " and more" else ""}.",
                        style = MaterialTheme.typography.bodySmall, color = MUTED, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        d.selected.base?.let { b ->
            val park = d.selected.park
            val i = d.selected.baseIndex
            Column {
                Text("From ${b.camp}:", color = MUTED, style = MaterialTheme.typography.bodyMedium)
                b.hikes.forEachIndexed { j, h ->
                    val t = h.name?.takeIf { it.isNotEmpty() } ?: h.dest
                    val meta = "${km(h.route.km)} km · ↑ ${metres(h.route.gain)} m" + if (h.route.scramble) " · scramble" else ""
                    LinkRow(t, meta) { model.openRoute(park, "$park/base/$i/$j", hint = HikeHint(t, meta)) }
                }
                LinkRow("The walk in", null) { model.openRoute(park, "$park/base/$i") }
            }
        }
        d.reports?.let { ReportsSection(it, d.selected.dest) }
        // The way there, the route for a GPS app, and a link to it.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val start = r.line.firstOrNull()
            Button(onClick = { start?.let { directions(context, it[1], it[0], title) } }, enabled = start != null, modifier = Modifier.weight(1f)) {
                Text("Directions to the trailhead", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { shareGpx(context, title, r.line) }, enabled = r.line.size >= 2) { Text("GPX") }
            if (d.shareUrl != null) OutlinedButton(onClick = { share(context, title, d.shareUrl) }) { Text("Share") }
        }
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2018-08" -> "Aug 2018"; "2018" -> "2018". */
internal fun reportDate(date: String?): String {
    if (date.isNullOrEmpty()) return ""
    val parts = date.split('-')
    val m = parts.getOrNull(1)?.toIntOrNull()
    return if (m != null && m in 1..12) "${MONTHS[m - 1]} ${parts[0]}" else parts[0]
}

/** "alltrails.com · Aug 2018 · trip report". */
internal fun reportMeta(p: ReportPage): String = listOf(
    p.site.removePrefix("www."), reportDate(p.date),
    when (p.type) { "trail_guide" -> "trail guide"; "trip_report" -> "trip report"; else -> "" },
).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * Pages on other sites about the route: trip reports and guides that follow
 * it, then, folded, pages about its destination. Each links out.
 */
@Composable
private fun ReportsSection(r: RouteReports, dest: String?) {
    if (r.reports.isEmpty() && r.about.isEmpty()) return
    val context = LocalContext.current
    var aboutOpen by remember(r) { mutableStateOf(r.reports.isEmpty()) }
    @Composable
    fun Pages(pages: List<ReportPage>) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            for (p in pages) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)).clickable { openUrl(context, p.url) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    p.thumb?.let { RemoteThumb(it, Modifier.size(width = 72.dp, height = 54.dp).clip(RoundedCornerShape(8.dp))) }
                    Column(Modifier.weight(1f)) {
                        Text(p.title.ifEmpty { p.site }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(reportMeta(p), style = MaterialTheme.typography.bodySmall, color = MUTED)
                    }
                }
            }
        }
    }
    Column {
        if (r.reports.isNotEmpty()) {
            Text("Trip reports and guides", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Pages(r.reports)
        }
        if (r.about.isNotEmpty()) {
            Text(
                "${if (aboutOpen) "▾" else "▸"} ${dest?.let { "More about $it" } ?: "More pages"} (${r.about.size})",
                style = MaterialTheme.typography.bodyMedium, color = MUTED,
                modifier = Modifier.fillMaxWidth().clickable { aboutOpen = !aboutOpen }.padding(vertical = 10.dp),
            )
            if (aboutOpen) Pages(r.about)
        }
    }
}

/** Distance (and shape), climb, walking time and high point, two to a row. */
@Composable
private fun StatTiles(r: Route, kind: String) {
    val shape = when (kind) {
        "loop" -> "loop"
        "out-and-back" -> "there and back"
        "point-to-point" -> "point to point"
        else -> "one way"
    }
    val tiles = listOf(
        "${km(r.km)} km" to shape,
        "↑ ${metres(r.gain)} m" to "climbing",
        timeLabel(hours(r), r).replace(Regex("^about "), "~") to "walking time",
        "${metres(r.max_ele)} m" to "high point",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((v, l) in row) {
                    Column(
                        Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(v, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(l, style = MaterialTheme.typography.labelSmall, color = MUTED)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

/** A row in a sheet's list: a name, a note on the right, and a number where it's pinned on the chart. */
@Composable
private fun ListRow(name: String, note: String, number: Int? = null, onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 40.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (number != null) {
            Box(Modifier.size(24.dp).background(PIN, CircleShape), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(note, style = MaterialTheme.typography.bodySmall, color = MUTED)
    }
}

private val PIN = Color(0xFFA21CAF)

/** "Easy", "Moderate", "Hard", "Scramble", coloured as the website's. */
@Composable
fun DifficultyPill(d: Difficulty) {
    val (bg, fg) = when (d) {
        Difficulty.Easy -> Color(0xFFECFDF5) to Color(0xFF065F46)
        Difficulty.Moderate -> Color(0xFFFEF3C7) to Color(0xFF78350F)
        Difficulty.Hard -> Color(0xFFFFEDD5) to Color(0xFF9A3412)
        Difficulty.Scramble -> Color(0xFFFEE2E2) to Color(0xFF7F1D1D)
    }
    Text(
        d.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A route's land cover as one bar, each kind its share; the unknown share striped. */
@Composable
fun TerrainBar(terrain: List<Pair<String, Double>>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(50))) {
        val total = terrain.sumOf { it.second }.takeIf { it > 0 } ?: return@Canvas
        var x = 0f
        for ((k, share) in terrain) {
            val w = (share / total * size.width).toFloat()
            drawTerrain(k, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height))
            x += w
        }
    }
}

@Composable
private fun TerrainSwatch(kind: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(2.dp))) {
        drawTerrain(kind, androidx.compose.ui.geometry.Offset.Zero, size)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTerrain(
    kind: String, at: androidx.compose.ui.geometry.Offset, sz: androidx.compose.ui.geometry.Size,
) {
    val base = Color(TERRAIN_COLORS[kind] ?: 0xFFD6D3D1)
    drawRect(base, at, sz)
    if (kind == "other") {
        // Diagonal stripes: unknown, not a kind of ground.
        val step = 6.dp.toPx()
        clipRect(at.x, at.y, at.x + sz.width, at.y + sz.height) {
            var sx = at.x - sz.height
            while (sx < at.x + sz.width) {
                drawLine(Color(TERRAIN_OTHER_STRIPE), androidx.compose.ui.geometry.Offset(sx, at.y + sz.height), androidx.compose.ui.geometry.Offset(sx + sz.height, at.y), strokeWidth = 3.dp.toPx())
                sx += step
            }
        }
    }
}

/** Shares a curated route as GPX, through Android's share sheet. */
private fun shareGpx(context: Context, title: String, line: List<List<Double>>) {
    val dir = java.io.File(context.cacheDir, "gpx").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = java.io.File(dir, com.hikeratlas.android.track.Gpx.fileName(title)).apply { writeText(routeGpx(title, line)) }
    context.startActivity(com.hikeratlas.android.track.gpxShareIntent(context, file, title))
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
