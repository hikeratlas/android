package com.hikeratlas.android.atproto

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * That an account uses Hiker Atlas (com.hikeratlas.temp.actor.profile/self,
 * in hikeratlas/atproto-lexicon), and its settings: units, and the licence
 * for new posts. As the website's ensureProfile (app/atproto/session.ts)
 * and saveSettings (app/atproto/posts.ts).
 */
object ProfileRecord {
    const val NSID = "com.hikeratlas.temp.actor.profile"
    private const val RKEY = "self"

    /**
     * The profile record, written the first time an account signs in (with
     * createdAt only) and never overwritten: indexers find our users by it.
     * Gives its value (the one just written, if it was missing), or null
     * if the PDS couldn't be asked: we try again next sign-in or launch.
     */
    suspend fun ensure(xrpc: Xrpc, did: String): JsonObject? {
        try {
            return read(xrpc, did) ?: run {
                val record = buildJsonObject { put("\$type", NSID); put("createdAt", now()) }
                xrpc.procedure(
                    "com.atproto.repo.createRecord",
                    buildJsonObject {
                        put("repo", did); put("collection", NSID); put("rkey", RKEY)
                        put("record", record)
                    },
                )
                record
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
    }

    /**
     * Changes settings ([change]'s fields, e.g. `units`), keeping the
     * record's others (createdAt, license). Throws if the record can't be
     * read: writing it blind would lose them.
     */
    suspend fun saveSettings(xrpc: Xrpc, did: String, change: Map<String, JsonElement>) {
        val now = read(xrpc, did) ?: JsonObject(emptyMap())
        val record = buildMap {
            put("createdAt", JsonPrimitive(now()))
            putAll(now)
            putAll(change)
            put("\$type", JsonPrimitive(NSID))
        }
        xrpc.procedure(
            "com.atproto.repo.putRecord",
            buildJsonObject {
                put("repo", did); put("collection", NSID); put("rkey", RKEY)
                put("record", JsonObject(record))
            },
        )
    }

    /**
     * The record's value; null if the PDS says there's none (HTTP 400,
     * RecordNotFound, as the website takes it). Other failures throw.
     */
    private suspend fun read(xrpc: Xrpc, did: String): JsonObject? = try {
        xrpc.query("com.atproto.repo.getRecord", mapOf("repo" to did, "collection" to NSID, "rkey" to RKEY))["value"] as? JsonObject
            ?: throw IOException("getRecord gave no value")
    } catch (e: XrpcException) {
        if (e.status == 400) null else throw e
    }

    // As JavaScript's toISOString, which the website writes: milliseconds, UTC.
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private fun now() = ISO.format(Instant.now())
}
