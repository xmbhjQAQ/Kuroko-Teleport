package com.kuroko.teleport.rf

import android.content.Context
import android.util.Base64
import com.kuroko.teleport.ipc.KurokoProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class CarrierPreset(
    val id: String,
    val label: String,
    val mcc: Int,
    val mnc: Int,
    val mncDigits: Int,
    val countryIso: String,
    val alpha: String
)

data class PinnedCell(
    val id: String = UUID.randomUUID().toString(),
    val rat: Int = 1, // 1 = LTE, 2 = NR
    val mcc: Int = 460,
    val mnc: Int = 0,
    val mncDigits: Int = 2,
    val tac: Long = 12345L,
    val ci: Long = 67890123L,
    val pci: Int = 101,
    val arfcn: Int = 38950,
    val band: Int = 40,
    val rsrpDbm: Int = -82,
    val followVirtual: Boolean = true,
    val pinLat: Double = 0.0,
    val pinLon: Double = 0.0
)

data class PinnedWifiAp(
    val id: String = UUID.randomUUID().toString(),
    val bssid: String = "02:1A:2B:3C:4D:5E",
    val ssid: String = "Kuroko-5G",
    val frequencyMhz: Int = 5180,
    val rssiDbm: Int = -54,
    val isConnectedAp: Boolean = false,
    val followVirtual: Boolean = true,
    val pinLat: Double = 0.0,
    val pinLon: Double = 0.0
)

data class PinnedBleBeacon(
    val id: String = UUID.randomUUID().toString(),
    val mac: String = "D4:3B:04:11:22:33",
    val deviceType: Int = 1, // 0 = GENERIC_BLE, 1 = IBEACON
    val name: String = "Office-Beacon",
    val uuidHex32: String = "FDA50693A4E24FB1AFCFC6EB07647825",
    val major: Int = 10001,
    val minor: Int = 1,
    val txPowerDbm: Int = -59,
    val followVirtual: Boolean = true,
    val pinLat: Double = 0.0,
    val pinLon: Double = 0.0
)

data class RfConfig(
    val cellAuto: Boolean = true,
    val wifiAuto: Boolean = true,
    val wifiSpoofConnected: Boolean = true,
    val bleAmbient: Boolean = false,
    val spoofSimCountry: Boolean = false,
    val cellPrefer5g: Boolean = true,
    val carrierAuto: Boolean = true,
    val carrierMcc: Int = 0,
    val carrierMnc: Int = 0,
    val carrierMncDigits: Int = 2,
    val carrierCountryIso: String = "",
    val carrierAlpha: String = "",
    val openCellIdToken: String = "",
    val pinnedCells: List<PinnedCell> = emptyList(),
    val pinnedWifis: List<PinnedWifiAp> = emptyList(),
    val pinnedBeacons: List<PinnedBleBeacon> = emptyList()
) {
    fun toRfFlags(): Int {
        var flags = 0
        if (cellAuto) flags = flags or KurokoProtocol.RF_FLAG_CELL_AUTO
        if (wifiAuto) flags = flags or KurokoProtocol.RF_FLAG_WIFI_AUTO
        if (wifiSpoofConnected) flags = flags or KurokoProtocol.RF_FLAG_WIFI_SPOOF_CONN
        if (bleAmbient) flags = flags or KurokoProtocol.RF_FLAG_BLE_AMBIENT
        if (spoofSimCountry) flags = flags or KurokoProtocol.RF_FLAG_SPOOF_SIM_COUNTRY
        if (cellPrefer5g) flags = flags or KurokoProtocol.RF_FLAG_CELL_PREFER_5G
        return flags
    }
}

object RfFormatting {
    val CARRIER_PRESETS: List<CarrierPreset> = listOf(
        CarrierPreset("cn_cmcc", "CN · CMCC", 460, 0, 2, "cn", "China Mobile"),
        CarrierPreset("cn_ct", "CN · Telecom", 460, 11, 2, "cn", "China Telecom"),
        CarrierPreset("cn_cu", "CN · Unicom", 460, 1, 2, "cn", "China Unicom"),
        CarrierPreset("hk_csl", "HK · CSL", 454, 0, 2, "hk", "CSL"),
        CarrierPreset("tw_cht", "TW · CHT", 466, 92, 2, "tw", "Chunghwa"),
        CarrierPreset("jp_dcm", "JP · docomo", 440, 10, 2, "jp", "NTT DOCOMO"),
        CarrierPreset("kr_skt", "KR · SKT", 450, 5, 2, "kr", "SKTelecom"),
        CarrierPreset("sg_st", "SG · Singtel", 525, 1, 2, "sg", "Singtel"),
        CarrierPreset("us_tmo", "US · T-Mobile", 310, 260, 3, "us", "T-Mobile"),
        CarrierPreset("uk_ee", "UK · EE", 234, 30, 2, "gb", "EE"),
        CarrierPreset("de_dt", "EU · Telekom", 262, 1, 2, "de", "Telekom.de"),
        CarrierPreset("au_tel", "AU · Telstra", 505, 1, 2, "au", "Telstra")
    )

