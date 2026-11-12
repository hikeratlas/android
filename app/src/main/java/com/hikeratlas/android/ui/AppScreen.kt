package com.hikeratlas.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.Text
import com.hikeratlas.android.track.SavedScreen
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.hikeratlas.android.offline.offlineAreasSection
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import android.net.Uri
import com.hikeratlas.android.HikerAtlasApp
import com.hikeratlas.android.map.MapScreen

private enum class Tab(val label: String, val selected: ImageVector, val unselected: ImageVector) {
    Map("Map", Icons.Filled.Map, Icons.Outlined.Map),
    // Recorded tracks (and later offline areas): shown once it has something in it.
    Saved("Saved", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    You("You", Icons.Filled.Person, Icons.Outlined.Person),
}

@Composable
fun AppScreen(app: HikerAtlasApp, link: Uri? = null, onLinkHandled: () -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(Tab.Map) }
    // A website link opens on the map.
    LaunchedEffect(link) { if (link != null) tab = Tab.Map }
    val library = app.library
    LaunchedEffect(Unit) {
        library.refresh()
        library.fillMissing()
    }
    // A saved track being looked at on the map.
    var viewTrack by rememberSaveable { mutableStateOf<Long?>(null) }
    val offline = app.offlineAreas
    // Offline areas' state: often while any is on its way, now and then otherwise.
    LaunchedEffect(Unit) {
        while (true) {
            offline.refresh()
            delay(if (offline.areas.any { !it.ready }) 1_000 else 15_000)
        }
    }
    val scope = rememberCoroutineScope()
    var wifiOnly by remember { mutableStateOf(offline.wifiOnly) }
    val tabs = Tab.entries.filter { it != Tab.Saved || library.tracks.isNotEmpty() || offline.areas.isNotEmpty() || tab == Tab.Saved }
    // A sheet on the map needs the room: the tabs step aside while it's open.
    var sheetOpen by remember { mutableStateOf(false) }
    val tabsShown = !(tab == Tab.Map && sheetOpen)
    var barPx by remember { mutableIntStateOf(0) }
    val barHeight = with(LocalDensity.current) { barPx.toDp() }
    // The tabs are drawn over the map rather than beside it, so showing and
    // hiding them never resizes the map (a resize redraws it: a flicker).
    Box(Modifier.fillMaxSize()) {
        // The map stays composed under the other tabs, so it keeps its place,
        // its tiles and whatever sheet is open.
        MapScreen(
            app, onSheetOpen = { sheetOpen = it }, bottomBar = if (tabsShown) barHeight else 0.dp,
            link = link, onLinkHandled = onLinkHandled,
            viewTrack = viewTrack.takeIf { tab == Tab.Map }, onViewClosed = { viewTrack = null },
        )
        if (tab == Tab.Saved) {
            Surface(Modifier.fillMaxSize().padding(bottom = barHeight)) {
                SavedScreen(
                    library.tracks,
                    onOpen = { id ->
                        viewTrack = id
                        tab = Tab.Map
                    },
                    above = {
                        offlineAreasSection(
                            offline.areas, wifiOnly,
                            onWifiOnly = { wifiOnly = it; offline.wifiOnly = it },
                            onRetry = { id -> scope.launch { offline.retry(id) } },
                            onDelete = { id -> scope.launch { offline.delete(id) } },
                        )
                    },
                )
            }
        }
        if (tab == Tab.You) Box(Modifier.fillMaxSize().padding(bottom = barHeight)) { YouScreen() }
        AnimatedVisibility(
            visible = tabsShown,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            NavigationBar(Modifier.onSizeChanged { barPx = it.height }) {
                for (t in tabs) {
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(if (t == tab) t.selected else t.unselected, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    }
}
