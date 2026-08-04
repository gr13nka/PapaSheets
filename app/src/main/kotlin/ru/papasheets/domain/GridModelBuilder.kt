package ru.papasheets.domain

import java.time.LocalDate
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.RecordWithValues
import ru.papasheets.data.repo.FieldValueColors
import ru.papasheets.matrixgrid.ContractorColumn
import ru.papasheets.matrixgrid.GridCell
import ru.papasheets.matrixgrid.GridField
import ru.papasheets.matrixgrid.GridModel
import ru.papasheets.matrixgrid.GridRow

/**
 * Чистая раскладка «дата × подрядчик» → [GridModel] для [ru.papasheets.matrixgrid.MatrixView].
 *
 * Правила (см. spec, «несколько строк на дату»):
 * - активные подрядчики по `orderIndex` становятся колонками слева направо;
 * - архивный подрядчик с записями в этом журнале остаётся колонкой в конце (иначе его записи молча
 *   исчезли бы из уже сведённого месяца) — колонка помечена [ContractorColumn.isArchived], архивный
 *   без записей в колонки не попадает;
 * - [fields] в своём порядке становятся подколонками внутри группы подрядчика;
 * - [valueColors] раскладываются по подколонкам заранее: рендерер проходит по видимым ячейкам
 *   каждый кадр жеста, и искать цвет по тексту значения там нельзя;
 * - записи группируются по дню; дни сортируются по [sortDesc];
 * - строк в дне = max(1, максимум записей одного подрядчика за этот день);
 * - записи подрядчика раскладываются сверху вниз по `createdAt` ASC;
 * - пустые дни (без записей) не создаются.
 *
 * Без Android-зависимостей — покрыта обычным JVM-тестом раскладки.
 */
fun buildGridModel(
    records: List<RecordWithValues>,
    contractors: List<ContractorEntity>,
    fields: List<FieldDefEntity>,
    valueColors: FieldValueColors,
    sortDesc: Boolean,
): GridModel {
    val active = contractors.filter { !it.isArchived }.sortedBy { it.orderIndex }
    val recordedContractorIds = records.map { it.record.contractorId }.toSet()
    val archivedWithRecords = contractors
        .filter { it.isArchived && it.id in recordedContractorIds }
        .sortedBy { it.orderIndex }
    val columns = active + archivedWithRecords
    val columnIndex = HashMap<String, Int>(columns.size)
    columns.forEachIndexed { index, contractor -> columnIndex[contractor.id] = index }

    val recordsByDay = records
        .filter { it.record.contractorId in columnIndex }
        .groupBy { it.record.dateEpochDay }
    val days = recordsByDay.keys.sorted().let { if (sortDesc) it.asReversed() else it }

    val rows = ArrayList<GridRow>()
    for (day in days) {
        val perColumn = arrayOfNulls<MutableList<RecordWithValues>>(columns.size)
        for (record in recordsByDay.getValue(day)) {
            val column = columnIndex.getValue(record.record.contractorId)
            (perColumn[column] ?: ArrayList<RecordWithValues>().also { perColumn[column] = it }).add(record)
        }
        perColumn.forEach { list -> list?.sortBy { it.record.createdAt } }

        val rowCount = maxOf(1, perColumn.maxOf { it?.size ?: 0 })
        val date = LocalDate.ofEpochDay(day)
        val label = JournalDates.shortMonth(date)
        val number = JournalDates.dayNumber(date)
        for (rowInDay in 0 until rowCount) {
            val cells = ArrayList<GridCell?>(columns.size)
            for (column in columns.indices) {
                val record = perColumn[column]?.getOrNull(rowInDay)
                cells.add(
                    record?.let { entry ->
                        val values = fields.map { entry.valueOf(it.id) }
                        GridCell(
                            recordId = entry.record.id,
                            thumbKeys = entry.record.photoIds,
                            values = values,
                            valueColors = fields.mapIndexed { i, field -> valueColors[field.id]?.get(values[i]) },
                        )
                    },
                )
            }
            rows.add(
                GridRow(
                    dateEpochDay = day,
                    dayLabel = label,
                    dayNumber = number,
                    isFirstOfDay = rowInDay == 0,
                    cells = cells,
                ),
            )
        }
    }

    return GridModel(
        contractors = columns.map { ContractorColumn(it.id, it.name, it.shortName, it.colorIndex, it.isArchived) },
        fields = fields.map { GridField(it.id, it.title, it.columnWidthDp, it.maxLines, it.showAtCompactLod) },
        rows = rows,
    )
}
