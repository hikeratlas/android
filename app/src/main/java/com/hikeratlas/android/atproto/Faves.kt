package com.hikeratlas.android.atproto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Faves (com.hikeratlas.temp.fave, in hikeratlas/atproto-lexicon), as the
 * website's app/atproto/faves.ts: records in the user's own repository, one
 * per route, whose record key is the route's key. The record holds only
 * when: faving again replaces it, unfaving is one delete, and whether a
 * route is faved is one lookup.
 */
object Faves {
    const val NSID = "com.hikeratlas.temp.fave"
    private const val PAGE = 100
    private const val PAGES = 5

    /** A fave: its route's key, and when ("" if the record doesn't say). */
    data class Fave(val key: String, val createdAt: String)

    /** Whether [key]'s route is faved: getRecord finds it. Errors other than "no such record" throw. */
    suspend fun isFaved(xrpc: Xrpc, did: String, key: String): Boolean = try {
        xrpc.query("com.atproto.repo.getRecord", mapOf("repo" to did, "collection" to NSID, "rkey" to key))
        true
    } catch (e: XrpcException) {
        // RecordNotFound is a 400, as the website takes it.
        if (e.status == 400) false else throw e
    }

    /** Faves [key]'s route (putRecord, so faving twice is one record) or unfaves it (deleteRecord). */
    suspend fun set(xrpc: Xrpc, did: String, key: String, on: Boolean) {
        if (on) {
            xrpc.procedure(
                "com.atproto.repo.putRecord",
                buildJsonObject {
                    put("repo", did); put("collection", NSID); put("rkey", key)
                    put("record", buildJsonObject { put("\$type", NSID); put("createdAt", now()) })
                },
            )
        } else {
            xrpc.procedure(
                "com.atproto.repo.deleteRecord",
                buildJsonObject { put("repo", did); put("collection", NSID); put("rkey", key) },
            )
        }
    }

    /**
     * Someone's faves, newest first: up to 500, the PDS paging by 100. With
     * [Xrpc.public] it reads anyone's (they're public records); throws if a
     * page can't be read, rather than give a list with some missing.
     */
    suspend fun list(xrpc: Xrpc, did: String): List<Fave> {
        val out = mutableListOf<Fave>()
        var cursor: String? = null
        for (page in 0 until PAGES) {
            val r = xrpc.query(
                "com.atproto.repo.listRecords",
                mapOf("repo" to did, "collection" to NSID, "limit" to PAGE, "cursor" to cursor),
            )
            val records = r["records"]?.jsonArray.orEmpty()
            for (rec in records) {
                val o = rec as? JsonObject ?: continue
                val key = o["uri"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: continue
                val at = ((o["value"] as? JsonObject)?.get("createdAt") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
                out += Fave(key, at)
            }
            cursor = r["cursor"]?.jsonPrimitive?.contentOrNull
            if (cursor == null || records.size < PAGE) break
        }
        return out.sortedByDescending { it.createdAt }
    }

    // As JavaScript's toISOString, which the website writes: milliseconds, UTC.
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private fun now() = ISO.format(Instant.now())
}
