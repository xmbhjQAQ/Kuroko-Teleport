package com.kuroko.teleport.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kuroko.teleport.geo.CoordTransform
import com.kuroko.teleport.geo.GeoPoint
import com.kuroko.teleport.geo.MapLayerType
import com.kuroko.teleport.geo.MapStyleSpec
import com.kuroko.teleport.geo.MapStyles
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// GeoJSON source IDs
private const val SRC_REAL_ACC_POLY = "kuroko-real-acc-poly-src"
private const val SRC_REAL_DOT = "kuroko-real-dot-src"
private const val SRC_STATIC_JITTER = "kuroko-static-jitter-src"
private const val SRC_STATIC_PIN = "kuroko-static-pin-src"
private const val SRC_ROUTE_LINE = "kuroko-route-line-src"
private const val SRC_ROUTE_CLOSE = "kuroko-route-close-src"
private const val SRC_WAYPOINTS = "kuroko-waypoints-src"
private const val SRC_START_PT = "kuroko-start-pt-src"
private const val SRC_LIVE_DOT = "kuroko-live-dot-src"

// Layer IDs
private const val LYR_REAL_ACC_FILL = "kuroko-real-acc-fill-lyr"
private const val LYR_REAL_ACC_RING = "kuroko-real-acc-ring-lyr"
private const val LYR_REAL_HALO = "kuroko-real-halo-lyr"
private const val LYR_REAL_CORE = "kuroko-real-core-lyr"
private const val LYR_STATIC_JITTER_FILL = "kuroko-static-jitter-fill-lyr"
private const val LYR_STATIC_JITTER_RING = "kuroko-static-jitter-ring-lyr"
private const val LYR_STATIC_PIN_OUTER = "kuroko-static-pin-outer-lyr"
private const val LYR_STATIC_PIN_INNER = "kuroko-static-pin-inner-lyr"
private const val LYR_ROUTE_CASING = "kuroko-route-casing-lyr"
private const val LYR_ROUTE_LINE = "kuroko-route-line-lyr"
private const val LYR_ROUTE_CLOSE = "kuroko-route-close-lyr"
private const val LYR_WAYPOINTS = "kuroko-waypoints-lyr"
private const val LYR_START_PT = "kuroko-start-pt-lyr"
private const val LYR_LIVE_HALO = "kuroko-live-halo-lyr"
private const val LYR_LIVE_CORE = "kuroko-live-core-lyr"

@Stable
class KurokoMapController {
    private val mainHandler = Handler(Looper.getMainLooper())
    internal var map: MapLibreMap? = null
    internal var activeLayer: MapLayerType = MapLayerType.AMAP_SATELLITE
    internal var pendingFlyTarget: Triple<Double, Double, Double>? = null

    fun getCenterWgs84(): GeoPoint? {
        val target = map?.cameraPosition?.target ?: return null
        val (wgsLat, wgsLon) = CoordTransform.mapToWgs84(
            target.latitude,
            target.longitude,
            activeLayer.isGcj02
        )
        return GeoPoint(wgsLat, wgsLon)
    }

    fun getZoom(): Double = map?.cameraPosition?.zoom ?: 15.5

    fun flyToWgs84(latWgs84: Double, lonWgs84: Double, zoom: Double = 16.5, animate: Boolean = true) {
        val runOnMain = Runnable {
            val m = map
            if (m == null) {
                pendingFlyTarget = Triple(latWgs84, lonWgs84, zoom)
                return@Runnable
            }
            val (mapLat, mapLon) = CoordTransform.wgs84ToMap(latWgs84, lonWgs84, activeLayer.isGcj02)
            val update = CameraUpdateFactory.newLatLngZoom(LatLng(mapLat, mapLon), zoom)
            if (animate) {
                m.animateCamera(update, 600)
            } else {
                m.moveCamera(update)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runOnMain.run()
        } else {
            mainHandler.post(runOnMain)
        }
    }

    fun fitRouteWgs84(points: List<GeoPoint>) {
        mainHandler.post {
            val m = map ?: return@post
            if (points.isEmpty()) return@post
            if (points.size == 1) {
                flyToWgs84(points[0].lat, points[0].lon, 17.0)
                return@post
            }
            val b = LatLngBounds.Builder()
            for (p in points) {
                val (mLat, mLon) = CoordTransform.wgs84ToMap(p.lat, p.lon, activeLayer.isGcj02)
                b.include(LatLng(mLat, mLon))
            }
            runCatching {
                m.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(b.build(), 120, 260, 120, 340),
                    650
                )
            }.onFailure {
                flyToWgs84(points[0].lat, points[0].lon, 16.5)
            }
        }
    }
}

