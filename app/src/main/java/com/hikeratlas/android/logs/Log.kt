package com.hikeratlas.android.logs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.hikeratlas.android.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's own log, kept on the phone for the Logs screen (#86), so a bug
 * can be looked at after the fact. Never uploaded.
 *
 * Callable from anywhere: `Log.i("outbox", "…")`. Lines are timed when
 * logged and written in order by one background thread, so a caller never
 * waits on the disk; nothing here ever throws into a caller. Before
 * [install] (and in tests that don't), lines go nowhere.
 *
 * What goes in: what the app did and why, with exception types and
 * messages. Never tokens, DPoP keys, or what people write (review text,
 * photo captions): callers don't pass those, and [scrub] masks anything
 * token-like that slips through. Handles and DIDs are public, and fine.
 * Places go through [point], rounded to about a kilometre.
 */
object Log {
    @Volatile private var store: LogStore? = null
    @Volatile private var executor: ExecutorService? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var clock: () -> Long = System::currentTimeMillis

    /** Debug lines are kept too ("Detailed logging"): on by default in debug builds only. */
    @Volatile var detailed: Boolean = false
        set(value) {
            field = value
            try { prefs?.edit { putBoolean(DETAILED, value) } } catch (_: Exception) {}
        }

    /** The files, once installed: for the Logs screen. */
    val files: LogStore? get() = store

    /** Starts keeping the log, in the app's private files (logs/). */
    fun install(context: Context) {
        val p = context.getSharedPreferences("logs", Context.MODE_PRIVATE)
        install(LogStore(File(context.filesDir, "logs")), p.getBoolean(DETAILED, BuildConfig.DEBUG))
        prefs = p
    }

    /** [install] with a store of its own; tests pass a [direct] executor so lines are written at once. */
    internal fun install(store: LogStore, detailed: Boolean, direct: Boolean = false, clock: () -> Long = System::currentTimeMillis) {
        executor?.shutdown()
        prefs = null
        this.clock = clock
        this.detailed = detailed
        this.store = store
        executor = if (direct) null else Executors.newSingleThreadExecutor { r -> Thread(r, "logs").apply { isDaemon = true } }
    }

    /** Forgets everything installed, so one test's log doesn't leak into the next. */
    internal fun resetForTest() {
        executor?.shutdownNow()
        executor = null
        store = null
        prefs = null
        detailed = false
        clock = System::currentTimeMillis
    }

    fun d(tag: String, message: String) {
        if (detailed) write(Level.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) = write(Level.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write(Level.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = write(Level.ERROR, tag, message, error)

    /** An uncaught exception, with its stack, written before returning (the app is about to die). */
    fun crash(thread: Thread, error: Throwable) {
        val s = store ?: return
        try {
            val text = scrub("Uncaught on ${thread.name}: ${error.stackTraceToString().trimEnd()}")
            val now = clock()
            // Lines already queued go first, then this, from this thread.
            flush()
            s.append(now, s.format(now, Level.ERROR, "crash", text))
        } catch (_: Throwable) {
        }
    }

    /** Waits (a little) for queued lines to be written: before reading the files, or a crash. */
    fun flush() {
        val ex = executor ?: return
        try {
            ex.submit {}.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    private fun write(level: Level, tag: String, message: String, error: Throwable?) {
        val s = store ?: return
        try {
            val now = clock()
            val text = scrub(if (error == null) message else "$message: ${describe(error)}")
            val line = s.format(now, level, tag, text)
            val ex = executor
            if (ex == null) s.append(now, line) else ex.execute { safely { s.append(now, line) } }
        } catch (_: Exception) {
            // Logging must never break what it watches (a full disk, a shut executor).
        }
    }

    private inline fun safely(f: () -> Unit) {
        try { f() } catch (_: Exception) {}
    }

    /** A place, rounded to 2 decimal places (about a kilometre): "47.45,-121.80". */
    fun point(lat: Double, lon: Double): String = String.format(Locale.US, "%.2f,%.2f", round2(lat), round2(lon))

    // Rounded before formatting, and + 0.0, so a place just south or west of 0 isn't "-0.00".
    private fun round2(v: Double) = Math.round(v * 100) / 100.0 + 0.0

    /** An exception's type and message, and its causes': "SocketTimeoutException: timeout". */
    fun describe(error: Throwable): String = buildString {
        var c: Throwable? = error
        var n = 0
        while (c != null && n < 4) {
            if (n > 0) append(" ← ")
            append(c.javaClass.simpleName.ifEmpty { c.javaClass.name })
            c.message?.let { append(": ").append(it) }
            c = c.cause.takeIf { it !== c }
            n++
        }
    }

    /**
     * Masks what looks like a secret, in case one is passed by mistake:
     * JWTs (access and refresh tokens), "Bearer …" and "DPoP …" header
     * values, and token-ish fields and query parameters (access_token=…,
     * "refresh_token": "…", code=…).
     */
    fun scrub(s: String): String {
        var out = JWT.replace(s, "[token]")
        out = SCHEME.replace(out) { "${it.groupValues[1]} [redacted]" }
        out = FIELD.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        return out
    }

    private const val DETAILED = "detailed"
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}(\.[A-Za-z0-9_-]*)?""")
    private val SCHEME = Regex("""(?i)\b(Bearer|DPoP)\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val FIELD = Regex(
        """(?i)\b(access_token|refresh_token|id_token|token|code_verifier|code|client_assertion|dpop|authorization|password|secret)("?\s*[:=]\s*"?)[^\s"&,;}]+""",
    )
}
