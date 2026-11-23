package com.kuroko.teleport.geo

import android.content.Context
import androidx.annotation.StringRes
import com.kuroko.teleport.BuildConfig
import com.kuroko.teleport.R
import com.kuroko.teleport.ui.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class SearchPlace(
    val name: String,
    val subtitle: String,
    val latWgs84: Double,
    val lonWgs84: Double,
    val provider: String,
    @StringRes val providerRes: Int = R.string.geo_provider_photon
) {
    fun providerLabel(context: Context): String = context.getString(providerRes)
}

/**
 * Pure HttpURLConnection address search returning canonical WGS-84 coordinates.
 * - Default (zero keys): Photon (Komoot/OSM) with automatic Nominatim fallback.
 * - Optional Settings keys: AMap Web REST Key (auto GCJ-02 -> WGS-84) or Tianditu Token (native WGS-84).
 */
object GeocoderClient {
    private val USER_AGENT = "KurokoTeleport/${BuildConfig.VERSION_NAME} (Android; open-source-controller)"

    suspend fun search(
        query: String,
        biasLatWgs84: Double? = null,
        biasLonWgs84: Double? = null,
        amapWebKey: String = "",
        tiandituToken: String = "",
        context: Context? = null
    ): Result<List<SearchPlace>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.success(emptyList())

        val uiCtx = context?.let { AppLocaleManager.localizedContext(it) }
        val locales = uiCtx?.resources?.configuration?.locales
        val primaryLocale = if (locales != null && !locales.isEmpty) locales[0] else Locale.getDefault()
        val primaryTag = primaryLocale.toLanguageTag().ifBlank { "en" }
        val acceptLang = if (primaryTag.equals("en", ignoreCase = true) ||
            primaryTag.startsWith("en-", ignoreCase = true)
        ) {
            primaryTag
        } else {
            "$primaryTag,en"
        }

        val amapKey = amapWebKey.trim()
        if (amapKey.isNotEmpty()) {
            val res = runCatching { searchAmap(trimmed, amapKey, uiCtx) }
            if (res.isSuccess && res.getOrNull()?.isNotEmpty() == true) {
                return@withContext res
            }
        }

        val tdtKey = tiandituToken.trim()
        if (tdtKey.isNotEmpty()) {
            val res = runCatching { searchTianditu(trimmed, tdtKey, uiCtx) }
            if (res.isSuccess && res.getOrNull()?.isNotEmpty() == true) {
                return@withContext res
            }
        }

        // Default zero-key path: Photon -> Nominatim fallback
        val photonRes = runCatching { searchPhoton(trimmed, biasLatWgs84, biasLonWgs84, uiCtx) }
        if (photonRes.isSuccess && photonRes.getOrNull()?.isNotEmpty() == true) {
            return@withContext photonRes
        }

        val nominatimRes = runCatching { searchNominatim(trimmed, acceptLang, uiCtx) }
        if (nominatimRes.isSuccess) {
            return@withContext nominatimRes
        }

