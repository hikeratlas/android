package com.hikeratlas.android.atproto

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Hiker Atlas's indexer (hikeratlas/atproto-indexer), told about each
 * record right after the app writes or deletes it, as the website does
 * (website#160): POST /notify with the record's at:// URI, and it applies
 * that one record from the author's PDS at once, rather than at its next
 * Jetstream run a minute or two later. So what someone posts, faves or
 * deletes from the phone shows on hike sheets right away.
 *
 * Fire and forget: on OkHttp's own threads, a short timeout, every failure
 * ignored (offline, the indexer down, its per-account rate limit). It never
 * delays or fails the write; without it, the record still arrives through
 * Jetstream.
 */
object Indexer {
    const val URL = "https://indexer.hikeratlas.com/notify"
    private val JSON = "application/json".toMediaType()

    /** Sends a notify; set by the app at start (and by tests). Unset, notifies are dropped. */
    @Volatile var notifier: (String) -> Unit = {}

    /** Tells the indexer about [uri], a record just written or deleted. */
    fun notify(uri: String) {
        try { notifier(uri) } catch (_: Exception) { /* never the write's problem */ }
    }

    /** A notifier posting to [url] with [http], quickly giving up. */
    fun http(http: OkHttpClient, url: String = URL): (String) -> Unit {
        val client = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
        return { uri ->
            val body = buildJsonObject { put("uri", uri) }.toString().toRequestBody(JSON)
            client.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) = response.close()
            })
        }
    }
}
