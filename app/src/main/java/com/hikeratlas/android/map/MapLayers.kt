package com.hikeratlas.android.map

import android.content.Context
import androidx.core.content.edit
import com.hikeratlas.android.imagery.Imagery
import com.hikeratlas.android.imagery.ImageryServer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/** Which of the map's optional layers are on; remembered between launches. */
data class LayerChoice(
    val imagery: Boolean = false,
    val hikes: Boolean = true,
    val contours: Boolean = true,
    val hillshade: Boolean = true,
) {
    fun save(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putBoolean("imagery", imagery); putBoolean("hikes", hikes)
        putBoolean("contours", contours); putBoolean("hillshade", hillshade)
    }

    companion object {
        private const val PREFS = "layers"

        fun load(context: Context): LayerChoice {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return LayerChoice(
                p.getBoolean("imagery", false), p.getBoolean("hikes", true),
                p.getBoolean("contours", true), p.getBoolean("hillshade", true),
            )
        }
    }
}

/**
 * Applies a [LayerChoice] to the basemap style. Aerial imagery works as on
 * the website (components/imagery.ts): its services go just above the
 * background, the map's fills and hillshade are hidden (the imagery stands in
 * for them), and lines and labels are restyled for a dark, busy background.
 */
class MapLayers(private val style: Style) {
    // The style's own paint values, to put back when the imagery goes off.
    private val original = HashMap<String, Array<PropertyValue<*>>>()
    private var imageryOn = false

    fun apply(c: LayerChoice) {
        setImagery(c.imagery)
        for (layer in style.layers) {
            val visible = when {
                layer.id == MapController.ROUTES_LAYER -> c.hikes
                sourceOf(layer) == CONTOURS -> c.contours
                layer is HillshadeLayer -> c.hillshade && !c.imagery
                layer is FillLayer || layer is FillExtrusionLayer -> !c.imagery
                else -> continue
            }
            layer.setProperties(PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE))
        }
    }

    private fun setImagery(on: Boolean) {
        if (on == imageryOn) return
        imageryOn = on
        if (on) {
            // Just above the background, in order.
            val above = style.layers.indexOfFirst { it.javaClass.simpleName != "BackgroundLayer" }.coerceAtLeast(0)
            Imagery.SERVICES.forEachIndexed { k, s ->
                val id = "imagery-${s.id}"
                if (style.getSource(id) == null) {
                    val tiles = TileSet("2.2.0", "https://${ImageryServer.HOST}/${s.id}/{z}/{y}/{x}").apply {
                        maxZoom = s.maxZoom.toFloat()
                        attribution = s.attribution
                        s.bounds?.let { b -> setBounds(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat()) }
                    }
                    style.addSource(RasterSource(id, tiles, 256))
                }
                if (style.getLayer(id) == null) style.addLayerAt(RasterLayer(id, id), above + k)
            }
            restyle()
        } else {
            for (s in Imagery.SERVICES) {
                style.removeLayer("imagery-${s.id}")
                style.removeSource("imagery-${s.id}")
            }
            for ((id, values) in original) style.getLayer(id)?.setProperties(*values)
            original.clear()
        }
    }

    private fun restyle() {
        for (layer in style.layers) {
            val paint = imageryPaint(layer) ?: continue
            original[layer.id] = saved(layer)
            layer.setProperties(*paint)
        }
    }

    /** The current values of the properties [imageryPaint] may change. */
    private fun saved(layer: Layer): Array<PropertyValue<*>> = when (layer) {
        is SymbolLayer -> arrayOf(layer.textColor, layer.textHaloColor, layer.textHaloWidth, layer.textHaloBlur)
        is LineLayer -> arrayOf(layer.lineColor, layer.lineOpacity, layer.lineBlur)
        else -> emptyArray()
    }.map(::asPaint).toTypedArray()

    /**
     * A getter's value, ready to set back. Getters return plain
     * PropertyValues, which setProperties() would apply as layout
     * properties; and an expression comes back as JSON, which it would pass
     * on unconverted.
     */
    private fun asPaint(v: PropertyValue<*>): PropertyValue<*> =
        PaintPropertyValue(v.name, if (v.isExpression) v.expression else v.value)

    companion object {
        const val CONTOURS = "contours"

        private fun sourceOf(layer: Layer): String? = when (layer) {
            is LineLayer -> layer.sourceId
            is SymbolLayer -> layer.sourceId
            is FillLayer -> layer.sourceId
            else -> null
        }

        private fun sourceLayerOf(layer: Layer): String? = when (layer) {
            is LineLayer -> layer.sourceLayer
            is SymbolLayer -> layer.sourceLayer
            else -> null
        }

        /**
         * The website's imageryPaint: labels white (light blue for water) on
         * a dark halo; trails light, so they show on dark forest; roads
         * translucent, without casings; streams faint; park edges thin and
         * light; contours and boundaries white or light grey.
         */
        fun imageryPaint(layer: Layer): Array<PropertyValue<*>>? {
            val sl = sourceLayerOf(layer)
            val id = layer.id
            if (layer is SymbolLayer) {
                val water = sl != null && (sl.startsWith("water") || sl == "lake_centerline")
                return arrayOf(
                    PropertyFactory.textColor(if (water) "#bae6fd" else "#ffffff"),
                    PropertyFactory.textHaloColor("rgba(0, 0, 0, 0.7)"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textHaloBlur(0.5f),
                )
            }
            if (layer !is LineLayer) return null
            return when (sl) {
                "road" -> arrayOf(PropertyFactory.lineOpacity(if (id.contains("casing")) 0f else 0.5f))
                "path" -> when {
                    id.contains("bridge") -> null
                    id.contains("closed") -> arrayOf(PropertyFactory.lineColor("#e4e4e7"))
                    id.contains("crossings") -> arrayOf(PropertyFactory.lineColor("#7dd3fc"))
                    Regex("mtb|bike|cycle").containsMatchIn(id) -> arrayOf(PropertyFactory.lineColor("#fdba74"), PropertyFactory.lineBlur(0f))
                    else -> arrayOf(PropertyFactory.lineColor("#fb7185"), PropertyFactory.lineBlur(0f))
                }
                "route" -> arrayOf(PropertyFactory.lineOpacity(0.15f))
                "water_line" -> arrayOf(PropertyFactory.lineOpacity(0.35f))
                "park_outline" -> if (id.contains("band")) {
                    arrayOf(PropertyFactory.lineOpacity(0f))
                } else {
                    arrayOf(PropertyFactory.lineColor("#bbf7d0"), PropertyFactory.lineOpacity(0.7f))
                }
                "contours-15" -> arrayOf(PropertyFactory.lineColor("#ffffff"))
                "boundary" -> arrayOf(PropertyFactory.lineColor("#e4e4e7"))
                else -> null
            }
        }
    }
}
