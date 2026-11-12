package com.hikeratlas.android.routes

import com.hikeratlas.android.elevation.ElevationProfiler
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The featured park and hike, as the website's homepage (app/featured.ts):
 * from a hand-picked list of showcase parks, the one nearest the phone (where
 * the map was last left) if one is within [NEAR_KM], else the day's; and that
 * park's best approachable hike.
 */
object Featured {
    /** Well-known parks with plenty of hikes, across both countries: regions.json paths, and short names for the switcher. */
    val SHOWCASE = listOf(
        "canada/alberta/banff-national-park" to "Banff",
        "canada/alberta/jasper-national-park" to "Jasper",
        "canada/british-columbia/yoho-national-park" to "Yoho",
        "canada/british-columbia/kootenay-national-park" to "Kootenay",
        "canada/british-columbia/garibaldi-provincial-park" to "Garibaldi",
        "canada/british-columbia/golden-ears-provincial-park" to "Golden Ears",
        "canada/british-columbia/e-c-manning-provincial-park" to "Manning",
        "canada/british-columbia/strathcona-provincial-park" to "Strathcona",
        "canada/british-columbia/mount-robson-provincial-park" to "Mount Robson",
        "canada/ontario/algonquin-provincial-park" to "Algonquin",
        "canada/ontario/killarney-provincial-park" to "Killarney",
        "canada/quebec/parc-national-du-mont-tremblant" to "Mont-Tremblant",
        "canada/new-brunswick/fundy-national-park" to "Fundy",
        "canada/nova-scotia/cape-breton-highlands-national-park" to "Cape Breton",
        "canada/newfoundland-and-labrador/gros-morne-national-park" to "Gros Morne",
        "united-states/washington/mount-rainier-national-park" to "Mount Rainier",
        "united-states/washington/olympic-national-park" to "Olympic",
        "united-states/california/yosemite-national-park" to "Yosemite",
        "united-states/california/sequoia-national-park" to "Sequoia",
        "united-states/california/joshua-tree-national-park" to "Joshua Tree",
        "united-states/utah/zion-national-park" to "Zion",
        "united-states/utah/bryce-canyon-national-park" to "Bryce Canyon",
        "united-states/arizona/grand-canyon-national-park" to "Grand Canyon",
        "united-states/wyoming/grand-teton-national-park" to "Grand Teton",
        "united-states/montana/waterton-glacier-international-peace-park" to "Waterton-Glacier",
        "united-states/colorado/rocky-mountain-national-park" to "Rocky Mountain",
        "united-states/north-carolina/great-smoky-mountains-national-park" to "Great Smokies",
        "united-states/virginia/shenandoah-national-park" to "Shenandoah",
        "united-states/new-hampshire/white-mountain-national-forest" to "White Mountains",
        "united-states/new-york/adirondack-park" to "Adirondacks",
        "united-states/maine/acadia-national-park" to "Acadia",
    )

    /** A showcase park this close (km, centre to centre) counts as near. */
    const val NEAR_KM = 500.0

    /** The featured hike: the best easy or moderate one, if it scores this share of the park's best. */
    const val APPROACHABLE_WITHIN = 0.7

    /** A showcase park found in the build's regions. */
    data class Showcase(val path: String, val short: String, val state: String, val area: Regions.Area)

    /** The showcase parks the build has, alphabetical by short name, as the switcher lists them. */
    fun parks(regions: Regions): List<Showcase> = SHOWCASE.sortedBy { it.second }.mapNotNull { (path, short) ->
        val (c, s, a) = path.split('/')
        val state = regions.countries.firstOrNull { it.slug == c }?.states?.firstOrNull { it.slug == s } ?: return@mapNotNull null
        val area = state.areas.firstOrNull { it.slug == a }?.takeIf { it.bbox != null } ?: return@mapNotNull null
        Showcase(path, short, state.name, area)
    }

    private fun mid(b: List<Double>) = listOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)

    /**
     * Which park: [showcase] if asked for (an index into [parks], wrapping),
     * else the nearest to [at] ([lon, lat]) within [NEAR_KM], else the day's.
     * Its index, and whether it was near.
     */
    fun pick(parks: List<Showcase>, at: Pair<Double, Double>?, day: Int, showcase: Int? = null): Pair<Int, Boolean>? {
        if (parks.isEmpty()) return null
        fun wrap(i: Int) = ((i % parks.size) + parks.size) % parks.size
        if (showcase != null) return wrap(showcase) to false
        val nearest = at?.let { (lon, lat) ->
            parks.indices.minByOrNull { ElevationProfiler.haversine(listOf(lon, lat), mid(parks[it].area.bbox!!)) }
                ?.let { it to ElevationProfiler.haversine(listOf(lon, lat), mid(parks[it].area.bbox!!)) / 1000 }
        }
        return if (nearest != null && nearest.second <= NEAR_KM) nearest.first to true else wrap(day) to false
    }

    /** The best approachable hike (easy or moderate) among the park's good ones, else its best. */
    fun hike(hikes: List<ParkHike>): ParkHike? {
        val byScore = hikes.sortedByDescending { it.score }
        val best = byScore.firstOrNull() ?: return null
        return byScore.firstOrNull { (it.diff == Difficulty.Easy || it.diff == Difficulty.Moderate) && it.score >= APPROACHABLE_WITHIN * best.score } ?: best
    }

    /** What the featured cards show. */
    data class Card(
        val parks: List<Showcase>,
        val index: Int,
        val near: Boolean,
        val parkId: String,
        val parkName: String,
        val doc: ParkDoc,
        /** "18 day hikes", "6 base camps"... */
        val counts: List<String>,
        val picks: List<Pair<String, ParkHike>>,
        val hike: ParkHike,
        /** The build's pictures of the cards, where it has them. */
        val parkImage: String?,
        val hikeImage: String?,
    )

    suspend fun load(repo: RoutesRepository, at: Pair<Double, Double>?, day: Int, showcase: Int? = null): Card? = coroutineScope {
        val regions = async { repo.regions() }
        val index = async { repo.index() }
        val parks = parks(regions.await())
        val (i, near) = pick(parks, at, day, showcase) ?: return@coroutineScope null
        val p = parks[i]
        val doc = repo.park(p.area.id) ?: return@coroutineScope null
        if (doc.day_hikes.isEmpty()) return@coroutineScope null
        val reports = try { repo.parkReports(doc.park?.id ?: p.area.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyMap() }
        val hikes = parkHikes(doc) { key -> reports[key]?.reports?.size ?: 0 }
        val h = hike(hikes) ?: return@coroutineScope null
        fun n(k: Int, one: String) = "$k ${if (k == 1) one else "${one}s"}"
        val counts = listOf(
            doc.day_hikes.size to "day hike", doc.base_camps.size to "base camp",
            doc.point_to_points.size + doc.see_also.size to "multi-day trip", doc.stops.size to "quick stop",
        ).filter { it.first > 0 }.map { n(it.first, it.second) }
        val home = index.await().home?.takeIf { Regex("^[\\w.-]+$").matches(it) }?.let { "${repo.root}/$it/${p.path.substringAfterLast('/')}" }
        Card(
            parks, i, near, p.area.id, doc.park?.name ?: p.area.name, doc, counts, picks(hikes), h,
            home?.let { "$it-park.jpg" }, home?.let { "$it-hike.jpg" },
        )
    }
}
