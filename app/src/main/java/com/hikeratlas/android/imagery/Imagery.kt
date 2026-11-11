package com.hikeratlas.android.imagery

/**
 * Aerial imagery, as the website shows it (components/imagery.ts): hosted
 * services, so nothing to mirror. Bottom to top: EOX Sentinel-2 cloudless
 * everywhere, then Quebec's, Ontario's, Saskatchewan's and USGS's own.
 */
object Imagery {
    class Service(
        val id: String,
        /** {z}/{y}/{x}, or {bbox} (Web Mercator metres) for an export endpoint. */
        val url: String,
        val maxZoom: Int,
        val attribution: String,
        val bounds: Box? = null,
        /** Fetch only tiles touching this outline (an asset). */
        val cover: String? = null,
        /** Skip tiles wholly inside this outline: something above covers them. */
        val skipInside: String? = null,
        /** Clip to the cover outline: the service draws something outside it. */
        val mask: Boolean = false,
        /** Make pure white transparent: the service's "no imagery". */
        val white: Boolean = false,
    )

    val SERVICES = listOf(
        Service(
            "s2", "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/{z}/{y}/{x}.jpg", 14,
            "<a href=\"https://cloudless.eox.at\">EOxCloudless</a> by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2025), " +
                "<a href=\"https://creativecommons.org/licenses/by-nc-sa/4.0/\">CC BY-NC-SA 4.0</a>",
            // 25 km inside the US's outline, so USGS fills everything EOX skips.
            skipInside = "imagery/us-inside.json",
        ),
        Service(
            "qc", "https://servicesmatriciels.mern.gouv.qc.ca/erdas-iws/ogc/wmts/Imagerie_Continue/Imagerie_GQ/default/GoogleMapsCompatibleExt2:epsg:3857/{z}/{y}/{x}.jpg", 18,
            "Imagerie : Gouvernement du Québec (CC BY 4.0)", doubleArrayOf(-79.8, 44.9, -57.0, 62.7),
            cover = "imagery/qc-mask.json", mask = true,
        ),
        Service(
            "on", "https://ws.lioservices.lrc.gov.on.ca/arcgis2/rest/services/LIO_Imagery/Ontario_Imagery_Web_Map_Service/MapServer/tile/{z}/{y}/{x}", 18,
            "Imagery: contains information licensed under the Open Government Licence – Ontario", doubleArrayOf(-95.3, 41.6, -74.2, 57.0),
            cover = "imagery/on-cover.json",
        ),
        Service(
            "sk", "https://gis.saskatchewan.ca/imagery/rest/services/SGIC_Public_Orthophotos/ImageServer/exportImage?bbox={bbox}&bboxSR=3857&imageSR=3857&size=256,256&format=jpgpng&f=image", 18,
            "Imagery: Saskatchewan Geospatial Imagery Collaborative (SGIC)", doubleArrayOf(-110.1, 48.9, -101.3, 60.1),
            cover = "imagery/sk-cover.json",
        ),
        Service(
            "usgs", "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}", 16,
            "Imagery: USDA, USGS The National Map", doubleArrayOf(-180.0, 15.0, -64.0, 72.0),
            cover = "imagery/us-cover.json", mask = true, white = true,
        ),
    )

    fun service(id: String) = SERVICES.firstOrNull { it.id == id }

    /** The service's tile URL for a tile. */
    fun tileUrl(s: Service, z: Int, x: Int, y: Int): String {
        val half = 20037508.342789244
        val size = 2 * half / (1 shl z)
        val bbox = listOf(x * size - half, half - (y + 1) * size, (x + 1) * size - half, half - y * size).joinToString(",")
        return s.url.replace("{z}", "$z").replace("{y}", "$y").replace("{x}", "$x").replace("{bbox}", bbox)
    }
}
