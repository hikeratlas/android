package com.hikeratlas.android.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** MapLibre's own cache of what's been looked at: its size, and clearing it. */
interface MapCache {
    suspend fun bytes(): Long
    suspend fun clear()
}

/**
 * MapLibre's ambient cache (up to 200 MB of tiles, fonts and icons from
 * browsing): its database file in MapLibre's resources path. Offline areas
 * are in the app's own store, so clearing this never touches them.
 */
class MapLibreCache(private val context: Context) : MapCache {
    private fun files() = File(FileSource.getResourcesCachePath(context)).let { dir ->
        listOf("mbgl-offline.db", "mbgl-offline.db-wal", "mbgl-offline.db-shm").map { File(dir, it) }
    }

    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { files().sumOf { if (it.exists()) it.length() else 0L } }

    override suspend fun clear() {
        val m = OfflineManager.getInstance(context)
        call { m.clearAmbientCache(it) }
        // Shrinks the file: clearing alone leaves its pages for reuse.
        call { m.packDatabase(it) }
    }

    private suspend fun call(f: (OfflineManager.FileSourceCallback) -> Unit) = suspendCancellableCoroutine { k ->
        f(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = k.resume(Unit)
            override fun onError(message: String) = k.resumeWithException(IOException(message))
        })
    }
}
