package com.kuroko.teleport.xposed

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiSsid
import android.os.Build
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Constructs authentic Android 12–17 (API 31–37) Wi-Fi ScanResult lists (including API 33+
 * WifiSsid and IEEE 802.11d Country Information Elements id=7) and spoofed WifiInfo objects
 * from KurokoShm v2 virtual Wi-Fi AP entries.
 */
object VirtualWifiBuilder {
    private const val TAG = "Kuroko-WifiBuilder"
    private const val IE_ID_SSID = 0
    private const val IE_ID_COUNTRY = 7

    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val fieldCache = ConcurrentHashMap<String, Field>()

    @SuppressLint("NewApi")
    fun buildScanResults(
        header: ShmReader.RfHeader?,
        aps: List<ShmReader.WifiApEntry>
    ): List<ScanResult> {
        if (aps.isEmpty()) return emptyList()
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
        val defaultCountry = sanitizeCountryCode(header?.wifiCountryIso)
        val out = ArrayList<ScanResult>(aps.size)

        for (ap in aps) {
            try {
                val sr = ScanResult()
                @Suppress("DEPRECATION")
                sr.SSID = ap.ssid
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        val wifiSsid = WifiSsid.fromBytes(ap.ssidBytes)
                        applyWifiSsid(sr, wifiSsid)
                    } catch (_: Throwable) {
                    }
                } else {
                    trySetLegacyWifiSsid(sr, ap.ssidBytes)
                }

                sr.BSSID = ap.bssidString()
                sr.capabilities = ap.capabilities.ifEmpty { "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]" }
                sr.level = ap.rssiDbm.coerceIn(-100, -25)
                sr.frequency = if (ap.frequencyMhz > 0) ap.frequencyMhz else 2437
                sr.channelWidth = when (ap.channelWidth) {
                    1 -> ScanResult.CHANNEL_WIDTH_40MHZ
                    2 -> ScanResult.CHANNEL_WIDTH_80MHZ
                    else -> ScanResult.CHANNEL_WIDTH_20MHZ
                }
                sr.centerFreq0 = if (ap.centerFreq0Mhz > 0) ap.centerFreq0Mhz else sr.frequency
                sr.centerFreq1 = 0
                sr.timestamp = if (ap.timestampUs > 0L && kotlin.math.abs(nowUs - ap.timestampUs) < 30_000_000L) {
                    ap.timestampUs
                } else {
                    nowUs
                }

                // Set Wi-Fi standard (11ax for 5GHz, 11n for 2.4GHz)
                val std = if (ap.is5Ghz) ScanResult.WIFI_STANDARD_11AX else ScanResult.WIFI_STANDARD_11N
                applyWifiStandard(sr, std)

                // Populate Information Elements: SSID (id=0) + 802.11d Country IE (id=7)
                val countryIso = sanitizeCountryCode(ap.countryIso).ifEmpty { defaultCountry }
                applyInformationElements(sr, ap, countryIso)

                out.add(sr)
            } catch (t: Throwable) {
                logOnce("build_sr", "Failed building virtual ScanResult: $t")
            }
        }
        return out
    }

    /**
     * Builds a clean spoofed WifiInfo matching the virtual connected AP while preserving
     * non-identifying IP/link/supplicant state from [orig] so target apps keep normal network checks
     * without leaking any physical router SSID/BSSID/MAC/MLO/Passpoint identifiers.
     */
    fun buildSpoofedWifiInfo(orig: WifiInfo, connectedAp: ShmReader.WifiApEntry): WifiInfo {
        return try {
            val builder = WifiInfo.Builder()
                .setSsid(connectedAp.ssidBytes)
                .setBssid(connectedAp.bssidString())
                .setRssi(connectedAp.rssiDbm.coerceIn(-95, -30))
                .setNetworkId(1)
            try {
                builder.setCurrentSecurityType(inferSecurityType(connectedAp.capabilities))
            } catch (_: Throwable) {
            }
            val spoofed = builder.build()

            // Transfer non-identifying link metrics from orig
            val freq = if (connectedAp.frequencyMhz > 0) connectedAp.frequencyMhz else 5180
            invokeSetter(spoofed, "setFrequency", Int::class.java, freq)
            val linkSpeed = if (orig.linkSpeed > 0) orig.linkSpeed else (if (connectedAp.is5Ghz) 866 else 144)
            invokeSetter(spoofed, "setLinkSpeed", Int::class.java, linkSpeed)
            invokeSetter(spoofed, "setTxLinkSpeedMbps", Int::class.java, linkSpeed)
            invokeSetter(spoofed, "setRxLinkSpeedMbps", Int::class.java, linkSpeed)
            val state = orig.supplicantState ?: SupplicantState.COMPLETED
            invokeSetter(spoofed, "setSupplicantState", SupplicantState::class.java, state)
            invokeSetter(spoofed, "setMacAddress", String::class.java, "02:00:00:00:00:00")

            // Copy IP address from orig (RFC1918 local interface IP needed by apps checking connectivity)
            val origIp = getFieldValue(orig, "mIpAddress") as? InetAddress
            if (origIp != null) {
                invokeSetter(spoofed, "setInetAddress", InetAddress::class.java, origIp)
            }
            spoofed
        } catch (t: Throwable) {
            logOnce("build_wifiinfo", "buildSpoofedWifiInfo failed (falling back to redact): $t")
            redactWifiInfo(orig)
        }
    }

    fun redactWifiInfo(orig: WifiInfo): WifiInfo {
        try {
            val redactions = orig.applicableRedactions
            if (redactions != 0L) {
                return orig.makeCopy(redactions)
            }
            return orig.makeCopy(-1L)
        } catch (_: Throwable) {
        }
        return try {
            val ctor = WifiInfo::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (_: Throwable) {
            orig
        }
    }

    private fun applyWifiSsid(sr: ScanResult, wifiSsid: WifiSsid) {
        if (invokeSetter(sr, "setWifiSsid", WifiSsid::class.java, wifiSsid)) return
        if (setFieldValue(sr, "mWifiSsid", wifiSsid)) return
        setFieldValue(sr, "wifiSsid", wifiSsid)
    }

    private fun trySetLegacyWifiSsid(sr: ScanResult, ssidBytes: ByteArray) {
        try {
            val legacyCls = Class.forName("android.net.wifi.WifiSsid")
            val createFn = legacyCls.getDeclaredMethod("createFromByteArray", ByteArray::class.java)
            createFn.isAccessible = true
            val obj = createFn.invoke(null, ssidBytes) ?: return
            if (!setFieldValue(sr, "wifiSsid", obj)) {
                setFieldValue(sr, "mWifiSsid", obj)
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyWifiStandard(sr: ScanResult, standard: Int) {
        if (invokeSetter(sr, "setWifiStandard", Int::class.java, standard)) return
        setFieldValue(sr, "mWifiStandard", standard)
    }

    private fun applyInformationElements(
        sr: ScanResult,
        ap: ShmReader.WifiApEntry,
        countryIso: String
    ) {
        try {
            val ies = ArrayList<ScanResult.InformationElement>(2)
            createInformationElement(IE_ID_SSID, 0, ap.ssidBytes)?.let { ies.add(it) }
            if (countryIso.length == 2) {
                val c0 = countryIso[0].code.toByte()
                val c1 = countryIso[1].code.toByte()
                val firstCh: Byte
                val numCh: Byte
                val maxPwr: Byte
                if (ap.frequencyMhz >= 5000) {
                    firstCh = 36
                    numCh = 4
                    maxPwr = 23
                } else {
                    firstCh = 1
                    numCh = if (countryIso == "US" || countryIso == "CA") 11 else 13
                    maxPwr = 20
                }
                val countryPayload = byteArrayOf(c0, c1, 0x20, firstCh, numCh, maxPwr)
                createInformationElement(IE_ID_COUNTRY, 0, countryPayload)?.let { ies.add(it) }
            }
            if (ies.isEmpty()) return

            val ieField = findField(ScanResult::class.java, "informationElements")
                ?: findField(ScanResult::class.java, "mInformationElements")
                ?: return
            val fieldType = ieField.type
            if (fieldType.isArray) {
                ieField.set(sr, ies.toTypedArray())
            } else if (List::class.java.isAssignableFrom(fieldType)) {
                ieField.set(sr, ies)
            }
        } catch (t: Throwable) {
            logOnce("apply_ie", "Failed attaching 802.11d IE: $t")
        }
    }

    @SuppressLint("NewApi")
    private fun createInformationElement(
        id: Int,
        idExt: Int,
        bytes: ByteArray
    ): ScanResult.InformationElement? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                return ScanResult.InformationElement(id, idExt, bytes)
            } catch (_: Throwable) {
            }
        }
        return try {
            val cls = ScanResult.InformationElement::class.java
            try {
                val ctor3 = cls.getDeclaredConstructor(Int::class.java, Int::class.java, ByteArray::class.java)
                ctor3.isAccessible = true
                return ctor3.newInstance(id, idExt, bytes)
            } catch (_: NoSuchMethodException) {
            }
            val ctor0 = cls.getDeclaredConstructor()
            ctor0.isAccessible = true
            val ie = ctor0.newInstance()
            setFieldValue(ie, "id", id)
            setFieldValue(ie, "idExt", idExt)
            setFieldValue(ie, "bytes", bytes)
            ie
        } catch (_: Throwable) {
            null
        }
    }

    private fun inferSecurityType(capabilities: String): Int {
        return when {
            capabilities.contains("SAE", ignoreCase = true) -> WifiInfo.SECURITY_TYPE_SAE
            capabilities.contains("EAP", ignoreCase = true) -> WifiInfo.SECURITY_TYPE_EAP
            capabilities.contains("PSK", ignoreCase = true) -> WifiInfo.SECURITY_TYPE_PSK
            capabilities.contains("OWE", ignoreCase = true) -> WifiInfo.SECURITY_TYPE_OWE
            else -> WifiInfo.SECURITY_TYPE_OPEN
        }
    }

    private fun sanitizeCountryCode(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim().uppercase()
        if (trimmed.length == 2 && trimmed[0] in 'A'..'Z' && trimmed[1] in 'A'..'Z') {
            return trimmed
        }
        return ""
    }

    private fun invokeSetter(target: Any, name: String, paramType: Class<*>, value: Any?): Boolean {
        return try {
            val key = "${target.javaClass.name}#$name(${paramType.name})"
            var m = methodCache[key]
            if (m == null) {
                var cur: Class<*>? = target.javaClass
                while (cur != null && cur != Any::class.java) {
                    try {
                        val found = cur.getDeclaredMethod(name, paramType)
                        found.isAccessible = true
                        methodCache[key] = found
                        m = found
                        break
                    } catch (_: NoSuchMethodException) {
                        cur = cur.superclass
                    }
                }
            }
            if (m != null) {
                m.invoke(target, value)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        val key = "${cls.name}.$name"
        fieldCache[key]?.let { return it }
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                fieldCache[key] = f
                return f
            } catch (_: NoSuchFieldException) {
                cur = cur.superclass
            }
        }
        return null
    }

    private fun getFieldValue(obj: Any, name: String): Any? {
        return try {
            findField(obj.javaClass, name)?.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    private fun setFieldValue(obj: Any, name: String, value: Any?): Boolean {
        return try {
            val f = findField(obj.javaClass, name) ?: return false
            f.set(obj, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun logOnce(key: String, msg: String) {
        if (loggedOnceKeys.add(key)) {
            XposedBridge.log("$TAG: $msg")
        }
    }
}
