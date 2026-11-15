package com.hikeratlas.android.atproto

import java.security.SecureRandom

/**
 * Record keys as AT Protocol's TIDs: microseconds since the epoch and a
 * random clock id, in 13 sortable base32 characters, as a PDS makes them.
 * The phone chooses a post's keys itself so that a create tried again
 * (its answer lost to a dropped connection) finds the record the first
 * try made, rather than making a second one.
 */
object Tid {
    private const val CHARS = "234567abcdefghijklmnopqrstuvwxyz"
    private val clock = SecureRandom().nextInt(1024).toLong()
    private var last = 0L

    /** A new TID, later than any made before it in this process. */
    @Synchronized
    fun next(nowMicros: Long = System.currentTimeMillis() * 1000): String {
        val t = maxOf(nowMicros, last + 1)
        last = t
        return encode((t shl 10) or clock)
    }

    internal fun encode(v: Long): String {
        val out = CharArray(13)
        var x = v and Long.MAX_VALUE
        for (i in 12 downTo 0) {
            out[i] = CHARS[(x and 31).toInt()]
            x = x ushr 5
        }
        return String(out)
    }

    /** Whether [s] is a TID as the spec has it (its first character keeps the top bit clear). */
    fun isTid(s: String) = s.length == 13 && s[0] in "234567abcdefghij" && s.all { it in CHARS }
}
