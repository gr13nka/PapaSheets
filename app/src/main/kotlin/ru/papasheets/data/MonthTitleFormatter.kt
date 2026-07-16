package ru.papasheets.data

import android.content.Context
import ru.papasheets.R

/** Заголовок журнала вида «Июль 2026» — по-русски, из strings.xml. */
class MonthTitleFormatter(context: Context) {
    private val monthNames = context.applicationContext.resources.getStringArray(R.array.month_names_nominative)

    fun title(year: Int, month: Int): String = "${monthNames[month - 1]} $year"
}
