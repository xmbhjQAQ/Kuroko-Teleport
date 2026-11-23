package com.kuroko.teleport.geo

import android.content.Context
import androidx.annotation.StringRes
import com.kuroko.teleport.R
import org.json.JSONArray
import org.json.JSONObject

enum class MapLayerType(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val shortLabelRes: Int,
    val displayName: String,
    val shortName: String,
    val isGcj02: Boolean,
    val requiresTiandituToken: Boolean = false
) {
    AMAP_SATELLITE(
        id = "amap_sat",
        labelRes = R.string.map_layer_amap_sat,
        shortLabelRes = R.string.map_layer_amap_sat_short,
        displayName = "AMap Satellite + Roads",
        shortName = "AMap Sat",
        isGcj02 = true
    ),
    ESRI_SATELLITE(
        id = "esri_sat",
        labelRes = R.string.map_layer_esri_sat,
        shortLabelRes = R.string.map_layer_esri_sat_short,
        displayName = "Esri HD Satellite",
        shortName = "Esri Sat",
        isGcj02 = false
    ),
    OPENFREEMAP_VECTOR(
        id = "openfreemap",
        labelRes = R.string.map_layer_openfreemap,
        shortLabelRes = R.string.map_layer_openfreemap_short,
        displayName = "OpenFreeMap Vector",
        shortName = "Vector",
        isGcj02 = false
    ),
    TIANDITU_SATELLITE(
        id = "tianditu_sat",
        labelRes = R.string.map_layer_tianditu_sat,
        shortLabelRes = R.string.map_layer_tianditu_sat_short,
        displayName = "Tianditu Satellite + Labels",
        shortName = "Tianditu",
        isGcj02 = false,
        requiresTiandituToken = true
    );

    fun label(context: Context): String = context.getString(labelRes)

    fun shortLabel(context: Context): String = context.getString(shortLabelRes)

    companion object {
        fun fromId(id: String?): MapLayerType =
            entries.firstOrNull { it.id == id } ?: AMAP_SATELLITE
    }
}

sealed class MapStyleSpec {
    data class FromJson(val json: String) : MapStyleSpec()
    data class FromUri(val uri: String) : MapStyleSpec()
}

/**
 * Pure MapLibre Style Spec v8 builders for 4 switchable map layers (zero commercial Android SDKs).
 */
object MapStyles {
    private const val OPENFREEMAP_LIBERTY_URL = "https://tiles.openfreemap.org/styles/liberty"

    fun buildStyleSpec(layer: MapLayerType, tiandituToken: String): MapStyleSpec {
        return when (layer) {
            MapLayerType.AMAP_SATELLITE -> MapStyleSpec.FromJson(buildAmapSatelliteJson())
            MapLayerType.ESRI_SATELLITE -> MapStyleSpec.FromJson(buildEsriSatelliteJson())
            MapLayerType.OPENFREEMAP_VECTOR -> MapStyleSpec.FromUri(OPENFREEMAP_LIBERTY_URL)
            MapLayerType.TIANDITU_SATELLITE -> {
                val tk = tiandituToken.trim()
                if (tk.isEmpty()) {
                    // Graceful fallback to Esri WGS-84 satellite if token is unset
                    MapStyleSpec.FromJson(buildEsriSatelliteJson())
                } else {
                    MapStyleSpec.FromJson(buildTiandituSatelliteJson(tk))
                }
            }
        }
    }

    private fun buildAmapSatelliteJson(): String {
        val satTiles = (1..4).map { idx ->
            "https://webst0$idx.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"
        }
        val labelTiles = (1..4).map { idx ->
            "https://webst0$idx.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}"
        }
        return buildDualRasterStyleJson(
            name = "AMap Satellite + Roads",
            baseId = "amap-sat",
            baseTiles = satTiles,
            baseMaxZoom = 18,
            overlayId = "amap-labels",
            overlayTiles = labelTiles,
            overlayMaxZoom = 18
        )
    }

    private fun buildEsriSatelliteJson(): String {
        val satTiles = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        )
        val refTiles = listOf(
            "https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"
        )
        return buildDualRasterStyleJson(
            name = "Esri World Imagery",
            baseId = "esri-sat",
            baseTiles = satTiles,
            baseMaxZoom = 19,
            overlayId = "esri-labels",
            overlayTiles = refTiles,
            overlayMaxZoom = 19
        )
    }

    private fun buildTiandituSatelliteJson(token: String): String {
        val imgTiles = (0..7).map { idx ->
            "https://t$idx.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=$token"
        }
        val ciaTiles = (0..7).map { idx ->
            "https://t$idx.tianditu.gov.cn/cia_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=cia&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=$token"
        }
        return buildDualRasterStyleJson(
            name = "Tianditu Satellite + Labels",
            baseId = "tdt-img",
            baseTiles = imgTiles,
            baseMaxZoom = 18,
            overlayId = "tdt-cia",
            overlayTiles = ciaTiles,
            overlayMaxZoom = 18
        )
    }

    private fun buildDualRasterStyleJson(
        name: String,
        baseId: String,
        baseTiles: List<String>,
        baseMaxZoom: Int,
        overlayId: String,
        overlayTiles: List<String>,
        overlayMaxZoom: Int
    ): String {
        val sources = JSONObject().apply {
            put(
                baseId,
                JSONObject().apply {
                    put("type", "raster")
                    put("tiles", JSONArray(baseTiles))
                    put("tileSize", 256)
                    put("minzoom", 0)
                    put("maxzoom", baseMaxZoom)
                }
            )
            put(
                overlayId,
                JSONObject().apply {
                    put("type", "raster")
                    put("tiles", JSONArray(overlayTiles))
                    put("tileSize", 256)
                    put("minzoom", 0)
                    put("maxzoom", overlayMaxZoom)
                }
            )
        }
        val layers = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "bg")
                    put("type", "background")
                    put("paint", JSONObject().put("background-color", "#0D1520"))
                }
            )
            put(
                JSONObject().apply {
                    put("id", "$baseId-layer")
                    put("type", "raster")
                    put("source", baseId)
                    put("minzoom", 0)
                    put("maxzoom", 22)
                }
            )
            put(
                JSONObject().apply {
                    put("id", "$overlayId-layer")
                    put("type", "raster")
                    put("source", overlayId)
                    put("minzoom", 0)
                    put("maxzoom", 22)
                }
            )
        }
        return JSONObject().apply {
            put("version", 8)
            put("name", name)
            put("sources", sources)
            put("layers", layers)
        }.toString()
    }
}
