package com.hikeratlas.android.logs

/**
 * Writes an uncaught exception and its stack to the log before the app
 * dies, then hands it on to the handler that was there before (Android's,
 * which shows "keeps stopping" and ends the process).
 */
class CrashLog(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            Log.crash(t, e)
        } catch (_: Throwable) {
        }
        // With none before, as the JVM would: the stack to stderr.
        if (previous != null) previous.uncaughtException(t, e) else e.printStackTrace()
    }

    companion object {
        /** Installs it once (an app made again in tests doesn't stack them). */
        fun install() {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashLog) return
            Thread.setDefaultUncaughtExceptionHandler(CrashLog(current))
        }
    }
}
