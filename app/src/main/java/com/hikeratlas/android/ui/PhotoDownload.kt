package com.hikeratlas.android.ui

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.hikeratlas.android.atproto.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * The viewer's Download: the photo as it was posted (its bytes, not
 * re-encoded), into Pictures/Hiker Atlas through MediaStore on Android 10+,
 * which needs no permission there. Before that, writing to shared storage
 * needs the storage permission, so Android 8 and 9 get the share sheet
 * instead (which offers saving it too).
 */
object PhotoDownload {
    const val FOLDER = "Hiker Atlas"
    /** Where it lands, as the gallery and Files show it. */
    val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$FOLDER"

    /** The type to save it as: the blob's own, else the server's, else JPEG (what the app posts). */
    fun mimeType(blob: String?, served: String?): String =
        listOfNotNull(blob, served?.substringBefore(';')?.trim()).firstOrNull { it in EXTENSIONS } ?: "image/jpeg"

    /** A name from the photo's CID: the same photo saved twice gets the same name (MediaStore numbers the second). */
    fun fileName(cid: String, mime: String): String {
        val safe = cid.filter { it.isLetterOrDigit() }.takeLast(16).ifEmpty { "photo" }
        return "hikeratlas-$safe.${EXTENSIONS[mime] ?: "jpg"}"
    }

    private val EXTENSIONS = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp")

    /** The new row: pending until its bytes are written, so the gallery doesn't show half a photo. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun values(name: String, mime: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun collection(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Writes [bytes] to a new image in Pictures/Hiker Atlas; a failure leaves no half-written row. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToPictures(resolver: ContentResolver, bytes: ByteArray, name: String, mime: String): Uri {
        val uri = resolver.insert(collection(), values(name, mime)) ?: throw IOException("Couldn't make the file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Couldn't write the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** The share sheet for a photo written to the cache's photos/ (which FileProvider may hand out). */
    fun shareIntent(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // So the grant reaches the app picked through the chooser.
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, "Save or share photo")
    }

    /**
     * Fetches the photo at [url] and saves it (Android 10+) or opens the
     * share sheet for it; gives what to say afterwards, null for the share
     * sheet (it says enough itself).
     */
    suspend fun download(context: Context, http: OkHttpClient, url: String, photo: Profiles.Photo): String? {
        val (bytes, served) = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body.bytes() to r.header("Content-Type")
            }
        }
        val mime = mimeType(photo.image.mimeType, served)
        val name = fileName(photo.image.cid, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            withContext(Dispatchers.IO) { saveToPictures(context.contentResolver, bytes, name, mime) }
            return "Saved to $RELATIVE_PATH"
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "photos").apply { mkdirs() }
            // Only the one being shared is kept.
            dir.listFiles()?.forEach { it.delete() }
            File(dir, name).apply { writeBytes(bytes) }
        }
        context.startActivity(shareIntent(context, file, mime))
        return null
    }
}
