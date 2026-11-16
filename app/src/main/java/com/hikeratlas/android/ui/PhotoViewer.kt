package com.hikeratlas.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.hikeratlas.android.atproto.Indexer
import com.hikeratlas.android.atproto.Photos
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.map.RemoteThumb
import com.hikeratlas.android.map.Thumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A post's photos, as the viewer shows them: whose, under which licence, and the one tapped ([start]). */
data class PhotoSet(
    val pds: String,
    val did: String,
    val handle: String?,
    /** The post's licence; null when it gives none (Hiker Atlas only, as the website reads it). */
    val license: String?,
    val photos: List<Profiles.Photo>,
    val start: Int = 0,
) {
    /** The photo as uploaded, from its author's PDS: to download, and where the indexer can't give it. */
    fun url(p: Profiles.Photo) = Profiles.blobUrl(pds, did, p.image.cid)
    /** Its thumbnail, through the indexer (fallback: [url]), for grids. */
    fun thumb(p: Profiles.Photo) = Indexer.photoUrl(did, p.image.cid)
    /** The photo itself through the indexer's cache (fallback: [url]), for the viewer. */
    fun full(p: Profiles.Photo) = Indexer.photoUrl(did, p.image.cid, fullsize = true)
}

/** A photo's place, for the map: where it was taken, and the photo to show beside it. */
data class PhotoPin(val lat: Double, val lon: Double, val url: String, val caption: String?)

/** What the viewer does besides showing photos. */
class PhotoViewerActions(
    /** Saves a photo at full size; gives what to say once it's done (null: nothing, e.g. the share sheet opened). */
    val download: suspend (url: String, photo: Profiles.Photo) -> String?,
    /** Closes the viewer and shows the photo's place on the map. */
    val showOnMap: (PhotoPin) -> Unit,
)

