package com.hikeratlas.android.routes

import android.net.Uri
import java.net.URLDecoder
import java.util.Locale

/**
 * What a website link opens in the app. The website's hike pages:
 *   /hikes/<country>[/<state>[/<park>[/<hike>]]], and a day hike from a base
 *   camp, .../<park>/<camp>/<hike>
 * (the website's app/hikes.ts and routes/hikes.$.tsx).
 */
sealed interface Link {
    /** A country or state: the map moves to it. */
    data class Region(val bbox: List<Double>) : Link

    data class Park(val id: String, val name: String, val bbox: List<Double>?) : Link

    /** [title] where the park lists it, shown while its routes load. */
    data class Hike(val park: String, val id: String, val title: String?) : Link

    companion object {
        private val HOSTS = setOf("www.hikeratlas.com", "hikeratlas.com")

        /** A hike page link's parts (lowercase, decoded), or null if it isn't one. */
        fun parts(uri: Uri?): List<String>? {
            uri ?: return null
            if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) !in HOSTS) return null
            return parts(uri.encodedPath ?: return null)
        }

        /** As the website's parsePath: /hikes/canada/alberta -> [canada, alberta]. */
        fun parts(path: String): List<String>? {
            val m = Regex("^/hikes(?:/(.*))?$").matchEntire(path.lowercase(Locale.ROOT)) ?: return null
            return (m.groupValues[1]).split('/').filter { it.isNotEmpty() }
                .map { decode(it).lowercase(Locale.ROOT) }
        }

        // As decodeURIComponent: a + stays a +.
        private fun decode(s: String): String =
            try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { s }

        /**
         * What [parts] names, from the build's regions and (for a hike) its
         * park's routes; null if nothing does. An unknown hike in a known park
         * opens the park, or a close match, as the website does.
         */
        suspend fun resolve(parts: List<String>, regions: Regions, park: suspend (String) -> ParkDoc?): Link? {
            if (parts.isEmpty()) return null
            val country = regions.countries.firstOrNull { it.slug == parts[0] } ?: return null
            if (parts.size == 1) return country.bbox?.let(::Region)
            val state = country.states.firstOrNull { it.slug == parts[1] } ?: return country.bbox?.let(::Region)
            if (parts.size == 2) return state.bbox?.let(::Region)
            val area = state.areas.firstOrNull { it.slug == parts[2] } ?: return state.bbox?.let(::Region)
            val parkLink = Park(area.id, area.name, area.bbox)
            if (parts.size == 3) return parkLink
            val doc = park(area.id) ?: return parkLink
            val slugs = hikeSlugs(doc)
            val id = findHike(slugs, parts.drop(3).joinToString("/")) ?: return parkLink
            val title = itemsFromPark(doc).firstOrNull { it.id == id }?.title
            return Hike(area.id, id, title)
        }

        /**
         * The route whose slug is [slug], else a close one (a renamed hike) at
         * the same depth, as the website's findHike: a slug it's the start
         * of, or one that's the start of it.
         */
        fun findHike(slugs: Map<String, String>, slug: String): String? {
            slugs.entries.firstOrNull { it.value == slug }?.let { return it.key }
            val depth = slug.count { it == '/' }
            val same = slugs.entries.filter { it.value.count { c -> c == '/' } == depth }
            val longer = same.filter { slug.startsWith("${it.value}-") }.maxByOrNull { it.value.length }
            val shorter = same.filter { it.value.startsWith("$slug-") }.minByOrNull { it.value.length }
            return (longer ?: shorter)?.key
        }
    }
}
