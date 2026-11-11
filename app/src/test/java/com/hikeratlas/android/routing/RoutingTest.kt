package com.hikeratlas.android.routing

import com.hikeratlas.android.contours.MvtLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

class RoutingTest {
    private val z = 14
    private val tx = 2600
    private val ty = 5560

    /** A tile with ways: (props, line in tile units). */
    private fun tile(vararg ways: Pair<Map<String, Any>, IntArray>): ByteArray {
        val path = MvtLayer("path")
        for ((props, line) in ways) path.add(props + mapOf("name" to "x"), listOf(line))
        return path.encode()
    }

    /** Tile units in tile (tx, ty) to [lng, lat]. */
    private fun ll(x: Double, y: Double, tileX: Int = tx): List<Double> {
        val n = 4096.0 * (1 shl z)
        val wx = tileX * 4096 + x
        val wy = ty * 4096 + y
        return listOf(wx / n * 360 - 180, atan(sinh(PI * (1 - 2 * wy / n))) * 180 / PI)
    }

    @Test
    fun routesAlongSharedNodesAndAvoidsForbiddenWays() {
        // A -- B -- C along y = 2000, and a shortcut A -- C that walkers may not use.
        val t = tile(
            mapOf<String, Any>() to intArrayOf(1000, 2000, 2000, 2000),
            mapOf<String, Any>() to intArrayOf(2000, 2000, 3000, 2000),
            mapOf<String, Any>("foot" to "no") to intArrayOf(1000, 2000, 2000, 1990, 3000, 2000),
            mapOf<String, Any>("closed" to true) to intArrayOf(1000, 2000, 3000, 2001),
        )
        val g = Graph.build(listOf(Triple(tx, ty, t)), z)
        val (s, e) = Graph.snapPair(g, ll(1000.0, 2000.0), ll(3000.0, 2000.0))
        val path = Graph.shortestPath(g, s, e)!!
        assertEquals(3, path.nodes.size) // A, B, C
        assertTrue(path.metres > 0)
    }

    @Test
    fun joinsWaysCutAtATileEdge() {
        val west = tile(mapOf<String, Any>() to intArrayOf(3000, 2000, 4096, 2000))
        val east = tile(mapOf<String, Any>() to intArrayOf(1, 2001, 1000, 2000))
        val g = Graph.build(listOf(Triple(tx, ty, west), Triple(tx + 1, ty, east)), z)
        val (s, e) = Graph.snapPair(g, ll(3000.0, 2000.0), ll(1000.0, 2000.0, tx + 1))
        assertTrue(Graph.shortestPath(g, s, e) != null)
    }

    @Test
    fun snapsToTheMainNetworkNotAStrayBit() {
        // The main trail, and a short unconnected bit right by the end point.
        val t = tile(
            mapOf<String, Any>() to intArrayOf(500, 2000, 3500, 2000),
            mapOf<String, Any>() to intArrayOf(3500, 1500, 3520, 1500),
        )
        val g = Graph.build(listOf(Triple(tx, ty, t)), z)
        val (s, e) = Graph.snapPair(g, ll(500.0, 2000.0), ll(3510.0, 1501.0))
        assertTrue(Graph.shortestPath(g, s, e) != null)
    }

    @Test
    fun tooFarApartIsAnError() {
        try {
            Graph.tilesAround(listOf(-123.0, 49.0), listOf(-120.0, 51.0))
            throw AssertionError("expected TooFarException")
        } catch (e: Graph.TooFarException) {
            // expected
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun plansAddsUndoesAndMoves() = runTest {
        val here = UnconfinedTestDispatcher(testScheduler)
        // One straight trail across the tile; every other tile empty.
        val t = tile(mapOf<String, Any>() to intArrayOf(100, 2000, 2000, 2000, 4000, 2000))
        var fetches = 0
        // Its own scope, on the unconfined dispatcher: its queue runs at once.
        val scope = CoroutineScope(here + Job())
        val planner = Planner(
            scope,
            fetch = { zz, tiles -> fetches++; tiles.associateWith { (x, y) -> if (zz == z && x == tx && y == ty) t else null } },
            profiler = null,
            io = here,
            cpu = here,
        )
        val zoom = 13.0 // routes at z14: two in from the view
        planner.add(ll(100.0, 2050.0), zoom)
        advanceUntilIdle()
        assertEquals(1, planner.points.size)
        planner.add(ll(2000.0, 1950.0), zoom)
        planner.add(ll(4000.0, 2000.0), zoom)
        advanceUntilIdle()
        assertNull(planner.error)
        assertEquals(3, planner.points.size)
        // Snapped onto the trail: the first point moved to y = 2000.
        assertEquals(ll(100.0, 2000.0)[1], planner.points[0][1], 1e-6)
        val full = planner.metres
        assertTrue(full > 0)
        // Tiles are cached: later legs fetch only what earlier ones didn't.
        val before = fetches
        planner.undo()
        advanceUntilIdle()
        assertEquals(2, planner.points.size)
        assertTrue(planner.metres < full)
        // Points snap to the trail's vertices (here at 100, 2000 and 4000).
        planner.move(1, ll(3900.0, 2030.0), zoom)
        advanceUntilIdle()
        assertEquals(ll(4000.0, 2000.0)[0], planner.points[1][0], 1e-6)
        assertEquals(full, planner.metres, 1e-6)
        assertTrue("no refetch for cached tiles", fetches - before <= 1)
        planner.clear()
        advanceUntilIdle()
        assertEquals(0, planner.points.size)
        assertEquals(0.0, planner.metres, 0.0)
        scope.cancel()
    }
}
