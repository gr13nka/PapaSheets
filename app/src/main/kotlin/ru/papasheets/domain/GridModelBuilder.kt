package ru.papasheets.domain

import java.time.LocalDate
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.matrixgrid.ContractorColumn
import ru.papasheets.matrixgrid.GridCell
import ru.papasheets.matrixgrid.GridModel
import ru.papasheets.matrixgrid.GridRow

/** Короткие русские месяцы для меток дат («17 июл»). Захардкожены здесь, чтобы функция оставалась чистой JVM. */
private val SHORT_MONTHS = arrayOf(
    "янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
)

/**
 * Чистая раскладка «дата × подрядчик» → [GridModel] для [ru.papasheets.matrixgrid.MatrixView].
 *
 * Правила (см. spec, «несколько строк на дату»):
 * - активные подрядчики по `orderIndex` становятся колонками слева направо;
 * - записи группируются по дню; дни сортируются по [sortDesc];
 * - строк в дне = max(1, максимум записей одного подрядчика за этот день);
 * - записи подрядчика раскладываются сверху вниз по `createdAt` ASC;
 * - пустые дни (без записей) не создаются.
 *
 * Без Android-зависимостей — покрыта обычным JVM-тестом раскладки.
 */
fun buildGridModel(
    records: List<RecordEntity>,
    contractors: List<ContractorEntity>,
    sortDesc: Boolean,
): GridModel {
    val columns = contractors.filter { !it.isArchived }.sortedBy { it.orderIndex }
    val columnIndex = HashMap<String, Int>(columns.size)
    columns.forEachIndexed { index, contractor -> columnIndex[contractor.id] = index }

    val recordsByDay = records
        .filter { it.contractorId in columnIndex }
        .groupBy { it.dateEpochDay }
    val days = recordsByDay.keys.sorted().let { if (sortDesc) it.asReversed() else it }

    val rows = ArrayList<GridRow>()
    for (day in days) {
        val perColumn = arrayOfNulls<MutableList<RecordEntity>>(columns.size)
        for (record in recordsByDay.getValue(day)) {
            val column = columnIndex.getValue(record.contractorId)
            (perColumn[column] ?: ArrayList<RecordEntity>().also { perColumn[column] = it }).add(record)
        }
        perColumn.forEach { it?.sortBy(RecordEntity::createdAt) }

        val rowCount = maxOf(1, perColumn.maxOf { it?.size ?: 0 })
        val label = dayLabel(day)
        for (rowInDay in 0 until rowCount) {
            val cells = ArrayList<GridCell?>(columns.size)
            for (column in columns.indices) {
                val record = perColumn[column]?.getOrNull(rowInDay)
                cells.add(record?.let { GridCell(it.id, it.photoId, it.locationCode, it.workText) })
            }
            rows.add(GridRow(dateEpochDay = day, dayLabel = label, isFirstOfDay = rowInDay == 0, cells = cells))
        }
    }

    return GridModel(
        contractors = columns.map { ContractorColumn(it.id, it.name, it.shortName, it.colorIndex) },
        rows = rows,
    )
}

private fun dayLabel(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return "${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}"
}
