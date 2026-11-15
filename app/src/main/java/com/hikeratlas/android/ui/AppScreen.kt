package com.hikeratlas.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import com.hikeratlas.android.track.SavedScreen
import com.hikeratlas.android.offline.MapLibreCache
import com.hikeratlas.android.offline.OfflineAreas
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.hikeratlas.android.offline.offlineAreasSection
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import android.net.Uri
import com.hikeratlas.android.HikerAtlasApp
import com.hikeratlas.android.map.MapScreen
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.Faves
import com.hikeratlas.android.atproto.SignedOutException
import com.hikeratlas.android.routes.KeyedHike
import com.hikeratlas.android.routes.RouteKeys
import com.hikeratlas.android.routes.findKeyedHikes

private enum class Tab(val label: String, val selected: ImageVector, val unselected: ImageVector) {
    Map("Map", Icons.Filled.Map, Icons.Outlined.Map),
    // Hikes by place: countries, states, parks.
    Explore("Explore", Icons.Filled.Explore, Icons.Outlined.Explore),
    // Offline areas, faved hikes and recorded tracks: shown once it may have something in it.
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
    // An offline area to open on the map (from the Saved tab), until it's opened.
    var openArea by remember { mutableStateOf<OfflineAreas.Target?>(null) }
    // Where Explore is, by slugs: kept as one string, so it survives rotation.
    var explore by rememberSaveable { mutableStateOf("") }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val mapCache = remember { MapLibreCache(context.applicationContext) }
    // Areas from an older build of the map offer Update.
    LaunchedEffect(Unit) { offline.checkUpdates() }
    val accountState by app.account.state.collectAsState()
    val did = (accountState as? AccountState.In)?.did
    val handle = (accountState as? AccountState.In)?.handle
    // Your profile, open over the You tab (signing out closes it); and a
    // posted track from it, on the map.
    var profileTarget by remember(did) { mutableStateOf<ProfileTarget?>(null) }
    var viewPosted by remember { mutableStateOf<PostedTrack?>(null) }
    val accountActions = AccountActions(
        state = accountState,
        signIn = { app.account.beginSignIn(it) },
        openPage = { openSignInPage(context, it) },
        signOut = { app.account.signOut() },
        clearError = { app.account.clearError() },
        openProfile = { did?.let { profileTarget = ProfileTarget(it, handle) } },
    )
    val faveActions = FaveActions(
        accountActions,
        isFaved = { key -> Faves.isFaved(app.account.xrpc, app.account.did ?: throw SignedOutException(), key) },
        setFave = { key, on -> Faves.set(app.account.xrpc, app.account.did ?: throw SignedOutException(), key, on) },
    )
    val photoStore = app.photoStore
    val outbox = app.outbox
    val waitingEntries by outbox.entries.collectAsState()
    val waitingActive by outbox.active.collectAsState()
    val postedCount by outbox.posted.collectAsState()
    val waitingActions = WaitingActions(
        waitingEntries, waitingActive,
        retry = { id -> scope.launch { outbox.retry(id) } },
        discard = { id -> scope.launch { outbox.discard(id) } },
    )
    val photoActions = remember(photoStore) {
        PhotoActions(
            prepare = { input ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        photoStore.save(PhotoSources.read(context, input.uri), input.capturedAt)
                    } finally {
                        // The camera's file: prepared into the store now.
                        if (input.temporary) input.uri.path?.let { java.io.File(it).delete() }
                    }
                }
            },
            discard = { p -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { photoStore.discard(p) } },
            thumbnail = { p ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.hikeratlas.android.atproto.Photos.thumbnail(java.io.File(p.file), 168)?.asImageBitmap()
                }
            },
        )
    }
    val postActions = PostActions(
        accountActions,
        tracks = library.tracks,
        license = { com.hikeratlas.android.atproto.Posts.licenseFor(app.account.xrpc, app.account.did ?: throw SignedOutException()) },
        trackStats = { id -> library.gpxText(id)?.let { com.hikeratlas.android.track.TrackMeasure.ofGpx(it) } },
        hikesNear = { id ->
            com.hikeratlas.android.track.hikesNear(library.lines(id), app.routes.regions(), app.routes.index(), { app.routes.park(it) })
        },
        tracksNear = { line -> library.tracksNear(line) },
        post = { d, progress -> sendDraft(outbox, app.account.did ?: throw SignedOutException(), handle, library, d, progress) },
        openProfile = { posted ->
            seePostTarget(did, handle, posted)?.let {
                profileTarget = it
                tab = Tab.You
            }
        },
        verifyPosted = { id -> app.account.did?.let { verifyPosted(app.account.xrpc, it, library, id) } },
        photos = photoActions,
        trackGpx = { id -> library.gpxText(id) },
        waitingTracks = waitingEntries.mapNotNull { it.trackId }.toSet(),
    )
    // The licence for new posts, read from the profile record when You opens.
    var license by remember(did) { mutableStateOf<String?>(null) }
    var licenseError by remember(did) { mutableStateOf<String?>(null) }
    LaunchedEffect(tab == Tab.You, did) {
        if (tab != Tab.You || did == null) return@LaunchedEffect
        licenseError = null
        try {
            val l = (com.hikeratlas.android.atproto.ProfileRecord.read(app.account.xrpc, did)?.get("license") as? kotlinx.serialization.json.JsonPrimitive)?.content
            license = l?.takeIf { it in com.hikeratlas.android.atproto.Posts.LICENSES } ?: com.hikeratlas.android.atproto.Posts.DEFAULT_LICENSE
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (license == null) licenseError = e.message ?: e.toString()
        }
    }
    // The faved hikes, read again each time Saved opens (one may have been
    // faved since, here or on the website); the hikes found are kept.
    var faved by remember(did) { mutableStateOf<FavedState>(FavedState.Loading) }
    var favedAttempt by remember { mutableIntStateOf(0) }
    val favedFound = remember(did) { mutableMapOf<String, KeyedHike>() }
    LaunchedEffect(tab == Tab.Saved, did, favedAttempt) {
        if (tab != Tab.Saved || did == null) return@LaunchedEffect
        if (faved is FavedState.Failed) faved = FavedState.Loading
        loadFaved(
            list = { Faves.list(app.account.xrpc, did) },
            find = { keys ->
                findKeyedHikes(keys, app.routes.regions(), app.routes.index(), { app.routes.park(it) }, { app.routes.parkOnline(it) })
            },
            known = favedFound.toMap(),
            canFind = RouteKeys.available,
        ) { s ->
            if (s is FavedState.Ready) s.found?.let { favedFound.putAll(it) }
            faved = s
        }
    }
    val profileActions = ProfileActions(
        load = { t ->
            com.hikeratlas.android.atproto.Profiles.loadDid(app.http, com.hikeratlas.android.atproto.Identity(app.http), t.did, t.handle)
        },
        findHikes = { keys ->
            findKeyedHikes(keys, app.routes.regions(), app.routes.index(), { app.routes.park(it) }, { app.routes.parkOnline(it) })
        },
        canFind = RouteKeys.available,
        gpx = { p, t ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.hikeratlas.android.atproto.Profiles.gpx(com.hikeratlas.android.atproto.Xrpc.public(app.http, p.pds), p.did, t.gpx)
            }
        },
        delete = { d -> deletePost(app.account.xrpc, app.account.did ?: throw SignedOutException(), library, d) },
        openHike = { h ->
            viewTrack = null
            viewPosted = null
            openArea = OfflineAreas.Target(com.hikeratlas.android.offline.OfflineStore.HIKE, h.id, h.title)
            tab = Tab.Map
        },
        showTrack = { t ->
            viewTrack = null
            viewPosted = t
            tab = Tab.Map
        },
        shareGpx = { title, gpx -> scope.launch { com.hikeratlas.android.track.shareGpx(context, title, gpx) } },
        openPhoto = { com.hikeratlas.android.map.openUrl(context, it) },
    )
    val tabs = Tab.entries.filter {
        it != Tab.Saved || library.tracks.isNotEmpty() || offline.areas.isNotEmpty() || did != null || tab == Tab.Saved
    }
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
            viewPosted = viewPosted.takeIf { tab == Tab.Map },
            // Back to the profile it came from.
            onPostedClosed = {
                viewPosted = null
                if (profileTarget != null) tab = Tab.You
            },
            openArea = openArea.takeIf { tab == Tab.Map }, onAreaOpened = { openArea = null },
            onCrumb = { c ->
                explore = c.path.joinToString("/")
                tab = Tab.Explore
            },
            faves = faveActions,
            posts = postActions,
        )
        if (tab == Tab.Saved) {
            Surface(Modifier.fillMaxSize().padding(bottom = barHeight)) {
                SavedScreen(
                    library.tracks,
                    onOpen = { id ->
                        viewPosted = null
                        viewTrack = id
                        tab = Tab.Map
                    },
                    above = {
                        offlineAreasSection(
                            offline.areas, wifiOnly,
                            onWifiOnly = { wifiOnly = it; offline.wifiOnly = it },
                            onRetry = { id -> scope.launch { offline.retry(id) } },
                            updateAvailable = { offline.updateAvailable(it) },
                            onUpdate = { id -> scope.launch { offline.update(id) } },
                            onDelete = { id -> scope.launch { offline.delete(id) } },
                            onOpen = { t ->
                                viewTrack = null
                                openArea = t
                                tab = Tab.Map
                            },
                        )
                        if (did != null) {
                            favedHikesSection(
                                faved,
                                onOpen = { h ->
                                    viewTrack = null
                                    openArea = OfflineAreas.Target(com.hikeratlas.android.offline.OfflineStore.HIKE, h.id, h.title)
                                    tab = Tab.Map
                                },
                                onRetry = { favedAttempt++ },
                            )
                        }
                    },
                )
            }
        }
        if (tab == Tab.Explore) {
            Box(Modifier.fillMaxSize().padding(bottom = barHeight)) {
                ExploreScreen(
                    regions = { app.routes.regions() },
                    cards = { app.routes.stateCards(it) },
                    path = explore.split('/').filter { it.isNotEmpty() },
                    onPath = { explore = it.joinToString("/") },
                    onPark = { id, name ->
                        viewTrack = null
                        openArea = OfflineAreas.Target(com.hikeratlas.android.offline.OfflineStore.PARK, id, name)
                        tab = Tab.Map
                    },
                    featured = { asked ->
                        com.hikeratlas.android.routes.Featured.load(
                            app.routes, com.hikeratlas.android.map.lastMapCenter(context), (System.currentTimeMillis() / 86_400_000L).toInt(), asked,
                        )
                    },
                    onHike = { _, id, title ->
                        viewTrack = null
                        openArea = OfflineAreas.Target(com.hikeratlas.android.offline.OfflineStore.HIKE, id, title)
                        tab = Tab.Map
                    },
                )
            }
        }
        if (tab == Tab.You) {
            val unitsActions = UnitsActions(
                current = com.hikeratlas.android.units.Units.current,
                signedIn = accountState is AccountState.In,
                save = { app.units.set(it) },
            )
            Box(Modifier.fillMaxSize().padding(bottom = barHeight)) {
                val target = profileTarget
                if (target != null && did != null) {
                    ProfileScreen(
                        target, profileActions, onBack = { profileTarget = null },
                        // Units and the licence: the profile's settings, here only (one place, as the website's).
                        settings = {
                            UnitsCard(unitsActions)
                            LicenseCard(
                                LicenseActions(license, licenseError) { l ->
                                    com.hikeratlas.android.atproto.ProfileRecord.saveSettings(
                                        app.account.xrpc, did, mapOf("license" to kotlinx.serialization.json.JsonPrimitive(l)),
                                    )
                                    license = l
                                },
                            )
                        },
                        waiting = waitingActions,
                        refreshKey = postedCount,
                    )
                } else {
                    YouScreen(
                        StorageActions(
                            areaCount = { offline.areas.size },
                            offlineBytes = { offline.bytes() },
                            mapCacheBytes = { mapCache.bytes() },
                            deleteAll = { offline.deleteAll() },
                            clearMapCache = { mapCache.clear() },
                        ),
                        accountActions,
                        unitsActions,
                        waiting = waitingActions,
                    )
                }
            }
        }
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

/**
 * The sign-in page, in a Custom Tab: the user's own browser (where they may
 * already be signed in to Bluesky), over the app. It comes back to the app
 * by the redirect URI (MainActivity).
 */
private fun openSignInPage(context: android.content.Context, url: String) {
    try {
        androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(url))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No browser to sign in with", android.widget.Toast.LENGTH_LONG).show()
    }
}
