package com.kuroko.teleport.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kuroko.teleport.R
import com.kuroko.teleport.geo.CoordTransform
import com.kuroko.teleport.geo.GeoPoint
import com.kuroko.teleport.geo.MapLayerType
import com.kuroko.teleport.ipc.KurokoProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Layer 2 Scenario Profile for Dynamic Mode (route + complete biomechanics/motion config).
 * Note: Target Apps remain purely global/independent (no boundTargets per #47).
 */
data class SavedRoute(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val waypoints: List<GeoPoint>,
    val loop: Boolean,
    val maxLaps: Int = 0,
    val targetDistanceKm: Float = 0f,
    val speedMps: Float = 2.8f,
    val paceVarRatio: Float = 0.08f,
    val motionMode: Int = KurokoProtocol.MOTION_AUTO,
    val carryMode: Int = KurokoProtocol.CARRY_HAND_SWING,
    val jitterM: Float = 2.0f,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class SavedTargetApp(
    val packageName: String,
    val uid: Int,
    val policyFlags: Int = KurokoProtocol.POLICY_ALL_STRICT
)

sealed class PackageUidLookup {
    data class Found(val uid: Int) : PackageUidLookup()
    object NotInstalled : PackageUidLookup()
    object LookupError : PackageUidLookup()
}

data class TargetResolutionResult(
    val activeTargets: List<SavedTargetApp>,
    val persistedTargets: List<SavedTargetApp>,
    val changed: Boolean,
    val removedPackages: List<String>,
    val updatedUidPackages: List<String>
)

fun resolveSavedTargets(
    saved: List<SavedTargetApp>,
    selfUid: Int,
    lookupUid: (String) -> PackageUidLookup
): TargetResolutionResult {
    val active = mutableListOf<SavedTargetApp>()
    val persisted = mutableListOf<SavedTargetApp>()
    val removed = mutableListOf<String>()
    val updated = mutableListOf<String>()
    val seenPackages = HashSet<String>()
    var changed = false

    for (entry in saved) {
        val pkg = entry.packageName.trim()
        if (pkg.isEmpty()) {
            changed = true
            continue
        }
        if (!seenPackages.add(pkg)) {
            changed = true
            continue
        }
        when (val lookup = lookupUid(pkg)) {
            is PackageUidLookup.Found -> {
                val uid = lookup.uid
                if (uid <= 0 || uid == selfUid) {
                    changed = true
                    removed.add(pkg)
                } else {
                    val resolved = SavedTargetApp(
                        packageName = pkg,
                        uid = uid,
                        policyFlags = entry.policyFlags
                    )
                    active.add(resolved)
                    persisted.add(resolved)
                    if (uid != entry.uid || pkg != entry.packageName) {
                        changed = true
                        if (uid != entry.uid) {
                            updated.add(pkg)
                        }
                    }
                }
            }
            PackageUidLookup.NotInstalled -> {
                changed = true
                removed.add(pkg)
            }
            PackageUidLookup.LookupError -> {
                persisted.add(entry)
            }
        }
    }

    return TargetResolutionResult(
        activeTargets = active,
        persistedTargets = persisted,
        changed = changed,
        removedPackages = removed,
        updatedUidPackages = updated
    )
}

const val SPEED_PRESET_KEY_WALK = "walk"
const val SPEED_PRESET_KEY_JOG = "jog"
const val SPEED_PRESET_KEY_RUN = "run"
const val SPEED_PRESET_KEY_SPRINT = "sprint"

/**
 * Layer 3 customizable speed preset (long-press any preset chip to overwrite with current setup).
 */
data class CustomSpeedPreset(
    val id: String,
    val name: String,
    val speedMps: Float,
    val paceVarRatio: Float,
    val carryMode: Int,
    val motionMode: Int,
    val builtInKey: String? = null
)

fun CustomSpeedPreset.displayName(context: Context): String {
    val uiCtx = AppLocaleManager.localizedContext(context)
    return when (builtInKey) {
        SPEED_PRESET_KEY_WALK -> uiCtx.getString(R.string.preset_walk)
        SPEED_PRESET_KEY_JOG -> uiCtx.getString(R.string.preset_jog)
        SPEED_PRESET_KEY_RUN -> uiCtx.getString(R.string.preset_run)
        SPEED_PRESET_KEY_SPRINT -> uiCtx.getString(R.string.preset_sprint)
        else -> name
    }
}

private fun inferBuiltInPresetKey(id: String, name: String): String? {
    val trimmed = name.trim()
    return when (id) {
        SPEED_PRESET_KEY_WALK ->
            if (trimmed.equals("Walk", ignoreCase = true) || trimmed == "\u6563\u6B65") SPEED_PRESET_KEY_WALK else null
        SPEED_PRESET_KEY_JOG ->
            if (trimmed.equals("Jog", ignoreCase = true) || trimmed == "\u6162\u8DD1") SPEED_PRESET_KEY_JOG else null
        SPEED_PRESET_KEY_RUN ->
            if (trimmed.equals("Run", ignoreCase = true) || trimmed == "\u5FEB\u8DD1") SPEED_PRESET_KEY_RUN else null
        SPEED_PRESET_KEY_SPRINT ->
            if (trimmed.equals("Sprint", ignoreCase = true) || trimmed == "\u51B2\u523A") SPEED_PRESET_KEY_SPRINT else null
        else -> null
    }
}

/**
 * Layer 1 Seamless Auto-Restore Workspace Draft + Preferences (100% state survival across restarts).
 */
data class AppSettings(
    val layerType: MapLayerType = MapLayerType.AMAP_SATELLITE,
    val tiandituToken: String = "",
    val amapWebKey: String = "",
    val lastLatWgs84: Double = 39.9042,
    val lastLonWgs84: Double = 116.4074,
    val lastZoom: Double = 15.5,
    val speedMps: Float = 2.8f,
    val motionMode: Int = KurokoProtocol.MOTION_AUTO,
    val carryMode: Int = KurokoProtocol.CARRY_HAND_SWING,
    val staticJitterM: Float = 1.5f,
    val dynamicJitterM: Float = 2.0f,
    val paceVarRatio: Float = 0.08f,
    val activeTab: Int = 0,
    val sheetExpanded: Boolean = true,
    val activeRouteProfileId: String? = null,
    val activePointProfileId: String? = null,
    val overlayEnabled: Boolean = false,
    val overlayAutoHideInApp: Boolean = true,
    val overlaySecureFlag: Boolean = false,
    val overlaySideRight: Boolean = true,
    val overlayYFraction: Float = 0.28f
)

data class WorkspaceDraft(
    val lastStaticPoint: GeoPoint? = null,
    val lastStaticLabel: String = "",
    val draftWaypoints: List<GeoPoint> = emptyList(),
    val draftLoop: Boolean = true,
    val draftMaxLaps: Int = 0,
    val draftTargetDistKm: Float = 0f
)

fun defaultSpeedPresets(): List<CustomSpeedPreset> = listOf(
    CustomSpeedPreset(
        id = SPEED_PRESET_KEY_WALK,
        name = "Walk",
        speedMps = 1.3f,
        paceVarRatio = 0.06f,
        carryMode = KurokoProtocol.CARRY_HAND_SWING,
        motionMode = KurokoProtocol.MOTION_WALK,
        builtInKey = SPEED_PRESET_KEY_WALK
    ),
    CustomSpeedPreset(
        id = SPEED_PRESET_KEY_JOG,
        name = "Jog",
        speedMps = 2.8f,
        paceVarRatio = 0.08f,
        carryMode = KurokoProtocol.CARRY_HAND_SWING,
        motionMode = KurokoProtocol.MOTION_JOG,
        builtInKey = SPEED_PRESET_KEY_JOG
    ),
    CustomSpeedPreset(
        id = SPEED_PRESET_KEY_RUN,
        name = "Run",
        speedMps = 4.2f,
        paceVarRatio = 0.08f,
        carryMode = KurokoProtocol.CARRY_HAND_SWING,
        motionMode = KurokoProtocol.MOTION_RUN,
        builtInKey = SPEED_PRESET_KEY_RUN
    ),
    CustomSpeedPreset(
        id = SPEED_PRESET_KEY_SPRINT,
        name = "Sprint",
        speedMps = 5.8f,
        paceVarRatio = 0.10f,
        carryMode = KurokoProtocol.CARRY_HAND_SWING,
        motionMode = KurokoProtocol.MOTION_SPRINT,
        builtInKey = SPEED_PRESET_KEY_SPRINT
    )
)

class RouteStore(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val prefs = appContext.getSharedPreferences("kuroko_store_v1", Context.MODE_PRIVATE)

    private fun localizedCtx(): Context = AppLocaleManager.localizedContext(appContext)

    fun loadSettings(): AppSettings {
        val legacyJitter = if (prefs.contains(KEY_LEGACY_JITTER)) {
            prefs.getFloat(KEY_LEGACY_JITTER, 2.0f)
        } else {
            null
        }
        val staticJ = if (prefs.contains(KEY_STATIC_JITTER)) {
            prefs.getFloat(KEY_STATIC_JITTER, 1.5f)
        } else {
            (legacyJitter ?: 1.5f).coerceIn(0f, 8f)
        }
        val dynamicJ = if (prefs.contains(KEY_DYNAMIC_JITTER)) {
            prefs.getFloat(KEY_DYNAMIC_JITTER, 2.0f)
        } else {
            (legacyJitter ?: 2.0f).coerceIn(0f, 8f)
        }

        return AppSettings(
            layerType = MapLayerType.fromId(prefs.getString(KEY_LAYER, MapLayerType.AMAP_SATELLITE.id)),
            tiandituToken = prefs.getString(KEY_TDT_TOKEN, "") ?: "",
            amapWebKey = prefs.getString(KEY_AMAP_KEY, "") ?: "",
            lastLatWgs84 = java.lang.Double.longBitsToDouble(
                prefs.getLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(39.9042))
            ),
            lastLonWgs84 = java.lang.Double.longBitsToDouble(
                prefs.getLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(116.4074))
            ),
            lastZoom = java.lang.Double.longBitsToDouble(
                prefs.getLong(KEY_LAST_ZOOM, java.lang.Double.doubleToRawLongBits(15.5))
            ),
            speedMps = prefs.getFloat(KEY_SPEED, 2.8f).coerceIn(0.5f, 8.0f),
            motionMode = prefs.getInt(KEY_MOTION_MODE, KurokoProtocol.MOTION_AUTO),
            carryMode = prefs.getInt(KEY_CARRY_MODE, KurokoProtocol.CARRY_HAND_SWING),
            staticJitterM = staticJ.coerceIn(0f, 8f),
            dynamicJitterM = dynamicJ.coerceIn(0f, 8f),
            paceVarRatio = prefs.getFloat(KEY_PACE_VAR, 0.08f).coerceIn(0f, 0.20f),
            activeTab = prefs.getInt(KEY_ACTIVE_TAB, 0).coerceIn(0, 1),
            sheetExpanded = prefs.getBoolean(KEY_SHEET_EXPANDED, true),
            activeRouteProfileId = prefs.getString(KEY_ACTIVE_ROUTE_ID, null)?.takeIf { it.isNotBlank() },
            activePointProfileId = prefs.getString(KEY_ACTIVE_POINT_ID, null)?.takeIf { it.isNotBlank() },
            overlayEnabled = prefs.getBoolean(KEY_OVERLAY_ENABLED, false),
            overlayAutoHideInApp = prefs.getBoolean(KEY_OVERLAY_AUTO_HIDE, true),
            overlaySecureFlag = prefs.getBoolean(KEY_OVERLAY_SECURE, false),
            overlaySideRight = prefs.getBoolean(KEY_OVERLAY_SIDE_RIGHT, true),
            overlayYFraction = prefs.getFloat(KEY_OVERLAY_Y_FRACTION, 0.28f).coerceIn(0.05f, 0.85f)
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_LAYER, settings.layerType.id)
            .putString(KEY_TDT_TOKEN, settings.tiandituToken)
            .putString(KEY_AMAP_KEY, settings.amapWebKey)
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(settings.lastLatWgs84))
            .putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(settings.lastLonWgs84))
            .putLong(KEY_LAST_ZOOM, java.lang.Double.doubleToRawLongBits(settings.lastZoom))
            .putFloat(KEY_SPEED, settings.speedMps)
            .putInt(KEY_MOTION_MODE, settings.motionMode)
            .putInt(KEY_CARRY_MODE, settings.carryMode)
            .putFloat(KEY_STATIC_JITTER, settings.staticJitterM)
            .putFloat(KEY_DYNAMIC_JITTER, settings.dynamicJitterM)
            .putFloat(KEY_PACE_VAR, settings.paceVarRatio)
            .putInt(KEY_ACTIVE_TAB, settings.activeTab)
            .putBoolean(KEY_SHEET_EXPANDED, settings.sheetExpanded)
            .putString(KEY_ACTIVE_ROUTE_ID, settings.activeRouteProfileId ?: "")
            .putString(KEY_ACTIVE_POINT_ID, settings.activePointProfileId ?: "")
            // Overlay prefs (KEY_OVERLAY_*) are exclusively written via updateOverlayPrefs()
            // so debounced MainActivity saves never clobber concurrent overlay changes.
            .apply()
    }

    fun updateMotionPrefs(
        speedMps: Float? = null,
        paceVarRatio: Float? = null,
        motionMode: Int? = null,
        carryMode: Int? = null
    ) {
        val editor = prefs.edit()
        if (speedMps != null) editor.putFloat(KEY_SPEED, speedMps.coerceIn(0.5f, 8.0f))
        if (paceVarRatio != null) editor.putFloat(KEY_PACE_VAR, paceVarRatio.coerceIn(0f, 0.20f))
        if (motionMode != null) editor.putInt(KEY_MOTION_MODE, motionMode)
        if (carryMode != null) editor.putInt(KEY_CARRY_MODE, carryMode.coerceIn(0, 1))
        editor.apply()
    }

    fun updateOverlayPrefs(
        enabled: Boolean? = null,
        autoHideInApp: Boolean? = null,
        secureFlag: Boolean? = null,
        sideRight: Boolean? = null,
        yFraction: Float? = null
    ) {
        val editor = prefs.edit()
        if (enabled != null) editor.putBoolean(KEY_OVERLAY_ENABLED, enabled)
        if (autoHideInApp != null) editor.putBoolean(KEY_OVERLAY_AUTO_HIDE, autoHideInApp)
        if (secureFlag != null) editor.putBoolean(KEY_OVERLAY_SECURE, secureFlag)
        if (sideRight != null) editor.putBoolean(KEY_OVERLAY_SIDE_RIGHT, sideRight)
        if (yFraction != null) editor.putFloat(KEY_OVERLAY_Y_FRACTION, yFraction.coerceIn(0.05f, 0.85f))
        editor.apply()
    }

    fun loadDraft(): WorkspaceDraft {
        val defaultLabel = localizedCtx().getString(R.string.route_default_crosshair_label)
        val raw = prefs.getString(KEY_DRAFT, null)
            ?: return WorkspaceDraft(lastStaticLabel = defaultLabel)
        return runCatching {
            val o = JSONObject(raw)
            val spObj = o.optJSONObject("staticPoint")
            val staticPt = if (spObj != null) {
                val lat = spObj.optDouble("lat", Double.NaN)
                val lon = spObj.optDouble("lon", Double.NaN)
                val alt = spObj.optDouble("alt", 45.0)
                if (CoordTransform.isValidWgs84(lat, lon, alt)) GeoPoint(lat, lon, alt) else null
            } else {
                null
            }
            val ptsArr = o.optJSONArray("waypoints") ?: JSONArray()
            val pts = mutableListOf<GeoPoint>()
            for (i in 0 until ptsArr.length()) {
                val p = ptsArr.optJSONObject(i) ?: continue
                val lat = p.optDouble("lat", Double.NaN)
                val lon = p.optDouble("lon", Double.NaN)
                val alt = p.optDouble("alt", 45.0)
                if (CoordTransform.isValidWgs84(lat, lon, alt)) {
                    pts.add(GeoPoint(lat, lon, alt))
                }
            }
            val rawLabel = o.optString("staticLabel", "")
            val resolvedLabel = if (rawLabel.isBlank() || rawLabel == LEGACY_CROSSHAIR_LABEL) {
                defaultLabel
            } else {
                rawLabel
            }
            WorkspaceDraft(
                lastStaticPoint = staticPt,
                lastStaticLabel = resolvedLabel,
                draftWaypoints = pts,
                draftLoop = o.optBoolean("loop", true),
                draftMaxLaps = o.optInt("maxLaps", 0).coerceAtLeast(0),
                draftTargetDistKm = o.optDouble("targetKm", 0.0).toFloat().coerceAtLeast(0f)
            )
        }.getOrDefault(WorkspaceDraft(lastStaticLabel = defaultLabel))
    }

    fun saveDraft(draft: WorkspaceDraft) {
        val o = JSONObject().apply {
            if (draft.lastStaticPoint != null) {
                put(
                    "staticPoint",
                    JSONObject().apply {
                        put("lat", draft.lastStaticPoint.lat)
                        put("lon", draft.lastStaticPoint.lon)
                        put("alt", draft.lastStaticPoint.alt)
                    }
                )
            }
            put("staticLabel", draft.lastStaticLabel)
            val arr = JSONArray()
            for (p in draft.draftWaypoints) {
                arr.put(JSONObject().apply {
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("alt", p.alt)
                })
            }
            put("waypoints", arr)
            put("loop", draft.draftLoop)
            put("maxLaps", draft.draftMaxLaps)
            put("targetKm", draft.draftTargetDistKm.toDouble())
        }
        prefs.edit().putString(KEY_DRAFT, o.toString()).apply()
    }

    fun loadSpeedPresets(): List<CustomSpeedPreset> {
        val defaults = defaultSpeedPresets()
        val raw = prefs.getString(KEY_SPEED_PRESETS, null) ?: return defaults
        return runCatching {
            val arr = JSONArray(raw)
            if (arr.length() != defaults.size) return@runCatching defaults
            val out = mutableListOf<CustomSpeedPreset>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return@runCatching defaults
                val def = defaults[i]
                val parsedId = o.optString("id", def.id).ifBlank { def.id }
                val parsedName = o.optString("name", def.name).ifBlank { def.name }
                val builtIn = if (o.has("builtInKey")) {
                    o.optString("builtInKey").takeIf { it.isNotBlank() }
                } else {
                    inferBuiltInPresetKey(parsedId, parsedName)
                }
                out.add(
                    CustomSpeedPreset(
                        id = parsedId,
                        name = parsedName,
                        speedMps = o.optDouble("speedMps", def.speedMps.toDouble()).toFloat().coerceIn(0.5f, 8.0f),
                        paceVarRatio = o.optDouble("paceVar", def.paceVarRatio.toDouble()).toFloat().coerceIn(0f, 0.20f),
                        carryMode = o.optInt("carryMode", def.carryMode).coerceIn(0, 1),
                        motionMode = o.optInt("motionMode", def.motionMode),
                        builtInKey = builtIn
                    )
                )
            }
            out
        }.getOrDefault(defaults)
    }

    fun saveSpeedPresets(presets: List<CustomSpeedPreset>) {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("speedMps", p.speedMps.toDouble())
                    put("paceVar", p.paceVarRatio.toDouble())
                    put("carryMode", p.carryMode)
                    put("motionMode", p.motionMode)
                    put("builtInKey", p.builtInKey ?: "")
                }
            )
        }
        prefs.edit().putString(KEY_SPEED_PRESETS, arr.toString()).apply()
    }

    fun loadRoutes(): List<SavedRoute> {
        val raw = prefs.getString(KEY_ROUTES, null) ?: return emptyList()
        val fallbackName = localizedCtx().getString(R.string.route_unnamed)
        return parseRoutesJson(raw, fallbackName = fallbackName, strictImport = false)
            .getOrDefault(emptyList())
    }

    fun saveRoutes(routes: List<SavedRoute>) {
        prefs.edit().putString(KEY_ROUTES, compactGson.toJson(encodeRoutesJsonArray(routes))).apply()
    }

    fun exportRoutesJson(routes: List<SavedRoute>): String {
        return Companion.exportRoutesJson(routes)
    }

    fun importRoutesJson(jsonText: String): Result<List<SavedRoute>> {
        val uiCtx = localizedCtx()
        return parseRoutesJson(
            jsonText = jsonText,
            fallbackName = uiCtx.getString(R.string.route_unnamed),
            strictImport = true,
            emptyErr = uiCtx.getString(R.string.route_import_err_empty),
            noRoutesErr = uiCtx.getString(R.string.route_import_err_no_routes),
            invalidErr = uiCtx.getString(R.string.route_import_err_invalid)
        )
    }

    fun loadRawTargets(): List<SavedTargetApp> {
        val raw = prefs.getString(KEY_TARGETS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<SavedTargetApp>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("pkg")
                val uid = o.optInt("uid", -1)
                val policy = o.optInt("policy", KurokoProtocol.POLICY_ALL_STRICT)
                if (pkg.isNotBlank()) {
                    out.add(SavedTargetApp(pkg, uid, policy))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * Re-resolves each candidate package's UID for the current Android user via PackageManager.
     * Prunes uninstalled packages, updates reassigned/reinstalled UIDs, rejects self UID,
     * and omits transient LookupError packages from activeTargets without wiping persisted choices.
     */
    fun resolveTargets(
        candidates: List<SavedTargetApp>,
        persistIfChanged: Boolean = true
    ): TargetResolutionResult {
        val pm = appContext.packageManager
        val selfUid = Process.myUid()
        val result = resolveSavedTargets(candidates, selfUid) { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                PackageUidLookup.Found(info.uid)
            } catch (_: PackageManager.NameNotFoundException) {
                PackageUidLookup.NotInstalled
            } catch (_: Throwable) {
                PackageUidLookup.LookupError
            }
        }
        if (persistIfChanged && result.changed) {
            saveTargets(result.persistedTargets)
        }
        return result
    }

    fun resolveActiveTargets(persistIfChanged: Boolean = true): List<SavedTargetApp> {
        return resolveTargets(loadRawTargets(), persistIfChanged = persistIfChanged).activeTargets
    }

    fun loadTargets(): List<SavedTargetApp> {
        return resolveActiveTargets(persistIfChanged = true)
    }

    fun saveTargets(targets: List<SavedTargetApp>) {
        val arr = JSONArray()
        for (t in targets) {
            arr.put(
                JSONObject().apply {
                    put("pkg", t.packageName)
                    put("uid", t.uid)
                    put("policy", t.policyFlags)
                }
            )
        }
        prefs.edit().putString(KEY_TARGETS, arr.toString()).apply()
    }

    companion object {
        private const val LEGACY_CROSSHAIR_LABEL = "\u5F53\u524D\u51C6\u661F\u4F4D\u7F6E"
        private const val KEY_LAYER = "layer_type"
        private const val KEY_TDT_TOKEN = "tianditu_token"
        private const val KEY_AMAP_KEY = "amap_web_key"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        private const val KEY_LAST_ZOOM = "last_zoom"
        private const val KEY_SPEED = "speed_mps"
        private const val KEY_MOTION_MODE = "motion_mode"
        private const val KEY_CARRY_MODE = "carry_mode"
        private const val KEY_LEGACY_JITTER = "jitter_m"
        private const val KEY_STATIC_JITTER = "static_jitter_m"
        private const val KEY_DYNAMIC_JITTER = "dynamic_jitter_m"
        private const val KEY_PACE_VAR = "pace_var_ratio"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_SHEET_EXPANDED = "sheet_expanded"
        private const val KEY_ACTIVE_ROUTE_ID = "active_route_profile_id"
        private const val KEY_ACTIVE_POINT_ID = "active_point_profile_id"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_AUTO_HIDE = "overlay_auto_hide_in_app"
        private const val KEY_OVERLAY_SECURE = "overlay_secure_flag"
        private const val KEY_OVERLAY_SIDE_RIGHT = "overlay_side_right"
        private const val KEY_OVERLAY_Y_FRACTION = "overlay_y_fraction"
        private const val KEY_DRAFT = "workspace_draft_json"
        private const val KEY_SPEED_PRESETS = "custom_speed_presets_json"
        private const val KEY_ROUTES = "saved_routes_json"
        private const val KEY_TARGETS = "saved_targets_json"

        private val compactGson = GsonBuilder().disableHtmlEscaping().create()
        private val prettyGson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

        fun exportRoutesJson(routes: List<SavedRoute>): String {
            val root = JsonObject().apply {
                addProperty("type", "kuroko_route")
                addProperty("version", 1)
                add("routes", encodeRoutesJsonArray(routes))
            }
            return prettyGson.toJson(root)
        }

        fun parseRoutesJson(
            jsonText: String,
            fallbackName: String = "Route",
            strictImport: Boolean = false,
            emptyErr: String = "Empty route JSON",
            noRoutesErr: String = "Missing routes array",
            invalidErr: String = "Invalid route data"
        ): Result<List<SavedRoute>> = runCatching {
            val trimmed = jsonText.trim()
            require(trimmed.isNotEmpty()) { emptyErr }
            val parsedElem = JsonParser.parseString(trimmed)
            val arr = when {
                parsedElem.isJsonArray -> parsedElem.asJsonArray
                parsedElem.isJsonObject -> {
                    val root = parsedElem.asJsonObject
                    val routesElem = root.get("routes")
                    if (routesElem != null && routesElem.isJsonArray) {
                        routesElem.asJsonArray
                    } else {
                        throw IllegalArgumentException(noRoutesErr)
                    }
                }
                else -> throw IllegalArgumentException(invalidErr)
            }
            val parsed = parseRoutesFromArray(
                arr = arr,
                fallbackName = fallbackName,
                strictImport = strictImport,
                invalidErr = invalidErr
            )
            if (strictImport) {
                require(parsed.isNotEmpty()) { invalidErr }
            }
            parsed
        }

        internal fun parseRoutesFromArray(
            arr: JsonArray,
            fallbackName: String = "Route",
            strictImport: Boolean = false,
            invalidErr: String = "Invalid route data"
        ): List<SavedRoute> {
            val list = mutableListOf<SavedRoute>()
            for (i in 0 until arr.size()) {
                val elem = arr.get(i)
                if (elem == null || !elem.isJsonObject) {
                    if (strictImport) throw IllegalArgumentException(invalidErr)
                    continue
                }
                val obj = elem.asJsonObject
                val ptsElem = obj.get("waypoints")
                if (ptsElem == null || !ptsElem.isJsonArray) {
                    if (strictImport) throw IllegalArgumentException(invalidErr)
                    continue
                }
                val ptsArr = ptsElem.asJsonArray
                val pts = mutableListOf<GeoPoint>()
                var hadInvalidWaypoint = false
                for (j in 0 until ptsArr.size()) {
                    val wpElem = ptsArr.get(j)
                    if (wpElem == null || !wpElem.isJsonObject) {
                        hadInvalidWaypoint = true
                        continue
                    }
                    val p = wpElem.asJsonObject
                    val lat = p.optCoordDouble("lat")
                    val lon = p.optCoordDouble("lon")
                    val alt = p.optOptionalAltDouble("alt", 45.0)
                    if (CoordTransform.isValidWgs84(lat, lon, alt)) {
                        pts.add(GeoPoint(lat, lon, alt))
                    } else {
                        hadInvalidWaypoint = true
                    }
                }
                val loop = obj.optBool("loop", true)
                if (strictImport) {
                    if (hadInvalidWaypoint || pts.size < 2 || CoordTransform.routeLengthMeters(pts, loop) <= CoordTransform.MIN_ROUTE_LENGTH_M) {
                        throw IllegalArgumentException(invalidErr)
                    }
                }
                if (pts.isNotEmpty()) {
                    val ts = when {
                        obj.has("updatedAt") -> obj.optLongSafe("updatedAt", System.currentTimeMillis())
                        else -> obj.optLongSafe("createdAt", System.currentTimeMillis())
                    }
                    list.add(
                        SavedRoute(
                            id = obj.optStr("id").ifBlank { UUID.randomUUID().toString() },
                            name = obj.optStr("name", fallbackName).ifBlank { fallbackName },
                            waypoints = pts,
                            loop = loop,
                            maxLaps = obj.optIntSafe("maxLaps", 0).coerceAtLeast(0),
                            targetDistanceKm = obj.optDoubleSafe("targetKm", 0.0).toFloat().coerceAtLeast(0f),
                            speedMps = obj.optDoubleSafe("speedMps", 2.8).toFloat().coerceIn(0.5f, 8.0f),
                            paceVarRatio = obj.optDoubleSafe("paceVar", 0.08).toFloat().coerceIn(0f, 0.20f),
                            motionMode = obj.optIntSafe("motionMode", KurokoProtocol.MOTION_AUTO),
                            carryMode = obj.optIntSafe("carryMode", KurokoProtocol.CARRY_HAND_SWING).coerceIn(0, 1),
                            jitterM = obj.optDoubleSafe("jitterM", 2.0).toFloat().coerceIn(0f, 8.0f),
                            updatedAtMs = ts
                        )
                    )
                }
            }
            return list.distinctBy { it.id }
        }

        private fun encodeRoutesJsonArray(routes: List<SavedRoute>): JsonArray {
            val arr = JsonArray()
            for (r in routes) {
                val ptsArr = JsonArray()
                for (p in r.waypoints) {
                    ptsArr.add(JsonObject().apply {
                        addProperty("lat", p.lat)
                        addProperty("lon", p.lon)
                        addProperty("alt", p.alt)
                    })
                }
                arr.add(
                    JsonObject().apply {
                        addProperty("id", r.id)
                        addProperty("name", r.name)
                        add("waypoints", ptsArr)
                        addProperty("loop", r.loop)
                        addProperty("maxLaps", r.maxLaps)
                        addProperty("targetKm", r.targetDistanceKm.toDouble())
                        addProperty("speedMps", r.speedMps.toDouble())
                        addProperty("paceVar", r.paceVarRatio.toDouble())
                        addProperty("motionMode", r.motionMode)
                        addProperty("carryMode", r.carryMode)
                        addProperty("jitterM", r.jitterM.toDouble())
                        addProperty("updatedAt", r.updatedAtMs)
                    }
                )
            }
            return arr
        }

        private fun JsonObject.optCoordDouble(key: String): Double {
            val elem = get(key) ?: return Double.NaN
            if (elem.isJsonNull || !elem.isJsonPrimitive) return Double.NaN
            val p = elem.asJsonPrimitive
            return when {
                p.isNumber -> runCatching { p.asDouble }.getOrDefault(Double.NaN)
                p.isString -> p.asString.trim().toDoubleOrNull() ?: Double.NaN
                else -> Double.NaN
            }
        }

        private fun JsonObject.optOptionalAltDouble(key: String, default: Double): Double {
            if (!has(key)) return default
            val elem = get(key)
            if (elem == null || elem.isJsonNull) return default
            if (!elem.isJsonPrimitive) return Double.NaN
            val p = elem.asJsonPrimitive
            return when {
                p.isNumber -> runCatching { p.asDouble }.getOrDefault(Double.NaN)
                p.isString -> p.asString.trim().toDoubleOrNull() ?: Double.NaN
                else -> Double.NaN
            }
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

        private fun JsonObject.optDoubleSafe(key: String, default: Double): Double {
            val elem = get(key) ?: return default
            if (elem.isJsonNull || !elem.isJsonPrimitive) return default
            val p = elem.asJsonPrimitive
            val v = when {
                p.isNumber -> runCatching { p.asDouble }.getOrDefault(default)
                p.isString -> p.asString.trim().toDoubleOrNull() ?: default
                else -> default
            }
            return if (v.isFinite()) v else default
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesSheet(
    routes: List<SavedRoute>,
    activeProfileId: String?,
    currentWaypoints: List<GeoPoint>,
    currentLoop: Boolean,
    currentMaxLaps: Int,
    currentSpeedMps: Float,
    currentPaceVarRatio: Float,
    currentCarryMode: Int,
    currentJitterM: Float,
    onDismiss: () -> Unit,
    onSaveAsNew: (String) -> Unit,
    onUpdateExisting: (SavedRoute, String) -> Unit,
    onLoadFullProfile: (SavedRoute) -> Unit,
    onLoadRouteOnly: (SavedRoute) -> Unit,
    onDeleteRoute: (SavedRoute) -> Unit,
    onExportJson: () -> String,
    onImportJson: (String) -> Result<Int>,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeRoute = remember(routes, activeProfileId) {
        routes.firstOrNull { it.id == activeProfileId }
    }
    var routeName by remember(activeRoute?.id) { mutableStateOf(activeRoute?.name ?: "") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NeuColors.Surface,
        contentColor = NeuColors.InkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header + Clipboard Export/Import
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Route, contentDescription = null, tint = NeuColors.CobaltDeep)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.route_sheet_title, routes.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.InkPrimary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeuButton(
                        onClick = {
                            if (routes.isEmpty()) {
                                onNotify(context.getString(R.string.route_export_empty))
                            } else {
                                val json = onExportJson()
                                val copied = runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            context.getString(R.string.route_clipboard_label),
                                            json
                                        )
                                    )
                                    cm != null
                                }.getOrDefault(false)
                                onNotify(
                                    if (copied) context.getString(R.string.route_export_copied, routes.size)
                                    else context.getString(R.string.route_clipboard_write_failed)
                                )
                            }
                        },
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.route_action_export),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    NeuButton(
                        onClick = {
                            val clip = runCatching {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val pc = cm?.primaryClip
                                if (pc != null && pc.itemCount > 0) {
                                    pc.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                } else {
                                    ""
                                }
                            }.getOrDefault("")
                            importText = if (clip.trim().startsWith("{") || clip.trim().startsWith("[")) clip else ""
                            showImportDialog = true
                        },
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.route_action_import),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            // Save / Overwrite Scenario Profile Card
            if (currentWaypoints.size >= 2) {
                val curLen = CoordTransform.routeLengthMeters(currentWaypoints, currentLoop)
                val lapText = if (currentMaxLaps <= 0) {
                    stringResource(R.string.route_laps_infinite)
                } else {
                    stringResource(R.string.route_laps_count, currentMaxLaps)
                }
                val carryText = stringResource(
                    if (currentCarryMode == KurokoProtocol.CARRY_POCKET) {
                        R.string.route_carry_pocket
                    } else {
                        R.string.route_carry_hand
                    }
                )
                val paceText = CoordTransform.formatPace(currentSpeedMps).removeSuffix("/km")
                val varPct = (currentPaceVarRatio * 100f).roundToInt()

                NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, elevation = 4.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .neuInset(cornerRadius = 10.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.route_summary_line,
                                    CoordTransform.formatDistance(curLen),
                                    lapText,
                                    paceText,
                                    varPct,
                                    carryText,
                                    String.format(Locale.US, "%.1f", currentJitterM)
                                ),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = NeuColors.InkPrimary
                            )
                        }
                        OutlinedTextField(
                            value = routeName,
                            onValueChange = { routeName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.route_name_label)) },
                            placeholder = { Text(stringResource(R.string.route_name_placeholder)) }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (activeRoute != null) {
                                NeuButton(
                                    onClick = {
                                        val name = routeName.trim().ifEmpty { activeRoute.name }
                                        onUpdateExisting(activeRoute, name)
                                    },
                                    modifier = Modifier.weight(1f),
                                    tone = NeuTone.EMERALD,
                                    contentPadding = PaddingValues(vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.route_btn_update),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            NeuButton(
                                onClick = {
                                    val name = routeName.trim().ifEmpty {
                                        context.getString(R.string.route_default_name_fmt, dateFmt.format(Date()))
                                    }
                                    onSaveAsNew(name)
                                    routeName = ""
                                },
                                modifier = Modifier.weight(1f),
                                tone = NeuTone.COBALT,
                                contentPadding = PaddingValues(vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(
                                        if (activeRoute != null) R.string.route_btn_save_as_new
                                        else R.string.route_btn_save_new
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 14.dp, surfaceColor = NeuColors.InsetBg, depth = 3.dp)
                        .padding(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.route_need_two_points_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NeuColors.InkSecondary
                    )
                }
            }

            // Saved Scenario Cards
            if (routes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 14.dp, surfaceColor = NeuColors.InsetBg, depth = 3.dp)
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.route_empty_list),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeuColors.InkSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(routes, key = { it.id }) { route ->
                        val isActive = route.id == activeProfileId
                        val lenM = CoordTransform.routeLengthMeters(route.waypoints, route.loop)
                        val lapDesc = if (route.maxLaps <= 0) {
                            stringResource(R.string.route_laps_infinite)
                        } else {
                            stringResource(R.string.route_laps_count, route.maxLaps)
                        }
                        val carryDesc = stringResource(
                            if (route.carryMode == KurokoProtocol.CARRY_POCKET) {
                                R.string.route_carry_pocket
                            } else {
                                R.string.route_carry_hand
                            }
                        )
                        val paceStr = CoordTransform.formatPace(route.speedMps).removeSuffix("/km")
                        val varPct = (route.paceVarRatio * 100f).roundToInt()

                        NeuCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 14.dp,
                            elevation = if (isActive) 5.dp else 3.5.dp,
                            surfaceColor = if (isActive) NeuColors.SurfaceHighlight else NeuColors.SurfaceMatte,
                            onClick = {
                                onLoadFullProfile(route)
                                onDismiss()
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isActive) {
                                                NeuLedDot(color = NeuColors.LedGreen, lit = true, size = 6.dp)
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                text = route.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = NeuColors.InkPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (route.loop) {
                                                Spacer(Modifier.width(5.dp))
                                                Icon(
                                                    Icons.Default.Loop,
                                                    contentDescription = stringResource(R.string.route_cd_closed_loop),
                                                    modifier = Modifier.size(14.dp),
                                                    tint = NeuColors.Emerald
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = stringResource(
                                                R.string.route_summary_line,
                                                CoordTransform.formatDistance(lenM),
                                                lapDesc,
                                                paceStr,
                                                varPct,
                                                carryDesc,
                                                String.format(Locale.US, "%.1f", route.jitterM)
                                            ),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = NeuColors.InkSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteRoute(route) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = stringResource(R.string.route_cd_delete),
                                            tint = NeuColors.Coral,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.route_card_meta_hint, route.waypoints.size),
                                        fontSize = 10.sp,
                                        color = NeuColors.InkMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    NeuButton(
                                        onClick = {
                                            onLoadRouteOnly(route)
                                            onDismiss()
                                        },
                                        tone = NeuTone.NEUTRAL,
                                        cornerRadius = 9.dp,
                                        elevation = 2.dp,
                                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.route_btn_load_path_only),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = NeuColors.CobaltDeep,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = NeuColors.Surface,
            titleContentColor = NeuColors.InkPrimary,
            textContentColor = NeuColors.InkPrimary,
            title = { Text(stringResource(R.string.route_import_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.route_import_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NeuColors.InkSecondary
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        label = { Text(stringResource(R.string.route_import_json_label)) }
                    )
                }
            },
            confirmButton = {
                NeuButton(
                    onClick = {
                        onImportJson(importText)
                            .onSuccess { count ->
                                showImportDialog = false
                                onNotify(context.getString(R.string.route_import_success, count))
                            }
                            .onFailure { err ->
                                onNotify(context.getString(R.string.route_import_failed, err.message.orEmpty()))
                            }
                    },
                    tone = NeuTone.COBALT,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.route_action_import), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.route_action_cancel), color = NeuColors.InkSecondary)
                }
            }
        )
    }
}
