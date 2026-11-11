package com.hikeratlas.android.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.routes.km
import com.hikeratlas.android.routes.metres

/** A campsite (or other mark) along the route, at metres from its start. */
data class Mark(val at: Double, val label: String)

private val LINE = Color(0xFFA21CAF)
private val FILL = Color(0x1FC026D3)
private val GRID = Color(0xFFE3E9E5)
private val MARK = Color(0xFF8A5A36)
private val LABEL = Color(0xFF4F5D55)

/**
 * The route's elevation along its length. Dragging along it reports the point
 * under the finger ([onScrub]), so the map can mark where that is.
 */
@Composable
fun ElevationChart(
    profile: ElevationProfiler.Profile,
    marks: List<Mark>,
    onScrub: (index: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var scrub by remember(profile) { mutableStateOf<Int?>(null) }
    val label = TextStyle(fontSize = 11.sp, color = LABEL)
    val markLabel = TextStyle(fontSize = 11.sp, color = MARK)
    val lo = profile.low
    val hi = profile.high
    val length = profile.length
    val onSurface = MaterialTheme.colorScheme.onSurface

    fun indexAt(x: Float, width: Float): Int {
        val d = (x / width).coerceIn(0f, 1f) * length
        var i = profile.distances.indexOfFirst { it >= d }
        if (i < 0) i = profile.distances.size - 1
        return i
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(140.dp)
            .semantics {
                contentDescription = "Elevation from ${metres(lo)} m to ${metres(hi)} m over ${km(length / 1000)} km"
            }
            .pointerInput(profile) {
                detectHorizontalDragGestures(
                    onDragStart = { scrub = indexAt(it.x, size.width.toFloat()); onScrub(scrub) },
                    onDragEnd = { scrub = null; onScrub(null) },
                    onDragCancel = { scrub = null; onScrub(null) },
                ) { change, _ -> scrub = indexAt(change.position.x, size.width.toFloat()); onScrub(scrub) }
            }
            .pointerInput(profile) {
                detectTapGestures(onPress = {
                    scrub = indexAt(it.x, size.width.toFloat()); onScrub(scrub)
                    tryAwaitRelease()
                    scrub = null; onScrub(null)
                })
            },
    ) {
        val top = 16.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val range = (hi - lo).coerceAtLeast(20.0)
        fun x(d: Double) = (d / length * size.width).toFloat()
        fun y(e: Double) = (bottom - (e - lo) / range * (bottom - top)).toFloat()

        drawLine(GRID, Offset(0f, top), Offset(size.width, top))
        drawLine(GRID, Offset(0f, bottom), Offset(size.width, bottom))

        val path = Path()
        profile.distances.forEachIndexed { i, d ->
            if (i == 0) path.moveTo(x(d), y(profile.elevations[i])) else path.lineTo(x(d), y(profile.elevations[i]))
        }
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, bottom)
            lineTo(0f, bottom)
            close()
        }
        drawPath(area, FILL)
        drawPath(path, LINE, style = Stroke(2.dp.toPx()))

        for (m in marks) {
            val i = profile.distances.indexOfFirst { it >= m.at }.let { if (it < 0) profile.distances.size - 1 else it }
            val mx = x(m.at.coerceAtMost(length))
            val my = y(profile.elevations[i])
            drawLine(MARK, Offset(mx, my), Offset(mx, bottom), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(Color.White, 4.dp.toPx(), Offset(mx, my))
            drawCircle(MARK, 4.dp.toPx(), Offset(mx, my), style = Stroke(2.dp.toPx()))
            val t = measurer.measure(m.label, markLabel)
            drawText(t, topLeft = Offset((mx + 4.dp.toPx()).coerceAtMost(size.width - t.size.width), (my - t.size.height - 4.dp.toPx()).coerceAtLeast(0f)))
        }

        val hiText = measurer.measure("${metres(hi)} m", label)
        drawText(hiText, topLeft = Offset(0f, 0f))
        val loText = measurer.measure("${metres(lo)} m", label)
        drawText(loText, topLeft = Offset(0f, bottom - loText.size.height - 2.dp.toPx()))
        val end = measurer.measure("${km(length / 1000)} km", label)
        drawText(end, topLeft = Offset(size.width - end.size.width, bottom + 2.dp.toPx()))
        drawText(measurer.measure("0 km", label), topLeft = Offset(0f, bottom + 2.dp.toPx()))

        scrub?.let { i ->
            val sx = x(profile.distances[i])
            val sy = y(profile.elevations[i])
            drawLine(onSurface, Offset(sx, top), Offset(sx, bottom))
            drawCircle(onSurface, 5.dp.toPx(), Offset(sx, sy))
            val t = measurer.measure("${km(profile.distances[i] / 1000)} km · ${metres(profile.elevations[i])} m", TextStyle(fontSize = 11.sp, color = onSurface))
            drawText(t, topLeft = Offset((sx - t.size.width / 2).coerceIn(0f, size.width - t.size.width), 0f))
        }
    }
}
