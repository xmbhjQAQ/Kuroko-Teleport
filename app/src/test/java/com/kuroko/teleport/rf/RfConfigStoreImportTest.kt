package com.kuroko.teleport.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RfConfigStoreImportTest {

    @Test
    fun strictImportRejectsEmptyUnrecognizedAndForeignTypeJson() {
        assertTrue(RfConfigStore.parseConfigJson("", strictImport = true).isFailure)
        assertTrue(RfConfigStore.parseConfigJson("{}", strictImport = true).isFailure)
        assertTrue(RfConfigStore.parseConfigJson("""{"foo":1}""", strictImport = true).isFailure)
        assertTrue(RfConfigStore.parseConfigJson("""{"foo":"bar"}""", strictImport = true).isFailure)
        assertTrue(
            RfConfigStore.parseConfigJson(
                """{"type":"kuroko_route","version":1,"routes":[]}""",
                strictImport = true
            ).isFailure
        )
        assertTrue(
            RfConfigStore.parseConfigJson(
                """{"type":"kuroko_points","version":1,"points":[]}""",
                strictImport = true
            ).isFailure
        )
        assertTrue(
            RfConfigStore.parseConfigJson(
                """{"type":"","cellAuto":true}""",
                strictImport = true
            ).isFailure
        )
        assertTrue(
            RfConfigStore.parseConfigJson(
                """{"type":"other","cellAuto":true}""",
                strictImport = true
            ).isFailure
        )
    }

    @Test
    fun strictImportRejectsWrongPinnedArrayTypes() {
        assertTrue(
            RfConfigStore.parseConfigJson(
                """{"type":"kuroko_rf_config","pinnedCells":{}}""",
                strictImport = true
            ).isFailure
        )
        assertTrue(
            RfConfigStore.parseConfigJson(
                """{"type":"kuroko_rf_config","pinnedWifis":"not_an_array"}""",
                strictImport = true
            ).isFailure
        )
        assertTrue(
            RfConfigStore.parseConfigJson(
                """{"type":"kuroko_rf_config","pinnedBeacons":123}""",
                strictImport = true
            ).isFailure
        )
    }

    @Test
    fun strictImportAcceptsExportedRoundTripAndPreservesToken() {
        val original = RfConfig(
            cellAuto = false,
            wifiAuto = true,
            wifiSpoofConnected = false,
            bleAmbient = true,
            spoofSimCountry = true,
            cellPrefer5g = false,
            carrierAuto = false,
            carrierMcc = 440,
            carrierMnc = 10,
            carrierMncDigits = 2,
            carrierCountryIso = "jp",
            carrierAlpha = "NTT DOCOMO",
            openCellIdToken = "secret-api-token",
            pinnedCells = listOf(
                PinnedCell(
                    id = "cell-1",
                    rat = 2,
                    mcc = 440,
                    mnc = 10,
                    mncDigits = 2,
                    tac = 2222L,
                    ci = 987654321L,
                    pci = 321,
                    arfcn = 632628,
                    band = 78,
                    rsrpDbm = -78,
                    followVirtual = false,
                    pinLat = 35.6812,
                    pinLon = 139.7671
                )
            ),
            pinnedWifis = listOf(
                PinnedWifiAp(
                    id = "wifi-1",
                    bssid = "aa:bb:cc:dd:ee:ff",
                    ssid = "Tokyo-5G",
                    frequencyMhz = 5240,
                    rssiDbm = -52,
                    isConnectedAp = true,
                    followVirtual = false,
                    pinLat = 35.6812,
                    pinLon = 139.7671
                )
            ),
            pinnedBeacons = listOf(
                PinnedBleBeacon(
                    id = "ble-1",
                    mac = "11:22:33:44:55:66",
                    deviceType = 1,
                    name = "Gate-01",
                    uuidHex32 = "fda50693a4e24fb1afcfc6eb07647825",
                    major = 101,
                    minor = 202,
                    txPowerDbm = -59,
                    followVirtual = false,
                    pinLat = 35.6812,
                    pinLon = 139.7671
                )
            )
        )

        val exported = RfConfigStore.exportConfigJson(original)
        assertFalse(exported.contains("secret-api-token"))

        val imported = RfConfigStore.parseConfigJson(
            rawJson = exported,
            preserveToken = "kept-local-token",
            strictImport = true
        ).getOrThrow()

        assertEquals("kept-local-token", imported.openCellIdToken)
        assertEquals(false, imported.cellAuto)
        assertEquals(true, imported.bleAmbient)
        assertEquals(440, imported.carrierMcc)
        assertEquals("jp", imported.carrierCountryIso)
        assertEquals(1, imported.pinnedCells.size)
        assertEquals(35.6812, imported.pinnedCells[0].pinLat, 1e-6)
        assertEquals(139.7671, imported.pinnedCells[0].pinLon, 1e-6)
        assertEquals(1, imported.pinnedWifis.size)
        assertEquals("AA:BB:CC:DD:EE:FF", imported.pinnedWifis[0].bssid)
        assertEquals(1, imported.pinnedBeacons.size)
        assertEquals("FDA50693A4E24FB1AFCFC6EB07647825", imported.pinnedBeacons[0].uuidHex32)
    }

    @Test
    fun strictImportAcceptsPartialValidConfigAndLenientAllowsEmptyObject() {
        val partial = RfConfigStore.parseConfigJson(
            rawJson = """{"cellAuto":false,"carrierAuto":false,"carrierMcc":310,"carrierMnc":260,"carrierMncDigits":3}""",
            preserveToken = "tok",
            strictImport = true
        ).getOrThrow()

        assertFalse(partial.cellAuto)
        assertFalse(partial.carrierAuto)
        assertEquals(310, partial.carrierMcc)
        assertEquals(260, partial.carrierMnc)
        assertEquals(3, partial.carrierMncDigits)
        assertEquals("tok", partial.openCellIdToken)

        // loadConfig() uses lenient mode (strictImport = false)
        val lenient = RfConfigStore.parseConfigJson("{}", preserveToken = "tok", strictImport = false)
        assertTrue(lenient.isSuccess)
    }

    @Test
    fun invalidPinCoordinatesAreSanitizedToZero() {
        val raw = """
            {
              "type": "kuroko_rf_config",
              "pinnedCells": [
                {"id": "c1", "pinLat": 95.0, "pinLon": 116.4, "followVirtual": false},
                {"id": "c2", "pinLat": 39.9042, "pinLon": 116.4074, "followVirtual": false}
              ],
              "pinnedWifis": [
                {"id": "w1", "pinLat": 39.9, "pinLon": -181.5, "followVirtual": false}
              ],
              "pinnedBeacons": [
                {"id": "b1", "pinLat": "NaN", "pinLon": 10.0, "followVirtual": false}
              ]
            }
        """.trimIndent()

        val cfg = RfConfigStore.parseConfigJson(raw, strictImport = true).getOrThrow()
        assertEquals(0.0, cfg.pinnedCells[0].pinLat, 0.0)
        assertEquals(0.0, cfg.pinnedCells[0].pinLon, 0.0)
        assertEquals(39.9042, cfg.pinnedCells[1].pinLat, 1e-6)
        assertEquals(116.4074, cfg.pinnedCells[1].pinLon, 1e-6)
        assertEquals(0.0, cfg.pinnedWifis[0].pinLat, 0.0)
        assertEquals(0.0, cfg.pinnedWifis[0].pinLon, 0.0)
        assertEquals(0.0, cfg.pinnedBeacons[0].pinLat, 0.0)
        assertEquals(0.0, cfg.pinnedBeacons[0].pinLon, 0.0)
    }
}
