package com.hikeratlas.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.BuildConfig
import com.hikeratlas.android.map.openUrl
import com.hikeratlas.android.routes.SITE

@Composable
fun YouScreen() {
    val context = LocalContext.current
    var credits by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("You", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Card("About") {
            Item("Blog") { openUrl(context, "$SITE/blog") }
            Item("Hiker Atlas on the web") { openUrl(context, SITE) }
            Item("Source code") { openUrl(context, "https://github.com/hikeratlas") }
            Item("Map credits", "OpenStreetMap contributors, Mapterhorn and others") { credits = true }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Version", modifier = Modifier.weight(1f))
                Text(BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (credits) {
        AlertDialog(
            onDismissRequest = { credits = false },
            confirmButton = { TextButton(onClick = { credits = false }) { Text("Close") } },
            title = { Text("Map credits") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Map data © OpenStreetMap contributors, under the Open Database License.")
                    Text("Elevation and hillshade: © Mapterhorn; Newfoundland: hikeratlas/nl-dem.")
                    TextButton(onClick = { openUrl(context, "https://www.openstreetmap.org/copyright") }) { Text("openstreetmap.org/copyright") }
                    TextButton(onClick = { openUrl(context, "https://mapterhorn.com/attribution") }) { Text("mapterhorn.com/attribution") }
                }
            },
        )
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun Item(title: String, subtitle: String? = null, onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
