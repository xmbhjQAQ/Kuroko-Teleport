package com.kuroko.teleport.update

import android.content.Context
import com.kuroko.teleport.BuildConfig
import com.kuroko.teleport.diag.AppLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseUrl: String,
    val moduleZipUrl: String? = null,
    val moduleZipSizeBytes: Long? = null,
    val publishedDate: String? = null,
    val changelog: String = ""
)

sealed class UpdateCheckOutcome {
    data class UpdateAvailable(
        val info: ReleaseUpdateInfo,
        val isIgnored: Boolean
    ) : UpdateCheckOutcome()

    data class UpToDate(
        val currentVersion: String,
        val latestTag: String?
    ) : UpdateCheckOutcome()

    data class Disabled(
        val channel: String
    ) : UpdateCheckOutcome()

    data class Failed(
        val message: String
    ) : UpdateCheckOutcome()
}

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val isPrerelease: Boolean = false
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // Stable release ranks strictly ahead of pre-release with the same numeric triplet
        return when {
            !isPrerelease && other.isPrerelease -> 1
            isPrerelease && !other.isPrerelease -> -1
            else -> 0
        }
    }

    companion object {
        private val SEMVER_REGEX = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].+)?$""")

        fun parse(raw: String?): SemVer? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val match = SEMVER_REGEX.matchEntire(trimmed) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val hasPrerelease = trimmed.contains('-')
            return SemVer(major, minor, patch, isPrerelease = hasPrerelease)
        }
    }
}

/**
 * Release-only GitHub update checker.
 *
 * - Compile-time gated by [BuildConfig.UPDATE_CHECK_ENABLED] (enabled strictly for GitHub Release
 *   builds; CI and default local builds perform zero network requests).
 * - Checks only published, non-draft, non-prerelease GitHub Releases (never CI artifacts/commits).
 * - Uses ETag conditional requests (`If-None-Match` -> 304) plus automatic fallback to
 *   `releases/latest/download/update.json` and redirect `Location` inspection to stay resilient
 *   under shared-IP GitHub API rate limits.
 * - Sends zero device identifiers or telemetry.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "kuroko_update_v1"
    private const val KEY_AUTO_CHECK = "auto_check_enabled"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_IGNORED_TAG = "ignored_tag"
    private const val KEY_CACHED_ETAG = "cached_etag"
    private const val KEY_CACHED_JSON = "cached_release_json"

    private const val AUTO_CHECK_COOLDOWN_MS = 24L * 60L * 60L * 1000L
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000
    private const val MAX_BODY_BYTES = 128 * 1024

    private val repoSlug: String
        get() = BuildConfig.GITHUB_REPO

    private val apiLatestUrl: String
        get() = "https://api.github.com/repos/$repoSlug/releases/latest"

    private val updateJsonUrl: String
        get() = "https://github.com/$repoSlug/releases/latest/download/update.json"

    private val webLatestUrl: String
        get() = "https://github.com/$repoSlug/releases/latest"

    private fun userAgent(): String = "Kuroko-Teleport/${BuildConfig.VERSION_NAME}"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isUpdateCheckSupported(): Boolean = BuildConfig.UPDATE_CHECK_ENABLED

    fun buildChannel(): String = BuildConfig.BUILD_CHANNEL

    fun isAutoCheckEnabled(context: Context): Boolean {
        if (!isUpdateCheckSupported()) return false
        return prefs(context).getBoolean(KEY_AUTO_CHECK, true)
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        if (!isUpdateCheckSupported()) return
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun getIgnoredTag(context: Context): String? =
        prefs(context).getString(KEY_IGNORED_TAG, null)?.takeIf { it.isNotBlank() }

    fun setIgnoredTag(context: Context, tagName: String?) {
        val editor = prefs(context).edit()
        if (tagName.isNullOrBlank()) {
            editor.remove(KEY_IGNORED_TAG)
        } else {
            editor.putString(KEY_IGNORED_TAG, tagName.trim())
        }
        editor.apply()
    }

    fun shouldAutoCheckNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isUpdateCheckSupported() || !isAutoCheckEnabled(context)) return false
        val lastMs = prefs(context).getLong(KEY_LAST_CHECK_MS, 0L)
        return lastMs <= 0L || (nowMs - lastMs) >= AUTO_CHECK_COOLDOWN_MS
    }

    suspend fun checkForUpdate(
        context: Context,
        manual: Boolean
    ): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        if (!isUpdateCheckSupported()) {
            return@withContext UpdateCheckOutcome.Disabled(BuildConfig.BUILD_CHANNEL)
        }

        val appCtx = context.applicationContext
        val localVer = SemVer.parse(BuildConfig.VERSION_NAME)
            ?: return@withContext UpdateCheckOutcome.Failed("Invalid local version: ${BuildConfig.VERSION_NAME}")

        val errors = mutableListOf<String>()
        var latest: ReleaseUpdateInfo? = null

        // Tier 1: GitHub Releases REST API with ETag caching (304 consumes 0 quota)
        when (val apiRes = fetchViaGitHubApi(appCtx)) {
            is FetchResult.Ok -> latest = apiRes.info
            is FetchResult.NoStableRelease -> {
                recordCheckTimestamp(appCtx)
                return@withContext UpdateCheckOutcome.UpToDate(BuildConfig.VERSION_NAME, null)
            }
            is FetchResult.Err -> errors += "API: ${apiRes.reason}"
        }

        // Tier 2: KernelSU update.json served directly from GitHub Release CDN (0 API rate limit)
        if (latest == null) {
            when (val cdnRes = fetchViaUpdateJson()) {
                is FetchResult.Ok -> latest = cdnRes.info
                is FetchResult.NoStableRelease -> Unit
                is FetchResult.Err -> errors += "CDN: ${cdnRes.reason}"
            }
        }

        // Tier 3: HEAD redirect on /releases/latest -> Location: .../releases/tag/vX.Y.Z (0 API rate limit)
        if (latest == null) {
            when (val redirRes = fetchViaLatestRedirect()) {
                is FetchResult.Ok -> latest = redirRes.info
                is FetchResult.NoStableRelease -> {
                    recordCheckTimestamp(appCtx)
                    return@withContext UpdateCheckOutcome.UpToDate(BuildConfig.VERSION_NAME, null)
                }
                is FetchResult.Err -> errors += "Redirect: ${redirRes.reason}"
            }
        }

        if (latest == null) {
            val detail = errors.firstOrNull() ?: "Network unavailable"
            if (manual) {
                AppLogBuffer.w(TAG, "Manual release check failed: ${errors.joinToString(" | ")}")
            }
            return@withContext UpdateCheckOutcome.Failed(detail)
        }

        recordCheckTimestamp(appCtx)
        val remoteVer = SemVer.parse(latest.versionName) ?: SemVer.parse(latest.tagName)
        if (remoteVer == null || remoteVer.isPrerelease) {
            return@withContext UpdateCheckOutcome.UpToDate(BuildConfig.VERSION_NAME, latest.tagName)
        }

        if (remoteVer > localVer) {
            val ignored = getIgnoredTag(appCtx)?.equals(latest.tagName, ignoreCase = true) == true
            AppLogBuffer.i(
                TAG,
                "Release update detected: local=v${BuildConfig.VERSION_NAME} -> remote=${latest.tagName} (ignored=$ignored, manual=$manual)"
            )
            return@withContext UpdateCheckOutcome.UpdateAvailable(latest, isIgnored = ignored)
        }

        UpdateCheckOutcome.UpToDate(BuildConfig.VERSION_NAME, latest.tagName)
    }

    private fun recordCheckTimestamp(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
    }

    private sealed class FetchResult {
        data class Ok(val info: ReleaseUpdateInfo) : FetchResult()
        object NoStableRelease : FetchResult()
        data class Err(val reason: String) : FetchResult()
    }

    private fun fetchViaGitHubApi(context: Context): FetchResult {
        val sp = prefs(context)
        val cachedEtag = sp.getString(KEY_CACHED_ETAG, null)?.takeIf { it.isNotBlank() }
        val cachedJson = sp.getString(KEY_CACHED_JSON, null)?.takeIf { it.isNotBlank() }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiLatestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", userAgent())
                if (cachedEtag != null && cachedJson != null) {
                    setRequestProperty("If-None-Match", cachedEtag)
                }
            }
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_MODIFIED && cachedJson != null -> {
                    parseGitHubReleaseJson(cachedJson)
                }
                code in 200..299 -> {
                    val body = readBoundedBody(conn)
                    val parsed = parseGitHubReleaseJson(body)
                    if (parsed is FetchResult.Ok) {
                        val etag = conn.getHeaderField("ETag")?.trim()
                        sp.edit().apply {
                            if (!etag.isNullOrEmpty()) putString(KEY_CACHED_ETAG, etag)
                            putString(KEY_CACHED_JSON, body)
                        }.apply()
                    }
                    parsed
                }
                code == HttpURLConnection.HTTP_NOT_FOUND -> FetchResult.NoStableRelease
                else -> FetchResult.Err("HTTP $code")
            }
        } catch (t: Throwable) {
            FetchResult.Err(t.message ?: t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseGitHubReleaseJson(rawJson: String): FetchResult {
        return try {
            val obj = JSONObject(rawJson)
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) {
                return FetchResult.NoStableRelease
            }
            val tag = obj.optString("tag_name").trim()
            if (tag.isEmpty()) return FetchResult.Err("Missing tag_name")
            val htmlUrl = obj.optString("html_url").trim().ifEmpty {
                "https://github.com/$repoSlug/releases/tag/$tag"
            }
            val publishedAt = obj.optString("published_at").trim().take(10).takeIf { it.length == 10 }
            val body = cleanReleaseNotes(obj.optString("body"))

            var zipUrl: String? = null
            var zipSize: Long? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name").trim()
                    if (name.endsWith("-ksu-module.zip", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)) {
                        val dl = asset.optString("browser_download_url").trim()
                        if (dl.startsWith("https://")) {
                            zipUrl = dl
                            val sz = asset.optLong("size", -1L)
                            if (sz > 0L) zipSize = sz
                            if (name.endsWith("-ksu-module.zip", ignoreCase = true)) break
                        }
                    }
                }
            }

            FetchResult.Ok(
                ReleaseUpdateInfo(
                    tagName = tag,
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    releaseUrl = htmlUrl,
                    moduleZipUrl = zipUrl,
                    moduleZipSizeBytes = zipSize,
                    publishedDate = publishedAt,
                    changelog = body
                )
            )
        } catch (t: Throwable) {
            FetchResult.Err("Parse error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun fetchViaUpdateJson(): FetchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(updateJsonUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", userAgent())
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return FetchResult.Err("HTTP $code")
            }
            val body = readBoundedBody(conn)
            val obj = JSONObject(body)
            val version = obj.optString("version").trim()
            if (version.isEmpty()) return FetchResult.Err("Missing version in update.json")
            val tag = if (version.startsWith("v", ignoreCase = true)) version else "v$version"
            val zipUrl = obj.optString("zipUrl").trim().takeIf { it.startsWith("https://") }
            val releaseUrl = "https://github.com/$repoSlug/releases/tag/$tag"
            FetchResult.Ok(
                ReleaseUpdateInfo(
                    tagName = tag,
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    releaseUrl = releaseUrl,
                    moduleZipUrl = zipUrl
                )
            )
        } catch (t: Throwable) {
            FetchResult.Err(t.message ?: t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchViaLatestRedirect(): FetchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(webLatestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent())
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")?.trim().orEmpty()
            if (code in 300..399 && location.contains("/releases/tag/")) {
                val tag = location.substringAfterLast("/releases/tag/").substringBefore('?').substringBefore('#').trim()
                if (tag.isEmpty()) return FetchResult.Err("Empty redirect tag")
                val releaseUrl = if (location.startsWith("https://")) {
                    location
                } else {
                    "https://github.com/$repoSlug/releases/tag/$tag"
                }
                FetchResult.Ok(
                    ReleaseUpdateInfo(
                        tagName = tag,
                        versionName = tag.removePrefix("v").removePrefix("V"),
                        releaseUrl = releaseUrl
                    )
                )
            } else if (code == HttpURLConnection.HTTP_NOT_FOUND || location.endsWith("/releases")) {
                FetchResult.NoStableRelease
            } else {
                FetchResult.Err("HTTP $code")
            }
        } catch (t: Throwable) {
            FetchResult.Err(t.message ?: t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBoundedBody(conn: HttpURLConnection): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
            val buf = CharArray(4096)
            var total = 0
            while (total < MAX_BODY_BYTES) {
                val n = reader.read(buf)
                if (n <= 0) break
                val take = minOf(n, MAX_BODY_BYTES - total)
                sb.append(buf, 0, take)
                total += take
            }
        }
        return sb.toString()
    }

    private fun cleanReleaseNotes(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val lines = raw.replace("\r\n", "\n").replace("\r", "\n").lines()
        val kept = mutableListOf<String>()
        var inChecksumBlock = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("### SHA-256", ignoreCase = true)) {
                inChecksumBlock = true
                continue
            }
            if (inChecksumBlock) {
                if (trimmed.startsWith("##") && !trimmed.startsWith("### SHA-256", ignoreCase = true)) {
                    inChecksumBlock = false
                } else {
                    continue
                }
            }
            kept += line.trimEnd()
        }
        val joined = kept.joinToString("\n").trim()
        return if (joined.length > 1400) joined.take(1400).trimEnd() + "\n…" else joined
    }
}
