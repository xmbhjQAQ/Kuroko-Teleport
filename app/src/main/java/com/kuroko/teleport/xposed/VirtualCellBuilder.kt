package com.kuroko.teleport.xposed

import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.telephony.CellIdentity
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Constructs authentic Android 12–17 (API 31–37) CellInfoLte / CellInfoNr, serving CellIdentity,
 * legacy GsmCellLocation Bundle, and cloned/rewritten ServiceState objects from KurokoShm v2 cells.
 *
 * Uses reflection-cached constructors with universal zero-arg + field fallbacks so OEM or
 * Android-version constructor signature drift can never crash com.android.phone or system_server.
 */
object VirtualCellBuilder {
    private const val TAG = "Kuroko-CellBuilder"

    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val methodCache = ConcurrentHashMap<String, Method>()

    // Cached constructors resolved lazily once per process
    @Volatile private var ctorLteId12: Constructor<*>? = null
    @Volatile private var ctorLteId9: Constructor<*>? = null
    @Volatile private var ctorLteId0: Constructor<*>? = null
    @Volatile private var lteIdResolved = false

    @Volatile private var ctorLteSs7: Constructor<*>? = null
    @Volatile private var ctorLteSs6: Constructor<*>? = null
    @Volatile private var ctorLteSs0: Constructor<*>? = null
    @Volatile private var lteSsResolved = false

    @Volatile private var ctorLteInfo5: Constructor<*>? = null
    @Volatile private var ctorLteInfo0: Constructor<*>? = null
    @Volatile private var lteInfoResolved = false

    @Volatile private var ctorNrId10: Constructor<*>? = null
    @Volatile private var ctorNrId8: Constructor<*>? = null
    @Volatile private var ctorNrId0: Constructor<*>? = null
    @Volatile private var nrIdResolved = false

    @Volatile private var ctorNrSs9: Constructor<*>? = null
    @Volatile private var ctorNrSs8: Constructor<*>? = null
    @Volatile private var ctorNrSs6: Constructor<*>? = null
    @Volatile private var ctorNrSs0: Constructor<*>? = null
    @Volatile private var nrSsResolved = false

    @Volatile private var ctorNrInfo5: Constructor<*>? = null
    @Volatile private var ctorNrInfo0: Constructor<*>? = null
    @Volatile private var nrInfoResolved = false

    fun buildCellInfoList(
        header: ShmReader.RfHeader?,
        cells: List<ShmReader.CellEntry>
    ): List<CellInfo> {
        if (cells.isEmpty()) return emptyList()
        val alpha = header?.carrierAlpha?.takeIf { it.isNotEmpty() } ?: ""
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val out = ArrayList<CellInfo>(cells.size)

        for (cell in cells) {
            try {
                val tsNs = if (cell.timestampNs > 0L && abs(nowNs - cell.timestampNs) < 30_000_000_000L) {
                    cell.timestampNs
                } else {
                    nowNs
                }
                val info: CellInfo? = if (cell.rat == ShmReader.KUROKO_CELL_RAT_NR) {
                    buildCellInfoNr(cell, alpha, tsNs)
                } else {
                    buildCellInfoLte(cell, alpha, tsNs)
                }
                if (info != null) {
                    out.add(info)
                }
            } catch (t: Throwable) {
                logOnce("build_cell_rat_${cell.rat}", "Failed building virtual cell rat=${cell.rat}: $t")
            }
        }
        return out
    }

    fun buildServingCellIdentity(
        header: ShmReader.RfHeader?,
        cells: List<ShmReader.CellEntry>
    ): CellIdentity? {
        if (cells.isEmpty()) return null
        val serving = cells.firstOrNull { it.registered } ?: cells.first()
        val alpha = header?.carrierAlpha?.takeIf { it.isNotEmpty() } ?: ""
        return try {
            if (serving.rat == ShmReader.KUROKO_CELL_RAT_NR) {
                buildCellIdentityNr(serving, alpha)
            } else {
                buildCellIdentityLte(serving, alpha)
            }
        } catch (t: Throwable) {
            logOnce("build_serving_id", "Failed building serving CellIdentity: $t")
            null
        }
    }

    fun buildGsmCellLocationBundle(cells: List<ShmReader.CellEntry>): Bundle? {
        if (cells.isEmpty()) return null
        val serving = cells.firstOrNull { it.registered } ?: cells.first()
        return Bundle().apply {
            putInt("lac", serving.tac and 0xFFFF)
            putInt("cid", (serving.ci and 0x0FFFFFFFL).toInt())
            putInt("psc", serving.pci.coerceIn(0, 511))
        }
    }

