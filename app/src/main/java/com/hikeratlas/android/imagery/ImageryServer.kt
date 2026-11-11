package com.hikeratlas.android.imagery

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.createBitmap
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.tan

/**
 * Serves aerial imagery at https://imagery.hikeratlas.invalid/{service}/{z}/{y}/{x},
 * as the website's imagery:// protocol does: each service is asked only for
 * tiles it can fill (by its coverage outline), and where a service draws
 * outside its area or marks "no imagery" in white, that's made transparent.
 */
class ImageryServer(private val assets: AssetManager, private val client: OkHttpClient) : Interceptor {
    private val outlines = ConcurrentHashMap<String, Outline>()

    private fun outline(path: String) = outlines.getOrPut(path) { Outline.parse(assets.open(path).use { it.readBytes().decodeToString() }) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        val parts = request.url.pathSegments
        val service = Imagery.service(parts.getOrNull(0) ?: "")
        val z = parts.getOrNull(1)?.toIntOrNull()
        val y = parts.getOrNull(2)?.toIntOrNull()
        val x = parts.getOrNull(3)?.toIntOrNull()
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        fun empty() = builder.code(204).message("No Content").body(ByteArray(0).toResponseBody()).build()
        if (service == null || z == null || y == null || x == null) return builder.code(404).message("Not Found").body(ByteArray(0).toResponseBody()).build()

        val box = Outline.tileBox(z, x, y)
        if (service.cover != null && !outline(service.cover).touches(box)) return empty()
        if (service.skipInside != null && outline(service.skipInside).within(box)) return empty()

        val url = Imagery.tileUrl(service, z, x, y)
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code == 404) return empty()
            if (!r.isSuccessful) throw IOException("${r.code} fetching $url")
            r.body.bytes()
        }
        val out = if (service.mask || service.white) process(bytes, service, z, x, y) ?: return empty() else bytes
        return builder.code(200).message("OK").header("Cache-Control", "max-age=604800")
            .body(out.toResponseBody(if (out === bytes) null else PNG)).build()
    }

    /** Clips to the cover outline and/or makes white transparent; a PNG. */
    private fun process(bytes: ByteArray, s: Imagery.Service, z: Int, x: Int, y: Int): ByteArray? {
        val img = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val px = img.width
        val out = createBitmap(px, img.height)
        val canvas = Canvas(out)
        if (s.mask && s.cover != null) {
            // Keep only what's inside the outline.
            val n = (1 shl z).toDouble()
            val path = Path()
            for (ring in outline(s.cover).rings) {
                ring.forEachIndexed { k, p ->
                    val mx = (((p[0] + 180) / 360 * n - x) * px).toFloat()
                    val my = (((1 - asinh(tan(p[1] * PI / 180)) / PI) / 2 * n - y) * px).toFloat()
                    if (k == 0) path.moveTo(mx, my) else path.lineTo(mx, my)
                }
                path.close()
            }
            canvas.drawPath(path, Paint().apply { isAntiAlias = true })
            canvas.drawBitmap(img, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
        } else {
            canvas.drawBitmap(img, 0f, 0f, null)
        }
        if (s.white) {
            val pixels = IntArray(px * out.height)
            out.getPixels(pixels, 0, px, 0, 0, px, out.height)
            for (i in pixels.indices) {
                val c = pixels[i]
                if ((c shr 16 and 0xff) >= 254 && (c shr 8 and 0xff) >= 254 && (c and 0xff) >= 254) pixels[i] = 0
            }
            out.setPixels(pixels, 0, px, 0, 0, px, out.height)
        }
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    companion object {
        const val HOST = "imagery.hikeratlas.invalid"
        private val PNG = "image/png".toMediaType()
    }
}
