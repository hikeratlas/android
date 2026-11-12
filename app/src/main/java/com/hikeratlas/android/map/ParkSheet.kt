package com.hikeratlas.android.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.routes.Difficulty
import com.hikeratlas.android.routes.LngLat
import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.ParkHike
import com.hikeratlas.android.routes.RouteReports
import com.hikeratlas.android.routes.Sort
import com.hikeratlas.android.routes.TimeBand
import com.hikeratlas.android.routes.km
import com.hikeratlas.android.routes.matches
import com.hikeratlas.android.routes.metres
import com.hikeratlas.android.routes.parkHikes
import com.hikeratlas.android.routes.sorted
import com.hikeratlas.android.routes.timeBand
import com.hikeratlas.android.routes.timeLabel
import com.hikeratlas.android.routes.trailhead
import kotlin.math.cos
import kotlin.math.roundToInt

// A park's sheet, as the website's (its #103 redesign): its counts as tabs,
// filters over its day hikes, and a card per hike with its shape, numbers,
// difficulty, terrain and trip reports. Base camps, multi-day trips and stops
// are a tab away.

private val MUTED @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val GREEN = Color(0xFF14532D)
private val THUMB_BG = Color(0xFFEEF2EA)
private val ROUTE = Color(0xFFC026D3)

/** A park's day hikes for its sheet, with their trip reports counted. */
fun parkHikesOf(doc: ParkDoc, reports: Map<String, RouteReports>?): List<ParkHike> =
    parkHikes(doc) { key -> reports?.get(key)?.reports?.size ?: 0 }

/** What each tab of a park lists, and how many. */
internal fun parkTabs(doc: ParkDoc): List<Triple<ParkTab, Int, String>> {
    val multi = doc.point_to_points.size + doc.see_also.size
    fun label(n: Int, one: String, many: String) = if (n == 1) one else many
    return listOf(
        Triple(ParkTab.Day, doc.day_hikes.size, label(doc.day_hikes.size, "day hike", "day hikes")),
        Triple(ParkTab.Camps, doc.base_camps.size, label(doc.base_camps.size, "base camp", "base camps")),
        Triple(ParkTab.Multi, multi, label(multi, "multi-day trip", "multi-day trips")),
        Triple(ParkTab.Stops, doc.stops.size, label(doc.stops.size, "quick stop", "quick stops")),
    ).filter { it.second > 0 }
}

/** The park's counts as tabs: in the sheet's peek. */
@Composable
fun ParkTabs(doc: ParkDoc, model: PanelModel) {
    val tabs = parkTabs(doc)
    if (tabs.size <= 1) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((tab, n, label) in tabs) {
            val on = model.parkTab == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .border(1.dp, if (on) GREEN else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .background(if (on) Color(0xFFF0FDF4) else Color.Transparent)
                    .clickable { model.parkTab = tab }.padding(10.dp),
            ) {
                Text(n.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = GREEN)
                Text(label, style = MaterialTheme.typography.labelSmall, color = GREEN, maxLines = 2)
            }
        }
    }
}

/** The park's crumbs, its country and state, left; the sheet's actions right: one row above its name. */
@Composable
fun CrumbsRow(crumbs: List<Crumb>, onCrumb: (Crumb) -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { i, c ->
                if (i > 0) Text(" › ", style = MaterialTheme.typography.labelMedium, color = MUTED)
                Text(
                    c.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = c.bbox != null) { onCrumb(c) }.heightIn(min = 40.dp).padding(vertical = 10.dp),
                )
            }
        }
        actions()
    }
}

