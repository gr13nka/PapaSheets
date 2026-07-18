package ru.papasheets.domain

import java.time.LocalDate
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.matrixgrid.ContractorColumn
import ru.papasheets.matrixgrid.GridCell
import ru.papasheets.matrixgrid.GridField
import ru.papasheets.matrixgrid.GridModel
import ru.papasheets.matrixgrid.GridRow

/**
 * ВРЕМЕННЫЙ синтез набора полей: движок матрицы уже умеет произвольное их число, а схема БД — ещё
 * нет, поэтому здесь воспроизводятся ровно те две подколонки, что были захардкожены в матрице до
 * рефакторинга. В этапе 3 набор придёт из БД, и константа исчезнет.
 *
 * `maxLines = 0` у вида работ снимает потолок строк: строка матрицы растягивается так, чтобы текст
 * был виден целиком, а не обрезался многоточием.
 */
private val LEGACY_FIELDS = listOf(
    GridField(id = "location", title = "Л", widthDp = 56, maxLines = 2, showAtCompactLod = true),
    GridField(id = "work", title = "ВИД РАБОТ", widthDp = 168, maxLines = 0, showAtCompactLod = false),
)

/**
 * Чистая раскладка «дата × подрядчик» → [GridModel] для [ru.papasheets.matrixgrid.MatrixView].
 *
 * Правила (см. spec, «несколько строк на дату»):
 * - активные подрядчики по `orderIndex` становятся колонками слева направо;
 * - архивный подрядчик с записями в этом журнале остаётся колонкой в конце (иначе его записи молча
 *   исчезли бы из уже сведённого месяца) — колонка помечена [ContractorColumn.isArchived], архивный
 *   без записей в колонки не попадает;
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
    val active = contractors.filter { !it.isArchived }.sortedBy { it.orderIndex }
    val recordedContractorIds = records.map { it.contractorId }.toSet()
    val archivedWithRecords = contractors
        .filter { it.isArchived && it.id in recordedContractorIds }
        .sortedBy { it.orderIndex }
    val columns = active + archivedWithRecords
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
        val date = LocalDate.ofEpochDay(day)
        val label = JournalDates.shortMonth(date)
        val number = JournalDates.dayNumber(date)
        for (rowInDay in 0 until rowCount) {
            val cells = ArrayList<GridCell?>(columns.size)
            for (column in columns.indices) {
                val record = perColumn[column]?.getOrNull(rowInDay)
                cells.add(record?.let { GridCell(it.id, it.photoId, listOf(it.locationCode, it.workText)) })
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
        fields = LEGACY_FIELDS,
        rows = rows,
    )
}
