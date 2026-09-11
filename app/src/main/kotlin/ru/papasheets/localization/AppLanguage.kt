package ru.papasheets.localization

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Язык интерфейса: пустой тег означает, что выбор делегирован системе. */
enum class AppLanguage(val languageTag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    RUSSIAN("ru"),
    ;

    companion object {
        fun current(): AppLanguage {
            val locale = AppCompatDelegate.getApplicationLocales()[0] ?: return SYSTEM
            return fromLanguageTag(locale.toLanguageTag())
        }

        fun fromLanguageTag(languageTag: String?): AppLanguage = when (
            LocaleListCompat.forLanguageTags(languageTag.orEmpty())[0]?.language
        ) {
            "ru" -> RUSSIAN
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }
}

/** Применяет язык через системный/AndroidX API; вызов пересоздаёт Activity. */
fun setAppLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(
        LocaleListCompat.forLanguageTags(language.languageTag),
    )
}

/**
 * Application context на Android 12 и ниже не получает override AppCompat автоматически.
 * Этот контекст нужен фоновому коду, который создаёт новые локализованные названия месяцев.
 */
fun Context.withAppLanguage(): Context {
    val locale = AppCompatDelegate.getApplicationLocales()[0] ?: return this
    val configuration = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(configuration)
}
