package ru.papasheets.domain

import kotlin.math.abs

/**
 * Четыре ширины подколонки поля вместо ввода dp вручную — в «Настройке группы» прораб двигает
 * слайдер по имени пресета, а не подбирает число пикселей.
 *
 * [NARROW]=56 и [WIDE]=168 — не произвольные числа: это ширины встроенных полей «Локация» и
 * «Вид работ» ([ru.papasheets.exportkit.backup.BuiltInFields.ALL]) и одновременно опорные точки
 * калибровки [ru.papasheets.exportkit.xlsx.Widths] (`dpToChars`) — оба встроенных поля лягут ровно
 * на пресет, ничего не сдвинув ни в матрице, ни в экспорте.
 *
 * Сама колонка `field_defs.columnWidthDp` остаётся числом в dp, а не индексом пресета: значение из
 * бэкапа или из записи, заведённой до появления пресетов, продолжает работать как есть — экран
 * показывает для него ближайший через [nearest] и не трогает хранимое число, пока прораб сам не
 * подвинет слайдер (см. `FieldEditorPane`).
 */
enum class ColumnWidth(val dp: Int) {
    NARROW(56),
    MEDIUM(112),
    WIDE(168),
    EXTRA_WIDE(224),
    ;

    companion object {
        val DEFAULT = MEDIUM

        /**
         * Ближайший пресет к [dp]. Ровно посередине между двумя пресетами побеждает более широкий —
         * тексту в ячейке лишний dp полезнее, чем недостача.
         */
        fun nearest(dp: Int): ColumnWidth {
            var best = entries.first()
            var bestDiff = Int.MAX_VALUE
            for (preset in entries) {
                val diff = abs(preset.dp - dp)
                if (diff < bestDiff || (diff == bestDiff && preset.dp > best.dp)) {
                    best = preset
                    bestDiff = diff
                }
            }
            return best
        }
    }
}