    /**
     * Clones [orig] into a new [ServiceState] with spoofed operator numeric/alpha and serving
     * CellIdentity inside its NetworkRegistrationInfos without mutating [orig].
     */
    fun rewriteServiceState(
        orig: ServiceState,
        header: ShmReader.RfHeader,
        servingIdentity: CellIdentity?
    ): ServiceState {
        return try {
            val copy = ServiceState(orig)
            val opNum = header.operatorNumeric()
            val alpha = header.carrierAlpha.ifEmpty { opNum }
            if (opNum.length >= 5) {
                copy.setOperatorName(alpha, alpha, opNum)
            }
            if (servingIdentity != null) {
                rewriteNriCellIdentities(copy, servingIdentity)
            }
            copy
        } catch (t: Throwable) {
            logOnce("rewrite_ss", "rewriteServiceState warning: $t")
            orig
        }
    }

    private fun rewriteNriCellIdentities(ssCopy: ServiceState, servingIdentity: CellIdentity) {
        try {
            val listObj = getFieldValue(ssCopy, "mNetworkRegistrationInfos") as? MutableList<*> ?: return
            @Suppress("UNCHECKED_CAST")
            val mutList = listObj as MutableList<Any?>
            for (i in mutList.indices) {
                val nri = mutList[i] as? NetworkRegistrationInfo ?: continue
                if (getFieldValue(nri, "mCellIdentity") == null) continue
                val cloned = cloneParcelable(nri, NetworkRegistrationInfo.CREATOR) ?: continue
                setFieldValue(cloned, "mCellIdentity", servingIdentity)
                mutList[i] = cloned
            }
        } catch (_: Throwable) {
        }
    }

    private fun <T : android.os.Parcelable> cloneParcelable(
        obj: T,
        creator: android.os.Parcelable.Creator<T>
    ): T? {
        val p = Parcel.obtain()
        return try {
            obj.writeToParcel(p, 0)
            p.setDataPosition(0)
            creator.createFromParcel(p)
        } catch (_: Throwable) {
            null
        } finally {
            p.recycle()
        }
    }

    // ==================== LTE Builders ====================

    private fun buildCellInfoLte(
        cell: ShmReader.CellEntry,
        alpha: String,
        tsNs: Long
    ): CellInfoLte? {
        val cid = buildCellIdentityLte(cell, alpha) ?: return null
        val css = buildCellSignalStrengthLte(cell) ?: return null
        resolveLteInfoCtors()
        val connStatus = if (cell.registered) {
            CellInfo.CONNECTION_PRIMARY_SERVING
        } else {
            CellInfo.CONNECTION_NONE
        }

        ctorLteInfo5?.let { ctor ->
            try {
                return ctor.newInstance(connStatus, cell.registered, tsNs, cid, css) as CellInfoLte
            } catch (_: Throwable) {
            }
        }
        return try {
            val info = (ctorLteInfo0?.newInstance() ?: return null) as CellInfoLte
            invokeSetter(info, "setCellConnectionStatus", Int::class.java, connStatus)
            invokeSetter(info, "setRegistered", Boolean::class.java, cell.registered)
            invokeSetter(info, "setTimeStamp", Long::class.java, tsNs)
            if (!invokeSetter(info, "setCellIdentity", CellIdentityLte::class.java, cid)) {
                setFieldValue(info, "mCellIdentityLte", cid)
            }
            if (!invokeSetter(info, "setCellSignalStrength", CellSignalStrengthLte::class.java, css)) {
                setFieldValue(info, "mCellSignalStrengthLte", css)
            }
            info
        } catch (t: Throwable) {
            logOnce("lte_info_ctor", "CellInfoLte construction failed: $t")
            null
        }
    }

