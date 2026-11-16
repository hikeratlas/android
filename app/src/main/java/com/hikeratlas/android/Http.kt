package com.hikeratlas.android

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The app's HTTP settings, shared with the live tests so they see what the app sees. */
object Http {
    // Offline downloads read up to 4 MB per range request (Pmtiles.MAX_READ):
    // OkHttp's default 10 s read timeout is too short for slow stretches. The
    // live tests used it and timed out in CI (android#95).
    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
}