/** The park's list: the tab that's open. */
@Composable
fun ParkBody(p: Panel.RouteList, doc: ParkDoc, model: PanelModel) {
    val park = p.parkId ?: doc.park?.id ?: ""
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (model.parkTab) {
            ParkTab.Day -> DayHikes(p, doc, model, park)
            ParkTab.Camps -> Others(
                "Base camps", "Walk in once, camp, and do day hikes from camp.",
                doc.base_camps.mapIndexed { i, b ->
                    Other(
                        b.hikes.size.toString(), b.camp,
                        "${km(b.approach.km)} km walk in from ${trailhead(b.from)} · ↑ ${metres(b.approach.gain)} m · ${b.hikes.size} day ${if (b.hikes.size == 1) "hike" else "hikes"} from camp",
                    ) { model.openRoute(park, "$park/base/$i", hint = HikeHint(b.camp, null)) }
                },
            )
            ParkTab.Multi -> Others(
                "Multi-day trips", "Backpacking routes, campground to campground.",
                doc.point_to_points.mapIndexed { i, t ->
                    val title = t.name?.takeIf { it.isNotEmpty() } ?: "${trailhead(t.from)} to ${trailhead(t.to)}"
                    val camps = t.route.stopovers.size
                    Other(
                        t.route.km.roundToInt().toString(), title,
                        "${t.route.km.roundToInt()} km · ↑ ${metres(t.route.gain)} m · ${if (t.kind == "loop") "loop" else "one way"}" +
                            if (camps > 0) " · $camps ${if (camps == 1) "campground" else "campgrounds"} along it" else "",
                    ) { model.openRoute(park, "$park/multi/$i", hint = HikeHint(title, null)) }
                } + doc.see_also.map { e ->
                    val title = e.name?.takeIf { it.isNotEmpty() } ?: "${trailhead(e.from)} to ${trailhead(e.to)}"
                    Other(
                        e.km.roundToInt().toString(), title,
                        "${e.km.roundToInt()} km · ↑ ${metres(e.gain)} m · ${if (e.kind == "loop") "loop" else "one way"} · in ${e.park_name}",
                    ) { model.openRoute(e.park, e.id, hint = HikeHint(title, null)) }
                },
            )
            ParkTab.Stops -> Others(
                "Quick stops", "A short walk from the car to a view: for a road trip or a rest day.",
                doc.stops.mapIndexed { i, s ->
                    Other("", s.dest, quickStop(s.route.one_way_km ?: (s.route.km / 2), s.from)) {
                        model.openRoute(park, "$park/stop/$i", hint = HikeHint(s.dest, null))
                    }
                },
            )
        }
    }
}

/** "By the road", "0.8 km there and back from Lot 3". */
internal fun quickStop(oneWayKm: Double, from: String): String {
    val end = from == "Road's end"
    if (oneWayKm < 0.05) return if (end) "By the road" else "By the road at ${trailhead(from)}"
    return "${km(oneWayKm * 2)} km there and back from ${if (end) "the road's end" else trailhead(from)}"
}

private val TIME_LABELS = listOf(null to "Any length", TimeBand.Short to "Under 2 h", TimeBand.Half to "2–5 h", TimeBand.Full to "Full day")

@Composable
private fun DayHikes(p: Panel.RouteList, doc: ParkDoc, model: PanelModel, park: String) {
    val hikes = remember(doc, p.reports) { parkHikesOf(doc, p.reports) }
    val f = model.filters
    val shown = sorted(hikes.filter { matches(it, f) }, model.sort)
    fun count(pred: (ParkHike) -> Boolean) = hikes.count(pred)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Find a hike", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        SortMenu(model.sort) { model.sort = it }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((band, label) in TIME_LABELS) {
            Chip(f.time == band, if (band == null) label else "$label · ${count { timeBand(it.hours) == band }}") { model.filters = f.copy(time = band) }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in Difficulty.entries) {
            val n = count { it.diff == d }
            if (n > 0) Chip(d in f.diffs, "${d.label} · $n") { model.filters = f.copy(diffs = if (d in f.diffs) f.diffs - d else f.diffs + d) }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (count { it.lake } > 0) Chip(f.lakes, "Lakes") { model.filters = f.copy(lakes = !f.lakes) }
        if (count { it.summit } > 0) Chip(f.summits, "Summits") { model.filters = f.copy(summits = !f.summits) }
        if (count { it.loop } > 0) Chip(f.loops, "Loops") { model.filters = f.copy(loops = !f.loops) }
        if (count { it.reports > 0 } > 0) Chip(f.reports, "Trip reports") { model.filters = f.copy(reports = !f.reports) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${shown.size} ${if (shown.size == 1) "hike" else "hikes"}" + if (f.any) " match" else if (model.sort == Sort.Best) ", best first" else "",
            style = MaterialTheme.typography.bodySmall, color = MUTED, modifier = Modifier.weight(1f),
        )
        if (f.any) TextButton(onClick = { model.filters = com.hikeratlas.android.routes.Filters() }) { Text("Clear filters") }
    }
    Column {
        for (h in shown) HikeCard(h) { model.openRoute(park, h.id, hint = HikeHint(h.title, null)) }
    }
    if (shown.isEmpty()) Text("No hikes match.", color = MUTED)
}

