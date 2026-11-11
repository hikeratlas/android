package com.hikeratlas.android.map

import org.maplibre.geojson.Feature
import kotlin.math.roundToInt

/**
 * A curated route, from the properties of the routes layer's features
 * (as hikeratlas/routes writes them to routes.geojsonl).
 */
data class RouteInfo(
    val id: String,
    val park: String,
    val type: String,
    val title: String,
    val kind: String?,
    val km: Double,
    val gain: Double,
) {
    val typeLabel: String get() = TYPE_LABEL[type] ?: type

    val displayTitle: String get() = if (type == "approach") "Base camp: $title" else title

    /** "Day hike · 12.3 km · ↑ 850 m", as the website shows it. */
    val summary: String
        get() = "$typeLabel · ${"%.1f".format(km)} km · ↑ ${"%,d".format(gain.roundToInt())} m"

    companion object {
        private val TYPE_LABEL = mapOf(
            "day" to "Day hike", "approach" to "Base camp", "multi-day" to "Multi-day",
            "from-camp" to "From camp", "stop" to "Stop",
        )
        private val TYPE_ORDER = listOf("day", "approach", "multi-day", "from-camp", "stop")

        fun from(f: Feature): RouteInfo? {
            val id = f.getStringOrNull("id") ?: return null
            return RouteInfo(
                id = id,
                park = f.getStringOrNull("park") ?: "",
                type = f.getStringOrNull("type") ?: "",
                title = f.getStringOrNull("title") ?: id,
                kind = f.getStringOrNull("kind"),
                km = f.getNumberOrNull("km")?.toDouble() ?: 0.0,
                gain = f.getNumberOrNull("gain")?.toDouble() ?: 0.0,
            )
        }

        /** One per route (a route is split across tiles), best first per park. */
        fun distinct(features: List<Feature>): List<RouteInfo> =
            features.mapNotNull(::from).distinctBy { it.id }.sortedWith(
                compareBy<RouteInfo> { it.park }
                    .thenBy { TYPE_ORDER.indexOf(it.type).let { i -> if (i < 0) 9 else i } }
                    .thenBy { it.id.substringAfterLast('/').toIntOrNull() ?: 0 },
            )

        private fun Feature.getStringOrNull(k: String) =
            if (hasNonNullValueForProperty(k)) properties()?.get(k)?.asString else null

        private fun Feature.getNumberOrNull(k: String) =
            if (hasNonNullValueForProperty(k)) properties()?.get(k)?.asNumber else null
    }
}
