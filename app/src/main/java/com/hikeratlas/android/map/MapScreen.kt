package com.hikeratlas.android.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hikeratlas.android.style.StyleLoader
import okhttp3.OkHttpClient
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val ROUTES_LAYER = "routes-route"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(http: OkHttpClient) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var routes by remember { mutableStateOf<List<RouteInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.cameraPosition = CameraStore.load(context)
            m.addOnCameraIdleListener { CameraStore.save(context, m.cameraPosition) }
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val r = 12 * density
                val hits = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), ROUTES_LAYER)
                routes = RouteInfo.distinct(hits)
                routes.isNotEmpty()
            }
            map = m
        }
    }

    LaunchedEffect(map, attempt) {
        val m = map ?: return@LaunchedEffect
        error = null
        try {
            val json = StyleLoader(http).load()
            m.setStyle(Style.Builder().fromJson(json)) { style = it }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            val m = map
            val s = style
            if (m != null && s != null) followLocation(context, m, s)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (style == null && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Couldn't load the map: $msg", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { attempt++ }, modifier = Modifier.padding(top = 12.dp)) { Text("Try again") }
            }
        }

        SmallFloatingActionButton(
            onClick = {
                val m = map ?: return@SmallFloatingActionButton
                val s = style ?: return@SmallFloatingActionButton
                if (hasLocationPermission(context)) {
                    followLocation(context, m, s)
                } else {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
        ) {
            Text("◎", style = MaterialTheme.typography.titleLarge)
        }
    }

    if (routes.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { routes = emptyList() }) {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
                items(routes, key = { it.id }) { r ->
                    ListItem(
                        headlineContent = { Text(r.displayTitle) },
                        supportingContent = { Text(r.summary) },
                    )
                }
            }
        }
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission") // callers check
private fun followLocation(context: Context, map: MapLibreMap, style: Style) {
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) {
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(true).build(),
        )
    }
    lc.isLocationComponentEnabled = true
    lc.renderMode = RenderMode.COMPASS
    lc.cameraMode = CameraMode.TRACKING
    if (map.cameraPosition.zoom < 12) lc.zoomWhileTracking(14.0)
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
