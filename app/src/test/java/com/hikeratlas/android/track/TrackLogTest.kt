package com.hikeratlas.android.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLogTest {
    // ~11.1 m per 0.0001° of latitude.
    private fun fix(t: Long, lat: Double, acc: Float? = 5f) = Fix(t, lat, -123.0, acc)

    @Test
    fun dropsVagueFixesAndJitter() {
        val log = TrackLog()
        assertTrue(log.accept(fix(0, 49.0)))
        assertFalse("no accuracy", log.accept(fix(1, 49.001, acc = null)))
        assertFalse("too vague", log.accept(fix(2, 49.001, acc = 45f)))
        assertFalse("under 5 m: jitter", log.accept(fix(3, 49.00003)))
        assertTrue(log.accept(fix(4, 49.0001)))
        assertEquals(2, log.segments.single().size)
        assertEquals(11.1, log.metres, 0.1)
    }

    @Test
    fun aPauseStartsASegmentAndItsGapIsntCounted() {
        val log = TrackLog()
        log.accept(fix(0, 49.0))
        log.accept(fix(1, 49.0001))
        log.breakSegment()
        log.breakSegment() // twice: still one new segment
        // A kilometre on, after the pause: not part of the distance.
        assertTrue(log.accept(fix(2, 49.009)))
        log.accept(fix(3, 49.0091))
        assertEquals(listOf(2, 2), log.segments.map { it.size })
        assertEquals(1, log.segment)
        assertEquals(22.2, log.metres, 0.2)
    }

    @Test
    fun restoredFixesCountAsTheyWere() {
        val log = TrackLog()
        log.restore(fix(0, 49.0), 0)
        log.restore(fix(1, 49.0001), 0)
        log.restore(fix(2, 49.009), 1)
        assertEquals(listOf(2, 1), log.segments.map { it.size })
        assertEquals(11.1, log.metres, 0.1)
    }
}
