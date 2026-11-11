package com.hikeratlas.android.search

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A search result, as the website's search API returns it. */
@Serializable
data class SearchItem(
    val kind: String,
    val name: String,
    val osm_id: String,
    val lat: Double,
    val lon: Double,
    val min_lat: Double? = null,
    val min_lon: Double? = null,
    val max_lat: Double? = null,
    val max_lon: Double? = null,
    val park: String? = null,
    val state: String? = null,
    val country: String? = null,
    val ele: Double? = null,
    val km: Double? = null,
    val area: Double? = null,
    val gain: Double? = null,
    val hike: String? = null,
    val hikes: Int? = null,
) {
    val key get() = "$kind/$osm_id"
    val hasBox get() = min_lat != null && min_lon != null && max_lat != null && max_lon != null
}

class SearchApi(private val client: OkHttpClient) {
    suspend fun search(query: String, nearLat: Double?, nearLon: Double?): List<SearchItem> = withContext(Dispatchers.IO) {
        val url = ENDPOINT.toHttpUrl().newBuilder().addQueryParameter("q", query).apply {
            if (nearLat != null && nearLon != null) {
                addQueryParameter("lat", String.format(Locale.US, "%.3f", nearLat))
                addQueryParameter("lon", String.format(Locale.US, "%.3f", nearLon))
            }
        }.build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("search failed: HTTP ${r.code}")
            JSON.decodeFromString(ListSerializer(SearchItem.serializer()), r.body.string())
        }
    }

    companion object {
        const val ENDPOINT = "https://lono5me4vqwi2h6dmm66oldkie0hwewm.lambda-url.us-east-1.on.aws/"
        val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        private val HIKE_LABEL = mapOf("day" to "Day hike", "base" to "Base camp", "multi" to "Multi-day", "stop" to "Stop")
        private val KIND_LABEL = mapOf(
            "lake" to "Lake", "waterfall" to "Waterfall", "camp_site" to "Campsite", "cliff" to "Cliff",
            "park" to "Park", "city" to "City", "town" to "Town", "village" to "Village",
        )

        private fun Double?.nonZero() = this?.takeIf { it != 0.0 }

        private fun kmText(km: Double) = if (km < 10) String.format(Locale.US, "%.1f km", km) else "${km.roundToInt()} km"
        private fun thousands(x: Double) = String.format(Locale.US, "%,d", x.roundToInt())

        /**
         * A result's second line, as the website writes it: "2,830 m · Jasper NP,
         * Alberta", "Lake · 1.1 km² · Banff NP, Alberta", "Park · 14 hikes · Alberta".
         */
        fun describe(item: SearchItem): String {
            val what = mutableListOf<String>()
            when (item.kind) {
                // As the website: zero (falsy in JavaScript) means unknown.
                "peak" -> item.ele.nonZero()?.let { what += "${thousands(it)} m" }
                "trail" -> item.km.nonZero()?.let { what += kmText(it) }
                "hike" -> {
                    what += HIKE_LABEL[item.hike] ?: "Hike"
                    item.km.nonZero()?.let { what += kmText(it) }
                    item.gain.nonZero()?.let { what += "↑ ${thousands(it)} m" }
                }
                else -> KIND_LABEL[item.kind]?.let { label ->
                    what += label
                    if (item.kind == "park" && item.hikes != null && item.hikes > 0) what += "${item.hikes} hike${if (item.hikes == 1) "" else "s"}"
                    if (item.kind == "lake" && item.area != null && item.area >= 100_000) {
                        what += String.format(Locale.US, if (item.area < 10e6) "%.1f km²" else "%.0f km²", item.area / 1e6)
                    }
                }
            }
            val where = listOfNotNull(item.park, item.state).filter { it.isNotEmpty() }.joinToString(", ")
            return (what + where).filter { it.isNotEmpty() }.joinToString(" · ")
        }

        /** "38 km away" for a result well away from where you're looking. */
        fun away(item: SearchItem, lat: Double?, lon: Double?): String {
            if (lat == null || lon == null) return ""
            val r = PI / 180
            val h = sin((item.lat - lat) * r / 2).let { it * it } +
                cos(lat * r) * cos(item.lat * r) * sin((item.lon - lon) * r / 2).let { it * it }
            val km = 2 * 6371 * asin(sqrt(min(1.0, h)))
            if (km < 25) return ""
            return "${if (km < 100) km.roundToInt() else (km / 10).roundToInt() * 10} km away"
        }

        /** Each result's second line; same-looking results get their country or position. */
        fun secondLines(items: List<SearchItem>, lat: Double?, lon: Double?): List<String> {
            val lines = items.map { listOf(describe(it), away(it, lat, lon)).filter(String::isNotEmpty).joinToString(" · ") }.toMutableList()
            items.indices.groupBy { "${items[it].name}\n${lines[it]}" }.values.filter { it.size > 1 }.forEach { idx ->
                val countries = idx.map { items[it].country }.toSet()
                for (i in idx) {
                    val extra = if (countries.size == idx.size) items[i].country else String.format(
                        Locale.US, "%.1f° %s, %.1f° %s",
                        kotlin.math.abs(items[i].lat), if (items[i].lat >= 0) "N" else "S",
                        kotlin.math.abs(items[i].lon), if (items[i].lon >= 0) "E" else "W",
                    )
                    lines[i] = listOfNotNull(lines[i], extra).filter(String::isNotEmpty).joinToString(" · ")
                }
            }
            return lines
        }
    }
}

/** The last few picks, shown when search is empty. */
class RecentSearches(context: Context) {
    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)

    fun load(): List<SearchItem> = try {
        SearchApi.JSON.decodeFromString(ListSerializer(SearchItem.serializer()), prefs.getString(KEY, "[]")!!)
    } catch (e: Exception) {
        emptyList()
    }

    fun add(item: SearchItem) {
        val v = (listOf(item) + load().filter { it.key != item.key }).take(MAX)
        prefs.edit { putString(KEY, SearchApi.JSON.encodeToString(ListSerializer(SearchItem.serializer()), v)) }
    }

    private companion object {
        const val KEY = "recent"
        const val MAX = 5
    }
}
