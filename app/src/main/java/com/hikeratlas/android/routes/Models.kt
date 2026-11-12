package com.hikeratlas.android.routes

import kotlinx.serialization.Serializable

// A park's routes document, from hikeratlas/routes: the parts the app reads.
// Mirrors the website's app/hikes.ts.

typealias LngLat = List<Double>

/** A route's line and what's along it. */
@Serializable
data class Route(
    val km: Double,
    val one_way_km: Double? = null,
    val gain: Double,
    val loss: Double = 0.0,
    val max_ele: Double = 0.0,
    /** Hardest SAC grade along it: 4 is a scramble. 0: ungraded. */
    val max_sac: Int? = null,
    /** [landcover, share of the route] */
    val landcover: List<List<kotlinx.serialization.json.JsonPrimitive>> = emptyList(),
    /** [kind, names]: the passes, peaks, lakes... it goes by. */
    val passes: List<List<kotlinx.serialization.json.JsonElement>> = emptyList(),
    val stopovers: List<Stopover> = emptyList(),
    val via: List<String> = emptyList(),
    val line: List<LngLat> = emptyList(),
) {
    val scramble: Boolean get() = (max_sac ?: 0) >= 4

    /** Land cover shares, e.g. ("forest", 0.88). */
    val terrain: List<Pair<String, Double>>
        get() = landcover.mapNotNull { e ->
            val k = e.getOrNull(0)?.content ?: return@mapNotNull null
            val v = e.getOrNull(1)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            k to v
        }

    /** The names of what it passes, by kind. */
    val passing: List<List<String>>
        get() = passes.mapNotNull { e ->
            (e.getOrNull(1) as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }
}

@Serializable
data class Stopover(val name: String, val km: Double, val at: LngLat)

/** A day hike, a day hike from a base camp, or a stop. */
@Serializable
data class Hike(
    val from: String,
    val dest: String,
    val kind: String,
    val name: String? = null,
    val why: List<List<kotlinx.serialization.json.JsonPrimitive>>? = null,
    val start: LngLat? = null,
    val route: Route,
)

@Serializable
data class BaseCamp(
    val camp: String,
    val from: String,
    val approach: Route,
    val hikes: List<Hike> = emptyList(),
)

@Serializable
data class PointToPoint(
    val from: String,
    val to: String,
    val kind: String,
    val name: String? = null,
    val route: Route,
)

@Serializable
data class Park(val id: String, val name: String, val website: String? = null, val operator: String? = null)

/** A multi-day trip of a smaller park inside a park, which the park lists too. */
@Serializable
data class SeeAlso(
    val id: String,
    val park: String,
    val park_name: String,
    val name: String? = null,
    val from: String,
    val to: String,
    val kind: String,
    val km: Double,
    val gain: Double,
)

@Serializable
data class ParkDoc(
    val park: Park? = null,
    val day_hikes: List<Hike> = emptyList(),
    val stops: List<Hike> = emptyList(),
    val base_camps: List<BaseCamp> = emptyList(),
    val point_to_points: List<PointToPoint> = emptyList(),
    val see_also: List<SeeAlso> = emptyList(),
)

/** A build's index of the parks' routes in its bundle (index.json). */
@Serializable
data class BundleIndex(
    val bundle: String,
    /** park osm id -> [offset, length] of its gzipped JSON in the bundle. */
    val parks: Map<String, List<Long>>,
    /** park id -> "<country>/<state>/<park>" */
    val paths: Map<String, String> = emptyMap(),
)

/** Every country, state and park with curated hikes (regions.json). */
@Serializable
data class Regions(val countries: List<Country> = emptyList()) {
    // Slugs as in the website's URLs; bbox is [west, south, east, north].
    @Serializable
    data class Country(val name: String, val slug: String = "", val bbox: List<Double>? = null, val states: List<State> = emptyList())

    @Serializable
    data class State(val name: String, val slug: String = "", val bbox: List<Double>? = null, val areas: List<Area> = emptyList())

    @Serializable
    data class Area(
        val id: String,
        val name: String,
        val slug: String = "",
        val bbox: List<Double>? = null,
        /** Every park id the area covers (its main [id] among them): some areas join several OSM parks. */
        val ids: List<String> = emptyList(),
    ) {
        fun covers(parkId: String) = id == parkId || parkId in ids
    }
}
