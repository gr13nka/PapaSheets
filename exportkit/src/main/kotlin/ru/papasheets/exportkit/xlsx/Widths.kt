package ru.papasheets.exportkit.xlsx

/**
 * Перевод ширины поля из dp (в них поле описано для матрицы) в символьные единицы Excel.
 *
 * Ширина хранится ровно один раз — в dp; xlsx выводит её отсюда, а не держит вторую копию,
 * иначе правка ширины поля в UI молча расходилась бы с экспортом.
 *
 * Калибровка — по эталонному журналу (`docs/reference/iyun-xlsx/xl/worksheets/sheet1.xml`, `<cols>`):
 * две подколонки, существовавшие до перехода на произвольный набор полей, дают опорные точки
 * 56dp → 7.75 («Л») и 168dp → 50.75 («ВИД РАБОТ»). Между ними и вокруг них — линейная интерполяция:
 * зависимость «символов в колонке» от ширины в пикселях в самом Excel тоже линейная, просто с
 * другими коэффициентами шрифта.
 */
object Widths {
    /** Ниже — колонка уже нечитаемо узкая, выше — не влезает на печатный лист даже в landscape. */
    private const val MIN_CHARS = 4.0
    private const val MAX_CHARS = 80.0

    private const val CHARS_PER_DP = (50.75 - 7.75) / (168 - 56)
    private const val ZERO_DP_CHARS = 7.75 - 56 * CHARS_PER_DP

    /** Округление до 2 знаков — чтобы в XML не текли хвосты вида `16.964285714285715`. */
    fun dpToChars(dp: Int): Double {
        val raw = ZERO_DP_CHARS + dp * CHARS_PER_DP
        return Math.round(raw.coerceIn(MIN_CHARS, MAX_CHARS) * 100.0) / 100.0
    }
}
