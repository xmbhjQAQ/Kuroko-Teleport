package com.kuroko.teleport.rf

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.kuroko.teleport.BuildConfig
import com.kuroko.teleport.ipc.KurokoProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed class LiveScanOutcome<out T> {
    data class Success<T>(val items: List<T>) : LiveScanOutcome<T>()
    data object PermissionRequired : LiveScanOutcome<Nothing>()
    data object BluetoothDisabled : LiveScanOutcome<Nothing>()
    data class Failed(val detail: String) : LiveScanOutcome<Nothing>()
}

/**
 * On-demand local signal importer (Cell / Wi-Fi / BLE iBeacon) and optional OpenCelliD lookup.
 * Never performs background or automatic network queries.
 */
object RfLiveImporter {
    private data class BleCandidate(val isBeacon: Boolean, val rssi: Int, val item: PinnedBleBeacon)

    private const val APPLE_COMPANY_ID = 0x004C
    private val USER_AGENT = "KurokoTeleport/${BuildConfig.VERSION_NAME} (Android)"

    @SuppressLint("MissingPermission")
    suspend fun scanNearbyCells(
        context: Context,
        pinLat: Double,
        pinLon: Double,
        followVirtual: Boolean
    ): LiveScanOutcome<PinnedCell> = withContext(Dispatchers.IO) {
        if (!hasFineLocation(context)) {
            return@withContext LiveScanOutcome.PermissionRequired
        }
        val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return@withContext LiveScanOutcome.Success(emptyList())

        val rawList = runCatching { tm.allCellInfo }.getOrNull().orEmpty()
        data class Candidate(val registered: Boolean, val rsrp: Int, val cell: PinnedCell)

        val candidates = mutableListOf<Candidate>()
        for (info in rawList) {
            when (info) {
                is CellInfoNr -> {
                    val id = info.cellIdentity as? CellIdentityNr ?: continue
                    val sig = info.cellSignalStrength as? CellSignalStrengthNr
                    val mccStr = id.mccString?.trim().orEmpty()
                    val mncStr = id.mncString?.trim().orEmpty()
                    val mcc = mccStr.toIntOrNull() ?: continue
                    val mnc = mncStr.toIntOrNull() ?: continue
                    val mncDigits = if (mncStr.length >= 3) 3 else 2
                    val tac = id.tac.takeIf { it != CellInfo.UNAVAILABLE && it >= 0 }?.toLong() ?: 0L
                    val nci = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG && it >= 0L } ?: continue
                    val pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE && it in 0..1007 } ?: 0
                    val nrarfcn = id.nrarfcn.takeIf { it != CellInfo.UNAVAILABLE && it >= 0 } ?: 633984
                    val band = id.bands.firstOrNull()?.takeIf { it > 0 } ?: 78
                    val rsrp = sig?.ssRsrp?.takeIf { it != CellInfo.UNAVAILABLE && it in -140..-44 } ?: -88
                    candidates.add(
                        Candidate(
                            registered = info.isRegistered,
                            rsrp = rsrp,
                            cell = PinnedCell(
                                rat = 2,
                                mcc = mcc,
                                mnc = mnc,
                                mncDigits = mncDigits,
                                tac = tac,
                                ci = nci,
                                pci = pci,
                                arfcn = nrarfcn,
                                band = band,
                                rsrpDbm = rsrp,
                                followVirtual = followVirtual,
                                pinLat = pinLat,
                                pinLon = pinLon
                            )
                        )
                    )
                }
                is CellInfoLte -> {
                    val id: CellIdentityLte = info.cellIdentity
                    val sig = info.cellSignalStrength
                    val mccStr = id.mccString?.trim().orEmpty()
                    val mncStr = id.mncString?.trim().orEmpty()
                    val mcc = mccStr.toIntOrNull() ?: continue
                    val mnc = mncStr.toIntOrNull() ?: continue
                    val mncDigits = if (mncStr.length >= 3) 3 else 2
                    val tac = id.tac.takeIf { it != CellInfo.UNAVAILABLE && it >= 0 }?.toLong() ?: 0L
                    val ci = id.ci.takeIf { it != CellInfo.UNAVAILABLE && it >= 0 }?.toLong() ?: continue
                    val pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE && it in 0..503 } ?: 0
                    val earfcn = id.earfcn.takeIf { it != CellInfo.UNAVAILABLE && it >= 0 } ?: 1850
                    val band = id.bands.firstOrNull()?.takeIf { it > 0 } ?: 3
                    val rsrp = sig.rsrp.takeIf { it != CellInfo.UNAVAILABLE && it in -140..-44 } ?: -86
                    candidates.add(
                        Candidate(
                            registered = info.isRegistered,
                            rsrp = rsrp,
                            cell = PinnedCell(
                                rat = 1,
                                mcc = mcc,
                                mnc = mnc,
                                mncDigits = mncDigits,
                                tac = tac,
                                ci = ci,
                                pci = pci,
                                arfcn = earfcn,
                                band = band,
                                rsrpDbm = rsrp,
                                followVirtual = followVirtual,
                                pinLat = pinLat,
                                pinLon = pinLon
                            )
                        )
                    )
                }
            }
        }

        val deduped = candidates
            .sortedWith(
                compareByDescending<Candidate> { it.registered }
                    .thenByDescending { it.rsrp }
            )
            .distinctBy { "${it.cell.rat}:${it.cell.mcc}:${it.cell.mnc}:${it.cell.ci}" }
            .take(KurokoProtocol.MAX_PINNED_CELLS)
            .map { it.cell }

        LiveScanOutcome.Success(deduped)
    }

    @SuppressLint("MissingPermission")
    suspend fun scanNearbyWifis(
        context: Context,
        pinLat: Double,
        pinLon: Double,
        followVirtual: Boolean
    ): LiveScanOutcome<PinnedWifiAp> = withContext(Dispatchers.IO) {
        if (!hasFineLocation(context)) {
            return@withContext LiveScanOutcome.PermissionRequired
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@withContext LiveScanOutcome.Success(emptyList())

        @Suppress("DEPRECATION")
        val connectedBssid = runCatching { wm.connectionInfo?.bssid?.uppercase(Locale.US) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }

        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        val parsed = results.mapNotNull { sr ->
            val rawBssid = sr.BSSID?.uppercase(Locale.US)?.trim().orEmpty()
            if (rawBssid.length < 12 || rawBssid == "02:00:00:00:00:00" || rawBssid == "00:00:00:00:00:00") {
                return@mapNotNull null
            }
            val ssid = extractWifiSsid(sr)
            if (ssid.isBlank()) return@mapNotNull null
            val normBssid = RfFormatting.normalizeMac(rawBssid)
            val isConn = connectedBssid != null && normBssid.equals(connectedBssid, ignoreCase = true)
            PinnedWifiAp(
                bssid = normBssid,
                ssid = ssid.take(32),
                frequencyMhz = sr.frequency.coerceIn(2412, 7125),
                rssiDbm = sr.level.coerceIn(-100, -25),
                isConnectedAp = isConn,
                followVirtual = followVirtual,
                pinLat = pinLat,
                pinLon = pinLon
            )
        }
            .sortedWith(
                compareByDescending<PinnedWifiAp> { it.isConnectedAp }
                    .thenByDescending { it.rssiDbm }
            )
            .distinctBy { it.bssid }
            .take(KurokoProtocol.MAX_PINNED_WIFIS)

        LiveScanOutcome.Success(parsed)
    }

    @SuppressLint("MissingPermission")
    suspend fun scanNearbyBleBeacons(
        context: Context,
        pinLat: Double,
        pinLon: Double,
        followVirtual: Boolean,
        durationMs: Long = 4000L
    ): LiveScanOutcome<PinnedBleBeacon> = withContext(Dispatchers.IO) {
        val hasScanPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasScanPerm || !hasFineLocation(context)) {
            return@withContext LiveScanOutcome.PermissionRequired
        }
        val hasConnectPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        val btMgr = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btMgr?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return@withContext LiveScanOutcome.BluetoothDisabled
        }
        val scanner = adapter.bluetoothLeScanner
            ?: return@withContext LiveScanOutcome.BluetoothDisabled

        val collected = ConcurrentHashMap<String, BleCandidate>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                recordBleResult(result, hasConnectPerm, pinLat, pinLon, followVirtual, collected)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) {
                    recordBleResult(r, hasConnectPerm, pinLat, pinLon, followVirtual, collected)
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val startErr = runCatching {
            scanner.startScan(null, settings, callback)
        }.exceptionOrNull()
        if (startErr != null) {
            return@withContext LiveScanOutcome.Failed(startErr.message ?: startErr.javaClass.simpleName)
        }

        try {
            delay(durationMs.coerceIn(1500L, 6000L))
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        val sorted = collected.values
            .sortedWith(
                compareByDescending<BleCandidate> { it.isBeacon }
                    .thenByDescending { it.rssi }
            )
            .take(KurokoProtocol.MAX_PINNED_BEACONS)
            .map { it.item }

        LiveScanOutcome.Success(sorted)
    }

    /**
     * Explicit on-demand OpenCelliD area lookup around (latWgs84, lonWgs84).
     * Invoked ONLY when user explicitly taps "Fetch Real Towers" with their own API key.
     */
    suspend fun fetchOpenCellIdTowers(
        apiKey: String,
        latWgs84: Double,
        lonWgs84: Double
    ): Result<List<PinnedCell>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = apiKey.trim()
            require(token.isNotEmpty()) { "Missing OpenCelliD token" }

            val delta = 0.015 // ~1.6 km search box
            val latMin = (latWgs84 - delta).coerceIn(-89.9, 89.9)
            val latMax = (latWgs84 + delta).coerceIn(-89.9, 89.9)
            val lonMin = (lonWgs84 - delta).coerceIn(-179.9, 179.9)
            val lonMax = (lonWgs84 + delta).coerceIn(-179.9, 179.9)
            val bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", latMin, lonMin, latMax, lonMax)
            val encodedKey = URLEncoder.encode(token, "UTF-8")
            val urlStr = "https://opencellid.org/cell/getInArea?key=$encodedKey&BBOX=$bbox&limit=12&format=json"

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
                }.orEmpty().trim()
                if (code !in 200..299) {
                    val errText = runCatching { JSONObject(body).optString("error") }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: "HTTP $code"
                    error(errText)
                }
                parseOpenCellIdResponse(body)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseOpenCellIdResponse(body: String): List<PinnedCell> {
        if (body.isBlank()) return emptyList()
        val cellsArr: JSONArray = if (body.startsWith("[")) {
            JSONArray(body)
        } else {
            val root = JSONObject(body)
            val apiErr = root.optString("error").trim()
            if (apiErr.isNotEmpty() && !root.has("cells")) {
                error(apiErr)
            }
            root.optJSONArray("cells") ?: JSONArray()
        }

        val out = mutableListOf<PinnedCell>()
        for (i in 0 until cellsArr.length()) {
            val c = cellsArr.optJSONObject(i) ?: continue
            val radio = c.optString("radio", "LTE").uppercase(Locale.US)
            val rat = if (radio.contains("NR") || radio.contains("5G")) 2 else 1
            val mcc = c.optInt("mcc", 0)
            val mnc = c.optInt("mnc", -1)
            val lac = c.optLong("lac", 0L)
            val cellId = c.optLong("cellid", -1L)
            val lat = c.optDouble("lat", Double.NaN)
            val lon = c.optDouble("lon", Double.NaN)
            if (mcc <= 0 || mnc < 0 || cellId < 0L || lat.isNaN() || lon.isNaN()) continue
            val mncDigits = if (mcc in setOf(310, 311, 312, 313, 316, 302) || mnc >= 100) 3 else 2
            val avgSig = c.optInt("averageSignalStrength", -84).let {
                if (it in -140..-44) it else -84
            }
            out.add(
                PinnedCell(
                    rat = rat,
                    mcc = mcc,
                    mnc = mnc,
                    mncDigits = mncDigits,
                    tac = lac.coerceIn(0L, 16_777_215L),
                    ci = cellId,
                    pci = ((cellId % 500L).toInt()).coerceIn(0, 503),
                    arfcn = if (rat == 2) 633984 else 1850,
                    band = if (rat == 2) 78 else 3,
                    rsrpDbm = avgSig,
                    followVirtual = false,
                    pinLat = lat,
                    pinLon = lon
                )
            )
            if (out.size >= KurokoProtocol.MAX_PINNED_CELLS) break
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun recordBleResult(
        result: ScanResult,
        hasConnectPerm: Boolean,
        pinLat: Double,
        pinLon: Double,
        followVirtual: Boolean,
        out: ConcurrentHashMap<String, BleCandidate>
    ) {
        val rawMac = result.device?.address?.uppercase(Locale.US)?.trim().orEmpty()
        if (rawMac.length < 12) return
        val normMac = RfFormatting.normalizeMac(rawMac)
        val record = result.scanRecord
        val appleData = record?.getManufacturerSpecificData(APPLE_COMPANY_ID)
        val recordName = record?.deviceName?.trim().orEmpty()
        val devName = if (recordName.isNotEmpty()) {
            recordName
        } else if (hasConnectPerm) {
            runCatching { result.device?.name?.trim().orEmpty() }.getOrDefault("")
        } else {
            ""
        }

        // Apple iBeacon manufacturer payload: 0x02 0x15 + 16B UUID + 2B Major + 2B Minor + 1B TxPower = 23B
        val isIBeacon = appleData != null &&
            appleData.size >= 23 &&
            appleData[0] == 0x02.toByte() &&
            appleData[1] == 0x15.toByte()

        if (!isIBeacon && devName.isBlank()) return

        val uuidHex = if (isIBeacon) {
            bytesToHexUpper(appleData!!, 2, 16)
        } else {
            "00000000000000000000000000000000"
        }
        val major = if (isIBeacon) {
            ((appleData!![18].toInt() and 0xFF) shl 8) or (appleData[19].toInt() and 0xFF)
        } else {
            0
        }
        val minor = if (isIBeacon) {
            ((appleData!![20].toInt() and 0xFF) shl 8) or (appleData[21].toInt() and 0xFF)
        } else {
            0
        }
        val txPower = if (isIBeacon) {
            appleData!![22].toInt().coerceIn(-100, 10)
        } else {
            (record?.txPowerLevel?.takeIf { it in -100..10 } ?: -59)
        }

        val item = PinnedBleBeacon(
            mac = normMac,
            deviceType = if (isIBeacon) 1 else 0,
            name = devName.ifBlank { if (isIBeacon) "iBeacon-$major-$minor" else "BLE-Device" }.take(23),
            uuidHex32 = uuidHex,
            major = major,
            minor = minor,
            txPowerDbm = txPower,
            followVirtual = followVirtual,
            pinLat = pinLat,
            pinLon = pinLon
        )

        val existing = out[normMac]
        if (existing == null || (isIBeacon && !existing.isBeacon) || result.rssi > existing.rssi) {
            out[normMac] = BleCandidate(isBeacon = isIBeacon, rssi = result.rssi, item = item)
        }
    }

    private fun extractWifiSsid(sr: android.net.wifi.ScanResult): String {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { sr.wifiSsid?.toString() }.getOrNull().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            sr.SSID.orEmpty()
        }
        return raw.trim().removeSurrounding("\"")
    }

    private fun hasFineLocation(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun bytesToHexUpper(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length * 2)
        for (i in offset until (offset + length)) {
            val b = bytes[i].toInt() and 0xFF
            sb.append(HEX_UPPER[b ushr 4])
            sb.append(HEX_UPPER[b and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX_UPPER = "0123456789ABCDEF"
}
