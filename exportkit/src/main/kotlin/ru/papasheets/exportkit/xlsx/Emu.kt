package ru.papasheets.exportkit.xlsx

/**
 * EMU (English Metric Units) — единица размеров в DrawingML: 914400 на дюйм, 12700 на пункт (pt).
 * Высота строки Excel и так задаётся в пунктах, поэтому вся геометрия фото в [XlsxWriter] считается
 * через пункты — без промежуточного перевода в пиксели.
 */
internal object Emu {
    private const val PER_POINT = 12700L

    fun fromPoints(pt: Double): Long = Math.round(pt * PER_POINT)
}