    private fun buildCellIdentityLte(cell: ShmReader.CellEntry, alpha: String): CellIdentityLte? {
        resolveLteIdCtors()
        val ci = (cell.ci and 0x0FFFFFFFL).toInt()
        val pci = cell.pci.coerceIn(0, 503)
        val tac = cell.tac and 0xFFFF
        val earfcn = cell.arfcn.coerceAtLeast(0)
        val bands = if (cell.band > 0) intArrayOf(cell.band) else intArrayOf(3)
        val bw = if (cell.bandwidthKhz > 0) cell.bandwidthKhz else 20000
        val mccStr = cell.mccString()
        val mncStr = cell.mncString()

        ctorLteId12?.let { ctor ->
            try {
                return ctor.newInstance(
                    ci, pci, tac, earfcn, bands, bw,
                    mccStr, mncStr, alpha, alpha,
                    Collections.emptyList<String>(), null
                ) as CellIdentityLte
            } catch (_: Throwable) {
            }
        }
        ctorLteId9?.let { ctor ->
            try {
                return ctor.newInstance(
                    ci, pci, tac, earfcn, bw, mccStr, mncStr, alpha, alpha
                ) as CellIdentityLte
            } catch (_: Throwable) {
            }
        }
        return try {
            val id = (ctorLteId0?.newInstance() ?: return null) as CellIdentityLte
            setFieldValue(id, "mCi", ci)
            setFieldValue(id, "mPci", pci)
            setFieldValue(id, "mTac", tac)
            setFieldValue(id, "mEarfcn", earfcn)
            setFieldValue(id, "mBands", bands)
            setFieldValue(id, "mBandwidth", bw)
            setFieldValue(id, "mMccStr", mccStr)
            setFieldValue(id, "mMncStr", mncStr)
            setFieldValue(id, "mAlphaLong", alpha)
            setFieldValue(id, "mAlphaShort", alpha)
            id
        } catch (t: Throwable) {
            logOnce("lte_id_ctor", "CellIdentityLte construction failed: $t")
            null
        }
    }

    private fun buildCellSignalStrengthLte(cell: ShmReader.CellEntry): CellSignalStrengthLte? {
        resolveLteSsCtors()
        val rsrp = cell.rsrpDbm.coerceIn(-140, -43)
        val rsrq = cell.rsrqDb.coerceIn(-34, -3)
        val rssnr = cell.sinrDb.coerceIn(-20, 30)
        val rssi = (rsrp + 22).coerceIn(-113, -51)
        val cqiTableIndex = CellInfo.UNAVAILABLE
        val cqi = 10
        val ta = if (cell.ta >= 0) cell.ta.coerceIn(0, 1282) else CellInfo.UNAVAILABLE

        val ss: CellSignalStrengthLte? = ctorLteSs7?.let { ctor ->
            try {
                ctor.newInstance(rssi, rsrp, rsrq, rssnr, cqiTableIndex, cqi, ta) as CellSignalStrengthLte
            } catch (_: Throwable) {
                null
            }
        } ?: ctorLteSs6?.let { ctor ->
            try {
                ctor.newInstance(rssi, rsrp, rsrq, rssnr, cqi, ta) as CellSignalStrengthLte
            } catch (_: Throwable) {
                null
            }
        } ?: try {
            val inst = (ctorLteSs0?.newInstance() ?: return null) as CellSignalStrengthLte
            setFieldValue(inst, "mRssi", rssi)
            setFieldValue(inst, "mRsrp", rsrp)
            setFieldValue(inst, "mRsrq", rsrq)
            setFieldValue(inst, "mRssnr", rssnr)
            setFieldValue(inst, "mCqiTableIndex", cqiTableIndex)
            setFieldValue(inst, "mCqi", cqi)
            setFieldValue(inst, "mTimingAdvance", ta)
            inst
        } catch (t: Throwable) {
            logOnce("lte_ss_ctor", "CellSignalStrengthLte construction failed: $t")
            null
        }

        if (ss != null) {
            ensureSignalLevel(ss, rsrp)
        }
        return ss
    }

    // ==================== 5G NR Builders ====================

    private fun buildCellInfoNr(
        cell: ShmReader.CellEntry,
        alpha: String,
        tsNs: Long
    ): CellInfoNr? {
        val cid = buildCellIdentityNr(cell, alpha) ?: return null
        val css = buildCellSignalStrengthNr(cell) ?: return null
        resolveNrInfoCtors()
        val connStatus = if (cell.registered) {
            CellInfo.CONNECTION_PRIMARY_SERVING
        } else {
            CellInfo.CONNECTION_NONE
        }

        ctorNrInfo5?.let { ctor ->
            try {
                return ctor.newInstance(connStatus, cell.registered, tsNs, cid, css) as CellInfoNr
            } catch (_: Throwable) {
            }
        }
        return try {
            val info = (ctorNrInfo0?.newInstance() ?: return null) as CellInfoNr
            invokeSetter(info, "setCellConnectionStatus", Int::class.java, connStatus)
            invokeSetter(info, "setRegistered", Boolean::class.java, cell.registered)
            invokeSetter(info, "setTimeStamp", Long::class.java, tsNs)
            setFieldValue(info, "mCellIdentity", cid)
            setFieldValue(info, "mCellSignalStrength", css)
            info
        } catch (t: Throwable) {
            logOnce("nr_info_ctor", "CellInfoNr construction failed: $t")
            null
        }
    }

