package com.hikeratlas.android.routes

/**
 * A park's name as a card shows it, as the website's parkLabel: shorter,
 * with what kind of park it is above ("Algonquin", "Provincial park"), and
 * the town that tells it from another of its name below ("near Hamilton").
 * Trail areas (ids "trail-...") are trails.
 */
data class ParkLabel(val name: String, val kind: String?, val near: String?)

private val KINDS: List<Pair<Regex, String>> = listOf(
    Regex(" National Park and Preserve$") to "National park and preserve",
    Regex(" National Park and National Historic Site$") to "National park and historic site",
    Regex(" National Park Reserve( of Canada)?$") to "National park reserve",
    Regex(" National Park( of Canada)?$") to "National park",
    Regex(" National Historic(al)? (Park|Site)( of Canada)?$") to "National historic site",
    Regex(" National (Recreation Area|Monument|Seashore|Lakeshore|Forest|Grassland|Preserve)$") to "National \$1",
    Regex(" Provincial (Park|Nature Reserve|Wilderness Area)$") to "Provincial \$1",
    Regex(" State (Park|Forest|Recreation Area|Natural Area|Game Lands)$") to "State \$1",
    Regex(" (Regional|County|Metro|Metropolitan|Municipal) Park$") to "\$1 park",
    Regex(" (Wilderness Area|Wilderness|Conservation Area|Nature Reserve|Nature Preserve|Ecological Reserve|Recreation Area|Wildlife Area|Wildlife Management Area|Preserve)$") to "\$1",
)

fun parkLabel(name: String, id: String? = null): ParkLabel {
    val m = Regex("^(.*) \\((near [^)]+)\\)$").find(name)
    val base = m?.groupValues?.get(1) ?: name
    val near = m?.groupValues?.get(2)
    if (id?.startsWith("trail-") == true) return ParkLabel(base, "Trail", near)
    for ((re, kind) in KINDS) {
        val k = re.find(base) ?: continue
        // Not "Kejimkujik National Park and" for a kind it has a part of.
        if (k.range.first > 0 && !Regex(" (and|&)$").containsMatchIn(base.substring(0, k.range.first))) {
            val label = Regex("\\$(\\d)").replace(kind) { g -> k.groupValues.getOrNull(g.groupValues[1].toInt()) ?: "" }
            return ParkLabel(base.substring(0, k.range.first), label.first() + label.substring(1).lowercase(), near)
        }
    }
    if (base.startsWith("Parc national ")) return ParkLabel(base, "Parc national", near)
    return ParkLabel(base, null, near)
}
