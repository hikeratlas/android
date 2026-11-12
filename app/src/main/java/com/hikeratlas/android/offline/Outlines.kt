package com.hikeratlas.android.offline

import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.Regions
import com.hikeratlas.android.routes.lookup

/**
 * Where a downloaded area's map is, as a box [west, south, east, north],
 * for drawing its outline offline: a park's box (what its download
 * covers), or the box around a hike [AreaPlan.HIKE_MARGIN_M] out.
 */
object Outlines {
    fun of(t: OfflineAreas.Target, regions: Regions?, doc: ParkDoc?): List<Double>? = when (t.kind) {
        OfflineStore.PARK -> regions?.countries.orEmpty().flatMap { it.states }.flatMap { it.areas }.firstOrNull { it.covers(t.ref) }?.bbox
        OfflineStore.HIKE -> doc?.let { lookup(it, t.ref) }?.route?.line?.takeIf { it.size >= 2 }
            ?.let { TileCover.boundsOf(listOf(it), AreaPlan.HIKE_MARGIN_M) }
        else -> null
    }
}
