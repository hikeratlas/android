package com.hikeratlas.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.hikeratlas.android.HikerAtlasApp
import com.hikeratlas.android.map.MapScreen

private enum class Tab(val label: String, val selected: ImageVector, val unselected: ImageVector) {
    // Saved (offline areas, recorded tracks) joins these once it has something in it.
    Map("Map", Icons.Filled.Map, Icons.Outlined.Map),
    You("You", Icons.Filled.Person, Icons.Outlined.Person),
}

@Composable
fun AppScreen(app: HikerAtlasApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.Map) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(if (t == tab) t.selected else t.unselected, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // The map stays composed under the other tabs, so it keeps its place,
            // its tiles and whatever sheet is open.
            MapScreen(app)
            if (tab == Tab.You) YouScreen()
        }
    }
}
