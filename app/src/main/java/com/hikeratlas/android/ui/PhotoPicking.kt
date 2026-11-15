package com.hikeratlas.android.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hikeratlas.android.atproto.Photos
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * A photo the user picked or just took, to be prepared for a post.
 * [capturedAt]: when, for a photo just taken (its EXIF may not say).
 * [temporary]: the app's own file (the camera's), dropped once prepared.
 */
data class PhotoInput(val uri: Uri, val capturedAt: Long? = null, val temporary: Boolean = false)

/**
 * Getting photos with their location. Android removes GPS from the photos
 * it hands an app, without any error, unless the app asks in the right way
 * for the photo's kind of URI and Android version:
 *
 * - API 26 to 28: nothing is removed; any URI is read as it is.
 * - API 29 (scoped storage) on: MediaStore redacts EXIF location unless the
 *   app holds ACCESS_MEDIA_LOCATION (a runtime permission) and opens the
 *   item through [MediaStore.setRequireOriginal]. That works for MediaStore
 *   URIs, and for the documents ACTION_OPEN_DOCUMENT returns from the media
 *   and external storage providers once turned into MediaStore URIs with
 *   [MediaStore.getMediaUri] (which carries over the app's access).
 * - The system photo picker (ACTION_PICK_IMAGES: API 33 on, and API 30 on
 *   through Google system updates) hands out read-only picker URIs, which
 *   ACCESS_MEDIA_LOCATION and setRequireOriginal don't apply to: its photos
 *   always came without their location. From version 37.1 the picker takes
 *   EXTRA_REQUEST_LOCATION_METADATA_ACCESS, and then asks the user whether
 *   to include location; if they allow it, the picker URI keeps it.
 *
 * So photos come from the photo picker, asking for location, from 37.1
 * on; before that, from ACTION_OPEN_DOCUMENT (the system's file chooser,
 * which shows recent images and the gallery too), whose photos keep their
 * location with ACCESS_MEDIA_LOCATION.
 */
object PhotoSources {
    /** MediaStore.EXTRA_REQUEST_LOCATION_METADATA_ACCESS (37.1), by value: compiling against 37 hasn't the constant. */
    const val EXTRA_REQUEST_LOCATION = "android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS"

    /** Whether the photo picker can hand over photos with their location (37.1 on). */
    @ChecksSdkIntAtLeast(api = 37)
    fun pickerKeepsLocation(): Boolean =
        Build.VERSION.SDK_INT >= 37 && (Build.VERSION.SDK_INT > 37 || Build.VERSION.SDK_INT_FULL >= 37_00001)

    /** Whether to ask for ACCESS_MEDIA_LOCATION before picking: API 29 on, not yet granted. */
    fun needsMediaLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED

    /**
     * A picked photo's bytes, its location kept where Android allows: first
     * the original (MediaStore, with ACCESS_MEDIA_LOCATION), then the URI as
     * it is, which works for any photo but may have lost its GPS.
     */
    fun read(context: Context, uri: Uri): ByteArray {
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !needsMediaLocation(context)) original(context, uri)?.let(::add)
            add(uri)
        }
        var failure: Exception? = null
        for (u in tries) {
            try {
                val stream = context.contentResolver.openInputStream(u) ?: throw FileNotFoundException("Nothing at $u")
                return stream.use(::readLimited)
            } catch (e: Exception) {
                // UnsupportedOperationException (no original to give), a
                // SecurityException, or no such file: try the next way.
                if (e is TooBig) throw e
                failure = e
            }
        }
        throw IOException(failure?.message ?: "Couldn't read the photo", failure)
    }

    /** The MediaStore URI of [uri]'s original, unredacted contents, if it has one (API 29 on). */
    private fun original(context: Context, uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val media = when {
            // Picker URIs (media/picker/…, media/picker_get_content/…): no original to give.
            uri.authority == MediaStore.AUTHORITY && uri.pathSegments.firstOrNull()?.startsWith("picker") == true -> null
            uri.authority == MediaStore.AUTHORITY -> uri
            DocumentsContract.isDocumentUri(context, uri) -> try {
                MediaStore.getMediaUri(context, uri)
            } catch (e: Exception) {
                // Not the media or external storage provider's (a cloud app's, say).
                null
            }
            else -> null
        } ?: return null
        return MediaStore.setRequireOriginal(media)
    }

    private class TooBig : IOException("The photo is too big to read (over 64 MB)")

    private fun readLimited(s: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > Photos.MAX_INPUT) throw TooBig()
        }
    }
}

/** The photo picker, asking for photos' location (37.1 on: see [PhotoSources]). */
private class PickWithLocation(max: Int) : ActivityResultContracts.PickMultipleVisualMedia(max) {
    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
        super.createIntent(context, input).putExtra(PhotoSources.EXTRA_REQUEST_LOCATION, true)
}

/**
 * "Choose photos" and "Take a photo", for the post form: up to [room]
 * more. Before choosing, asks for ACCESS_MEDIA_LOCATION (API 29 on),
 * saying why, once per form; choosing goes ahead either way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoButtons(room: Int, enabled: Boolean, onPicked: (List<PhotoInput>) -> Unit) {
    val context = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(PickWithLocation(Photos.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) onPicked(uris.take(room).map { PhotoInput(it) })
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris.take(room).map { PhotoInput(it) })
    }
    fun choose() {
        if (PhotoSources.pickerKeepsLocation()) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            documents.launch(arrayOf("image/*"))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, on to choosing: without it, photos just lack their location.
        choose()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        cameraFile = null
        if (f == null) return@rememberLauncherForActivityResult
        if (ok && f.length() > 0) onPicked(listOf(PhotoInput(Uri.fromFile(f), capturedAt = System.currentTimeMillis(), temporary = true)))
        else f.delete()
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (!asked && PhotoSources.needsMediaLocation(context)) explain = true else choose()
            },
            enabled = enabled && room > 0,
        ) { Text("Choose photos") }
        OutlinedButton(
            onClick = {
                // Into the app's own storage, shared with the camera app for the one photo.
                val f = File(context.filesDir, "camera/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
                cameraFile = f
                cameraFailed = false
                try {
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
                } catch (e: ActivityNotFoundException) {
                    cameraFile = null
                    cameraFailed = true
                }
            },
            enabled = enabled && room > 0,
        ) { Text("Take a photo") }
    }
    if (cameraFailed) Text("No camera app to take a photo with.")

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Keep where your photos were taken?") },
            text = {
                Text(
                    "Android hides where a photo was taken from apps, unless you allow it to share photo locations. " +
                        "With it, your photos show the place on your post, as they do on the website.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    asked = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) permission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) else choose()
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { explain = false; asked = true; choose() }) { Text("Not now") }
            },
        )
    }
}
