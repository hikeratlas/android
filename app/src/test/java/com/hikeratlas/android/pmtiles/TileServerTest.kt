package com.hikeratlas.android.pmtiles

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class TileServerTest {
    private val client = OkHttpClient.Builder().addInterceptor(TileServer).build()

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun servesTilesAndNoContentForMissingOnes() {
        val id = TileId.fromZxy(1, 1, 0)
        val archive = TestArchive.build(mapOf(id to "hello".toByteArray()), Compression.NONE, 0, 1)
        val (_, template) = TileServer.register(BytesSource(archive, key = "tile-server-test"))

        get(template.replace("{z}/{x}/{y}", "1/1/0")).use {
            assertEquals(200, it.code)
            assertEquals("hello", it.body.string())
        }
        get(template.replace("{z}/{x}/{y}", "1/0/0")).use { assertEquals(204, it.code) }
        get("https://${TileServer.HOST}/nope/0/0/0").use { assertEquals(404, it.code) }
    }
}
