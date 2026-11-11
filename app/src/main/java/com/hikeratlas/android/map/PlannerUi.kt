package com.hikeratlas.android.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.routes.km
import com.hikeratlas.android.routes.metres
import com.hikeratlas.android.routing.Planner

/** What the planner asks for next, as the website words it (for taps). */
private fun status(p: Planner): String = p.error ?: when {
    p.busy || p.pending.isNotEmpty() -> "Finding a route…"
    p.points.isEmpty() -> "Tap the map to set a start."
    p.points.size == 1 -> "Tap the next point: each is routed along trails from the one before."
    else -> "Tap to add a point; drag one to move it."
}

/** The planner's bar at the top: what to do, and Done. */
@Composable
fun PlannerBar(planner: Planner, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(26.dp), shadowElevation = 4.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (planner.points.size < 2) "Plan a route" else "${km(planner.metres / 1000)} km",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

/** The planner's panel at the bottom: status, distance and climb, the elevation chart, undo and clear. */
@Composable
fun PlannerPanel(planner: Planner, onScrub: (DoubleArray?) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val prof = planner.profile
                    if (planner.points.size >= 2) {
                        Text(
                            "${km(planner.metres / 1000)} km" + (prof?.let { p ->
                                " · ↑ ${metres(p.gain)} m · ↓ ${metres(p.loss)} m · high ${metres(p.high)} m"
                            } ?: ""),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        status(planner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (planner.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedIconButton(onClick = planner::undo, enabled = planner.points.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo the last point")
                }
                OutlinedIconButton(onClick = planner::clear, enabled = planner.points.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear the route")
                }
            }
            if (planner.busy || planner.pending.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
            val prof = planner.profile
            if (planner.points.size >= 2 && prof != null) {
                ElevationChart(prof, emptyList(), onScrub = { i -> onScrub(i?.let { prof.coords[it] }) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}