@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    controller: KurokoMapController,
    layerType: MapLayerType,
    tiandituToken: String,
    initialLatWgs84: Double,
    initialLonWgs84: Double,
    initialZoom: Double,
    activeTab: Int,
    staticPointWgs84: GeoPoint?,
    jitterM: Float,
    waypointsWgs84: List<GeoPoint>,
    loopEnabled: Boolean,
    realLocationWgs84: GeoPoint?,
    realAccuracyM: Float,
    simActive: Boolean,
    simPointWgs84: GeoPoint?,
    pulsePhase: Float,
    onCenterChangedWgs84: (GeoPoint, Double) -> Unit,
    onMapTap: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
        }
    }

    // Hold freshest Compose state for async MapLibre callbacks
    val latestTab by rememberUpdatedState(activeTab)
    val latestStaticPt by rememberUpdatedState(staticPointWgs84)
    val latestJitter by rememberUpdatedState(jitterM)
    val latestWaypoints by rememberUpdatedState(waypointsWgs84)
    val latestLoop by rememberUpdatedState(loopEnabled)
    val latestRealLoc by rememberUpdatedState(realLocationWgs84)
    val latestRealAcc by rememberUpdatedState(realAccuracyM)
    val latestSimActive by rememberUpdatedState(simActive)
    val latestSimPoint by rememberUpdatedState(simPointWgs84)
    val latestPulse by rememberUpdatedState(pulsePhase)
    val latestOnCenterChanged by rememberUpdatedState(onCenterChangedWgs84)
    val latestOnMapTap by rememberUpdatedState(onMapTap)

    val styleHolder = remember { arrayOfNulls<Style>(1) }
    val appliedStyleKey = remember { arrayOfNulls<String>(1) }

    fun pushOverlayData(style: Style, layer: MapLayerType) {
        if (!style.isFullyLoaded) return
        val isGcj = layer.isGcj02
        val emptyFc = FeatureCollection.fromFeatures(emptyArray())

        // 1. Real device location blue marker + accuracy halo
        val realLoc = latestRealLoc
        if (realLoc != null) {
            val (rLat, rLon) = CoordTransform.wgs84ToMap(realLoc.lat, realLoc.lon, isGcj)
            style.getSourceAs<GeoJsonSource>(SRC_REAL_DOT)?.setGeoJson(
                FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(rLon, rLat)))
            )
            val accMeters = latestRealAcc.coerceIn(6f, 250f).toDouble()
            style.getSourceAs<GeoJsonSource>(SRC_REAL_ACC_POLY)?.setGeoJson(
                FeatureCollection.fromFeature(Feature.fromGeometry(buildCirclePolygon(rLat, rLon, accMeters)))
            )
        } else {
            style.getSourceAs<GeoJsonSource>(SRC_REAL_DOT)?.setGeoJson(emptyFc)
            style.getSourceAs<GeoJsonSource>(SRC_REAL_ACC_POLY)?.setGeoJson(emptyFc)
        }

        // 2. Static mode coral pin + translucent jitter circle (Tab 0)
        val staticPt = latestStaticPt
        if (latestTab == 0 && staticPt != null) {
            val (sLat, sLon) = CoordTransform.wgs84ToMap(staticPt.lat, staticPt.lon, isGcj)
            style.getSourceAs<GeoJsonSource>(SRC_STATIC_PIN)?.setGeoJson(
                FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(sLon, sLat)))
            )
            val jitterRadiusM = latestJitter.coerceAtLeast(2.5f).toDouble()
            style.getSourceAs<GeoJsonSource>(SRC_STATIC_JITTER)?.setGeoJson(
                FeatureCollection.fromFeature(Feature.fromGeometry(buildCirclePolygon(sLat, sLon, jitterRadiusM)))
            )
        } else {
            style.getSourceAs<GeoJsonSource>(SRC_STATIC_PIN)?.setGeoJson(emptyFc)
            style.getSourceAs<GeoJsonSource>(SRC_STATIC_JITTER)?.setGeoJson(emptyFc)
        }

        // 3. Dynamic mode route polyline + waypoints (Tab 1)
        if (latestTab == 1) {
            val mapCoords = latestWaypoints.map { wp ->
                val (lat, lon) = CoordTransform.wgs84ToMap(wp.lat, wp.lon, isGcj)
                Point.fromLngLat(lon, lat)
            }

            val lineFc = if (mapCoords.size >= 2) {
                FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(mapCoords)))
            } else {
                emptyFc
            }
            style.getSourceAs<GeoJsonSource>(SRC_ROUTE_LINE)?.setGeoJson(lineFc)

            val closeFc = if (latestLoop && mapCoords.size >= 3) {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(LineString.fromLngLats(listOf(mapCoords.last(), mapCoords.first())))
                )
            } else {
                emptyFc
            }
            style.getSourceAs<GeoJsonSource>(SRC_ROUTE_CLOSE)?.setGeoJson(closeFc)

            val wpFeatures = mapCoords.drop(1).map { Feature.fromGeometry(it) }
            style.getSourceAs<GeoJsonSource>(SRC_WAYPOINTS)?.setGeoJson(
                FeatureCollection.fromFeatures(wpFeatures)
            )

            val startFc = if (mapCoords.isNotEmpty()) {
                FeatureCollection.fromFeature(Feature.fromGeometry(mapCoords.first()))
            } else {
                emptyFc
            }
            style.getSourceAs<GeoJsonSource>(SRC_START_PT)?.setGeoJson(startFc)
        } else {
            style.getSourceAs<GeoJsonSource>(SRC_ROUTE_LINE)?.setGeoJson(emptyFc)
            style.getSourceAs<GeoJsonSource>(SRC_ROUTE_CLOSE)?.setGeoJson(emptyFc)
            style.getSourceAs<GeoJsonSource>(SRC_WAYPOINTS)?.setGeoJson(emptyFc)
            style.getSourceAs<GeoJsonSource>(SRC_START_PT)?.setGeoJson(emptyFc)
        }

        // 4. Active live simulated position dot (pulsing emerald dot)
        val livePt = latestSimPoint
        val liveFc = if (latestSimActive && livePt != null) {
            val (lLat, lLon) = CoordTransform.wgs84ToMap(livePt.lat, livePt.lon, isGcj)
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(lLon, lLat)))
        } else {
            emptyFc
        }
        style.getSourceAs<GeoJsonSource>(SRC_LIVE_DOT)?.setGeoJson(liveFc)

        style.getLayerAs<CircleLayer>(LYR_LIVE_HALO)?.setProperties(
            circleRadius(12f + latestPulse * 14f),
            circleOpacity((0.42f * (1f - latestPulse)).coerceIn(0.06f, 0.42f))
        )
    }

    fun installOverlayLayers(style: Style) {
        if (style.getSource(SRC_ROUTE_LINE) != null) return

        // Register all sources
        style.addSource(GeoJsonSource(SRC_REAL_ACC_POLY))
        style.addSource(GeoJsonSource(SRC_REAL_DOT))
        style.addSource(GeoJsonSource(SRC_STATIC_JITTER))
        style.addSource(GeoJsonSource(SRC_STATIC_PIN))
        style.addSource(GeoJsonSource(SRC_ROUTE_LINE))
        style.addSource(GeoJsonSource(SRC_ROUTE_CLOSE))
        style.addSource(GeoJsonSource(SRC_WAYPOINTS))
        style.addSource(GeoJsonSource(SRC_START_PT))
        style.addSource(GeoJsonSource(SRC_LIVE_DOT))

        // Layer stack bottom -> top:
        // (1) Real device location blue accuracy fill + blue dot
        style.addLayer(
            FillLayer(LYR_REAL_ACC_FILL, SRC_REAL_ACC_POLY).withProperties(
                fillColor(Color.parseColor("#3B82F6")),
                fillOpacity(0.16f)
            )
        )
        style.addLayer(
            LineLayer(LYR_REAL_ACC_RING, SRC_REAL_ACC_POLY).withProperties(
                lineColor(Color.parseColor("#2563EB")),
                lineWidth(1.5f),
                lineOpacity(0.45f)
            )
        )
        style.addLayer(
            CircleLayer(LYR_REAL_HALO, SRC_REAL_DOT).withProperties(
                circleColor(Color.parseColor("#3B82F6")),
                circleRadius(15f),
                circleOpacity(0.24f)
            )
        )
        style.addLayer(
            CircleLayer(LYR_REAL_CORE, SRC_REAL_DOT).withProperties(
                circleColor(Color.parseColor("#2563EB")),
                circleRadius(7.5f),
                circleStrokeWidth(3.0f),
                circleStrokeColor(Color.WHITE)
            )
        )

        // (2) Static mode jitter circle + vivid coral pin
        style.addLayer(
            FillLayer(LYR_STATIC_JITTER_FILL, SRC_STATIC_JITTER).withProperties(
                fillColor(Color.parseColor("#EF4444")),
                fillOpacity(0.16f)
            )
        )
        style.addLayer(
            LineLayer(LYR_STATIC_JITTER_RING, SRC_STATIC_JITTER).withProperties(
                lineColor(Color.parseColor("#EF4444")),
                lineWidth(2.0f),
                lineDasharray(arrayOf(2.0f, 1.5f)),
                lineOpacity(0.75f)
            )
        )
        style.addLayer(
            CircleLayer(LYR_STATIC_PIN_OUTER, SRC_STATIC_PIN).withProperties(
                circleColor(Color.parseColor("#EF4444")),
                circleRadius(9.0f),
                circleStrokeWidth(3.0f),
                circleStrokeColor(Color.WHITE)
            )
        )
        style.addLayer(
            CircleLayer(LYR_STATIC_PIN_INNER, SRC_STATIC_PIN).withProperties(
                circleColor(Color.WHITE),
                circleRadius(2.8f)
            )
        )

        // (3) Dynamic mode route polyline + green start + orange waypoints
        style.addLayer(
            LineLayer(LYR_ROUTE_CASING, SRC_ROUTE_LINE).withProperties(
                lineColor(Color.parseColor("#0F172A")),
                lineWidth(7.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineOpacity(0.75f)
            )
        )
        style.addLayer(
            LineLayer(LYR_ROUTE_LINE, SRC_ROUTE_LINE).withProperties(
                lineColor(Color.parseColor("#0EA5E9")),
                lineWidth(4.6f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineOpacity(0.96f)
            )
        )
        style.addLayer(
            LineLayer(LYR_ROUTE_CLOSE, SRC_ROUTE_CLOSE).withProperties(
                lineColor(Color.parseColor("#10B981")),
                lineWidth(3.6f),
                lineDasharray(arrayOf(2.2f, 1.6f)),
                lineCap(Property.LINE_CAP_ROUND),
                lineOpacity(0.92f)
            )
        )
        style.addLayer(
            CircleLayer(LYR_WAYPOINTS, SRC_WAYPOINTS).withProperties(
                circleColor(Color.parseColor("#F59E0B")),
                circleRadius(5.8f),
                circleStrokeWidth(2.2f),
                circleStrokeColor(Color.WHITE)
            )
        )
        style.addLayer(
            CircleLayer(LYR_START_PT, SRC_START_PT).withProperties(
                circleColor(Color.parseColor("#10B981")),
                circleRadius(7.6f),
                circleStrokeWidth(2.6f),
                circleStrokeColor(Color.WHITE)
            )
        )

        // (4) Active live simulated position dot (topmost)
        style.addLayer(
            CircleLayer(LYR_LIVE_HALO, SRC_LIVE_DOT).withProperties(
                circleColor(Color.parseColor("#10B981")),
                circleRadius(16f),
                circleOpacity(0.32f)
            )
        )
        style.addLayer(
            CircleLayer(LYR_LIVE_CORE, SRC_LIVE_DOT).withProperties(
                circleColor(Color.parseColor("#10B981")),
                circleRadius(7.5f),
                circleStrokeWidth(3.0f),
                circleStrokeColor(Color.WHITE)
            )
        )
    }

    fun applyStyle(map: MapLibreMap, targetLayer: MapLayerType, token: String, preserveWgsCenter: Boolean) {
        val key = "${targetLayer.id}|${if (targetLayer == MapLayerType.TIANDITU_SATELLITE) token else ""}"
        if (appliedStyleKey[0] == key && styleHolder[0]?.isFullyLoaded == true) return

        // Capture physical WGS-84 center BEFORE flipping activeLayer flag
        val prevWgsCenter = if (preserveWgsCenter) controller.getCenterWgs84() else null
        val prevZoom = map.cameraPosition.zoom

        controller.activeLayer = targetLayer
        appliedStyleKey[0] = key

        if (prevWgsCenter != null) {
            val (newLat, newLon) = CoordTransform.wgs84ToMap(
                prevWgsCenter.lat,
                prevWgsCenter.lon,
                targetLayer.isGcj02
            )
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(newLat, newLon))
                        .zoom(prevZoom)
                        .tilt(0.0)
                        .build()
                )
            )
        }

        val spec = MapStyles.buildStyleSpec(targetLayer, token)
        val builder = when (spec) {
            is MapStyleSpec.FromJson -> Style.Builder().fromJson(spec.json)
            is MapStyleSpec.FromUri -> Style.Builder().fromUri(spec.uri)
        }
        map.setStyle(builder) { loadedStyle ->
            styleHolder[0] = loadedStyle
            installOverlayLayers(loadedStyle)
            pushOverlayData(loadedStyle, targetLayer)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(layerType, tiandituToken) {
        val m = controller.map ?: return@LaunchedEffect
        applyStyle(m, layerType, tiandituToken, preserveWgsCenter = true)
    }

    LaunchedEffect(
        activeTab,
        staticPointWgs84,
        jitterM,
        waypointsWgs84,
        loopEnabled,
        realLocationWgs84,
        realAccuracyM,
        simActive,
        simPointWgs84,
        pulsePhase,
        layerType
    ) {
        val s = styleHolder[0] ?: return@LaunchedEffect
        pushOverlayData(s, layerType)
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                getMapAsync { m ->
                    controller.map = m
                    controller.activeLayer = layerType

                    m.uiSettings.apply {
                        isLogoEnabled = false
                        isAttributionEnabled = false
                        isCompassEnabled = true
                        isTiltGesturesEnabled = false
                        isRotateGesturesEnabled = true
                    }

                    val pending = controller.pendingFlyTarget
                    if (pending != null) {
                        controller.pendingFlyTarget = null
                        val (mLat, mLon) = CoordTransform.wgs84ToMap(pending.first, pending.second, layerType.isGcj02)
                        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(mLat, mLon), pending.third))
                    } else {
                        val (initLat, initLon) = CoordTransform.wgs84ToMap(
                            initialLatWgs84,
                            initialLonWgs84,
                            layerType.isGcj02
                        )
                        m.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(initLat, initLon))
                                    .zoom(initialZoom)
                                    .tilt(0.0)
                                    .build()
                            )
                        )
                    }

                    m.addOnCameraMoveListener {
                        val center = controller.getCenterWgs84() ?: return@addOnCameraMoveListener
                        latestOnCenterChanged(center, m.cameraPosition.zoom)
                    }

                    m.addOnMapClickListener {
                        latestOnMapTap()
                        false
                    }

                    applyStyle(m, layerType, tiandituToken, preserveWgsCenter = false)
                }
            }
        }
    )
}

/**
 * Generates a 36-vertex GeoJSON circle polygon centered at (lat, lon) with radius in meters.
 */
private fun buildCirclePolygon(centerLat: Double, centerLon: Double, radiusMeters: Double): Polygon {
    val steps = 36
    val latRad = Math.toRadians(centerLat)
    val dLatPerM = 1.0 / 111320.0
    val dLonPerM = 1.0 / (111320.0 * cos(latRad).coerceAtLeast(0.01))
    val ring = ArrayList<Point>(steps + 1)
    for (i in 0..steps) {
        val theta = (2.0 * PI * i) / steps
        val lat = centerLat + sin(theta) * radiusMeters * dLatPerM
        val lon = centerLon + cos(theta) * radiusMeters * dLonPerM
        ring.add(Point.fromLngLat(lon, lat))
    }
    return Polygon.fromLngLats(listOf(ring))
}
