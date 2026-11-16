package com.hikeratlas.android.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

class LogStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 86_400_000L
    // 2026-11-16 12:00 UTC.
    private val t0 = 1_794_830_400_000L

    private fun store(maxBytes: Long = LogStore.MAX_BYTES) = LogStore(tmp.root, { ZoneOffset.UTC }, maxBytes = maxBytes)

    private fun LogStore.log(t: Long, message: String, level: Level = Level.INFO) = append(t, format(t, level, "test", message))

    @Test
    fun aFilePerDayAndTheLast14Kept() {
        val s = store()
        for (d in 0 until 20) s.log(t0 + d * day, "day $d")
        val names = s.files().map { it.name }
        assertEquals(14, names.size)
        // 20 days from 2026-11-16: the last is 2026-12-05, the first kept 13 days before.
        assertEquals("2026-11-22.txt", names.first())
        assertEquals("2026-12-05.txt", names.last())
        assertEquals("day 19", s.read().first().message)
        assertEquals("day 6", s.read().last().message)
    }

    @Test
    fun theSizeCapDropsTheOldestDaysFirst() {
        val s = store(maxBytes = 4_000)
        val big = "x".repeat(900)
        for (d in 0 until 5) {
            s.log(t0 + d * day, "day $d $big")
        }
        // Each day's line is ~950 bytes: four fit, the oldest went.
        val names = s.files().map { it.name }
        assertEquals(listOf("2026-11-17.txt", "2026-11-18.txt", "2026-11-19.txt", "2026-11-20.txt"), names)
        assertTrue(s.files().sumOf { it.length() } <= 4_000)
    }

    @Test
    fun aBusyDayAloneOverTheCapKeepsItsLatestHalf() {
        val s = store(maxBytes = 2_000)
        s.log(t0 - day, "yesterday")
        for (i in 0 until 100) s.log(t0 + i, "line $i")
        val total = s.files().sumOf { it.length() }
        assertTrue("$total", total <= 2_000)
        assertEquals(listOf("2026-11-16.txt"), s.files().map { it.name })
        val entries = s.read()
        assertEquals("line 99", entries.first().message)
        // Cut at an entry: every line read back is whole.
        assertTrue(entries.all { it.message.startsWith("line ") })
    }

    @Test
    fun linesReadBackNewestFirstWithStackTracesWhole() {
        val s = store()
        s.log(t0, "first")
        s.log(t0 + 1_000, "boom\nat a.b(C.kt:1)\nat d.e(F.kt:2)", Level.ERROR)
        s.log(t0 + 2_000, "after", Level.WARN)
        val e = s.read()
        assertEquals(listOf("after", "boom\nat a.b(C.kt:1)\nat d.e(F.kt:2)", "first"), e.map { it.message })
        assertEquals(listOf(Level.WARN, Level.ERROR, Level.INFO), e.map { it.level })
        assertEquals("2026-11-16 12:00:01.000", e[1].time)
        assertEquals("test", e[1].tag)
        assertTrue(s.text().startsWith("2026-11-16 12:00:00.000 I test: first\n"))
    }

    @Test
    fun clearDeletesEverything() {
        val s = store()
        s.log(t0 - day, "yesterday")
        s.log(t0, "today")
        s.clear()
        assertEquals(emptyList<LogEntry>(), s.read())
        assertEquals(0, s.files().size)
        s.log(t0, "again")
        assertEquals(listOf("again"), s.read().map { it.message })
    }
}
