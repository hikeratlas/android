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
import org.maplibre.android.style.layers.PropertyFactory.circleColor
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
        private const val SEL = "curated-sel"
        private const val HOVER = "curated-hover"
    }
}
