package com.hikeratlas.android.map

import android.graphics.PointF
import android.graphics.RectF
import com.google.gson.JsonElement
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import androidx.core.graphics.toColorInt
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** What the app does to the map: the selected route, taps, camera moves. */
class MapController(val map: MapLibreMap, private val density: Float) {
    private var style: Style? = null

    /** Adds the selection layers once the basemap style has loaded. */
    fun onStyleLoaded(s: Style) {
        style = s
        // Downloaded areas' outlines, offline: under the rest.
        s.addSource(GeoJsonSource(OFFLINE_AREAS))
        s.addLayer(
            LineLayer(OFFLINE_AREAS, OFFLINE_AREAS).withProperties(
                lineColor(OFFLINE_COLOR), lineWidth(2f), lineDasharray(arrayOf(3f, 2f)), lineOpacity(0.85f),
            ),
        )
        // Tracks: a saved one being looked at, and the one being recorded,
        // under the selected route.
        for (id in listOf(VIEWED, TRACK)) {
            s.addSource(GeoJsonSource(id))
            s.addLayer(
                LineLayer("$id-casing", id).withProperties(
                    lineColor("#ffffff"), lineWidth(7f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            s.addLayer(
                LineLayer(id, id).withProperties(
                    lineColor(TRACK_COLOR), lineWidth(4f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }
        s.addSource(GeoJsonSource(SEL))
        s.addSource(GeoJsonSource(HOVER))
        s.addLayer(
            LineLayer("$SEL-casing", SEL).withProperties(
                lineColor("#ffffff"), lineWidth(7f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            LineLayer(SEL, SEL).withProperties(
                lineColor(ROUTE_COLOR), lineWidth(4f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            CircleLayer(HOVER, HOVER).withProperties(
                circleRadius(6f), circleColor("#0b0b0b"), circleStrokeColor("#ffffff"), circleStrokeWidth(2.5f),
            ),
        )
        addPlannerLayers(s)
    }

    /**
     * The route planner's line and points, as the website draws them: the
     * start green, the end red, the rest the route's colour, with a wide
     * invisible ring under each to take a finger's drag; taps still being
     * routed hollow, joined to the route by a dashed line.
     */
    private fun addPlannerLayers(s: Style) {
        for (id in listOf(PLAN_LINE, PLAN_POINTS, PLAN_PENDING)) s.addSource(GeoJsonSource(id))
        val round = arrayOf(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))
        s.addLayer(LineLayer("$PLAN_LINE-casing", PLAN_LINE).withProperties(*round, lineColor("#ffffff"), lineWidth(7f)))
        s.addLayer(LineLayer(PLAN_LINE, PLAN_LINE).withProperties(*round, lineColor(ROUTE_COLOR), lineWidth(4f)))
        s.addLayer(
            LineLayer("$PLAN_PENDING-line", PLAN_PENDING)
                .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
                .withProperties(lineColor(ROUTE_COLOR), lineWidth(2f), lineDasharray(arrayOf(2f, 2f)), lineOpacity(0.8f)),
        )
        s.addLayer(
            CircleLayer("$PLAN_PENDING-points", PLAN_PENDING)
                .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
                .withProperties(circleRadius(6f), circleColor("#ffffff"), circleStrokeColor(ROUTE_COLOR), circleStrokeWidth(2.5f)),
        )
        s.addLayer(CircleLayer(PLAN_HIT, PLAN_POINTS).withProperties(circleRadius(22f), circleColor("#000000"), circleOpacity(0f)))
        val role = Expression.get("role")
        s.addLayer(
            CircleLayer(PLAN_POINTS, PLAN_POINTS).withProperties(
                circleRadius(Expression.match(role, Expression.literal(8f), Expression.stop("via", 6f))),
                circleColor(
                    Expression.match(
                        role, Expression.color(ROUTE_COLOR.toColorInt()),
                        Expression.stop("start", Expression.color(START_COLOR.toColorInt())),
                        Expression.stop("end", Expression.color(END_COLOR.toColorInt())),
                    ),
                ),
                circleStrokeColor("#ffffff"), circleStrokeWidth(2.5f),
            ),
        )
        s.addLayer(
            SymbolLayer("$PLAN_POINTS-labels", PLAN_POINTS)
                .withFilter(Expression.neq(role, Expression.literal("via")))
                .withProperties(
                    textField(Expression.get("label")), textFont(arrayOf(LABEL_FONT)), textSize(13f),
                    textOffset(arrayOf(0f, 1.3f)), textAnchor(Property.TEXT_ANCHOR_TOP), textAllowOverlap(true),
                    textColor(
                        Expression.match(
                            role, Expression.color(START_COLOR.toColorInt()),
                            Expression.stop("end", Expression.color(END_COLOR.toColorInt())),
                        ),
                    ),
                    textHaloColor("#ffffff"), textHaloWidth(2f),
                ),
        )
    }

    /** Draws the planned route: its points (start, via, end), its line, and taps still being routed. */
    fun showPlan(points: List<List<Double>>, line: List<List<Double>>, pending: List<List<Double>>) {
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(PLAN_LINE)?.let { src ->
            if (line.size < 2) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            else src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it[0], it[1]) })))
        }
        val loop = points.size > 2 && com.hikeratlas.android.elevation.ElevationProfiler.haversine(points.first(), points.last()) <=
            com.hikeratlas.android.routing.Planner.SAME_END_M
        val features = points.mapIndexed { i, p ->
            val role = when {
                i == 0 -> "start"
                i == points.size - 1 -> if (loop) "via" else "end"
                else -> "via"
            }
            val label = if (i == 0) (if (loop) "Start · End" else "Start") else if (role == "end") "End" else ""
            Feature.fromGeometry(Point.fromLngLat(p[0], p[1])).apply {
                addNumberProperty("i", i)
                addStringProperty("role", role)
                addStringProperty("label", label)
            }
        // The start drawn last, so on a loop it sits on top of the end.
        }.sortedBy { if (it.getStringProperty("role") == "start") 1 else 0 }
        s.getSourceAs<GeoJsonSource>(PLAN_POINTS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        val from = points.lastOrNull()?.let { listOf(it) }.orEmpty()
        val chain = from + pending
        val pendingFeatures = buildList {
            if (pending.isNotEmpty() && chain.size > 1) add(Feature.fromGeometry(LineString.fromLngLats(chain.map { Point.fromLngLat(it[0], it[1]) })))
            pending.forEach { add(Feature.fromGeometry(Point.fromLngLat(it[0], it[1]))) }
        }
        s.getSourceAs<GeoJsonSource>(PLAN_PENDING)?.setGeoJson(FeatureCollection.fromFeatures(pendingFeatures))
    }

    /** Outlines the downloaded areas, boxes [west, south, east, north] (none: clears them). */
    fun showOfflineAreas(boxes: List<List<Double>>) {
        val src = style?.getSourceAs<GeoJsonSource>(OFFLINE_AREAS) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(boxes.map { b -> Feature.fromGeometry(LineString.fromLngLats(boxRing(b))) }))
    }

    /** Draws the track being recorded: a line per stretch between pauses (none: clears it). */
    fun showTrack(segments: List<List<List<Double>>>) = drawTrack(TRACK, segments)

    /** Draws a saved track being looked at (none: clears it). */
    fun showViewedTrack(segments: List<List<List<Double>>>) = drawTrack(VIEWED, segments)

    private fun drawTrack(source: String, segments: List<List<List<Double>>>) {
        val src = style?.getSourceAs<GeoJsonSource>(source) ?: return
        val lines = segments.filter { it.size >= 2 }.map { seg ->
            Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it[0], it[1]) }))
        }
        src.setGeoJson(FeatureCollection.fromFeatures(lines))
    }