        Result.failure(
            nominatimRes.exceptionOrNull()
                ?: photonRes.exceptionOrNull()
                ?: IOException(
                    uiCtx?.getString(R.string.geo_error_search_failed)
                        ?: "Place search failed. Please check your network connection."
                )
        )
    }

    private fun searchPhoton(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        uiCtx: Context?
    ): List<SearchPlace> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val bias = if (biasLat != null && biasLon != null) {
            String.format(Locale.US, "&lat=%.5f&lon=%.5f", biasLat, biasLon)
        } else {
            ""
        }
        val body = httpGet("https://photon.komoot.io/api/?q=$q&limit=8$bias")
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return emptyList()
        val providerName = uiCtx?.getString(R.string.geo_provider_photon) ?: "Photon"
        val results = mutableListOf<SearchPlace>()
        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val geom = feat.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val props = feat.optJSONObject("properties") ?: JSONObject()
            val name = props.optString("name").ifBlank {
                props.optString("street").ifBlank { props.optString("city", query) }
            }
            val parts = listOfNotNull(
                props.optString("country").takeIf { it.isNotBlank() },
                props.optString("state").takeIf { it.isNotBlank() },
                props.optString("city").takeIf { it.isNotBlank() },
                props.optString("district").takeIf { it.isNotBlank() },
                props.optString("street").takeIf { it.isNotBlank() && it != name }
            ).distinct()
            results.add(
                SearchPlace(
                    name = name,
                    subtitle = parts.joinToString(" · "),
                    latWgs84 = lat,
                    lonWgs84 = lon,
                    provider = providerName,
                    providerRes = R.string.geo_provider_photon
                )
            )
        }
        return results
    }

    private fun searchNominatim(
        query: String,
        acceptLang: String,
        uiCtx: Context?
    ): List<SearchPlace> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val langParam = URLEncoder.encode(acceptLang, StandardCharsets.UTF_8.name())
        val url = "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=8&accept-language=$langParam"
        val body = httpGet(url)
        val arr = JSONArray(body)
        val providerName = uiCtx?.getString(R.string.geo_provider_osm) ?: "OSM"
        val results = mutableListOf<SearchPlace>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lat = obj.optString("lat").toDoubleOrNull() ?: continue
            val lon = obj.optString("lon").toDoubleOrNull() ?: continue
            val display = obj.optString("display_name")
            val shortName = obj.optString("name").ifBlank {
                display.substringBefore(",").ifBlank { query }
            }
            results.add(
                SearchPlace(
                    name = shortName,
                    subtitle = display,
                    latWgs84 = lat,
                    lonWgs84 = lon,
                    provider = providerName,
                    providerRes = R.string.geo_provider_osm
                )
            )
        }
        return results
    }

    private fun searchAmap(query: String, key: String, uiCtx: Context?): List<SearchPlace> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val k = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        val url = "https://restapi.amap.com/v3/place/text?key=$k&keywords=$q&offset=8&page=1&extensions=base"
        val body = httpGet(url)
        val root = JSONObject(body)
        if (root.optString("status") != "1") return emptyList()
        val pois = root.optJSONArray("pois") ?: return emptyList()
        val providerName = uiCtx?.getString(R.string.geo_provider_amap) ?: "AMap \u2192 WGS84"
        val results = mutableListOf<SearchPlace>()
        for (i in 0 until pois.length()) {
            val poi = pois.optJSONObject(i) ?: continue
            val locParts = poi.optString("location").split(",")
            if (locParts.size < 2) continue
            val gcjLon = locParts[0].toDoubleOrNull() ?: continue
            val gcjLat = locParts[1].toDoubleOrNull() ?: continue
            val (wgsLat, wgsLon) = CoordTransform.gcj02ToWgs84(gcjLat, gcjLon)
            val name = poi.optString("name").ifBlank { query }
            val sub = listOf(
                poi.optString("pname"),
                poi.optString("cityname"),
                poi.optString("adname"),
                poi.optString("address")
            ).filter { it.isNotBlank() && it != "[]" }.distinct().joinToString(" ")
            results.add(
                SearchPlace(
                    name = name,
                    subtitle = sub,
                    latWgs84 = wgsLat,
                    lonWgs84 = wgsLon,
                    provider = providerName,
                    providerRes = R.string.geo_provider_amap
                )
            )
        }
        return results
    }

    private fun searchTianditu(query: String, token: String, uiCtx: Context?): List<SearchPlace> {
        val postStr = JSONObject().apply {
            put("keyWord", query)
            put("level", 12)
            put("mapBound", "73.0,3.0,136.0,54.0")
            put("queryType", 1)
            put("start", 0)
            put("count", 8)
        }.toString()
        val encodedPost = URLEncoder.encode(postStr, StandardCharsets.UTF_8.name())
        val tk = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
        val url = "https://api.tianditu.gov.cn/v2/search?postStr=$encodedPost&type=query&tk=$tk"
        val body = httpGet(url)
        val root = JSONObject(body)
        val pois = root.optJSONArray("pois") ?: return emptyList()
        val providerName = uiCtx?.getString(R.string.geo_provider_tianditu) ?: "Tianditu"
        val results = mutableListOf<SearchPlace>()
        for (i in 0 until pois.length()) {
            val poi = pois.optJSONObject(i) ?: continue
            val lonlat = poi.optString("lonlat").trim()
            val parts = lonlat.split(',', ' ').filter { it.isNotBlank() }
            if (parts.size < 2) continue
            val lon = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            val name = poi.optString("name").ifBlank { query }
            val address = poi.optString("address")
            results.add(
                SearchPlace(
                    name = name,
                    subtitle = address,
                    latWgs84 = lat,
                    lonWgs84 = lon,
                    provider = providerName,
                    providerRes = R.string.geo_provider_tianditu
                )
            )
        }
        return results
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