    fun macToHex12(mac: String): String {
        val hex = mac.uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }
        return when {
            hex.length >= 12 -> hex.substring(0, 12)
            hex.isEmpty() -> "020000000001"
            else -> hex.padEnd(12, '0')
        }
    }

    fun normalizeMac(mac: String): String {
        val hex12 = macToHex12(mac)
        return hex12.chunked(2).joinToString(":")
    }

    fun normalizeUuidHex32(raw: String): String {
        val hex = raw.uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }
        return when {
            hex.length >= 32 -> hex.substring(0, 32)
            hex.isEmpty() -> "00000000000000000000000000000000"
            else -> hex.padEnd(32, '0')
        }
    }

    fun formatUuidDashed(uuidHex32: String): String {
        val h = normalizeUuidHex32(uuidHex32)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

    fun utf8ToHex(text: String, maxBytes: Int): String {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        val len = minOf(raw.size, maxBytes)
        if (len <= 0) return ""
        val sb = StringBuilder(len * 2)
        for (i in 0 until len) {
            val b = raw[i].toInt() and 0xFF
            sb.append(HEX_CHARS[b ushr 4])
            sb.append(HEX_CHARS[b and 0x0F])
        }
        return sb.toString()
    }

    fun alphaToBase64OrAuto(alpha: String): String {
        val trimmed = alpha.trim().take(15)
        if (trimmed.isEmpty()) return "AUTO"
        return Base64.encodeToString(
            trimmed.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
    }

    fun normalizeCountryIsoOrAuto(iso: String): String {
        val cleaned = iso.trim().lowercase(Locale.US).filter { it in 'a'..'z' }
        return if (cleaned.length == 2) cleaned else "AUTO"
    }

    fun formatMnc(mnc: Int, digits: Int): String {
        return if (digits >= 3) {
            String.format(Locale.US, "%03d", mnc.coerceIn(0, 999))
        } else {
            String.format(Locale.US, "%02d", mnc.coerceIn(0, 99))
        }
    }

    private const val HEX_CHARS = "0123456789abcdef"
}

class RfConfigStore(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): RfConfig {
        val raw = prefs.getString(KEY_RF_CONFIG_JSON, null)
        val savedToken = prefs.getString(KEY_OPENCELLID_TOKEN, "").orEmpty()
        if (raw.isNullOrBlank()) {
            return RfConfig(openCellIdToken = savedToken)
        }
        return parseConfigJson(raw, preserveToken = savedToken).getOrElse {
            RfConfig(openCellIdToken = savedToken)
        }
    }

    fun saveConfig(config: RfConfig) {
        val clean = sanitizeConfig(config)
        val json = encodeConfigObject(clean).toString()
        prefs.edit()
            .putString(KEY_RF_CONFIG_JSON, json)
            .putString(KEY_OPENCELLID_TOKEN, clean.openCellIdToken.trim())
            .apply()
    }

    /**
     * Exports RF configuration JSON for backup/sharing.
     * Deliberately EXCLUDES openCellIdToken so personal API keys are never exported.
     */
    fun exportConfigJson(config: RfConfig): String {
        val clean = sanitizeConfig(config)
        return encodeConfigObject(clean).apply {
            put("type", "kuroko_rf_config")
            put("version", 1)
        }.toString(2)
    }

    fun importConfigJson(rawJson: String, preserveToken: String): Result<RfConfig> {
        return parseConfigJson(rawJson, preserveToken = preserveToken)
    }

    private fun sanitizeConfig(c: RfConfig): RfConfig = c.copy(
        carrierMcc = c.carrierMcc.coerceIn(0, 999),
        carrierMnc = c.carrierMnc.coerceIn(0, 999),
        carrierMncDigits = if (c.carrierMncDigits == 3) 3 else 2,
        carrierCountryIso = c.carrierCountryIso.trim().lowercase(Locale.US).filter { it in 'a'..'z' }.take(2),
        carrierAlpha = c.carrierAlpha.trim().take(15),
        openCellIdToken = c.openCellIdToken.trim(),
        pinnedCells = c.pinnedCells.take(KurokoProtocol.MAX_PINNED_CELLS).map { sanitizeCell(it) },
        pinnedWifis = c.pinnedWifis.take(KurokoProtocol.MAX_PINNED_WIFIS).map { sanitizeWifi(it) },
        pinnedBeacons = c.pinnedBeacons.take(KurokoProtocol.MAX_PINNED_BEACONS).map { sanitizeBeacon(it) }
    )

    private fun sanitizeCell(cell: PinnedCell): PinnedCell = cell.copy(
        rat = if (cell.rat == 2) 2 else 1,
        mcc = cell.mcc.coerceIn(1, 999),
        mnc = cell.mnc.coerceIn(0, 999),
        mncDigits = if (cell.mncDigits == 3) 3 else 2,
        tac = cell.tac.coerceIn(0L, 16_777_215L),
        ci = cell.ci.coerceAtLeast(0L),
        pci = cell.pci.coerceIn(0, 1007),
        arfcn = cell.arfcn.coerceAtLeast(0),
        band = cell.band.coerceIn(0, 512),
        rsrpDbm = cell.rsrpDbm.coerceIn(-140, -44)
    )

    private fun sanitizeWifi(ap: PinnedWifiAp): PinnedWifiAp = ap.copy(
        bssid = RfFormatting.normalizeMac(ap.bssid),
        ssid = ap.ssid.trim().take(32).ifEmpty { "WiFi-AP" },
        frequencyMhz = ap.frequencyMhz.coerceIn(2412, 7125),
        rssiDbm = ap.rssiDbm.coerceIn(-100, -25)
    )

    private fun sanitizeBeacon(b: PinnedBleBeacon): PinnedBleBeacon = b.copy(
        mac = RfFormatting.normalizeMac(b.mac),
        deviceType = if (b.deviceType == 1) 1 else 0,
        name = b.name.trim().take(23),
        uuidHex32 = RfFormatting.normalizeUuidHex32(b.uuidHex32),
        major = b.major.coerceIn(0, 65535),
        minor = b.minor.coerceIn(0, 65535),
        txPowerDbm = b.txPowerDbm.coerceIn(-100, 10)
    )

    private fun encodeConfigObject(c: RfConfig): JSONObject {
        return JSONObject().apply {
            put("cellAuto", c.cellAuto)
            put("wifiAuto", c.wifiAuto)
            put("wifiSpoofConnected", c.wifiSpoofConnected)
            put("bleAmbient", c.bleAmbient)
            put("spoofSimCountry", c.spoofSimCountry)
            put("cellPrefer5g", c.cellPrefer5g)
            put("carrierAuto", c.carrierAuto)
            put("carrierMcc", c.carrierMcc)
            put("carrierMnc", c.carrierMnc)
            put("carrierMncDigits", c.carrierMncDigits)
            put("carrierCountryIso", c.carrierCountryIso)
            put("carrierAlpha", c.carrierAlpha)
            put("pinnedCells", JSONArray().apply {
                for (cell in c.pinnedCells) {
                    put(JSONObject().apply {
                        put("id", cell.id)
                        put("rat", cell.rat)
                        put("mcc", cell.mcc)
                        put("mnc", cell.mnc)
                        put("mncDigits", cell.mncDigits)
                        put("tac", cell.tac)
                        put("ci", cell.ci)
                        put("pci", cell.pci)
                        put("arfcn", cell.arfcn)
                        put("band", cell.band)
                        put("rsrpDbm", cell.rsrpDbm)
                        put("followVirtual", cell.followVirtual)
                        put("pinLat", cell.pinLat)
                        put("pinLon", cell.pinLon)
                    })
                }
            })
            put("pinnedWifis", JSONArray().apply {
                for (ap in c.pinnedWifis) {
                    put(JSONObject().apply {
                        put("id", ap.id)
                        put("bssid", ap.bssid)
                        put("ssid", ap.ssid)
                        put("frequencyMhz", ap.frequencyMhz)
                        put("rssiDbm", ap.rssiDbm)
                        put("isConnectedAp", ap.isConnectedAp)
                        put("followVirtual", ap.followVirtual)
                        put("pinLat", ap.pinLat)
                        put("pinLon", ap.pinLon)
                    })
                }
            })
            put("pinnedBeacons", JSONArray().apply {
                for (b in c.pinnedBeacons) {
                    put(JSONObject().apply {
                        put("id", b.id)
                        put("mac", b.mac)
                        put("deviceType", b.deviceType)
                        put("name", b.name)
                        put("uuidHex32", b.uuidHex32)
                        put("major", b.major)
                        put("minor", b.minor)
                        put("txPowerDbm", b.txPowerDbm)
                        put("followVirtual", b.followVirtual)
                        put("pinLat", b.pinLat)
                        put("pinLon", b.pinLon)
                    })
                }
            })
        }
    }

    private fun parseConfigJson(rawJson: String, preserveToken: String): Result<RfConfig> = runCatching {
        val trimmed = rawJson.trim()
        require(trimmed.isNotEmpty()) { "empty JSON" }
        val o = JSONObject(trimmed)
        val cellsArr = o.optJSONArray("pinnedCells") ?: JSONArray()
        val cells = mutableListOf<PinnedCell>()
        for (i in 0 until minOf(cellsArr.length(), KurokoProtocol.MAX_PINNED_CELLS)) {
            val c = cellsArr.optJSONObject(i) ?: continue
            cells.add(
                sanitizeCell(
                    PinnedCell(
                        id = c.optString("id").ifBlank { UUID.randomUUID().toString() },
                        rat = c.optInt("rat", 1),
                        mcc = c.optInt("mcc", 460),
                        mnc = c.optInt("mnc", 0),
                        mncDigits = c.optInt("mncDigits", 2),
                        tac = c.optLong("tac", 12345L),
                        ci = c.optLong("ci", 67890123L),
                        pci = c.optInt("pci", 101),
                        arfcn = c.optInt("arfcn", 38950),
                        band = c.optInt("band", 40),
                        rsrpDbm = c.optInt("rsrpDbm", -82),
                        followVirtual = c.optBoolean("followVirtual", true),
                        pinLat = c.optDouble("pinLat", 0.0),
                        pinLon = c.optDouble("pinLon", 0.0)
                    )
                )
            )
        }

        val wifisArr = o.optJSONArray("pinnedWifis") ?: JSONArray()
        val wifis = mutableListOf<PinnedWifiAp>()
        for (i in 0 until minOf(wifisArr.length(), KurokoProtocol.MAX_PINNED_WIFIS)) {
            val w = wifisArr.optJSONObject(i) ?: continue
            wifis.add(
                sanitizeWifi(
                    PinnedWifiAp(
                        id = w.optString("id").ifBlank { UUID.randomUUID().toString() },
                        bssid = w.optString("bssid", "02:1A:2B:3C:4D:5E"),
                        ssid = w.optString("ssid", "WiFi-AP"),
                        frequencyMhz = w.optInt("frequencyMhz", 5180),
                        rssiDbm = w.optInt("rssiDbm", -58),
                        isConnectedAp = w.optBoolean("isConnectedAp", false),
                        followVirtual = w.optBoolean("followVirtual", true),
                        pinLat = w.optDouble("pinLat", 0.0),
                        pinLon = w.optDouble("pinLon", 0.0)
                    )
                )
            )
        }

        val beaconsArr = o.optJSONArray("pinnedBeacons") ?: JSONArray()
        val beacons = mutableListOf<PinnedBleBeacon>()
        for (i in 0 until minOf(beaconsArr.length(), KurokoProtocol.MAX_PINNED_BEACONS)) {
            val b = beaconsArr.optJSONObject(i) ?: continue
            beacons.add(
                sanitizeBeacon(
                    PinnedBleBeacon(
                        id = b.optString("id").ifBlank { UUID.randomUUID().toString() },
                        mac = b.optString("mac", "D4:3B:04:11:22:33"),
                        deviceType = b.optInt("deviceType", 1),
                        name = b.optString("name", ""),
                        uuidHex32 = b.optString("uuidHex32", ""),
                        major = b.optInt("major", 1),
                        minor = b.optInt("minor", 1),
                        txPowerDbm = b.optInt("txPowerDbm", -59),
                        followVirtual = b.optBoolean("followVirtual", true),
                        pinLat = b.optDouble("pinLat", 0.0),
                        pinLon = b.optDouble("pinLon", 0.0)
                    )
                )
            )
        }

        sanitizeConfig(
            RfConfig(
                cellAuto = o.optBoolean("cellAuto", true),
                wifiAuto = o.optBoolean("wifiAuto", true),
                wifiSpoofConnected = o.optBoolean("wifiSpoofConnected", true),
                bleAmbient = o.optBoolean("bleAmbient", false),
                spoofSimCountry = o.optBoolean("spoofSimCountry", false),
                cellPrefer5g = o.optBoolean("cellPrefer5g", true),
                carrierAuto = o.optBoolean("carrierAuto", true),
                carrierMcc = o.optInt("carrierMcc", 0),
                carrierMnc = o.optInt("carrierMnc", 0),
                carrierMncDigits = o.optInt("carrierMncDigits", 2),
                carrierCountryIso = o.optString("carrierCountryIso", ""),
                carrierAlpha = o.optString("carrierAlpha", ""),
                openCellIdToken = preserveToken,
                pinnedCells = cells,
                pinnedWifis = wifis,
                pinnedBeacons = beacons
            )
        )
    }

    companion object {
        private const val PREFS_NAME = "kuroko_rf_v1"
        private const val KEY_RF_CONFIG_JSON = "rf_config_json"
        private const val KEY_OPENCELLID_TOKEN = "opencellid_token"
    }
}
