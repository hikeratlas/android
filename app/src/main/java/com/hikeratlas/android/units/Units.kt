package com.hikeratlas.android.units

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.roundToInt

enum class DistanceUnit(val symbol: String) { KM("km"), MI("mi") }
enum class ElevationUnit(val symbol: String) { M("m"), FT("ft") }

/**
 * The units distances and elevations show in, as the website's
 * app/units.ts: km or mi, m or ft. Data stays metric everywhere; these
 * only format it for display, rounded as the website rounds, so a hike
 * reads the same in the app and on the site.
 */
data class Units(val distance: DistanceUnit = DistanceUnit.KM, val elevation: ElevationUnit = ElevationUnit.M) {
    val kmPerUnit get() = if (distance == DistanceUnit.MI) KM_PER_MI else 1.0
    val metresPerUnit get() = if (elevation == ElevationUnit.FT) M_PER_FT else 1.0

    /** A distance's number alone, in these units (for chart ticks, badges). */
    fun distanceValue(km: Double) = km / kmPerUnit
    fun elevationValue(m: Double) = m / metresPerUnit

    /** A distance given in km: "12.4 km" or "7.7 mi". [digits]: decimals (by default one below 10, else none). */
    fun distance(km: Double, digits: Int? = null): String {
        val v = distanceValue(km)
        return "${toFixed(v, digits ?: if (v < 10) 1 else 0)} ${distance.symbol}"
    }

    /** An elevation or climb given in metres, its number alone: "1,150" or "3,773". */
    fun elevationNumber(m: Double): String = String.format(Locale.US, "%,d", elevationValue(m).roundToInt())

    /** An elevation or climb given in metres: "1,150 m" or "3,773 ft". */
    fun elevation(m: Double) = "${elevationNumber(m)} ${elevation.symbol}"

    /** A range of heights: "1,150–2,300 m". */
    fun elevationRange(lo: Double, hi: Double) = "${elevationNumber(lo)}–${elevationNumber(hi)} ${elevation.symbol}"

    /** Where along a route, given in km: "km 3.4", "mi 2.1", as the website's hike sheet. */
    fun at(km: Double) = "${distance.symbol} ${toFixed(distanceValue(km), 1)}"

    /** An area given in m², for lakes: "1.1 km²" or "0.4 mi²". */
    fun area(m2: Double): String {
        val v = m2 / (kmPerUnit * kmPerUnit * 1e6)
        return "${toFixed(v, if (v < 10) 1 else 0)} ${distance.symbol}²"
    }

    /** As the profile record's `units`. */
    fun toJson() = JsonObject(mapOf("distance" to JsonPrimitive(distance.symbol), "elevation" to JsonPrimitive(elevation.symbol)))

    companion object {
        val METRIC = Units()
        private const val KM_PER_MI = 1.609344
        private const val M_PER_FT = 0.3048

        /** Units from a profile record's `units` (other values, or none, are metric), as the website's unitsOf. */
        fun of(json: JsonElement?): Units {
            val o = json as? JsonObject
            fun field(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return Units(
                if (field("distance") == "mi") DistanceUnit.MI else DistanceUnit.KM,
                if (field("elevation") == "ft") ElevationUnit.FT else ElevationUnit.M,
            )
        }

        /**
         * The units the app shows now: the profile's when signed in, else
         * the phone's setting ([UnitsSetting] keeps it). Compose state, so
         * whatever shows a distance follows a change at once; text made
         * outside composition (a notification, say) reads it when it's made.
         */
        var current: Units by mutableStateOf(METRIC)
    }
}

/**
 * JavaScript's Number.toFixed: rounds the double's exact value, halves
 * away from zero. (String.format rounds the shortest decimal for the
 * double instead, so 0.15, really 0.1499…, would be "0.2" there and "0.1"
 * on the website.)
 */
internal fun toFixed(x: Double, digits: Int): String =
    BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).toPlainString()

// The formatters, in the units shown now.
fun dist(km: Double, digits: Int? = null) = Units.current.distance(km, digits)
fun elev(m: Double) = Units.current.elevation(m)
fun elevRange(lo: Double, hi: Double) = Units.current.elevationRange(lo, hi)
fun distanceAt(km: Double) = Units.current.at(km)