/** "CC BY 4.0 · @handle": the licence as posts word it, and whose photo it is. */
internal fun photoCredit(license: String?, handle: String?, did: String): String {
    val label = Posts.LICENSE_LABEL[license ?: "hikeratlas-only"] ?: license
    val who = handle?.let { "@$it" } ?: "${did.take(16)}…"
    return "$label · $who"
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private val SCRIM = Color.Black.copy(alpha = 0.55f)
private val MUTED = Color.White.copy(alpha = 0.75f)
private val PLACEHOLDER = Color.White.copy(alpha = 0.08f)

/**
 * A post's photos, full screen on black: pinch to zoom, drag to pan, swipe
 * between them. Below, the photo's caption and licence, and its place on
 * the map when it has one; above, Close and Download. Back closes it.
 *
 * A full-screen composable rather than a Dialog: the app draws it over
 * everything, and tests can drive it (Robolectric doesn't idle some
 * content in dialogs).
 */
@Composable
fun PhotoViewer(set: PhotoSet, actions: PhotoViewerActions, onClose: () -> Unit) {
    val n = set.photos.size
    if (n == 0) return
    val pager = rememberPagerState(initialPage = set.start.coerceIn(0, n - 1)) { n }
    var zoomed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    LightSystemBars()
    val photo = set.photos[pager.currentPage.coerceIn(0, n - 1)]
    // A message is about the photo it was for.
    LaunchedEffect(pager.currentPage) { message = null }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("photo-viewer")) {
        HorizontalPager(
            pager, Modifier.fillMaxSize().testTag("photo-pager"),
            // Zoomed in, a drag pans the photo instead of turning the page.
            userScrollEnabled = !zoomed,
            key = { it },
        ) { page ->
            val p = set.photos[page]
            ZoomablePhoto(set.full(p), set.url(p), set.thumb(p), p.aspectRatio, p.alt, page, pager) { if (page == pager.currentPage) zoomed = it }
        }
        Row(
            Modifier.fillMaxWidth().background(SCRIM).statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val white = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = MUTED)
            IconButton(onClick = onClose, colors = white) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            if (n > 1) Text("${pager.currentPage + 1} / $n", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    saving = true
                    message = null
                    val p = photo
                    scope.launch {
                        message = try {
                            actions.download(set.url(p), p)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Couldn't save the photo: ${e.message ?: e}"
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                colors = white,
            ) { Icon(Icons.Filled.Download, contentDescription = "Download") }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(SCRIM).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            photo.alt?.takeIf { it.isNotBlank() }?.let {
                // Captions run to 1,000 characters: a long one scrolls rather than covering the photo.
                Text(
                    it, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                )
            }
            Text(photoCredit(set.license, set.handle, set.did), color = MUTED, style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
            val lat = photo.lat
            val lon = photo.lon
            if (lat != null && lon != null) {
                TextButton(onClick = { actions.showOnMap(PhotoPin(lat, lon, set.url(photo), photo.alt?.takeIf { it.isNotBlank() })) }) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Show on map", color = Color.White, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/**
 * One photo, fitted to the screen: pinch zooms (up to [MAX_ZOOM]), a drag
 * pans once zoomed, a double tap zooms in on that spot or back out. It
 * goes back to fitted when swiped away. [onZoomed] says whether it's zoomed in.
 * [url] is through the indexer, [fallback] from the PDS; [thumb], the grid's
 * thumbnail, shown while it loads if it's to hand, in a box of the photo's
 * [ratio] (when its record has one), where the photo will be, so nothing jumps.
 */
@Composable
private fun ZoomablePhoto(
    url: String, fallback: String, thumb: String, ratio: Photos.AspectRatio?, alt: String?, page: Int, pager: PagerState,
    onZoomed: (Boolean) -> Unit,
) {
    var img by remember(url) { mutableStateOf<ImageBitmap?>(Thumbs.cachedPhoto(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (img == null) {
            img = Thumbs.loadPhoto(url, fallback)
            failed = img == null
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // How far the photo can move each way: no further than its edges, fitted and scaled.
    fun clamp(o: Offset, s: Float): Offset {
        val i = img ?: return Offset.Zero
        if (box.width == 0 || box.height == 0) return Offset.Zero
        val fit = minOf(box.width.toFloat() / i.width, box.height.toFloat() / i.height)
        val maxX = ((i.width * fit * s - box.width) / 2).coerceAtLeast(0f)
        val maxY = ((i.height * fit * s - box.height) / 2).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun zoomTo(s: Float, o: Offset) {
        scale = s
        offset = clamp(o, s)
        onZoomed(s > 1f)
    }
    val transform = rememberTransformableState { zoom, pan, _ ->
        val s = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        zoomTo(s, offset + pan)
    }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage != page && scale != 1f) zoomTo(1f, Offset.Zero) }
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(url) {
                detectTapGestures(onDoubleTap = { at ->
                    if (scale > 1f) {
                        zoomTo(1f, Offset.Zero)
                    } else {
                        // Keep the tapped spot under the finger.
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        zoomTo(DOUBLE_TAP_ZOOM, (centre - at) * (DOUBLE_TAP_ZOOM - 1f))
                    }
                })
            }
            // Unzoomed, one-finger drags go to the pager, to turn the page.
            .transformable(transform, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        val i = img
        when {
            i != null -> Image(
                i, contentDescription = alt?.takeIf { it.isNotBlank() } ?: "Photo", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            failed -> Text("Couldn't load the photo", color = MUTED)
            else -> {
                val small = Thumbs.cached(thumb, Thumbs.GRID_PX)
                if (ratio != null) {
                    // Where the photo will be: its shape, fitted as it will be.
                    Box(
                        Modifier.aspectRatio(ratio.width.toFloat() / ratio.height).background(PLACEHOLDER).testTag("photo-placeholder"),
                    ) {
                        small?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                    }
                } else {
                    // The grid's thumbnail while the full photo loads, when it's to hand.
                    small?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

/** The map's panel for a photo's place: the photo small, its caption, and Close (back where it was opened). */
@Composable
fun PhotoPlacePanel(pin: PhotoPin, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), shadowElevation = 8.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                RemoteThumb(pin.url, Modifier.fillMaxSize(), Thumbs.GRID_PX)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Where this photo was taken", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                pin.caption?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
    }
}

/** Light status and navigation bar icons while the viewer's black is behind them; as they were after. */
@Composable
private fun LightSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.activity()?.window ?: return@DisposableEffect onDispose {}
        val bars = WindowCompat.getInsetsController(window, view)
        val status = bars.isAppearanceLightStatusBars
        val nav = bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        onDispose {
            bars.isAppearanceLightStatusBars = status
            bars.isAppearanceLightNavigationBars = nav
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