    private fun buildCellIdentityNr(cell: ShmReader.CellEntry, alpha: String): CellIdentityNr? {
        resolveNrIdCtors()
        val pci = cell.pci.coerceIn(0, 1007)
        val tac = cell.tac and 0xFFFFFF
        val nrarfcn = cell.arfcn.coerceAtLeast(0)
        val bands = if (cell.band > 0) intArrayOf(cell.band) else intArrayOf(78)
        val mccStr = cell.mccString()
        val mncStr = cell.mncString()
        val nci = cell.ci and 0xFFFFFFFFFL

        ctorNrId10?.let { ctor ->
            try {
                return ctor.newInstance(
                    pci, tac, nrarfcn, bands, mccStr, mncStr, nci,
                    alpha, alpha, Collections.emptyList<String>()
                ) as CellIdentityNr
            } catch (_: Throwable) {
            }
        }
        ctorNrId8?.let { ctor ->
            try {
                return ctor.newInstance(
                    pci, tac, nrarfcn, mccStr, mncStr, nci, alpha, alpha
                ) as CellIdentityNr
            } catch (_: Throwable) {
            }
        }
        return try {
            val id = (ctorNrId0?.newInstance() ?: return null) as CellIdentityNr
            setFieldValue(id, "mPci", pci)
            setFieldValue(id, "mTac", tac)
            setFieldValue(id, "mNrArfcn", nrarfcn)
            setFieldValue(id, "mBands", bands)
            setFieldValue(id, "mMccStr", mccStr)
            setFieldValue(id, "mMncStr", mncStr)
            setFieldValue(id, "mNci", nci)
            setFieldValue(id, "mAlphaLong", alpha)
            setFieldValue(id, "mAlphaShort", alpha)
            id
        } catch (t: Throwable) {
            logOnce("nr_id_ctor", "CellIdentityNr construction failed: $t")
            null
        }
    }

    private fun buildCellSignalStrengthNr(cell: ShmReader.CellEntry): CellSignalStrengthNr? {
        resolveNrSsCtors()
        val ssRsrp = cell.rsrpDbm.coerceIn(-140, -44)
        val ssRsrq = cell.rsrqDb.coerceIn(-43, 20)
        val ssSinr = cell.sinrDb.coerceIn(-23, 40)
        val taMicros = if (cell.ta >= 0) (cell.ta * 16).coerceIn(0, 128200) else CellInfo.UNAVAILABLE
        val unavail = CellInfo.UNAVAILABLE

        val ss: CellSignalStrengthNr? = ctorNrSs9?.let { ctor ->
            try {
                ctor.newInstance(
                    unavail, unavail, unavail, unavail, Collections.emptyList<Int>(),
                    ssRsrp, ssRsrq, ssSinr, taMicros
                ) as CellSignalStrengthNr
            } catch (_: Throwable) {
                null
            }
        } ?: ctorNrSs8?.let { ctor ->
            try {
                ctor.newInstance(
                    unavail, unavail, unavail, unavail, Collections.emptyList<Int>(),
                    ssRsrp, ssRsrq, ssSinr
                ) as CellSignalStrengthNr
            } catch (_: Throwable) {
                null
            }
        } ?: ctorNrSs6?.let { ctor ->
            try {
                ctor.newInstance(unavail, unavail, unavail, ssRsrp, ssRsrq, ssSinr) as CellSignalStrengthNr
            } catch (_: Throwable) {
                null
            }
        } ?: try {
            val inst = (ctorNrSs0?.newInstance() ?: return null) as CellSignalStrengthNr
            setFieldValue(inst, "mCsiRsrp", unavail)
            setFieldValue(inst, "mCsiRsrq", unavail)
            setFieldValue(inst, "mCsiSinr", unavail)
            setFieldValue(inst, "mSsRsrp", ssRsrp)
            setFieldValue(inst, "mSsRsrq", ssRsrq)
            setFieldValue(inst, "mSsSinr", ssSinr)
            setFieldValue(inst, "mTimingAdvanceMicros", taMicros)
            inst
        } catch (t: Throwable) {
            logOnce("nr_ss_ctor", "CellSignalStrengthNr construction failed: $t")
            null
        }

        if (ss != null) {
            ensureSignalLevel(ss, ssRsrp)
        }
        return ss
    }

