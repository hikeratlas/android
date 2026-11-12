package com.hikeratlas.android.search

import com.hikeratlas.android.offline.OfflineAreas
import com.hikeratlas.android.offline.OfflineStore
import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.Regions
import com.hikeratlas.android.routes.itemsFromPark
import com.hikeratlas.android.routes.lookup
import kotlinx.coroutines.CancellationException
import java.text.Normalizer
import java.util.Locale
import kotlin.math.cos

/**
 * Search with no network: the parks and hikes downloaded for offline use,
 * from the routes kept with them, as search results of the website's kinds,
 * so a pick opens the same way.
 */
class OfflineSearch(
    private val regions: suspend () -> Regions?,
    /** The downloaded parks and hikes (those ready). */
    private val saved: suspend () -> List<OfflineAreas.Target>,
    /** A park's routes, from the phone. */
    private val park: suspend (String) -> ParkDoc?,
) {
    suspend fun search(query: String, nearLat: Double?, nearLon: Double?): List<SearchItem> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val areas = try {
            regions()?.countries.orEmpty().flatMap { c -> c.states.flatMap { s -> s.areas.map { it to s.name } } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        fun areaOf(parkId: String) = areas.firstOrNull { it.first.covers(parkId) }

        val items = LinkedHashMap<String, SearchItem>()
        for (t in saved()) {
            val parkId = if (t.kind == OfflineStore.HIKE) t.ref.substringBefore('/') else t.ref
            val area = areaOf(parkId)
            if (t.kind == OfflineStore.PARK) {
                val (a, state) = area ?: (null to null)
                val box = a?.bbox
                val item = SearchItem(
                    kind = "park", name = a?.name ?: t.name, osm_id = t.ref,
                    lat = box?.let { (it[1] + it[3]) / 2 } ?: 0.0, lon = box?.let { (it[0] + it[2]) / 2 } ?: 0.0,
                    min_lon = box?.get(0), min_lat = box?.get(1), max_lon = box?.get(2), max_lat = box?.get(3),
                    state = state,
                )
                if (box != null) items[item.key] = item
            }
            val doc = try {
                park(parkId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            val parkName = doc.park?.name ?: area?.first?.name
            // A park's own hikes (not another park's it lists); a hike's area, just it.
            for (r in itemsFromPark(doc).filter { it.park == parkId && (t.kind == OfflineStore.PARK || it.id == t.ref) }) {
                val route = lookup(doc, r.id)?.route ?: continue
                val line = route.line.takeIf { it.isNotEmpty() } ?: continue
                items["hike/${r.id}"] = SearchItem(
                    kind = "hike", name = r.title, osm_id = r.id, lat = line[0][1], lon = line[0][0],
                    min_lon = line.minOf { it[0] }, min_lat = line.minOf { it[1] },
                    max_lon = line.maxOf { it[0] }, max_lat = line.maxOf { it[1] },
                    park = parkName?.let(::shortPark), state = area?.second,
                    km = route.km, gain = route.gain, hike = r.id.split('/').getOrNull(1),
                )
            }
        }
        return items.values.filter { matches(it.name, tokens) }
            .sortedWith(compareBy({ !fold(it.name).startsWith(tokens[0]) }, { distance(it, nearLat, nearLon) }, { it.name }))
            .take(MAX)
    }

    companion object {
        const val MAX = 30

        /** The query's words, folded (no accents or case). */
        fun tokens(query: String) = fold(query).split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

        /** Every query word begins one of the name's words, as the website's search matches. */
        fun matches(name: String, tokens: List<String>): Boolean {
            val words = fold(name).split(Regex("[^\\p{L}\\p{N}'’]+")).filter { it.isNotEmpty() }
            return tokens.all { t -> words.any { it.startsWith(t) } }
        }

        private fun shortPark(name: String) = com.hikeratlas.android.routes.RoutesRepository.shortPark(name)

        /** Squared, roughly: only for ordering. */
        private fun distance(i: SearchItem, lat: Double?, lon: Double?): Double {
            if (lat == null || lon == null) return 0.0
            val dx = (i.lon - lon) * cos(Math.toRadians(lat))
            val dy = i.lat - lat
            return dx * dx + dy * dy
        }
    }
}

/** Without accents or case: "Élan" matches "elan". */
internal fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
