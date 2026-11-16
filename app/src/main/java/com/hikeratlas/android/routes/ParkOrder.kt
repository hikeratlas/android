package com.hikeratlas.android.routes

import java.text.Collator
import java.util.Locale

/**
 * How a state's parks are ordered (#92, as the website's sortParks,
 * website#168): by fame (QRank), then hikes; by hikes, then name; or by name
 * as the card shows it ("Algonquin", not "Algonquin Provincial Park").
 */
enum class ParkOrder(val label: String) {
    POPULAR("Popular"), HIKES("Most hikes"), NAME("A–Z");

    companion object {
        fun of(name: String?): ParkOrder? = entries.firstOrNull { it.name == name }
    }
}

/** Whether a state's parks have ranks to order by fame: a build from before them has none. */
fun hasRanks(areas: List<Regions.Area>) = areas.any { (it.rank ?: 0L) > 0L }

/** [areas] in [order]; a new list, [areas] left as it was. Ties fall back to hikes, then name. */
fun sortParks(areas: List<Regions.Area>, order: ParkOrder): List<Regions.Area> {
    // Case and accents aside, as the website's localeCompare(…, { sensitivity: 'base' }).
    val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
    val byName = Comparator<Regions.Area> { a, b -> collator.compare(parkLabel(a.name, a.id).name, parkLabel(b.name, b.id).name) }
    val byHikes = compareByDescending<Regions.Area> { it.hikes }
    val byRank = compareByDescending<Regions.Area> { it.rank ?: 0L }
    val cmp = when (order) {
        ParkOrder.NAME -> byName
        ParkOrder.HIKES -> byHikes.then(byName)
        ParkOrder.POPULAR -> byRank.then(byHikes).then(byName)
    }
    return areas.sortedWith(cmp)
}
