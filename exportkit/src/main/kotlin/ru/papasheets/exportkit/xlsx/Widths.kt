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

    /**
     * Ширина колонки в символьных единицах, вмещающая [pt] пунктов. Нужна там, где ширину диктует не
     * поле матрицы, а вставленный объект: колонка Ф обязана быть не уже фото, которое в ней лежит.
     *
     * Здесь, в отличие от [dpToChars], пересчёт не эмпирический, а по формуле OOXML: ширина колонки
     * задана в ширинах символа «0» ([MAX_DIGIT_WIDTH_PX] для Calibri 11) плюс [PADDING_PX] на поля
     * ячейки. Пункты переводятся в пиксели по 96 dpi — той же плотности, в которой Excel считает
     * ширины колонок, тогда как высоты строк и размеры картинок он держит в пунктах.
     */
    fun ptToChars(pt: Double): Double {
        val px = pt * PX_PER_POINT
        val raw = (px - PADDING_PX) / MAX_DIGIT_WIDTH_PX
        return Math.round(raw.coerceIn(MIN_CHARS, MAX_CHARS) * 100.0) / 100.0
    }

    private const val PX_PER_POINT = 96.0 / 72.0
    private const val MAX_DIGIT_WIDTH_PX = 7.0
    private const val PADDING_PX = 5.0
}
