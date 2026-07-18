package ru.papasheets.exportkit.model

/**
 * Плоский снимок журнала-месяца для экспорта. Раскладка (порядок подрядчиков-колонок, разбиение
 * записей на дни и строки внутри дня) уже решена вызывающей стороной — app/domain/export строит его
 * поверх той же раскладки, что и матрица ([ru.papasheets.domain.buildGridModel]), не дублируя логику.
 * exportkit ничего не знает о Room/UUID/Android — отсюда его быстрые JVM-тесты формата.
 */
class JournalSnapshot(
    /** Заголовок журнала, например «Июль 2026» — идёт и в имя листа xlsx, и в имя файла. */
    val title: String,
    /** Порядок = порядок групп колонок в xlsx слева направо и порядок подрядчика в CSV. */
    val contractors: List<SnapshotContractor>,
    /** Дни строго по возрастанию даты — бумажный журнал читается сверху вниз, вне зависимости от сортировки в UI. */
    val days: List<SnapshotDay>,
)

class SnapshotContractor(val name: String)

class SnapshotDay(
    /** Уже отформатированная дата, например «17.07.2026» — и колонка A в xlsx, и поле «Дата» в CSV. */
    val dateLabel: String,
    /** Строки этого дня в порядке отображения; пустых дней в списке нет. */
    val rows: List<SnapshotRow>,
)

class SnapshotRow(
    /** Ровно contractors.size элементов; null — пустой слот (в xlsx — пустые ячейки, в CSV строка не создаётся). */
    val cells: List<SnapshotCell?>,
)

class SnapshotCell(
    val locationCode: String,
    val workText: String,
    /** Ключ фото для [PhotoBytesProvider] или null, если фото ещё нет. */
    val photoId: String?,
)
