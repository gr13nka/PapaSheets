package ru.papasheets.domain.export

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.domain.buildGridModel
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.SnapshotCell
import ru.papasheets.exportkit.model.SnapshotContractor
import ru.papasheets.exportkit.model.SnapshotDay
import ru.papasheets.exportkit.model.SnapshotRow
import ru.papasheets.matrixgrid.GridCell

private val EXPORT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * [JournalSnapshot] для экспорта — раскладка колонок и строк ровно та же, что и в матрице
 * ([buildGridModel]): дни всегда по возрастанию (бумажный журнал читается сверху вниз, независимо
 * от тумблера сортировки в UI), порядок подрядчиков — активные по orderIndex + архивные-с-записями
 * в конец. Никакой отдельной логики раскладки здесь нет — только перевод [ru.papasheets.matrixgrid.GridModel]
 * в формат, который понимает exportkit (без recordId, с готовой строкой даты).
 */
fun buildJournalSnapshot(
    journalTitle: String,
    records: List<RecordEntity>,
    contractors: List<ContractorEntity>,
): JournalSnapshot {
    val grid = buildGridModel(records, contractors, sortDesc = false)

    val days = ArrayList<SnapshotDay>()
    var index = 0
    while (index < grid.rows.size) {
        val dateEpochDay = grid.rows[index].dateEpochDay
        val rows = ArrayList<SnapshotRow>()
        while (index < grid.rows.size && grid.rows[index].dateEpochDay == dateEpochDay) {
            rows.add(SnapshotRow(grid.rows[index].cells.map { it?.let(::toSnapshotCell) }))
            index++
        }
        days.add(SnapshotDay(dateLabel = LocalDate.ofEpochDay(dateEpochDay).format(EXPORT_DATE_FORMAT), rows = rows))
    }

    return JournalSnapshot(
        title = journalTitle,
        contractors = grid.contractors.map { SnapshotContractor(name = it.name) },
        days = days,
    )
}

private fun toSnapshotCell(cell: GridCell): SnapshotCell =
    SnapshotCell(locationCode = cell.locationCode, workText = cell.workText, photoId = cell.thumbKey)
