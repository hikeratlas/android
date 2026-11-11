package com.hikeratlas.android.track

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A track as GPX 1.1: a <trkseg> per stretch between pauses, each fix a
 * <trkpt> with its time. <ele> is GPS's altitude above sea level where
 * Android gave one (14+), else above the WGS84 ellipsoid, as GPS reports it.
 * Everything else recorded (the ellipsoid altitude, accuracies, speed) is
 * kept in Hiker Atlas extensions, so nothing is lost.
 */
object Gpx {
    const val NS = "https://www.hikeratlas.com/gpx/1"
    const val MIME = "application/gpx+xml"

    fun write(name: String, started: Long, points: List<Pair<Int, Fix>>): String {
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun t(ms: Long) = time.format(Date(ms))
        // Plain decimals: GPX has no 5.0E-5 (a longitude near the prime meridian).
        fun n(x: Number) = java.math.BigDecimal(x.toString()).stripTrailingZeros().toPlainString()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Hiker Atlas\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        sb.append(" xmlns:ha=\"$NS\"")
        sb.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        sb.append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata>\n    <name>").append(escape(name)).append("</name>\n    <time>").append(t(started)).append("</time>\n  </metadata>\n")
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        for ((_, seg) in points.groupBy({ it.first }, { it.second }).toSortedMap()) {
            sb.append("    <trkseg>\n")
            for (f in seg) {
                sb.append("      <trkpt lat=\"").append(n(f.lat)).append("\" lon=\"").append(n(f.lon)).append("\">")
                (f.mslAltitude ?: f.altitude)?.let { sb.append("<ele>").append(n(it)).append("</ele>") }
                sb.append("<time>").append(t(f.t)).append("</time>")
                val ext = buildList {
                    f.altitude?.let { add("<ha:ellipsoid_ele>${n(it)}</ha:ellipsoid_ele>") }
                    f.mslAltitude?.let { add("<ha:msl_ele>${n(it)}</ha:msl_ele>") }
                    f.accuracy?.let { add("<ha:hacc>${n(it)}</ha:hacc>") }
                    f.verticalAccuracy?.let { add("<ha:vacc>${n(it)}</ha:vacc>") }
                    f.speed?.let { add("<ha:speed>${n(it)}</ha:speed>") }
                }
                if (ext.isNotEmpty()) sb.append("<extensions>").append(ext.joinToString("")).append("</extensions>")
                sb.append("</trkpt>\n")
            }
            sb.append("    </trkseg>\n")
        }
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** A file name from a track's name: "Raging River State Forest · 14 Nov" -> "Raging River State Forest - 14 Nov.gpx". */
    fun fileName(name: String): String {
        val safe = name.replace("·", "-").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").replace(Regex("\\s+"), " ").trim()
        return (safe.ifEmpty { "Track" }).take(80) + ".gpx"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** Android's share sheet for an exported GPX file, readable by the app picked. */
fun gpxShareIntent(context: android.content.Context, file: java.io.File, title: String): android.content.Intent {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType(Gpx.MIME)
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // So the grant reaches the app picked through the chooser.
    send.clipData = android.content.ClipData.newRawUri(title, uri)
    return android.content.Intent.createChooser(send, "Share track")
}
