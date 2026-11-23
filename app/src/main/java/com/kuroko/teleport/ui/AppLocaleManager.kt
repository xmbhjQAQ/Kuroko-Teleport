package com.kuroko.teleport.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.kuroko.teleport.ipc.KurokoRuntime
import com.kuroko.teleport.overlay.FloatingOverlayService
import java.util.Locale

/**
 * Declarative language entry for Kuroko Teleport's data-driven i18n registry.
 * To add a new language in the future:
 *   1. Add `app/src/main/res/values-<bcp47>/strings*.xml`
 *   2. Add `<locale android:name="<bcp47>" />` to `res/xml/locales_config.xml`
 *   3. Append one `SupportedLanguage(tag, endonym)` entry to `SUPPORTED_LANGUAGES` below.
 */
data class SupportedLanguage(
    val tag: String,
    val endonym: String,
    val isSystemDefault: Boolean = false
)

/**
 * Data-driven per-app language manager (Android 13+ native LocaleManager + API 31-32 fallback).
 * Shared contract with FloatingOverlayService and KurokoRuntime.
 */
object AppLocaleManager {
    const val ACTION_REFRESH_LOCALE = "com.kuroko.teleport.overlay.ACTION_REFRESH_LOCALE"
    const val TAG_AUTO = "auto"

    private const val PREFS_NAME = "kuroko_locale_prefs"
    private const val KEY_LANG_TAG = "app_lang_tag"
    private val SAFE_BCP47_REGEX = Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8}){0,2}$")

    /**
     * Single registry of UI-selectable languages.
     * Concrete entries always use their native endonym so users can always navigate back.
     */
    val SUPPORTED_LANGUAGES: List<SupportedLanguage> = listOf(
        SupportedLanguage(tag = TAG_AUTO, endonym = "", isSystemDefault = true),
        SupportedLanguage(tag = "zh-CN", endonym = "\u7B80\u4F53\u4E2D\u6587"), // Simplified Chinese endonym
        SupportedLanguage(tag = "en", endonym = "English")
    )

    val supportedLanguages: List<SupportedLanguage> get() = SUPPORTED_LANGUAGES

    fun isValidLangTag(rawTag: String?): Boolean {
        val t = rawTag?.trim().orEmpty()
        return t.equals(TAG_AUTO, ignoreCase = true) ||
            (t.length <= 16 && SAFE_BCP47_REGEX.matches(t))
    }

    fun normalizeTag(rawTag: String?): String {
        val cleaned = rawTag?.trim()?.replace('_', '-').orEmpty()
        if (cleaned.isEmpty() || cleaned.equals(TAG_AUTO, ignoreCase = true)) {
            return TAG_AUTO
        }
        // 1. Exact case-insensitive registry match (e.g. "zh-cn" -> "zh-CN", "en" -> "en")
        SUPPORTED_LANGUAGES.firstOrNull { !it.isSystemDefault && it.tag.equals(cleaned, ignoreCase = true) }
            ?.let { return it.tag }

        // 2. Match by parsed Locale language + region/script against registry
        val parsed = Locale.forLanguageTag(cleaned)
        if (parsed.language.isNotEmpty()) {
            SUPPORTED_LANGUAGES.firstOrNull { entry ->
                if (entry.isSystemDefault) return@firstOrNull false
                val el = Locale.forLanguageTag(entry.tag)
                el.language.equals(parsed.language, ignoreCase = true) &&
                    el.country.equals(parsed.country, ignoreCase = true)
            }?.let { return it.tag }

            SUPPORTED_LANGUAGES.firstOrNull { entry ->
                if (entry.isSystemDefault) return@firstOrNull false
                val el = Locale.forLanguageTag(entry.tag)
                el.language.equals(parsed.language, ignoreCase = true)
            }?.let { return it.tag }
        }

        return if (isValidLangTag(cleaned)) cleaned else TAG_AUTO
    }

    fun getSavedTag(context: Context): String {
        val appCtx = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = appCtx.getSystemService(LocaleManager::class.java)
            val appLocales = lm?.applicationLocales
            if (appLocales != null) {
                return if (appLocales.isEmpty) {
                    TAG_AUTO
                } else {
                    normalizeTag(appLocales[0]?.toLanguageTag())
                }
            }
        }
        return readSavedPrefOnly(appCtx)
    }

    fun applyLanguage(context: Context, tag: String) {
        val canonical = normalizeTag(tag)
        val appCtx = context.applicationContext ?: context
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG_TAG, canonical)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = appCtx.getSystemService(LocaleManager::class.java)
            lm?.applicationLocales = if (canonical == TAG_AUTO) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(canonical)
            }
        } else {
            val targetLocale = resolveTargetLocale(canonical)
            Locale.setDefault(targetLocale)
            findActivity(context)?.recreate()
        }

        if (KurokoRuntime.overlayRunning.value) {
            val refreshIntent = Intent(appCtx, FloatingOverlayService::class.java).apply {
                action = ACTION_REFRESH_LOCALE
            }
            runCatching { appCtx.startService(refreshIntent) }
        }

        KurokoRuntime.syncLangToDaemonAsync(canonical)
    }

    /**
     * Wraps Activity/Service base Context in attachBaseContext().
     * On API 33+, Android LocaleManager natively applies per-app locales.
     */
    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return base
        }
        val tag = readSavedPrefOnly(base)
        if (tag == TAG_AUTO) return base
        return createLocaleContext(base, resolveTargetLocale(tag))
    }

    /**
     * Returns a Context configured with the currently active Kuroko Teleport language,
     * safe for Services, notifications, and KurokoRuntime background messages on all APIs.
     */
    fun localizedContext(base: Context): Context {
        val targetLocale = resolveTargetLocale(getSavedTag(base))
        val currentLocales = base.resources.configuration.locales
        val currentLocale = if (!currentLocales.isEmpty) currentLocales[0] else null
        if (currentLocale != null && localesEquivalent(currentLocale, targetLocale)) {
            return base
        }
        return createLocaleContext(base, targetLocale)
    }

    private fun readSavedPrefOnly(base: Context): String {
        return runCatching {
            val prefs = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            normalizeTag(prefs.getString(KEY_LANG_TAG, TAG_AUTO))
        }.getOrDefault(TAG_AUTO)
    }

    private fun resolveTargetLocale(tag: String): Locale {
        if (tag == TAG_AUTO) {
            val sysLocales = Resources.getSystem().configuration.locales
            return if (!sysLocales.isEmpty) sysLocales[0] else Locale.getDefault()
        }
        val parsed = Locale.forLanguageTag(tag)
        return if (parsed.language.isNotEmpty()) parsed else Locale.ENGLISH
    }

    private fun localesEquivalent(a: Locale, b: Locale): Boolean {
        if (!a.language.equals(b.language, ignoreCase = true)) return false
        if (b.country.isNotEmpty() && !a.country.equals(b.country, ignoreCase = true)) return false
        if (b.script.isNotEmpty() && !a.script.equals(b.script, ignoreCase = true)) return false
        return true
    }

    private fun createLocaleContext(base: Context, locale: Locale): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    private fun findActivity(context: Context): Activity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return cur as? Activity
    }
}
