package io.raaz.messenger.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleManager {

    private const val PREF_NAME = "raaz_locale"
    private const val KEY_LANGUAGE = "language"
    const val LANG_FA = "fa"
    const val LANG_EN = "en"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, LANG_FA) ?: LANG_FA
    }

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, lang).apply()
    }

    fun applyLocale(context: Context): Context {
        val lang = getLanguage(context)
        return applyLocale(context, lang)
    }

    fun applyLocale(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun isRtl(context: Context): Boolean = getLanguage(context) == LANG_FA
}
