package com.hikeratlas.android.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pictures decoded no bigger than they're shown: grid thumbnails small, the viewer's at most 2048 px. */
class ThumbsTest {
    @Test
    fun sampleSizeKeepsTheLongerSideWithinTheLimit() {
        assertEquals(1, Thumbs.sampleSize(2048, 1536, Thumbs.FULL_PX))
        assertEquals(2, Thumbs.sampleSize(4000, 3000, Thumbs.FULL_PX))
        assertEquals(4, Thumbs.sampleSize(2048, 1536, Thumbs.GRID_PX))
        assertEquals(1, Thumbs.sampleSize(300, 300, Thumbs.GRID_PX))
        assertEquals(2, Thumbs.sampleSize(900, 1280, Thumbs.GRID_PX))
    }
}
