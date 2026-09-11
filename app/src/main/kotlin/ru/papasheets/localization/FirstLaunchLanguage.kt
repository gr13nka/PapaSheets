package ru.papasheets.localization

import android.app.Activity
import android.content.Context

/**
 * Разовый выбор языка на первом запуске версии с локализацией.
 *
 * До неё приложение было только русским, поэтому установка, обновлённая поверх прежней версии,
 * получает явный русский, даже если система на другом языке: иначе у прораба интерфейс сам собой
 * сменился бы на английский. Новая установка следует системе. Решение принимается один раз — маркер
 * лежит в собственном файле prefs, — поэтому выбранное потом «Как в системе» им не перекрывается.
 *
 * @return язык, который нужно выставить, или `null` — ничего не менять.
 */
internal fun firstLaunchLanguage(existingInstall: Boolean, decided: Boolean, chosen: AppLanguage): AppLanguage? =
    if (existingInstall && !decided && chosen == AppLanguage.SYSTEM) AppLanguage.RUSSIAN else null

/**
 * Применяет [firstLaunchLanguage]. Вызывать из `onCreate` после `super.onCreate`: раньше AppCompat на
 * API < 33 выбор языка не применяет. Смена языка пересоздаёт Activity, поэтому маркер пишется до неё —
 * пересозданная Activity застаёт решение уже принятым.
 */
fun Activity.applyFirstLaunchLanguage() {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val decided = prefs.getBoolean(KEY_DECIDED, false)
    if (!decided) prefs.edit().putBoolean(KEY_DECIDED, true).apply()
    firstLaunchLanguage(isUpdatedInstall(), decided, AppLanguage.current())?.let(::setAppLanguage)
}

/** Приложение обновлено поверх прежней версии, а не поставлено начисто. */
private fun Context.isUpdatedInstall(): Boolean {
    @Suppress("DEPRECATION") // перегрузка с PackageInfoFlags есть только с API 33
    val info = packageManager.getPackageInfo(packageName, 0)
    return info.firstInstallTime != info.lastUpdateTime
}

private const val PREFS_NAME = "appLanguage"
private const val KEY_DECIDED = "firstLaunchDecided"
