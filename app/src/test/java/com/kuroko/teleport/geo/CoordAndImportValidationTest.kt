package com.kuroko.teleport.geo

import com.kuroko.teleport.ui.PointStore
import com.kuroko.teleport.ui.RouteStore
import com.kuroko.teleport.ui.SavedPoint
import com.kuroko.teleport.ui.SavedRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordAndImportValidationTest {

    @Test
    fun isValidWgs84EnforcesRangeAndFiniteness() {
        assertTrue(CoordTransform.isValidWgs84(0.0, 0.0))
        assertTrue(CoordTransform.isValidWgs84(-90.0, -180.0, 0.0))
        assertTrue(CoordTransform.isValidWgs84(90.0, 180.0, -100.0))
        assertTrue(CoordTransform.isValidWgs84(39.9042, 116.4074, 45.0))

        assertFalse(CoordTransform.isValidWgs84(91.0, 0.0))
        assertFalse(CoordTransform.isValidWgs84(-90.001, 0.0))
        assertFalse(CoordTransform.isValidWgs84(0.0, 181.0))
        assertFalse(CoordTransform.isValidWgs84(0.0, -180.001))
        assertFalse(CoordTransform.isValidWgs84(Double.NaN, 116.4))
        assertFalse(CoordTransform.isValidWgs84(39.9, Double.NaN))
        assertFalse(CoordTransform.isValidWgs84(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(CoordTransform.isValidWgs84(0.0, Double.NEGATIVE_INFINITY))
        assertFalse(CoordTransform.isValidWgs84(39.9, 116.4, Double.NaN))
        assertFalse(CoordTransform.isValidWgs84(39.9, 116.4, Double.POSITIVE_INFINITY))
    }

    @Test
    fun strictRouteImportRejectsOutOfRangeNaNSwappedAndZeroLengthRoutes() {
        val outOfRangeLat = """{"routes":[{"name":"bad-lat","waypoints":[{"lat":91.0,"lon":0.0},{"lat":39.9,"lon":116.4}]}]}"""
        val outOfRangeLon = """{"routes":[{"name":"bad-lon","waypoints":[{"lat":39.9,"lon":181.0},{"lat":39.9,"lon":116.4}]}]}"""
        val nanCoord = """{"routes":[{"name":"nan","waypoints":[{"lat":"NaN","lon":116.4},{"lat":39.9,"lon":116.5}]}]}"""
        val swappedCoords = """{"routes":[{"name":"swapped","waypoints":[{"lat":116.4074,"lon":39.9042},{"lat":116.4174,"lon":39.9142}]}]}"""
        val zeroLengthRoute = """{"routes":[{"name":"zero","waypoints":[{"lat":39.9042,"lon":116.4074},{"lat":39.9042,"lon":116.4074}]}]}"""
        val singlePointRoute = """{"routes":[{"name":"single","waypoints":[{"lat":39.9042,"lon":116.4074}]}]}"""

        assertTrue(RouteStore.parseRoutesJson(outOfRangeLat, strictImport = true).isFailure)
        assertTrue(RouteStore.parseRoutesJson(outOfRangeLon, strictImport = true).isFailure)
        assertTrue(RouteStore.parseRoutesJson(nanCoord, strictImport = true).isFailure)
        assertTrue(RouteStore.parseRoutesJson(swappedCoords, strictImport = true).isFailure)
        assertTrue(RouteStore.parseRoutesJson(zeroLengthRoute, strictImport = true).isFailure)
        assertTrue(RouteStore.parseRoutesJson(singlePointRoute, strictImport = true).isFailure)
    }

    @Test
    fun strictRouteImportAcceptsValidAndAntimeridianRoutes() {
        val validRoute = SavedRoute(
            id = "r-valid",
            name = "Morning Loop",
            waypoints = listOf(
                GeoPoint(39.9042, 116.4074, 45.0),
                GeoPoint(39.9092, 116.4124, 48.0),
                GeoPoint(39.9042, 116.4174, 46.0)
            ),
            loop = true
        )
        val exported = RouteStore.exportRoutesJson(listOf(validRoute))
        val parsed = RouteStore.parseRoutesJson(exported, strictImport = true).getOrThrow()
        assertEquals(1, parsed.size)
        assertEquals("r-valid", parsed[0].id)
        assertEquals(3, parsed[0].waypoints.size)

        val antimeridianJson = """
            {
              "type": "kuroko_route",
              "version": 1,
              "routes": [
                {
                  "id": "r-anti",
                  "name": "Antimeridian Crossing",
                  "loop": false,
                  "waypoints": [
                    {"lat": 0.0, "lon": 179.99, "alt": 10.0},
                    {"lat": 0.0, "lon": -179.99, "alt": 10.0}
                  ]
                }
              ]
            }
        """.trimIndent()
        val antiParsed = RouteStore.parseRoutesJson(antimeridianJson, strictImport = true).getOrThrow()
        assertEquals(1, antiParsed.size)
        val lengthM = CoordTransform.routeLengthMeters(antiParsed[0].waypoints, antiParsed[0].loop)
        assertEquals(2226.39, lengthM, 25.0)
    }

    @Test
    fun lenientRouteLoadFiltersInvalidWaypointsSafely() {
        val raw = """
            [
              {
                "id": "r-mixed",
                "name": "Stored Route",
                "waypoints": [
                  {"lat": 39.9042, "lon": 116.4074},
                  {"lat": 95.0, "lon": 116.4074},
                  {"lat": 39.9092, "lon": 116.4124}
                ]
              }
            ]
        """.trimIndent()
        val loaded = RouteStore.parseRoutesJson(raw, strictImport = false).getOrThrow()
        assertEquals(1, loaded.size)
        assertEquals(2, loaded[0].waypoints.size)
    }

    @Test
    fun strictPointImportRejectsInvalidCoordsAndAcceptsValidExport() {
        val badLat = """{"points":[{"name":"bad-lat","lat":91.0,"lon":0.0}]}"""
        val badLon = """{"points":[{"name":"bad-lon","lat":0.0,"lon":181.0}]}"""
        val nanPt = """{"points":[{"name":"nan","lat":"NaN","lon":0.0}]}"""
        val swappedPt = """{"points":[{"name":"swapped","lat":116.4074,"lon":39.9042}]}"""
        val mixedPts = """{"points":[{"name":"ok","lat":39.9,"lon":116.4},{"name":"bad","lat":95.0,"lon":116.4}]}"""

        assertTrue(PointStore.parsePointsJson(badLat, strictImport = true).isFailure)
        assertTrue(PointStore.parsePointsJson(badLon, strictImport = true).isFailure)
        assertTrue(PointStore.parsePointsJson(nanPt, strictImport = true).isFailure)
        assertTrue(PointStore.parsePointsJson(swappedPt, strictImport = true).isFailure)
        assertTrue(PointStore.parsePointsJson(mixedPts, strictImport = true).isFailure)

        // Lenient mode (loadPoints) filters bad points without losing valid ones
        val lenient = PointStore.parsePointsJson(mixedPts, strictImport = false).getOrThrow()
        assertEquals(1, lenient.size)
        assertEquals("ok", lenient[0].name)

        // Valid export -> strict import round-trip
        val validPoint = SavedPoint(
            id = "p-1",
            name = "Office",
            latWgs84 = 35.6812,
            lonWgs84 = 139.7671,
            altM = 30.0,
            jitterM = 2.0f
        )
        val exported = PointStore.exportPointsJson(listOf(validPoint))
        val parsed = PointStore.parsePointsJson(exported, strictImport = true).getOrThrow()
        assertEquals(1, parsed.size)
        assertEquals("p-1", parsed[0].id)
        assertEquals(35.6812, parsed[0].latWgs84, 1e-6)
        assertEquals(139.7671, parsed[0].lonWgs84, 1e-6)
    }

    @Test
    fun nonFiniteMetadataFieldsFallBackToDefaultsAndExportSafely() {
        val rawPoint = """
            {
              "points": [
                {"id": "p-nan", "name": "NaN Jitter", "lat": 39.9042, "lon": 116.4074, "jitterM": "NaN"},
                {"id": "p-inf", "name": "Inf Jitter", "lat": 39.9142, "lon": 116.4174, "jitterM": Infinity}
              ]
            }
        """.trimIndent()
        val parsedPoints = PointStore.parsePointsJson(rawPoint, strictImport = true).getOrThrow()
        assertEquals(2, parsedPoints.size)
        assertEquals(1.5f, parsedPoints[0].jitterM, 1e-6f)
        assertEquals(1.5f, parsedPoints[1].jitterM, 1e-6f)
        val reExportedPoints = PointStore.exportPointsJson(parsedPoints)
        assertEquals(2, PointStore.parsePointsJson(reExportedPoints, strictImport = true).getOrThrow().size)

        val rawRoute = """
            {
              "routes": [
                {
                  "id": "r-nonfinite",
                  "name": "NonFinite Meta",
                  "targetKm": "Infinity",
                  "speedMps": NaN,
                  "paceVar": "NaN",
                  "jitterM": "-Infinity",
                  "waypoints": [
                    {"lat": 39.9042, "lon": 116.4074},
                    {"lat": 39.9092, "lon": 116.4124}
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsedRoutes = RouteStore.parseRoutesJson(rawRoute, strictImport = true).getOrThrow()
        assertEquals(1, parsedRoutes.size)
        assertEquals(0.0f, parsedRoutes[0].targetDistanceKm, 1e-6f)
        assertEquals(2.8f, parsedRoutes[0].speedMps, 1e-6f)
        assertEquals(0.08f, parsedRoutes[0].paceVarRatio, 1e-6f)
        assertEquals(2.0f, parsedRoutes[0].jitterM, 1e-6f)
        val reExportedRoutes = RouteStore.exportRoutesJson(parsedRoutes)
        assertEquals(1, RouteStore.parseRoutesJson(reExportedRoutes, strictImport = true).getOrThrow().size)
    }
}
