package com.kuroko.teleport.rf

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kuroko.teleport.geo.CoordTransform
import com.kuroko.teleport.ipc.KurokoProtocol
import java.nio.charset.StandardCharsets
import java.util.Base64
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

    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        val sb = StringBuilder(minOf(text.length, maxBytes))
        var byteCount = 0
        var index = 0
        val len = text.length
        while (index < len) {
            val cp = Character.codePointAt(text, index)
            val charCount = Character.charCount(cp)
            if (charCount == 1 && Character.isSurrogate(text[index])) {
                index += 1
                continue
            }
            val cpBytes = when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (byteCount + cpBytes > maxBytes) {
                break
            }
            sb.appendCodePoint(cp)
            byteCount += cpBytes
            index += charCount
        }
        return sb.toString()
    }

    fun utf8ToHex(text: String, maxBytes: Int): String {
        val safe = truncateUtf8(text, maxBytes)
        val raw = safe.toByteArray(StandardCharsets.UTF_8)
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.size * 2)
        for (bByte in raw) {
            val b = bByte.toInt() and 0xFF
            sb.append(HEX_CHARS[b ushr 4])
            sb.append(HEX_CHARS[b and 0x0F])
        }
        return sb.toString()
    }

    fun alphaToBase64OrAuto(alpha: String): String {
        val trimmed = truncateUtf8(alpha.trim(), 15)
        if (trimmed.isEmpty()) return "AUTO"
        return Base64.getEncoder().encodeToString(
            trimmed.toByteArray(StandardCharsets.UTF_8)
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

enum class GmsToggleFeedback {
    ENABLED,
    DISABLED,
    FAILED
}

data class GmsToggleOutcome(
    val confirmedState: Boolean,
    val succeeded: Boolean,
    val feedback: GmsToggleFeedback
)

suspend fun executeGmsToggle(
    prevConfirmed: Boolean,
    requestedEnabled: Boolean,
    onSetGmsEnabled: suspend (Boolean) -> Boolean
): GmsToggleOutcome {
    val ok = runCatching { onSetGmsEnabled(requestedEnabled) }.getOrDefault(false)
    return if (ok) {
        GmsToggleOutcome(
            confirmedState = requestedEnabled,
            succeeded = true,
            feedback = if (requestedEnabled) GmsToggleFeedback.ENABLED else GmsToggleFeedback.DISABLED
        )
    } else {
        GmsToggleOutcome(
            confirmedState = prevConfirmed,
            succeeded = false,
            feedback = GmsToggleFeedback.FAILED
        )
    }
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
        return parseConfigJson(raw, preserveToken = savedToken, strictImport = false).getOrElse {
            RfConfig(openCellIdToken = savedToken)
        }
    }

    fun saveConfig(config: RfConfig) {
        val clean = sanitizeConfig(config)
        val json = compactGson.toJson(encodeConfigObject(clean))
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
        return Companion.exportConfigJson(config)
    }

    fun importConfigJson(rawJson: String, preserveToken: String): Result<RfConfig> {
        return parseConfigJson(rawJson, preserveToken = preserveToken, strictImport = true)
    }

    companion object {
        private const val PREFS_NAME = "kuroko_rf_v1"
        private const val KEY_RF_CONFIG_JSON = "rf_config_json"
        private const val KEY_OPENCELLID_TOKEN = "opencellid_token"
        private const val RF_CONFIG_TYPE = "kuroko_rf_config"

        private val KNOWN_CONFIG_KEYS = setOf(
            "cellAuto",
            "wifiAuto",
            "wifiSpoofConnected",
            "bleAmbient",
            "spoofSimCountry",
            "cellPrefer5g",
            "carrierAuto",
            "carrierMcc",
            "carrierMnc",
            "carrierMncDigits",
            "carrierCountryIso",
            "carrierAlpha",
            "pinnedCells",
            "pinnedWifis",
            "pinnedBeacons"
        )

        private val compactGson = GsonBuilder().disableHtmlEscaping().create()
        private val prettyGson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

        fun exportConfigJson(config: RfConfig): String {
            val clean = sanitizeConfig(config)
            val obj = encodeConfigObject(clean)
            obj.addProperty("type", RF_CONFIG_TYPE)
            obj.addProperty("version", 1)
            return prettyGson.toJson(obj)
        }

        fun parseConfigJson(
            rawJson: String,
            preserveToken: String = "",
            strictImport: Boolean = false
        ): Result<RfConfig> = runCatching {
            val trimmed = rawJson.trim()
            require(trimmed.isNotEmpty()) { "empty JSON" }
            val parsedElem = JsonParser.parseString(trimmed)
            require(parsedElem.isJsonObject) { "RF config JSON must be an object" }
            val o = parsedElem.asJsonObject

            if (strictImport) {
                var hasValidType = false
                if (o.has("type")) {
                    val typeElem = o.get("type")
                    val typeVal = if (typeElem != null && typeElem.isJsonPrimitive && typeElem.asJsonPrimitive.isString) {
                        typeElem.asString
                    } else {
                        null
                    }
                    require(typeVal == RF_CONFIG_TYPE) {
                        "Unsupported JSON type: ${typeVal ?: "invalid"}"
                    }
                    hasValidType = true
                }
                val hasKnownKey = KNOWN_CONFIG_KEYS.any { o.has(it) }
                require(hasValidType || hasKnownKey) {
                    "Unrecognized RF config JSON"
                }
                for (arrKey in listOf("pinnedCells", "pinnedWifis", "pinnedBeacons")) {
                    if (o.has(arrKey) && !o.get(arrKey).isJsonNull) {
                        require(o.get(arrKey).isJsonArray) {
                            "Field '$arrKey' must be a JSON array"
                        }
                    }
                }
            }

            val cellsArr = o.optJsonArray("pinnedCells")
            val cells = mutableListOf<PinnedCell>()
            if (cellsArr != null) {
                for (i in 0 until minOf(cellsArr.size(), KurokoProtocol.MAX_PINNED_CELLS)) {
                    val c = cellsArr.get(i)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    cells.add(
                        sanitizeCell(
                            PinnedCell(
                                id = c.optStr("id").ifBlank { UUID.randomUUID().toString() },
                                rat = c.optIntSafe("rat", 1),
                                mcc = c.optIntSafe("mcc", 460),
                                mnc = c.optIntSafe("mnc", 0),
                                mncDigits = c.optIntSafe("mncDigits", 2),
                                tac = c.optLongSafe("tac", 12345L),
                                ci = c.optLongSafe("ci", 67890123L),
                                pci = c.optIntSafe("pci", 101),
                                arfcn = c.optIntSafe("arfcn", 38950),
                                band = c.optIntSafe("band", 40),
                                rsrpDbm = c.optIntSafe("rsrpDbm", -82),
                                followVirtual = c.optBool("followVirtual", true),
                                pinLat = c.optPinDouble("pinLat"),
                                pinLon = c.optPinDouble("pinLon")
                            )
                        )
                    )
                }
            }

            val wifisArr = o.optJsonArray("pinnedWifis")
            val wifis = mutableListOf<PinnedWifiAp>()
            if (wifisArr != null) {
                for (i in 0 until minOf(wifisArr.size(), KurokoProtocol.MAX_PINNED_WIFIS)) {
                    val w = wifisArr.get(i)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    wifis.add(
                        sanitizeWifi(
                            PinnedWifiAp(
                                id = w.optStr("id").ifBlank { UUID.randomUUID().toString() },
                                bssid = w.optStr("bssid", "02:1A:2B:3C:4D:5E"),
                                ssid = w.optStr("ssid", "WiFi-AP"),
                                frequencyMhz = w.optIntSafe("frequencyMhz", 5180),
                                rssiDbm = w.optIntSafe("rssiDbm", -58),
                                isConnectedAp = w.optBool("isConnectedAp", false),
                                followVirtual = w.optBool("followVirtual", true),
                                pinLat = w.optPinDouble("pinLat"),
                                pinLon = w.optPinDouble("pinLon")
                            )
                        )
                    )
                }
            }

            val beaconsArr = o.optJsonArray("pinnedBeacons")
            val beacons = mutableListOf<PinnedBleBeacon>()
            if (beaconsArr != null) {
                for (i in 0 until minOf(beaconsArr.size(), KurokoProtocol.MAX_PINNED_BEACONS)) {
                    val b = beaconsArr.get(i)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    beacons.add(
                        sanitizeBeacon(
                            PinnedBleBeacon(
                                id = b.optStr("id").ifBlank { UUID.randomUUID().toString() },
                                mac = b.optStr("mac", "D4:3B:04:11:22:33"),
                                deviceType = b.optIntSafe("deviceType", 1),
                                name = b.optStr("name", ""),
                                uuidHex32 = b.optStr("uuidHex32", ""),
                                major = b.optIntSafe("major", 1),
                                minor = b.optIntSafe("minor", 1),
                                txPowerDbm = b.optIntSafe("txPowerDbm", -59),
                                followVirtual = b.optBool("followVirtual", true),
                                pinLat = b.optPinDouble("pinLat"),
                                pinLon = b.optPinDouble("pinLon")
                            )
                        )
                    )
                }
            }

            sanitizeConfig(
                RfConfig(
                    cellAuto = o.optBool("cellAuto", true),
                    wifiAuto = o.optBool("wifiAuto", true),
                    wifiSpoofConnected = o.optBool("wifiSpoofConnected", true),
                    bleAmbient = o.optBool("bleAmbient", false),
                    spoofSimCountry = o.optBool("spoofSimCountry", false),
                    cellPrefer5g = o.optBool("cellPrefer5g", true),
                    carrierAuto = o.optBool("carrierAuto", true),
                    carrierMcc = o.optIntSafe("carrierMcc", 0),
                    carrierMnc = o.optIntSafe("carrierMnc", 0),
                    carrierMncDigits = o.optIntSafe("carrierMncDigits", 2),
                    carrierCountryIso = o.optStr("carrierCountryIso", ""),
                    carrierAlpha = o.optStr("carrierAlpha", ""),
                    openCellIdToken = preserveToken,
                    pinnedCells = cells,
                    pinnedWifis = wifis,
                    pinnedBeacons = beacons
                )
            )
        }

        internal fun sanitizeConfig(c: RfConfig): RfConfig = c.copy(
            carrierMcc = c.carrierMcc.coerceIn(0, 999),
            carrierMnc = c.carrierMnc.coerceIn(0, 999),
            carrierMncDigits = if (c.carrierMncDigits == 3) 3 else 2,
            carrierCountryIso = c.carrierCountryIso.trim().lowercase(Locale.US).filter { it in 'a'..'z' }.take(2),
            carrierAlpha = RfFormatting.truncateUtf8(c.carrierAlpha.trim(), 15),
            openCellIdToken = c.openCellIdToken.trim(),
            pinnedCells = c.pinnedCells.take(KurokoProtocol.MAX_PINNED_CELLS).map { sanitizeCell(it) },
            pinnedWifis = c.pinnedWifis.take(KurokoProtocol.MAX_PINNED_WIFIS).map { sanitizeWifi(it) },
            pinnedBeacons = c.pinnedBeacons.take(KurokoProtocol.MAX_PINNED_BEACONS).map { sanitizeBeacon(it) }
        )

        internal fun sanitizeCell(cell: PinnedCell): PinnedCell {
            val validPin = CoordTransform.isValidWgs84(cell.pinLat, cell.pinLon)
            return cell.copy(
                rat = if (cell.rat == 2) 2 else 1,
                mcc = cell.mcc.coerceIn(1, 999),
                mnc = cell.mnc.coerceIn(0, 999),
                mncDigits = if (cell.mncDigits == 3) 3 else 2,
                tac = cell.tac.coerceIn(0L, 16_777_215L),
                ci = cell.ci.coerceAtLeast(0L),
                pci = cell.pci.coerceIn(0, 1007),
                arfcn = cell.arfcn.coerceAtLeast(0),
                band = cell.band.coerceIn(0, 512),
                rsrpDbm = cell.rsrpDbm.coerceIn(-140, -44),
                pinLat = if (validPin) cell.pinLat else 0.0,
                pinLon = if (validPin) cell.pinLon else 0.0
            )
        }

        internal fun sanitizeWifi(ap: PinnedWifiAp): PinnedWifiAp {
            val validPin = CoordTransform.isValidWgs84(ap.pinLat, ap.pinLon)
            return ap.copy(
                bssid = RfFormatting.normalizeMac(ap.bssid),
                ssid = RfFormatting.truncateUtf8(ap.ssid.trim(), 32).ifEmpty { "WiFi-AP" },
                frequencyMhz = ap.frequencyMhz.coerceIn(2412, 7125),
                rssiDbm = ap.rssiDbm.coerceIn(-100, -25),
                pinLat = if (validPin) ap.pinLat else 0.0,
                pinLon = if (validPin) ap.pinLon else 0.0
            )
        }

        internal fun sanitizeBeacon(b: PinnedBleBeacon): PinnedBleBeacon {
            val validPin = CoordTransform.isValidWgs84(b.pinLat, b.pinLon)
            return b.copy(
                mac = RfFormatting.normalizeMac(b.mac),
                deviceType = if (b.deviceType == 1) 1 else 0,
                name = RfFormatting.truncateUtf8(b.name.trim(), 24),
                uuidHex32 = RfFormatting.normalizeUuidHex32(b.uuidHex32),
                major = b.major.coerceIn(0, 65535),
                minor = b.minor.coerceIn(0, 65535),
                txPowerDbm = b.txPowerDbm.coerceIn(-100, 10),
                pinLat = if (validPin) b.pinLat else 0.0,
                pinLon = if (validPin) b.pinLon else 0.0
            )
        }

        private fun encodeConfigObject(c: RfConfig): JsonObject {
            return JsonObject().apply {
                addProperty("cellAuto", c.cellAuto)
                addProperty("wifiAuto", c.wifiAuto)
                addProperty("wifiSpoofConnected", c.wifiSpoofConnected)
                addProperty("bleAmbient", c.bleAmbient)
                addProperty("spoofSimCountry", c.spoofSimCountry)
                addProperty("cellPrefer5g", c.cellPrefer5g)
                addProperty("carrierAuto", c.carrierAuto)
                addProperty("carrierMcc", c.carrierMcc)
                addProperty("carrierMnc", c.carrierMnc)
                addProperty("carrierMncDigits", c.carrierMncDigits)
                addProperty("carrierCountryIso", c.carrierCountryIso)
                addProperty("carrierAlpha", c.carrierAlpha)
                add("pinnedCells", JsonArray().apply {
                    for (cell in c.pinnedCells) {
                        add(JsonObject().apply {
                            addProperty("id", cell.id)
                            addProperty("rat", cell.rat)
                            addProperty("mcc", cell.mcc)
                            addProperty("mnc", cell.mnc)
                            addProperty("mncDigits", cell.mncDigits)
                            addProperty("tac", cell.tac)
                            addProperty("ci", cell.ci)
                            addProperty("pci", cell.pci)
                            addProperty("arfcn", cell.arfcn)
                            addProperty("band", cell.band)
                            addProperty("rsrpDbm", cell.rsrpDbm)
                            addProperty("followVirtual", cell.followVirtual)
                            addProperty("pinLat", cell.pinLat)
                            addProperty("pinLon", cell.pinLon)
                        })
                    }
                })
                add("pinnedWifis", JsonArray().apply {
                    for (ap in c.pinnedWifis) {
                        add(JsonObject().apply {
                            addProperty("id", ap.id)
                            addProperty("bssid", ap.bssid)
                            addProperty("ssid", ap.ssid)
                            addProperty("frequencyMhz", ap.frequencyMhz)
                            addProperty("rssiDbm", ap.rssiDbm)
                            addProperty("isConnectedAp", ap.isConnectedAp)
                            addProperty("followVirtual", ap.followVirtual)
                            addProperty("pinLat", ap.pinLat)
                            addProperty("pinLon", ap.pinLon)
                        })
                    }
                })
                add("pinnedBeacons", JsonArray().apply {
                    for (b in c.pinnedBeacons) {
                        add(JsonObject().apply {
                            addProperty("id", b.id)
                            addProperty("mac", b.mac)
                            addProperty("deviceType", b.deviceType)
                            addProperty("name", b.name)
                            addProperty("uuidHex32", b.uuidHex32)
                            addProperty("major", b.major)
                            addProperty("minor", b.minor)
                            addProperty("txPowerDbm", b.txPowerDbm)
                            addProperty("followVirtual", b.followVirtual)
                            addProperty("pinLat", b.pinLat)
                            addProperty("pinLon", b.pinLon)
                        })
                    }
                })
            }
        }

        private fun JsonObject.optJsonArray(key: String): JsonArray? {
            val elem = get(key) ?: return null
            return if (elem.isJsonArray) elem.asJsonArray else null
        }

        private fun JsonObject.optStr(key: String, default: String = ""): String {
            val elem = get(key) ?: return default
            if (elem.isJsonNull || !elem.isJsonPrimitive) return default
            return runCatching { elem.asString }.getOrDefault(default)
        }

        private fun JsonObject.optBool(key: String, default: Boolean): Boolean {
            val elem = get(key) ?: return default
            if (elem.isJsonNull || !elem.isJsonPrimitive) return default
            val p = elem.asJsonPrimitive
            return when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> runCatching { p.asInt != 0 }.getOrDefault(default)
                p.isString -> when (p.asString.trim().lowercase(Locale.US)) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> default
                }
                else -> default
            }
        }

        private fun JsonObject.optIntSafe(key: String, default: Int): Int {
            val elem = get(key) ?: return default
            if (elem.isJsonNull || !elem.isJsonPrimitive) return default
            val p = elem.asJsonPrimitive
            return when {
                p.isNumber -> runCatching { p.asInt }.getOrDefault(default)
                p.isString -> p.asString.trim().toIntOrNull() ?: default
                else -> default
            }
        }

        private fun JsonObject.optLongSafe(key: String, default: Long): Long {
            val elem = get(key) ?: return default
            if (elem.isJsonNull || !elem.isJsonPrimitive) return default
            val p = elem.asJsonPrimitive
            return when {
                p.isNumber -> runCatching { p.asLong }.getOrDefault(default)
                p.isString -> p.asString.trim().toLongOrNull() ?: default
                else -> default
            }
        }

        private fun JsonObject.optPinDouble(key: String): Double {
            if (!has(key)) return 0.0
            val elem = get(key)
            if (elem == null || elem.isJsonNull) return 0.0
            if (!elem.isJsonPrimitive) return Double.NaN
            val p = elem.asJsonPrimitive
            return when {
                p.isNumber -> runCatching { p.asDouble }.getOrDefault(Double.NaN)
                p.isString -> p.asString.trim().toDoubleOrNull() ?: Double.NaN
                else -> Double.NaN
            }
        }
    }
}
