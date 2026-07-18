package ru.papasheets.exportkit.xlsx

import kotlin.math.ceil
import kotlin.math.floor
import ru.papasheets.exportkit.model.SnapshotField
import ru.papasheets.exportkit.model.SnapshotRow

/**
 * Высота строки с хотя бы одним фото, в пунктах. Эталонный журнал держит фото как крошечную
 * иконку в строке по умолчанию (15pt) — то же самое было бы нечитаемо; здесь фото сознательно
 * крупнее (см. отчёт M6), высоту строки под них выбираем сами.
 */
internal const val PHOTO_ROW_HEIGHT_PT = 90.0

/** Высота одной строки текста 11pt Calibri — та же величина, что Excel держит как высоту строки по умолчанию. */
internal const val LINE_HEIGHT_PT = 15.0

/**
 * Оценка высоты строки листа под перенесённый текст.
 *
 * Нужна только строкам с фото. Excel сам подгоняет высоту под wrap-текст, но лишь пока у строки нет
 * `customHeight="1"` — а фото без явной `ht` схлопнется вместе со строкой, так что для таких строк
 * автоподбор приходится выключать и считать высоту самим. Строки без фото `ht` не получают вовсе:
 * автоподбор Excel точнее любой оценки отсюда.
 *
 * Это именно оценка: настоящая ширина символа зависит от шрифта и конкретных букв, здесь же
 * «сколько символов влезло в [SnapshotField.widthChars]». Ошибка в большую сторону безобидна
 * (лишний воздух в строке), в меньшую — обрежет хвост текста при печати, поэтому вместимость
 * округляется вниз.
 */
internal object RowHeight {
    fun forPhotoRow(row: SnapshotRow, fields: List<SnapshotField>): Double =
        maxOf(PHOTO_ROW_HEIGHT_PT, estimateTextLines(row, fields) * LINE_HEIGHT_PT)

    /** Строк текста в самой «высокой» ячейке строки; поля без переноса всегда однострочны. */
    fun estimateTextLines(row: SnapshotRow, fields: List<SnapshotField>): Int {
        val wrapped = fields.withIndex().filter { it.value.wrap }
        if (wrapped.isEmpty()) return 1
        return row.cells.filterNotNull().maxOfOrNull { cell ->
            wrapped.maxOf { (index, field) -> valueLines(cell.values.getOrElse(index) { "" }, field.widthChars) }
        } ?: 1
    }

    private fun valueLines(value: String, widthChars: Double): Int {
        if (value.isEmpty()) return 1
        val capacity = floor(widthChars).toInt().coerceAtLeast(1)
        return value.split('\n').sumOf { segment ->
            maxOf(1, ceil(segment.length.toDouble() / capacity).toInt())
        }
    }
}