    /** The planned point under a touch, by index, if any. */
    fun planPointAt(p: PointF): Int? {
        if (style?.getLayer(PLAN_HIT) == null) return null
        return map.queryRenderedFeatures(p, PLAN_HIT).firstOrNull()?.getNumberProperty("i")?.toInt()
    }

    fun showRoute(line: List<List<Double>>?) {
        val src = style?.getSourceAs<GeoJsonSource>(SEL) ?: return
        if (line == null || line.size < 2) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it[0], it[1]) })))
        }
    }

    /** Draws a trail's lines (all its ways) as the selection, or clears it. */
    fun showLines(lines: List<List<List<Double>>>?) {
        val src = style?.getSourceAs<GeoJsonSource>(SEL) ?: return
        src.setGeoJson(
            FeatureCollection.fromFeatures(
                lines.orEmpty().filter { it.size >= 2 }.map { l -> Feature.fromGeometry(LineString.fromLngLats(l.map { Point.fromLngLat(it[0], it[1]) })) },
            ),
        )
    }

    fun showHover(p: DoubleArray?) {
        val src = style?.getSourceAs<GeoJsonSource>(HOVER) ?: return
        if (p == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList())) else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p[0], p[1])))
    }

    /** Fits a line into the part of the map that a bottom sheet [bottomPx] tall leaves. */
    fun fitLine(line: List<List<Double>>, bottomPx: Int) {
        if (line.size < 2) return
        val b = LatLngBounds.Builder().includes(line.map { LatLng(it[1], it[0]) }).build()
        fitBounds(b, bottomPx)
    }

    fun fitBox(west: Double, south: Double, east: Double, north: Double, bottomPx: Int) {
        fitBounds(LatLngBounds.from(north, east, south, west), bottomPx)
    }

    private fun fitBounds(b: LatLngBounds, bottomPx: Int) {
        val pad = (30 * density).toInt()
        val top = (110 * density).toInt() // below the search bar
        val cam = map.getCameraForLatLngBounds(b, intArrayOf(pad, top, pad, bottomPx + pad)) ?: return
        val zoom = minOf(cam.zoom, 15.0)
        map.easeCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder(cam).zoom(zoom).build()), 600)
    }

    fun jumpTo(lat: Double, lon: Double, zoom: Double) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
    }

    /** Route-layer features under a tap, as property maps. */
    fun routesAt(p: PointF): List<Map<String, Any?>> = query(p, 12f, ROUTES_LAYER)

    /** The park label under a tap, if any. */
    fun parkAt(p: PointF): Map<String, Any?>? = query(p, 8f, PARK_LABEL).firstOrNull()

    /** Every route drawn in view, once zoomed in far enough to see them (z9). */
    fun routesInView(widthPx: Int, heightPx: Int): List<Map<String, Any?>> {
        if (map.cameraPosition.zoom < 9 || style?.getLayer(ROUTES_LAYER) == null) return emptyList()
        return map.queryRenderedFeatures(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), ROUTES_LAYER).map(::props)
    }

    private fun query(p: PointF, radiusDp: Float, layer: String): List<Map<String, Any?>> {
        if (style?.getLayer(layer) == null) return emptyList()
        val r = radiusDp * density
        return map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), layer).map(::props)
    }

    private fun props(f: Feature): Map<String, Any?> =
        f.properties()?.entrySet()?.associate { (k, v) -> k to plain(v) }.orEmpty()

    private fun plain(v: JsonElement): Any? = when {
        v.isJsonNull -> null
        v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asNumber.toDouble()
        v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
        v.isJsonPrimitive -> v.asString
        else -> v.toString()
    }

    companion object {
        const val ROUTES_LAYER = "routes-route"
        // The basemap's park names (styles/parks.json's label-park, prefixed by mapt).
        const val PARK_LABEL = "parks-label-park"
        const val ROUTE_COLOR = "#c026d3"
        const val START_COLOR = "#15803d"
        const val END_COLOR = "#b91c1c"
        // The basemap style's label font: its glyphs are what the map can draw.
        const val LABEL_FONT = "Hubot Sans-Regular"
        private const val PLAN_LINE = "plan-line"
        private const val PLAN_POINTS = "plan-points"
        private const val PLAN_PENDING = "plan-pending"
        private const val PLAN_HIT = "plan-hit"
        const val TRACK_COLOR = "#e8590c"
        const val OFFLINE_COLOR = "#1f4d2c"
        private const val OFFLINE_AREAS = "offline-areas"

        /** A box's outline, closed: [west, south, east, north]. */
        fun boxRing(b: List<Double>): List<Point> = listOf(
            Point.fromLngLat(b[0], b[1]), Point.fromLngLat(b[2], b[1]), Point.fromLngLat(b[2], b[3]),
            Point.fromLngLat(b[0], b[3]), Point.fromLngLat(b[0], b[1]),
        )
        private const val TRACK = "track-recording"
        private const val VIEWED = "track-viewed"
        private const val SEL = "curated-sel"
        private const val HOVER = "curated-hover"
    }
}
