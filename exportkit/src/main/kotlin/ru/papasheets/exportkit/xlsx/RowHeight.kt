package ru.papasheets.exportkit.xlsx

import kotlin.math.ceil
import kotlin.math.floor
import ru.papasheets.exportkit.model.SnapshotField
import ru.papasheets.exportkit.model.SnapshotRow

/**
 * Высота строки с хотя бы одним фото, в пунктах.
 *
 * Нижнюю границу диктует просмотрщик прораба: Google Sheets на телефоне рисует встроенную
 * картинку, только если она вылезает за свою ячейку-якорь ИЛИ её длинная сторона не меньше ~52pt
 * (правило и замеры на устройстве 2026-07-22 — docs/evolution.md, «xlsx»). Наша вёрстка держит
 * фото целиком внутри ячейки (вылезание — наезд на соседние колонки и обрезка на печати), поэтому
 * работает только ветка размера: [PHOTO_BOX_PT] обязан быть ≥ 52. Отсюда 64 = 52 + 4pt запаса +
 * [PHOTO_PADDING_PT]; подтверждённый пол — 60 (фото 52). «Компактные» 36 (фото 28) делали фото
 * невидимым в Sheets — выглядело как «фото не выгрузились», хотя файл корректен и Excel рисует
 * любой размер. Прежние 90pt (M6) видимость давали, но растягивали журнал сильнее необходимого.
 *
 * Мелко фото только показывается: в лист по-прежнему кладётся medium-JPEG целиком (см. [XlsxWriter]),
 * так что увеличение в Excel показывает детали.
 */
internal const val PHOTO_ROW_HEIGHT_PT = 64.0

/** Поле сверху и снизу от фото внутри его строки — иначе картинка упирается в линии сетки. */
private const val PHOTO_PADDING_PT = 8.0

/**
 * Сторона квадрата, в который вписывается фото любых пропорций (см. [XlsxWriter]).
 *
 * Ограничение по ОБЕИМ сторонам, а не по одной высоте: якорь `oneCellAnchor` задаёт размер картинки
 * явно и ничем её не подрезает, поэтому фото, растянутое только по высоте, у альбомного кадра
 * вылезало бы за колонку Ф и наезжало на первую колонку поля. От этой же величины считается ширина
 * колонки Ф в [SheetXml] — одна константа на обе стороны, так что разъехаться им нечем.
 */
internal const val PHOTO_BOX_PT = PHOTO_ROW_HEIGHT_PT - PHOTO_PADDING_PT

/** Высота одной строки текста 11pt Calibri — та же величина, что Excel держит как высоту строки по умолчанию. */
internal const val LINE_HEIGHT_PT = 15.0

/**
 * Потолок высоты строки в OOXML. Значение выше Excel считает недопустимым и отказывается открывать
 * файл целиком — то есть переполнение стоит не обрезанного текста, а всего экспорта.
 */
internal const val MAX_ROW_HEIGHT_PT = 409.5

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
            .coerceAtMost(MAX_ROW_HEIGHT_PT)

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
        return value.split('\n').sumOf { segment -> wrappedLines(segment, capacity) }
    }

    /**
     * Строк, на которые Excel разложит один абзац в колонке шириной [capacity] символов.
     *
     * Считается именно перенос ПО СЛОВАМ, а не деление длины на вместимость: слово, не влезающее в
     * остаток строки, уезжает на следующую целиком, и текст занимает больше строк, чем следует из
     * простого деления. Ошибка здесь односторонняя и потому опасная — недооценённая высота обрезает
     * хвост текста при печати, а обнаруживается это уже на бумаге.
     *
     * Слово длиннее всей колонки — единственный случай, когда Excel рвёт посимвольно, и он считается
     * отдельно.
     */
    private fun wrappedLines(paragraph: String, capacity: Int): Int {
        var lines = 1
        var used = 0
        for (word in paragraph.split(' ')) {
            val needed = if (used == 0) word.length else used + 1 + word.length
            when {
                needed <= capacity -> used = needed
                word.length <= capacity -> {
                    lines++
                    used = word.length
                }
                else -> {
                    if (used > 0) lines++
                    val chunks = ceil(word.length.toDouble() / capacity).toInt()
                    lines += chunks - 1
                    used = word.length - (chunks - 1) * capacity
                }
            }
        }
        return lines
    }
}
