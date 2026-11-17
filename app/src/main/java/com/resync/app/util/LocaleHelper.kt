package com.resync.app.util

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "resync_language_prefs"
    private const val KEY_LANGUAGE = "app_language"

    const val LANG_SYSTEM = "system"
    const val LANG_PT_BR = "pt-BR"
    const val LANG_EN = "en"

    fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAppLanguage(context: Context): String {
        return getPreferences(context).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun getLocale(context: Context): Locale {
        return when (getAppLanguage(context)) {
            LANG_EN -> Locale.ENGLISH
            LANG_PT_BR -> Locale("pt", "BR")
            else -> Locale.getDefault()
        }
    }

    fun setAppLanguage(context: Context, languageTag: String) {
        val validTag = when (languageTag) {
            LANG_EN -> LANG_EN
            LANG_PT_BR -> LANG_PT_BR
            else -> LANG_SYSTEM
        }

        getPreferences(context).edit().putString(KEY_LANGUAGE, validTag).commit()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                localeManager?.applicationLocales = if (validTag == LANG_SYSTEM) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(validTag)
                }
            } catch (e: Exception) {
                // Ignore if system fails to update per-app locales
            }
        }
    }

    fun wrapContext(base: Context): Context {
        val locale = getLocale(base)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return base.createConfigurationContext(config)
    }

    fun getLanguageDisplayName(context: Context, tag: String = getAppLanguage(context)): String {
        return when (tag) {
            LANG_EN -> "English"
            LANG_PT_BR -> "Português (Brasil)"
            else -> {
                val isEn = getLocale(context).language == "en"
                if (isEn) "System default" else "Padrão do sistema"
            }
        }
    }
}
