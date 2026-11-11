package com.hikeratlas.android.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import android.net.Uri
import com.hikeratlas.android.routes.Link
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hikeratlas.android.HikerAtlasApp
import com.hikeratlas.android.routes.RouteItem
import com.hikeratlas.android.routes.itemsFromProperties
import com.hikeratlas.android.routing.Planner
import com.hikeratlas.android.search.SearchApi
import com.hikeratlas.android.search.SearchItem
import com.hikeratlas.android.search.SearchScreen
import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.pmtiles.TileServer
import com.hikeratlas.android.style.StyleLoader
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The map, its search, and the sheet for routes and parks. [onSheetOpen]
 * says whether a sheet is open, so the app can hide its tabs meanwhile.
 */
@Composable
fun MapScreen(
    app: HikerAtlasApp,
    onSheetOpen: (Boolean) -> Unit = {},
    bottomBar: Dp = 0.dp,
    /** A website link to open (a hike, park or region page), until [onLinkHandled]. */
    link: Uri? = null,
    onLinkHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val mapView = rememberMapViewWithLifecycle()

    var controller by remember { mutableStateOf<MapController?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var styleError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var inView by remember { mutableStateOf<List<RouteItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var layerChoice by remember { mutableStateOf(LayerChoice.load(context)) }
    var mapLayers by remember { mutableStateOf<MapLayers?>(null) }
    var planning by remember { mutableStateOf(false) }
    val planner = remember {
        Planner(
            scope,
            fetch = { z, tiles -> TileServer.register(HttpRangeSource(StyleLoader.BASEMAP_URL, app.http)).first.tiles(z, tiles) },
            profiler = app.profiler,
        )
    }
    val isPlanning by rememberUpdatedState(planning)

    val model = remember {
        // Fitting the route waits for the sheet to know its height (see below).
        PanelModel(
            scope, app.routes, app.profiler,
            basemap = { TileServer.register(HttpRangeSource(StyleLoader.BASEMAP_URL, app.http)).first },
            onTrail = { lines -> controller?.showLines(lines) },
        ) { sel, _ ->
            val c = controller ?: return@PanelModel
            c.showHover(null)
            c.showRoute(sel?.route?.line)
        }
    }
    val panel = model.panel

    // Parks with curated hikes, for tapping park names.
    var hikedParks by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        try { hikedParks = app.routes.index().parks.keys } catch (_: Exception) { /* names just aren't tappable */ }
    }
    val currentHiked by rememberUpdatedState(hikedParks)

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            val c = MapController(m, density)
            m.cameraPosition = CameraStore.load(context)
            // The bottom sheet covers the map's bottom edge: keep the credits
            // and compass up top, below the search bar and location button.
            val dp = { v: Int -> (v * density).toInt() }
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.compassGravity = Gravity.TOP or Gravity.START
            m.uiSettings.setCompassMargins(dp(12), dp(76), 0, 0)
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.attributionGravity = Gravity.TOP or Gravity.END
            m.uiSettings.setAttributionMargins(0, dp(136), dp(16), 0)
            m.addOnCameraIdleListener {
                CameraStore.save(context, m.cameraPosition)
                inView = itemsFromProperties(c.routesInView(mapView.width, mapView.height), fromCamp = false)
                if (isPlanning) {
                    val b = m.projection.visibleRegion.latLngBounds
                    planner.prefetchView(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth, m.cameraPosition.zoom)
                }
            }
            // While planning, a finger on a point drags it: the legs either
            // side are routed again through where it's let go.
            var dragging: Int? = null
            mapView.onPlannerTouch { v, ev ->
                if (!isPlanning) return@onPlannerTouch false
                val at = android.graphics.PointF(ev.x, ev.y)
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dragging = c.planPointAt(at)
                        dragging != null
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val i = dragging ?: return@onPlannerTouch false
                        val ll = m.projection.fromScreenLocation(at)
                        c.showPlan(planner.points.toMutableList().also { it[i] = listOf(ll.longitude, ll.latitude) }, planner.coords, planner.pending)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val i = dragging ?: return@onPlannerTouch false
                        dragging = null
                        v.performClick()
                        val ll = m.projection.fromScreenLocation(at)
                        val p = listOf(ll.longitude, ll.latitude)
                        val last = planner.points.size - 1
                        // A loop starts and ends at one place: dragging either end moves both.
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                            c.showPlan(planner.points, planner.coords, planner.pending)
                        } else if ((i == 0 || i == last) && planner.isLoop()) {
                            planner.moveEnds(p, m.cameraPosition.zoom)
                        } else {
                            planner.move(i, p, m.cameraPosition.zoom)
                        }
                        true
                    }
                    else -> dragging != null
                }
            }
            m.addOnMapClickListener { latLng ->
                if (isPlanning) {
                    planner.add(listOf(latLng.longitude, latLng.latitude), m.cameraPosition.zoom)
                    return@addOnMapClickListener true
                }
                val p = m.projection.toScreenLocation(latLng)
                val found = itemsFromProperties(c.routesAt(p))
                when {
                    found.size == 1 -> model.openRoute(found[0].park, found[0].id, fit = false, hint = HikeHint(found[0].title, found[0].meta))
                    found.size > 1 -> model.showList(Panel.RouteList("${found.size} routes here", found))
                    else -> {
                        val park = c.parkAt(p) ?: return@addOnMapClickListener false
                        val id = park["osm_id"]?.toString()?.removeSuffix(".0") ?: return@addOnMapClickListener false
                        val name = (park["name"] ?: park["short_name"] ?: "").toString()
                        val about = ParkAbout.of(park["operator"], park["website"])
                        when {
                            id in currentHiked -> model.openPark(id, name, about)
                            about != null -> model.showList(Panel.RouteList(name, emptyList(), about))
                            else -> return@addOnMapClickListener false
                        }
                    }
                }
                true
            }
            controller = c
        }
    }

    LaunchedEffect(controller, attempt) {
        val c = controller ?: return@LaunchedEffect
        styleError = null
        val loader = StyleLoader(app.http, context.cacheDir)
        fun apply(json: String) = c.map.setStyle(Style.Builder().fromJson(json)) { s ->
            c.onStyleLoaded(s)
            mapLayers = MapLayers(s).also { it.apply(layerChoice) }
            // A new style drops what was drawn on the old one.
            (model.panel as? Panel.Detail)?.let { c.showRoute(it.selected.route.line) }
            c.showPlan(planner.points, planner.coords, planner.pending)
            style = s
        }
        // Start from last time's style, so the map draws at once; then use
        // the fresh one if it's any different.
        val saved = if (attempt == 0) loader.cached() else null
        if (saved != null) {
            loader.registerArchives(saved)
            apply(saved)
        }
        try {
            val fresh = loader.load()
            if (fresh != saved) apply(fresh)
        } catch (e: Exception) {
            if (saved == null) styleError = e.message ?: e.toString()
        }
    }

    var containerPx by remember { mutableIntStateOf(0) }
    // Measured afresh for each sheet: a height left from the last one would
    // put this one's first frame at the wrong height.
    var headerPx by remember(panel != Panel.None) { mutableIntStateOf(0) }
    val navInsetPx = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    // Zero until the header is measured: the sheet waits off-screen till then.
    val peekPx = if (headerPx > 0) headerPx + navInsetPx else 0
    val open = panel != Panel.None
    // A fresh sheet each time one opens, so it starts low however the last was left.
    val sheet = remember(open) { AnchoredDraggableState(Stop.Peek) }

    LaunchedEffect(open, planning) { onSheetOpen(open || planning) }
    // Draw the plan as it changes.
    LaunchedEffect(planner.points, planner.coords, planner.pending, style) {
        controller?.showPlan(planner.points, planner.coords, planner.pending)
    }
    val stopPlanning = {
        planner.clear()
        controller?.showHover(null)
        planning = false
    }
    BackHandler(enabled = planning && !searching) { stopPlanning() }
    // A website link the app was opened with: move to what it names, and
    // open it. Links the app doesn't know just open the map.
    LaunchedEffect(link, controller) {
        val uri = link ?: return@LaunchedEffect
        val c = controller ?: return@LaunchedEffect
        onLinkHandled()
        val parts = Link.parts(uri) ?: return@LaunchedEffect
        searching = false
        layersOpen = false
        if (planning) stopPlanning()
        val resolved = try {
            Link.resolve(parts, app.routes.regions()) { app.routes.park(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        fun fit(b: List<Double>, bottomPx: Int) = c.fitBox(b[0], b[1], b[2], b[3], bottomPx)
        when (resolved) {
            is Link.Region -> { model.close(); fit(resolved.bbox, 0) }
            is Link.Park -> {
                model.openPark(resolved.id, resolved.name)
                resolved.bbox?.let { fit(it, (220 * density).toInt()) }
            }
            // Fitting the hike waits for its sheet, as for a tapped one.
            is Link.Hike -> model.openRoute(resolved.park, resolved.id, fit = true, hint = resolved.title?.let { HikeHint(it, null) })
            null -> {}
        }
    }
    // A newly opened panel starts at its lowest height: the map stays in view.
    LaunchedEffect(panel::class, (panel as? Panel.Detail)?.selected?.id, (panel as? Panel.RouteList)?.heading) {
        if (open && sheet.anchors.hasPositionFor(Stop.Peek)) sheet.animateTo(Stop.Peek)
    }
    // Fit the route into the map the sheet leaves: when it opens (if asked),
    // and whenever the sheet comes to rest at half height.
    val detail = panel as? Panel.Detail
    LaunchedEffect(detail?.selected?.id, model.fitPending, sheet.settledValue, peekPx, containerPx) {
        val c = controller ?: return@LaunchedEffect
        val d = detail ?: return@LaunchedEffect
        if (peekPx <= navInsetPx || sheet.offset.isNaN()) return@LaunchedEffect
        val settled = sheet.settledValue
        if ((model.fitPending && settled != Stop.Full) || settled == Stop.Half) {
            c.fitLine(d.selected.route.line, (containerPx - sheet.offset).roundToInt())
            model.fitPending = false
        }
    }
    BackHandler(enabled = open && !searching) { model.back() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val c = controller
        val s = style
        if (granted.values.any { it } && c != null && s != null) followLocation(context, c, s)
    }
    val locate = {
        val c = controller
        val s = style
        if (c != null && s != null) {
            if (hasLocationPermission(context)) {
                followLocation(context, c, s)
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { containerPx = it.height }) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (style == null && styleError == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        styleError?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load the map: $msg", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { attempt++ }, modifier = Modifier.padding(top = 12.dp)) { Text("Try again") }
            }
        }

        if (planning) {
            PlannerBar(planner, onDone = stopPlanning, modifier = Modifier.statusBarsPadding().padding(12.dp))
            PlannerPanel(planner, onScrub = { p -> controller?.showHover(p) }, modifier = Modifier.align(Alignment.BottomCenter))
        } else if (open) {
            // While a sheet is open: back (to the list it came from), search
            // and location, as small buttons. At full height, just back.
            val back = (panel as? Panel.Detail)?.back
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (back != null) {
                    Surface(
                        onClick = { model.back() },
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 4.dp,
                        modifier = Modifier.height(48.dp).widthIn(max = 240.dp),
                    ) {
                        Row(Modifier.padding(start = 10.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(back.heading, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (sheet.settledValue != Stop.Full) {
                    RoundButton(Icons.Filled.Search, "Search") { searching = true }
                    Spacer(Modifier.width(8.dp))
                    RoundButton(Icons.Filled.Layers, "Map layers") { layersOpen = true }
                    Spacer(Modifier.width(8.dp))
                    RoundButton(Icons.Filled.MyLocation, "Show my location", locate)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                Surface(
                    onClick = { searching = true },
                    shape = RoundedCornerShape(26.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text("Search hikes, parks, peaks, trails", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.align(Alignment.End).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundButton(Icons.Filled.Layers, "Map layers") { layersOpen = true }
                    RoundButton(Icons.Filled.Route, "Plan a route") {
                        model.close()
                        planner.clear()
                        planning = true
                        controller?.map?.let { m ->
                            val b = m.projection.visibleRegion.latLngBounds
                            planner.prefetchView(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth, m.cameraPosition.zoom)
                        }
                    }
                    RoundButton(Icons.Filled.MyLocation, "Show my location", locate)
                }
            }
            if (inView.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 8.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBar).fillMaxWidth(),
                ) {
                    InViewPeek(inView.size) {
                        model.showList(Panel.RouteList("${inView.size} curated ${if (inView.size == 1) "hike" else "hikes"} in view", inView))
                    }
                }
            }
        }

        if (open && !planning && containerPx > 0) {
            MapSheet(
                state = sheet,
                containerPx = containerPx,
                peekPx = peekPx,
                onHeaderHeight = { headerPx = it },
                // A hike with nothing below its peek stays at the peek.
                halfAndFull = panel is Panel.RouteList || panel is Panel.Trail || (panel is Panel.Detail && hasDetailBody(panel)),
                header = { PanelHeader(model, full = sheet.settledValue == Stop.Full) { p -> controller?.showHover(p) } },
                body = { PanelBody(model) { p -> controller?.showHover(p) } },
            )
        }
    }

    if (layersOpen) {
        LayersSheet(
            choice = layerChoice,
            onChange = { c ->
                layerChoice = c
                c.save(context)
                mapLayers?.apply(c)
            },
            onOpenOsm = {
                controller?.map?.cameraPosition?.let { cam ->
                    val t = cam.target ?: return@let
                    openUrl(context, osmUrl(t.longitude, t.latitude, cam.zoom))
                }
            },
            onDismiss = { layersOpen = false },
        )
    }

    if (searching) {
        val target = controller?.map?.cameraPosition
        val near = target?.takeIf { it.zoom >= 5 }?.target
        SearchScreen(
            api = app.search,
            recents = app.recentSearches,
            nearLat = near?.latitude,
            nearLon = near?.longitude,
            onPick = { item ->
                searching = false
                scope.launch { onSearchPick(item, controller, model, (220 * density).toInt()) }
            },
            onClose = { searching = false },
        )
    }
}

/**
 * The planner's touch handling: drags of its points only. MapView (MapLibre's)
 * doesn't override performClick; taps still go through its own click
 * listeners, and a drag's release calls performClick.
 */
@SuppressLint("ClickableViewAccessibility")
private fun MapView.onPlannerTouch(listener: (android.view.View, android.view.MotionEvent) -> Boolean) =
    setOnTouchListener(listener)

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, shadowElevation = 4.dp, modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label) }
    }
}

/** Moves to a search result, and opens its park or hike. */
private fun onSearchPick(item: SearchItem, c: MapController?, model: PanelModel, sheetPx: Int) {
    c ?: return
    when (item.kind) {
        "park" -> model.openPark(item.osm_id, item.name)
        // A curated hike's osm_id is its route id, <park>/day/i etc.
        "hike" -> model.openRoute(
            item.osm_id.substringBefore('/'), item.osm_id, fit = false,
            hint = HikeHint(item.name, SearchApi.describe(item)),
        )
        // An OSM trail: its own panel, with its elevation and the hikes that walk it.
        "trail" -> model.openTrail(
            item.name, item.km, item.park, item.state,
            if (item.hasBox) doubleArrayOf(item.min_lon!!, item.min_lat!!, item.max_lon!!, item.max_lat!!) else null,
        )
        else -> model.close()
    }
    if (item.hasBox) {
        c.fitBox(item.min_lon!!, item.min_lat!!, item.max_lon!!, item.max_lat!!, if (item.kind in setOf("park", "hike", "trail")) sheetPx else 0)
    } else {
        c.jumpTo(item.lat, item.lon, 13.0)
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission") // callers check
private fun followLocation(context: Context, c: MapController, style: Style) {
    val lc = c.map.locationComponent
    if (!lc.isLocationComponentActivated) {
        lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(true).build())
    }
    lc.isLocationComponentEnabled = true
    lc.renderMode = RenderMode.COMPASS
    lc.cameraMode = CameraMode.TRACKING
    if (c.map.cameraPosition.zoom < 12) lc.zoomWhileTracking(14.0)
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return mapView
}

/** Where the map was left, to open there next time. */
private object CameraStore {
    private const val PREFS = "camera"

    fun load(context: Context): CameraPosition {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Default: most of North America.
        return CameraPosition.Builder()
            .target(LatLng(p.getFloat("lat", 45f).toDouble(), p.getFloat("lng", -100f).toDouble()))
            .zoom(p.getFloat("zoom", 3f).toDouble())
            .build()
    }

    fun save(context: Context, c: CameraPosition) {
        val t = c.target ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat("lat", t.latitude.toFloat())
            putFloat("lng", t.longitude.toFloat())
            putFloat("zoom", c.zoom.toFloat())
        }
    }
}
