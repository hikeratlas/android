package com.hikeratlas.android.routes

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reads the published routes bundle. Opt in with HIKERATLAS_LIVE=1. */
class LiveRoutesTest {
    @Test
    fun readsGaribaldi() = runBlocking {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val repo = RoutesRepository(OkHttpClient())
        assertTrue(repo.index().parks.size > 1000)
        val doc = repo.park("2226817")!!
        assertEquals("Garibaldi Provincial Park", doc.park?.name)
        assertTrue(doc.day_hikes.isNotEmpty())
        assertTrue(doc.day_hikes.first().route.line.size > 10)
    }
}
