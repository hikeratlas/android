package com.hikeratlas.android.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaPlanTest {
    @Test
    fun tilesAreTheSpecs() {
        assertEquals(0 to 0, TileCover.tile(-122.9, 49.9, 0))
        // Vancouver at z12 (checked against the formula in Python).
        assertEquals(647 to 1401, TileCover.tile(-123.12, 49.28, 12))
        assertEquals(4L, TileCover.boxCount(listOf(-180.0, -85.0, 179.9, 85.0), 1))
    }

    // Garibaldi PP's box (regions.json) and a line across it.
    private val garibaldi = listOf(-123.16251, 49.71873, -122.79999, 50.17506)
    private val tusk = listOf(listOf(-123.03, 49.94), listOf(-123.02, 49.97), listOf(-123.0, 50.0))

    @Test
    fun aParkIsItsBoxWithFineTerrainAlongItsRoutes() {
        val p = AreaPlan.park(garibaldi, listOf(tusk))
        assertEquals((0..14).toSet(), p.vector.keys)
        assertEquals(TileCover.box(garibaldi, 14), p.vector[14])
        // Coarse terrain over the box to z12; finer only near the routes.
        assertEquals(TileCover.box(garibaldi, 12), p.dem[12])
        assertTrue(p.dem[14]!!.size < TileCover.box(garibaldi, 14).size / 10)
        assertTrue(p.dem[14]!!.containsAll(TileCover.corridor(listOf(tusk), 14, 0.0)))
        assertTrue(p.withRoutes)
        // And along its trails, once its map tiles are kept: within its box, at the fine zooms.
        assertEquals(13..14, p.alongTrails!!.zooms)
        assertEquals(garibaldi, p.alongTrails!!.box)
    }

    @Test
    fun aBigParkGetsCoarserTerrainOverall() {
        val banff = listOf(-117.13848, 50.75151, -115.14476, 52.24216)
        val p = AreaPlan.park(banff, listOf(listOf(listOf(-116.2, 51.4), listOf(-116.1, 51.45))))
        assertTrue(TileCover.boxCount(banff, 12) > AreaPlan.BIG_PARK_DEM_TILES)
        assertEquals(TileCover.box(banff, 11), p.dem[11])
        // z12 only along the routes, not the whole park.
        assertTrue(p.dem[12]!!.size < 20)
    }

    @Test
    fun aHikeIsACorridor() {
        val p = AreaPlan.hike(tusk)
        val box = TileCover.boundsOf(listOf(tusk), AreaPlan.HIKE_MARGIN_M)
        assertEquals(TileCover.box(box, 9), p.vector[9])
        // From z10, the 1 km corridor: fewer tiles than its box.
        assertTrue(p.vector[14]!!.size <= TileCover.box(box, 14).size)
        assertTrue(p.vector[14]!!.containsAll(TileCover.corridor(listOf(tusk), 14, 0.0)))
        assertEquals((0..14).toSet(), p.dem.keys)
        assertEquals(null, p.alongTrails)
    }

    @Test
    fun theOverviewIsTheBasemapAtSmallScale() {
        val p = AreaPlan.overview(listOf(-170.0, 20.0, -48.0, 75.0))
        assertEquals((0..AreaPlan.OVERVIEW_MAX).toSet(), p.vector.keys)
        assertTrue(p.dem.isEmpty())
        assertFalse(p.withRoutes)
    }
}
