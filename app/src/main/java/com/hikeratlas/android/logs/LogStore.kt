package com.hikeratlas.android.logs

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A level, as written in a line: the letter after the time. */
enum class Level(val letter: Char, val label: String) {
    DEBUG('D', "Debug"),
    INFO('I', "Info"),
    WARN('W', "Warning"),
    ERROR('E', "Error"),
    ;

    companion object {
        fun of(c: Char): Level? = entries.firstOrNull { it.letter == c }
    }
}

/** One line of the log, read back: [time] as written ("2026-11-16 14:03:22.123", the phone's time). */
data class LogEntry(val time: String, val level: Level, val tag: String, val message: String) {
    val day get() = time.take(10)
}

/**
 * The log's files: a plain text file per day (yyyy-MM-dd.txt, the phone's
 * date) in [dir], the last [days] days kept and no more than [maxBytes] in
 * all, so a busy day can't fill the phone. Synchronized, so the screen can
 * read while lines are written.
 *
 * A line is "2026-11-16 14:03:22.123 I outbox: message"; a message's later
 * lines (a stack trace) follow, each starting with a tab.
 */
class LogStore(
    val dir: File,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    val days: Int = DAYS,
    val maxBytes: Long = MAX_BYTES,
) {
    private var total = -1L
    private var today: String? = null

    /** The line for a message at [millis], as it's written. */
    fun format(millis: Long, level: Level, tag: String, message: String): String {
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone())
        return "${TIME.format(t)} ${level.letter} $tag: ${message.replace("\r", "").replace("\n", "\n\t")}\n"
    }

    /** Appends [line] (from [format]) to [millis]'s day, then keeps to the limits. */
    @Synchronized
    fun append(millis: Long, line: String) {
        val day = DAY.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone()))
        dir.mkdirs()
        if (total < 0) total = files().sumOf { it.length() }
        val bytes = line.toByteArray()
        File(dir, "$day.txt").appendBytes(bytes)
        total += bytes.size
        // A new day, or over the cap: old days go.
        if (day != today || total > maxBytes) {
            today = day
            prune(LocalDate.parse(day))
        }
    }

    /** Every entry, newest first. */
    @Synchronized
    fun read(): List<LogEntry> {
        val out = mutableListOf<LogEntry>()
        for (f in files()) parse(f.readText(), out)
        return out.asReversed()
    }

    /** The files as they are, oldest first, for sharing. */
    @Synchronized
    fun text(): String = files().joinToString("") { it.readText() }

    @Synchronized
    fun clear() {
        files().forEach { it.delete() }
        total = 0
    }

    /** The day files, oldest first. */
    fun files(): List<File> =
        dir.listFiles { f -> f.isFile && NAME.matches(f.name) }?.sortedBy { it.name }.orEmpty()

    private fun prune(today: LocalDate) {
        val oldest = today.minusDays(days - 1L).toString()
        var files = files()
        for (f in files) if (f.name.take(10) < oldest) f.delete()
        files = files()
        total = files.sumOf { it.length() }
        // Oldest first, but never today's: what's happening now matters most.
        for (f in files.dropLast(1)) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
        // Today alone is over the cap: its older half goes.
        val last = files.lastOrNull()
        if (total > maxBytes && last != null && last.exists()) {
            val text = last.readText()
            var from = text.length - (maxBytes / 2).toInt().coerceAtMost(text.length)
            // From the start of an entry, not mid-way through one.
            while (from < text.length && !(from == 0 || text[from - 1] == '\n' && text[from] != '\t')) from++
            last.writeText(text.substring(from))
            total = last.length()
        }
    }

    companion object {
        const val DAYS = 14
        const val MAX_BYTES = 5L * 1024 * 1024
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val NAME = Regex("""\d{4}-\d{2}-\d{2}\.txt""")
        private val LINE = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([DIWE]) ([^:]*): ?(.*)$""")

        internal fun parse(text: String, out: MutableList<LogEntry>) {
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val m = LINE.matchEntire(line)
                if (m != null) {
                    val (time, level, tag, message) = m.destructured
                    out += LogEntry(time, Level.of(level[0])!!, tag, message)
                } else if (out.isNotEmpty()) {
                    // A later line of the entry above (a stack trace).
                    val last = out.removeAt(out.size - 1)
                    out += last.copy(message = last.message + "\n" + line.removePrefix("\t"))
                }
            }
        }
    }
}