@Composable
private fun SortMenu(sort: Sort, onSort: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Sort: ${sortLabel(sort)} ▾", style = MaterialTheme.typography.labelMedium) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (s in Sort.entries) DropdownMenuItem(text = { Text(sortLabel(s)) }, onClick = { onSort(s); open = false })
        }
    }
}

private fun sortLabel(s: Sort) = when (s) {
    Sort.Best -> "Best first"
    Sort.Shortest -> "Shortest"
    Sort.LeastClimb -> "Least climbing"
    Sort.Highest -> "Highest point"
}

@Composable
private fun Chip(on: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = on, onClick = onClick, label = { Text(label) })
}

/** "8.3 km · ↑ 850 m · ~4.5 h · out & back". */
internal fun cardStats(h: ParkHike) = "${km(h.route.km)} km · ↑ ${metres(h.route.gain)} m · ${timeLabel(h.hours, h.route)} · ${h.shape}"

/** A day hike's card: its shape, title and difficulty, numbers, terrain, trailhead and what it passes, and its reports. */
@Composable
private fun HikeCard(h: ParkHike, onOpen: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteThumb(h.route.line, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(h.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                DifficultyPill(h.diff)
            }
            Text(cardStats(h), style = MaterialTheme.typography.bodySmall)
            TerrainBar(h.route.terrain, Modifier.width(144.dp).height(5.dp))
            Text(
                "from ${h.from}" + if (h.features.isNotEmpty()) " · ${h.features.take(2).joinToString(", ")}" else "",
                style = MaterialTheme.typography.labelSmall, color = MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (h.reports > 0) {
            Text(
                "${h.reports} ${if (h.reports == 1) "report" else "reports"}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3F6212),
                modifier = Modifier.align(Alignment.Top).background(Color(0xFFECFCCB), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** A route's shape, scaled into a square (longitude by latitude, so it isn't stretched). */
@Composable
fun RouteThumb(line: List<LngLat>, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clip(RoundedCornerShape(8.dp)).background(THUMB_BG)) {
        val pts = thumbPoints(line, this.size.width, 6.dp.toPx()) ?: return@Canvas
        val path = Path().apply { pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) } }
        drawPath(path, ROUTE, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The website's thumb: a line's points scaled into a side×side square with [pad], about 60 of them. */
internal fun thumbPoints(line: List<LngLat>, side: Float, pad: Float): List<Pair<Float, Float>>? {
    if (line.size < 2) return null
    val step = maxOf(1, line.size / 60)
    val pts = line.filterIndexed { i, _ -> i % step == 0 || i == line.size - 1 }
    val k = cos(Math.toRadians(pts[0][1]))
    val xs = pts.map { it[0] * k }
    val ys = pts.map { -it[1] }
    val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
    val sc = (side - 2 * pad) / maxOf(x1 - x0, y1 - y0, 1e-9)
    val dx = (side - (x1 - x0) * sc) / 2
    val dy = (side - (y1 - y0) * sc) / 2
    return xs.indices.map { i -> ((xs[i] - x0) * sc + dx).toFloat() to ((ys[i] - y0) * sc + dy).toFloat() }
}

private class Other(val badge: String, val title: String, val meta: String, val open: () -> Unit)

@Composable
private fun Others(title: String, blurb: String, items: List<Other>) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(blurb, style = MaterialTheme.typography.bodySmall, color = MUTED, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        for (it in items) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                Modifier.fillMaxWidth().clickable(onClick = it.open).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (it.badge.isNotEmpty()) {
                    Box(Modifier.size(44.dp).background(THUMB_BG, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text(it.badge, fontWeight = FontWeight.Bold, color = Color(0xFF3F6212))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(it.meta, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

