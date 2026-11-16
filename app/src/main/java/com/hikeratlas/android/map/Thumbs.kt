package com.hikeratlas.android.map

import android.graphics.Bitmap
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
import com.hikeratlas.android.atproto.Exif
import com.hikeratlas.android.atproto.Photos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pictures from the web (trip reports' thumbnails, posts' photos): fetched
 * with the app's client, decoded (WebP, JPEG, PNG; a JPEG drawn upright by
 * its EXIF Orientation, as a browser does) and kept for the session. A
 * post's photo comes through the indexer (getPhoto), with its author's PDS
 * as the fallback: the cache keeps it under the indexer's URL either way.
 * Caches are bounded by bytes, not count: one full-size photo (2048 px) is
 * 16 MB decoded, so a count would let a few posts fill the heap.
 */
object Thumbs {
    /** Set by the app at start; without it (tests), nothing loads. */
    @Volatile var client: OkHttpClient? = null

    /** Posts' photos in a grid: decoded at most this big, not at full size. */
    const val GRID_PX = 640
    /** The viewer's photos: the lexicon's photos are at most 2048 px; anything bigger is scaled down. */
    const val FULL_PX = 2048

    private val budget = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L shl 20).toInt()
    private val cache = bitmapCache(budget)
    // Apart, so a few full-size photos don't push every thumbnail out (and back in).
    private val full = bitmapCache(budget)

    private fun bitmapCache(bytes: Int) = object : LruCache<String, ImageBitmap>(bytes) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun key(url: String, maxPx: Int?) = if (maxPx == null) url else "$url#$maxPx"

    /** [url]'s picture if it's been loaded, decoded at most [maxPx] on its longer side (null: as it is). */
    fun cached(url: String, maxPx: Int? = null): ImageBitmap? = cache.get(key(url, maxPx))

    /**
     * The picture, or null where it can't be had (offline, gone, not a
     * picture). [fallback]: where else to get it when [url] can't give it
     * (a photo the indexer hasn't got: from its author's PDS).
     */
    suspend fun load(url: String, maxPx: Int? = null, fallback: String? = null): ImageBitmap? = fetch(url, fallback, maxPx, cache)

    fun cachedPhoto(url: String): ImageBitmap? = full.get(key(url, FULL_PX))

    /** A photo at full size, for the viewer; [fallback] as for [load]. */
    suspend fun loadPhoto(url: String, fallback: String? = null): ImageBitmap? = fetch(url, fallback, FULL_PX, full)

    private suspend fun fetch(url: String, fallback: String?, maxPx: Int?, into: LruCache<String, ImageBitmap>): ImageBitmap? {
        val k = key(url, maxPx)
        into.get(k)?.let { return it }
        val c = client ?: return null
        return withContext(Dispatchers.IO) {
            // As the website's PhotoImg: whatever goes wrong with the indexer
            // (404 for a post it hasn't indexed yet, or it's down), the PDS.
            (get(c, url, maxPx) ?: fallback?.let { get(c, it, maxPx) })?.also { into.put(k, it) }
        }
    }

    private fun get(c: OkHttpClient, url: String, maxPx: Int?): ImageBitmap? = try {
        c.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else decode(r.body.bytes(), maxPx)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun decode(bytes: ByteArray, maxPx: Int?): ImageBitmap? {
        val opts = BitmapFactory.Options()
        if (maxPx != null) {
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxPx)
            opts.inJustDecodeBounds = false
        }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return upright(b, bytes).asImageBitmap()
    }

    /**
     * [b] turned as its JPEG's EXIF Orientation says: a photo uploaded as it
     * is keeps its camera's (often 6, stored on its side), and its record's
     * aspectRatio is its size once turned, so placeholders match it.
     */
    private fun upright(b: Bitmap, bytes: ByteArray): Bitmap {
        val m = Photos.orientationMatrix(Exif.read(bytes).orientation) ?: return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
    }

    /** BitmapFactory's inSampleSize (a power of two) for a [w]×[h] picture to be at most [maxPx] on its longer side. */
    internal fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var s = 1
        while (maxOf(w, h) / s > maxPx) s *= 2
        return s
    }
}

/**
 * A picture from [url] (else [fallback]), cropped to [modifier]'s size: a
 * pale box while it loads, nothing if it can't. [maxPx] decodes it smaller
 * (null: as it is).
 */
@Composable
fun RemoteThumb(url: String, modifier: Modifier, maxPx: Int? = null, fallback: String? = null) {
    var img by remember(url, maxPx) { mutableStateOf(Thumbs.cached(url, maxPx)) }
    var failed by remember(url, maxPx) { mutableStateOf(false) }
    LaunchedEffect(url, maxPx) {
        if (img == null) {
            img = Thumbs.load(url, maxPx, fallback)
            failed = img == null
        }
    }
    val i = img
    when {
        i != null -> Image(i, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
        !failed -> Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
