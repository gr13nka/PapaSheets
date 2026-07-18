package ru.papasheets.domain.export

import java.time.LocalDate
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.domain.JournalDates
import ru.papasheets.domain.buildGridModel
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.SnapshotCell
import ru.papasheets.exportkit.model.SnapshotContractor
import ru.papasheets.exportkit.model.SnapshotDay
import ru.papasheets.exportkit.model.SnapshotField
import ru.papasheets.exportkit.model.SnapshotRow
import ru.papasheets.exportkit.xlsx.Widths

/**
 * [JournalSnapshot] для экспорта — раскладка колонок, полей и строк ровно та же, что и в матрице
 * ([buildGridModel]): дни всегда по возрастанию (бумажный журнал читается сверху вниз, независимо
 * от тумблера сортировки в UI), порядок подрядчиков — активные по orderIndex + архивные-с-записями
 * в конец. Никакой отдельной логики раскладки здесь нет — только перевод [ru.papasheets.matrixgrid.GridModel]
 * в формат, который понимает exportkit (без recordId, с готовой строкой даты и геометрией в единицах Excel).
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
            rows.add(
                SnapshotRow(
                    grid.rows[index].cells.map { cell ->
                        cell?.let { SnapshotCell(values = it.values, photoId = it.thumbKey) }
                    },
                ),
            )
            index++
        }
        days.add(
            SnapshotDay(
                dateLabel = JournalDates.numeric(LocalDate.ofEpochDay(dateEpochDay)),
                rows = rows,
            ),
        )
    }

    return JournalSnapshot(
        title = journalTitle,
        contractors = grid.contractors.map { SnapshotContractor(name = it.name) },
        // Ширина поля живёт в dp (там её задаёт пользователь), в символьные единицы Excel её
        // переводит exportkit — второй копии ширины нет, поэтому колонка листа не разойдётся с матрицей.
        // Перенос строк в xlsx включается там же, где матрица снимает потолок строк: maxLines != 1.
        fields = grid.fields.map {
            SnapshotField(title = it.title, widthChars = Widths.dpToChars(it.widthDp), wrap = it.maxLines != 1)
        },
        days = days,
    )
}
