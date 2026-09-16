package ru.papasheets.domain

import java.time.LocalDate
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.repo.FieldValueColors
import ru.papasheets.matrixgrid.ContractorColumn
import ru.papasheets.matrixgrid.GridCell
import ru.papasheets.matrixgrid.GridModel
import ru.papasheets.matrixgrid.GridRow

/** Ключ фото-плейсхолдера строки 0. [ru.papasheets.matrixgrid.ThumbnailSource] его никогда не найдёт, поэтому рендерер рисует заглушку — ровно то, что нужно показать в превью. */
private const val PREVIEW_THUMB_KEY = "preview"

/**
 * Раскладка «Настройка группы»: три строки над двумя днями для ОДНОЙ группы — реальный кусок
 * матрицы, а не рисунок под неё. Поля проходят через тот же [FieldDefEntity.toGridField] и ту же
 * [FieldValueColors.colorsFor], что и [buildGridModel], поэтому ширина колонки, перенос текста и
 * цвет значения в превью физически не могут разойтись с журналом — расходиться им попросту негде,
 * обе функции берут ровно один и тот же код.
 *
 * Строка 0 — первая запись первого дня, с фото-плейсхолдером (прораб должен увидеть, куда встанет
 * миниатюра); строка 1 — вторая запись того же дня (граница дня не рисуется, `isFirstOfDay = false`);
 * строка 2 — первая запись следующего дня. Значение поля `f` в строке `i` — очередной пример из
 * [samples] по кругу; когда примеров ещё нет (ни истории, ни пресетов), ячейка показывает подпись
 * самого поля — на живом превью пустая ячейка выглядела бы как баг, а не как «пока нечего показать».
 */
fun buildGroupPreview(
    fields: List<FieldDefEntity>,
    group: ContractorColumn,
    samples: Map<String, List<String>>,
    valueColors: FieldValueColors,
    firstDay: LocalDate,
    dateLabel: (LocalDate) -> String,
): GridModel {
    val days = listOf(firstDay, firstDay, firstDay.plusDays(1))
    val isFirstOfDay = listOf(true, false, true)
    val thumbKeys = listOf(listOf(PREVIEW_THUMB_KEY), emptyList(), emptyList())

    val rows = days.indices.map { row ->
        val date = days[row]
        val values = fields.map { field -> field.sampleValue(samples, row) }
        GridRow(
            dateEpochDay = date.toEpochDay(),
            dayLabel = dateLabel(date),
            dayNumber = JournalDates.dayNumber(date),
            isFirstOfDay = isFirstOfDay[row],
            cells = listOf(
                GridCell(
                    recordId = "preview-$row",
                    thumbKeys = thumbKeys[row],
                    values = values,
                    valueColors = valueColors.colorsFor(fields, values),
                ),
            ),
        )
    }

    return GridModel(
        contractors = listOf(group),
        fields = fields.map { it.toGridField() },
        rows = rows,
    )
}

private fun FieldDefEntity.sampleValue(samples: Map<String, List<String>>, rowIndex: Int): String {
    val list = samples[id]
    return if (list.isNullOrEmpty()) label else list[rowIndex % list.size]
}