    private fun ensureSignalLevel(ss: Any, rsrp: Int) {
        try {
            val m = findMethodByName(ss.javaClass, "updateLevel", 2)
            if (m != null) {
                m.invoke(ss, null, null)
                return
            }
        } catch (_: Throwable) {
        }
        val lvl = when {
            rsrp >= -85 -> 4
            rsrp >= -95 -> 3
            rsrp >= -105 -> 2
            rsrp >= -115 -> 1
            else -> 0
        }
        setFieldValue(ss, "mLevel", lvl)
    }

    // ==================== Constructor Resolution ====================

    private fun resolveLteIdCtors() {
        if (lteIdResolved) return
        for (c in CellIdentityLte::class.java.declaredConstructors) {
            c.isAccessible = true
            val p = c.parameterTypes
            when {
                p.size == 0 -> ctorLteId0 = c
                p.size == 12 && p[4] == IntArray::class.java && p[6] == String::class.java -> ctorLteId12 = c
                p.size == 9 && p[5] == String::class.java -> ctorLteId9 = c
            }
        }
        lteIdResolved = true
    }

    private fun resolveLteSsCtors() {
        if (lteSsResolved) return
        for (c in CellSignalStrengthLte::class.java.declaredConstructors) {
            c.isAccessible = true
            val p = c.parameterTypes
            when {
                p.size == 0 -> ctorLteSs0 = c
                p.size == 7 && p.all { it == Int::class.javaPrimitiveType } -> ctorLteSs7 = c
                p.size == 6 && p.all { it == Int::class.javaPrimitiveType } -> ctorLteSs6 = c
            }
        }
        lteSsResolved = true
    }

    private fun resolveLteInfoCtors() {
        if (lteInfoResolved) return
        for (c in CellInfoLte::class.java.declaredConstructors) {
            c.isAccessible = true
            val p = c.parameterTypes
            when {
                p.size == 0 -> ctorLteInfo0 = c
                p.size == 5 &&
                    p[0] == Int::class.javaPrimitiveType &&
                    p[1] == Boolean::class.javaPrimitiveType &&
                    p[2] == Long::class.javaPrimitiveType -> ctorLteInfo5 = c
            }
        }
        lteInfoResolved = true
    }

    private fun resolveNrIdCtors() {
        if (nrIdResolved) return
        for (c in CellIdentityNr::class.java.declaredConstructors) {
            c.isAccessible = true
            val p = c.parameterTypes
            when {
                p.size == 0 -> ctorNrId0 = c
                p.size == 10 && p[3] == IntArray::class.java && p[6] == Long::class.javaPrimitiveType -> ctorNrId10 = c
                p.size == 8 && p[5] == Long::class.javaPrimitiveType -> ctorNrId8 = c
            }
        }
        nrIdResolved = true
    }

    private fun resolveNrSsCtors() {
        if (nrSsResolved) return
        for (c in CellSignalStrengthNr::class.java.declaredConstructors) {
            c.isAccessible = true
            val p = c.parameterTypes
            when {
                p.size == 0 -> ctorNrSs0 = c
                p.size == 9 && p[4] == List::class.java && p[8] == Int::class.javaPrimitiveType -> ctorNrSs9 = c
                p.size == 8 && p[4] == List::class.java -> ctorNrSs8 = c
                p.size == 6 && p.all { it == Int::class.javaPrimitiveType } -> ctorNrSs6 = c
            }
        }
        nrSsResolved = true
    }

    private fun resolveNrInfoCtors() {
        if (nrInfoResolved) return
        for (c in CellInfoNr::class.java.declaredConstructors) {
            c.isAccessible = true
            val p = c.parameterTypes
            when {
                p.size == 0 -> ctorNrInfo0 = c
                p.size == 5 &&
                    p[0] == Int::class.javaPrimitiveType &&
                    p[1] == Boolean::class.javaPrimitiveType &&
                    p[2] == Long::class.javaPrimitiveType -> ctorNrInfo5 = c
            }
        }
        nrInfoResolved = true
    }

    // ==================== Reflection Helpers ====================

    private fun findMethodByName(cls: Class<*>, name: String, paramCount: Int): Method? {
        val key = "${cls.name}#$name/$paramCount"
        methodCache[key]?.let { return it }
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (m.name == name && m.parameterCount == paramCount) {
                    m.isAccessible = true
                    methodCache[key] = m
                    return m
                }
            }
            cur = cur.superclass
        }
        return null
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
