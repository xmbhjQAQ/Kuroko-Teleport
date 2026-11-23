package com.kuroko.teleport.geo

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val alt: Double = 45.0
)

/**
 * High-precision WGS-84 <-> GCJ-02 coordinate transforms (<0.01m iterative inverse)
 * and geodesic utilities matching native/daemon/sim_engine.cpp.
 */
object CoordTransform {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val EARTH_RADIUS_M = 6378137.0

    fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    fun wgs84ToGcj02(wgsLat: Double, wgsLon: Double): Pair<Double, Double> {
        if (outOfChina(wgsLat, wgsLon)) return wgsLat to wgsLon
        var dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0)
        var dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1.0 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1.0 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (wgsLat + dLat) to (wgsLon + dLon)
    }

    /**
     * Iterative fixed-point inversion of WGS-84 -> GCJ-02.
     * Converges in 3-4 iterations to < 1e-10 deg (< 0.001 m residual error).
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
        if (outOfChina(gcjLat, gcjLon)) return gcjLat to gcjLon
        var wgsLat = gcjLat
        var wgsLon = gcjLon
        for (i in 0 until 15) {
            val (estLat, estLon) = wgs84ToGcj02(wgsLat, wgsLon)
            val dLat = estLat - gcjLat
            val dLon = estLon - gcjLon
            wgsLat -= dLat
            wgsLon -= dLon
            if (abs(dLat) < 1e-10 && abs(dLon) < 1e-10) break
        }
        return wgsLat to wgsLon
    }

    fun wgs84ToMap(latWgs84: Double, lonWgs84: Double, isGcj02Layer: Boolean): Pair<Double, Double> {
        return if (isGcj02Layer) wgs84ToGcj02(latWgs84, lonWgs84) else (latWgs84 to lonWgs84)
    }

    fun mapToWgs84(mapLat: Double, mapLon: Double, isGcj02Layer: Boolean): Pair<Double, Double> {
        return if (isGcj02Layer) gcj02ToWgs84(mapLat, mapLon) else (mapLat to mapLon)
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val sinDLat = sin(dLat * 0.5)
        val sinDLon = sin(dLon * 0.5)
        val a = sinDLat * sinDLat + cos(rLat1) * cos(rLat2) * sinDLon * sinDLon
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
    }

    fun routeLengthMeters(points: List<GeoPoint>, closedLoop: Boolean): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversineMeters(
                points[i - 1].lat, points[i - 1].lon,
                points[i].lat, points[i].lon
            )
        }
        if (closedLoop && points.size >= 3) {
            total += haversineMeters(
                points.last().lat, points.last().lon,
                points.first().lat, points.first().lon
            )
        }
        return total
    }

    fun formatDistance(meters: Double): String {
        return if (meters < 1000.0) {
            String.format(Locale.US, "%.0f m", meters)
        } else {
            String.format(Locale.US, "%.2f km", meters / 1000.0)
        }
    }

    fun formatPace(speedMps: Float): String {
        if (speedMps < 0.25f) return "--'--\"/km"
        val secPerKm = (1000.0 / speedMps).toInt().coerceIn(60, 5999)
        val min = secPerKm / 60
        val sec = secPerKm % 60
        return String.format(Locale.US, "%d'%02d\"/km", min, sec)
    }
}
