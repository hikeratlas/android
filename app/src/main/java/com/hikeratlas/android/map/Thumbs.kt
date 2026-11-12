package com.hikeratlas.android.map

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Small pictures from the web (trip reports' thumbnails): fetched with the
 * app's client, decoded (WebP, JPEG, PNG) and kept for the session.
 */
object Thumbs {
    /** Set by the app at start; without it (tests), nothing loads. */
    @Volatile var client: OkHttpClient? = null

    private val cache = LruCache<String, ImageBitmap>(64)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    /** The picture, or null where it can't be had (offline, gone, not a picture). */
    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val c = client ?: return null
        return withContext(Dispatchers.IO) {
            try {
                c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val bytes = r.body.bytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }?.also { cache.put(url, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** A picture from [url], cropped to [modifier]'s size: a pale box while it loads, nothing if it can't. */
@Composable
fun RemoteThumb(url: String, modifier: Modifier) {
    var img by remember(url) { mutableStateOf(Thumbs.cached(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (img == null) {
            img = Thumbs.load(url)
            failed = img == null
        }
    }
    val i = img
    when {
        i != null -> Image(i, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
        !failed -> Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
