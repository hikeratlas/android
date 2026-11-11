package com.hikeratlas.android.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** A link to the same view on openstreetmap.org (whose zooms are one higher than ours). */
fun osmUrl(lng: Double, lat: Double, zoom: Double): String {
    val z = (zoom + 1).roundToInt().coerceIn(0, 19)
    return String.format(Locale.US, "https://www.openstreetmap.org/#map=%d/%.5f/%.5f", z, lat, lng)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(choice: LayerChoice, onChange: (LayerChoice) -> Unit, onOpenOsm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text("Map layers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BaseCard("Map", !choice.imagery, Modifier.weight(1f), aerial = false) { onChange(choice.copy(imagery = false)) }
                BaseCard("Aerial imagery", choice.imagery, Modifier.weight(1f), aerial = true) { onChange(choice.copy(imagery = true)) }
            }
            Toggle("Curated hikes", choice.hikes) { onChange(choice.copy(hikes = it)) }
            Toggle("Contour lines", choice.contours) { onChange(choice.copy(contours = it)) }
            Toggle("Hillshade", choice.hillshade, enabled = !choice.imagery) { onChange(choice.copy(hillshade = it)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            TextButton(onClick = onOpenOsm, modifier = Modifier.padding(top = 4.dp)) { Text("Open this view on OpenStreetMap ↗") }
        }
    }
}

@Composable
private fun BaseCard(label: String, selected: Boolean, modifier: Modifier, aerial: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        selected = selected,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column {
            // A small sketch of each: the map's greens and water, or imagery's darker tones.
            Canvas(Modifier.fillMaxWidth().height(72.dp)) {
                drawRect(if (aerial) Color(0xFF4D5E3E) else Color(0xFFD3EFDF))
                if (aerial) drawRect(Color(0xFF3F4F33), size = Size(size.width * 0.35f, size.height))
                val lake = Path().apply {
                    moveTo(size.width * 0.38f, size.height * 0.4f)
                    cubicTo(size.width * 0.5f, size.height * 0.15f, size.width * 0.8f, size.height * 0.2f, size.width * 0.78f, size.height * 0.5f)
                    cubicTo(size.width * 0.76f, size.height * 0.8f, size.width * 0.45f, size.height * 0.8f, size.width * 0.38f, size.height * 0.4f)
                }
                drawPath(lake, if (aerial) Color(0xFF2D5E74) else Color(0xFF5FB6E6))
                drawLine(Color(0x99C026D3), Offset(0f, size.height * 0.9f), Offset(size.width * 0.4f, size.height * 0.4f), strokeWidth = 6f)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = on && enabled, onCheckedChange = null, enabled = enabled)
    }
}
