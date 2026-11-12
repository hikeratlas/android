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
    /** Missing in builds from before it. */
    val min_ele: Double? = null,
    /** Hardest SAC grade along it: 4 is a scramble. 0: ungraded. */
    val max_sac: Int? = null,
    /** [landcover, share of the route] */
    val landcover: List<List<kotlinx.serialization.json.JsonPrimitive>> = emptyList(),
    /** [kind, names]: the passes, peaks, lakes... it goes by. */
    val passes: List<List<kotlinx.serialization.json.JsonElement>> = emptyList(),
    /**
     * [kind, name, km, [lon, lat]]: what it passes, in order (routes builds
     * after hikeratlas/routes#170); empty before.
     */
    val along: List<List<kotlinx.serialization.json.JsonElement>> = emptyList(),
    val stopovers: List<Stopover> = emptyList(),
    val via: List<String> = emptyList(),
    val line: List<LngLat> = emptyList(),
) {
    /** What it passes, in order, where the build says. */
    val alongs: List<Along>
        get() = along.mapNotNull { e ->
            val kind = (e.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val name = (e.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val km = (e.getOrNull(2) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val at = (e.getOrNull(3) as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
                ?.takeIf { it.size == 2 } ?: return@mapNotNull null
            Along(kind, name, km, at)
        }

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

/** Something a route passes: a lake, peak, waterfall... [km] from its start, at [at] (lon, lat). */
data class Along(val kind: String, val name: String, val km: Double, val at: LngLat)

@Serializable
data class Stopover(val name: String, val km: Double, val at: LngLat)

/** A day hike, a day hike from a base camp, or a stop. */
@Serializable
data class Hike(
    val from: String,
    val dest: String,
    /** loop, out-and-back, or one-way (walked one way only, e.g. the Grouse Grind: routes#165). */
    val kind: String,
    val name: String? = null,
    /** How a one-way hike comes back: a lift's name ("Blue Grouse Gondola"). */
    val return_by: String? = null,
    /** Stays the same across routes builds, unlike the id (routes#153); missing before it. */
    val key: String? = null,
    /** The score's parts, [name, points]: the park's hikes are listed best first by their total. */
    val why: List<List<kotlinx.serialization.json.JsonPrimitive>>? = null,
    val score: Double? = null,
    /** What the destination is: peak, lake, waterfall, viewpoint, trail... */
    val dest_kind: String? = null,
    val start: LngLat? = null,
    val route: Route,
)

@Serializable
data class BaseCamp(
    val camp: String,
    val from: String,
    val key: String? = null,
    val approach: Route,
    val hikes: List<Hike> = emptyList(),
)

@Serializable
data class PointToPoint(
    val from: String,
    val to: String,
    val kind: String,
    val name: String? = null,
    val key: String? = null,
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
    /** Trip reports and other pages about the routes (hikeratlas/cc-crawler); missing in older builds. */
    val reports: ReportsIndex? = null,
    /** Each state's park cards, "<country>/<state>" -> [offset, length] in the bundle; missing in older builds. */
    val cards: Map<String, List<Long>> = emptyMap(),
    /** The homepage's featured cards as pictures: a folder of this build's routes ("<home>/<park slug>-park.jpg", "-hike.jpg"); missing in builds without them. */
    val home: String? = null,
)

/** A park's card in its state's list (basemap's hike-paths.py): its best hike, what its hikes pass, its outline (SVG, 100×100). */
@Serializable
data class StateCard(val top: String? = null, val tags: List<String> = emptyList(), val outline: String? = null)

/** The reports bundle: each park's pages gzipped, at [offset, length]. */
@Serializable
data class ReportsIndex(val bundle: String, val parks: Map<String, List<Long>> = emptyMap())

/**
 * A page on another site about a route: a trip report or trail guide that
 * follows it, or a page about its destination.
 */
@Serializable
data class ReportPage(
    val url: String,
    val title: String = "",
    val site: String = "",
    /** From someone's own site (a blog), not an organisation's. */
    val personal: Boolean = false,
    /** trip_report or trail_guide. */
    val type: String? = null,
    /** YYYY, YYYY-MM or YYYY-MM-DD. */
    val date: String? = null,
    /** A photo from the page: a path next to the bundle, made a URL when read. */
    val thumb: String? = null,
)

/** A route's pages: [reports] follow the route; [about] are about its destination. */
@Serializable
data class RouteReports(val reports: List<ReportPage> = emptyList(), val about: List<ReportPage> = emptyList())

/** Every country, state and park with curated hikes (regions.json). */
@Serializable
data class Regions(val countries: List<Country> = emptyList()) {
    // Slugs as in the website's URLs; bbox is [west, south, east, north].
    @Serializable
    data class Country(
        val name: String, val slug: String = "", val bbox: List<Double>? = null, val states: List<State> = emptyList(),
        val hikes: Int = 0, val parks: Int = 0,
    )

    @Serializable
    data class State(
        val name: String, val slug: String = "", val bbox: List<Double>? = null, val areas: List<Area> = emptyList(),
        val hikes: Int = 0, val parks: Int = 0,
    )

    @Serializable
    data class Area(
        val id: String,
        val name: String,
        val slug: String = "",
        val bbox: List<Double>? = null,
        /** Every park id the area covers (its main [id] among them): some areas join several OSM parks. */
        val ids: List<String> = emptyList(),
        /** Its curated hikes. */
        val hikes: Int = 0,
    ) {
        fun covers(parkId: String) = id == parkId || parkId in ids
    }
}
