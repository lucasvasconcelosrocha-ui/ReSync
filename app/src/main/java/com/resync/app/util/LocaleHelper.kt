package com.resync.app.util

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "resync_language_prefs"
    private const val KEY_LANGUAGE = "app_language"

    const val LANG_SYSTEM = "system"
    const val LANG_PT_BR = "pt-BR"
    const val LANG_EN = "en"

    // Default factory language is English ("en")
    private val _currentLanguageFlow = MutableStateFlow(LANG_EN)
    val currentLanguageFlow: StateFlow<String> = _currentLanguageFlow.asStateFlow()

    fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAppLanguage(context: Context): String {
        val lang = getPreferences(context).getString(KEY_LANGUAGE, LANG_EN) ?: LANG_EN
        _currentLanguageFlow.value = lang
        return lang
    }

    fun getLocale(context: Context): Locale {
        return when (getAppLanguage(context)) {
            LANG_EN -> Locale.ENGLISH
            LANG_PT_BR -> Locale("pt", "BR")
            LANG_SYSTEM -> Locale.getDefault()
            else -> Locale.ENGLISH
        }
    }

    fun setAppLanguage(context: Context, languageTag: String) {
        val validTag = when (languageTag) {
            LANG_EN -> LANG_EN
            LANG_PT_BR -> LANG_PT_BR
            LANG_SYSTEM -> LANG_SYSTEM
            else -> LANG_EN
        }

        getPreferences(context).edit().putString(KEY_LANGUAGE, validTag).commit()
        _currentLanguageFlow.value = validTag

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
