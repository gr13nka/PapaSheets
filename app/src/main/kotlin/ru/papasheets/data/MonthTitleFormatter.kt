package ru.papasheets.data

import android.content.Context
import ru.papasheets.R
import ru.papasheets.localization.withAppLanguage

/** Заголовок журнала вида «Июль 2026» / “July 2026” в выбранном языке приложения. */
class MonthTitleFormatter(context: Context) {
    private val appContext = context.applicationContext

    fun title(year: Int, month: Int): String {
        val monthNames = appContext.withAppLanguage().resources.getStringArray(R.array.month_names_nominative)
        return "${monthNames[month - 1]} $year"
    }
}
