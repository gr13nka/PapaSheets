package ru.papasheets.domain.xlsx

import java.time.LocalDate
import java.util.UUID
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.exportkit.xlsx.read.ParsedSheet
import ru.papasheets.exportkit.xlsx.read.PhotoRef

/**
 * Решённый план импорта: все сопоставления с содержимым БД уже сделаны, осталось записать.
 *
 * План строится один раз — на нём же считается предпросмотр, им же выполняется запись
 * ([XlsxImportPreview] не хранит своих цифр, а выводит их отсюда). Благодаря этому подтверждённый
 * импорт не может разойтись с показанными пользователю числами.
 *
 * Ни файла, ни байтов фото здесь нет намеренно: это решение о том, ЧТО получится, а не о том, откуда
 * взялось. Всё, что связано с исходным файлом, живёт в [XlsxImportPreview] рядом, но отдельно.
 */
internal class XlsxImportPlan(
    val year: Int,
    val month: Int,
    /** true, если журнал за этот месяц уже заведён и записи добавятся в него, а не в новый. */
    val journalExists: Boolean,
    val newContractors: List<ContractorEntity>,
    val newFields: List<FieldDefEntity>,
    val records: List<PlannedRecord>,
    /** Группы файла, легшие на уже заведённых подрядчиков по имени. */
    val matchedContractorCount: Int,
    /** Колонки файла, легшие на существующие поля — включая встроенные «Л» и «ВИД РАБОТ». */
    val matchedFieldCount: Int,
    /** Группы и колонки без подписи: привязать их не к чему, импорт их пропускает. */
    val skippedUnnamedContractors: Int,
    val skippedUnnamedFields: Int,
) {
    /** Сколько дней журнала затронет импорт: записи одного дня показываются одной датой. */
    val dayCount: Int get() = records.distinctBy { it.date }.size

    /** Всего доступных фото по всем записям. Больше не приедет, меньше — может (см. `importPhotos`). */
    val photoCount: Int get() = records.sumOf { it.photos.size }
}

/**
 * Одна будущая запись журнала. Подрядчик и поля адресуются готовыми id: новые сущности получают
 * их ещё на этапе планирования, поэтому запись не зависит от того, создавались они сейчас или
 * лежали в базе с прошлого раза.
 */
internal class PlannedRecord(
    val date: LocalDate,
    val contractorId: String,
    /** id поля → значение; пустые значения сюда не попадают. */
    val values: Map<String, String>,
    /** Доступные фото записи по порядку (максимум — сколько колонок Ф было в файле). */
    val photos: List<PhotoRef>,
)

/**
 * Раскладывает чужую таблицу по тому, что уже заведено на устройстве.
 *
 * Вынесено из [XlsxImportInteractor] отдельно, потому что это самостоятельное решение, а не деталь
 * чтения файла: id из чужой таблицы взяться неоткуда, поэтому подрядчики опознаются по имени, а
 * колонки — по подписи, и ошибка здесь означает молча не туда разложенные записи в файле, где
 * проверить глазами нечего. Такое решение обязано проверяться тестами, а тесты — не требовать
 * ни `Context`, ни устройства: здесь нет ни Android, ни Room-запросов, ни ввода-вывода, только
 * разобранный лист и списки того, что уже есть.
 *
 * `now` приходит параметром, а не берётся внутри: у всех созданных сущностей должна быть одна
 * отметка времени, и тесту нужен воспроизводимый результат.
 */
internal object XlsxImportPlanner {

    fun plan(
        sheet: ParsedSheet,
        existingContractors: List<ContractorEntity>,
        existingFields: List<FieldDefEntity>,
        existingJournals: List<JournalEntity>,
        now: Long,
    ): XlsxImportPlan {
        val month = resolveMonth(sheet)
        val contractors = ContractorMatcher(existingContractors, sheet.contractors, now)
        val fields = FieldMatcher(existingFields, sheet.fieldTitles, now)

        val records = ArrayList<PlannedRecord>()
        for (day in sheet.days) {
            val date = day.date ?: continue
            for (row in day.rows) {
                row.cells.forEachIndexed { contractorIndex, cell ->
                    if (cell == null) return@forEachIndexed
                    val contractorId = contractors.idFor(contractorIndex) ?: return@forEachIndexed
                    val values = fields.valuesFor(cell.values)
                    val photos = cell.photos.filter { it.isPresent }
                    // Запись без единого значения и без фото переносить нечего.
                    if (values.isEmpty() && photos.isEmpty()) return@forEachIndexed
                    records += PlannedRecord(date, contractorId, values, photos)
                }
            }
        }

        if (records.isEmpty()) {
            throw XlsxImportException("В таблице не нашлось ни одной записи, которую можно перенести")
        }

        return XlsxImportPlan(
            year = month.year,
            month = month.monthValue,
            journalExists = existingJournals.any { it.year == month.year && it.month == month.monthValue },
            newContractors = contractors.newEntities,
            newFields = fields.newEntities,
            records = records,
            matchedContractorCount = contractors.matchedCount,
            matchedFieldCount = fields.matchedCount,
            skippedUnnamedContractors = contractors.unnamedCount,
            skippedUnnamedFields = fields.unnamedCount,
        )
    }

    /**
     * Месяц журнала берётся из самих дат — это единственный надёжный источник. Наш собственный
     * экспорт пишет дату без года («01.06»), и тогда определить месяц нечем: имя листа у заказчика
     * «Лист2», а в имени файла года тоже нет. Угадывать здесь нельзя — записи уехали бы в чужой год.
     *
     * Месяц выбирается по большинству дат, а записи меньшинства не отбрасываются: файл-месяц обычно
     * задевает соседний день на стыке (последнее число предыдущего месяца в первой строке). Потерять
     * такую запись хуже, чем показать её в журнале соседнего месяца — дата у неё своя собственная и
     * останется верной.
     */
    private fun resolveMonth(sheet: ParsedSheet): LocalDate {
        val dates = sheet.days.mapNotNull { it.date }
        if (dates.isEmpty()) {
            throw XlsxImportException(
                "В таблице не удалось распознать даты с годом — импортировать такой файл нельзя",
            )
        }
        return dates.groupingBy { LocalDate.of(it.year, it.monthValue, 1) }.eachCount()
            .maxByOrNull { it.value }!!.key
    }
}

/**
 * Сопоставление подрядчиков файла с теми, что уже заведены на устройстве, — по имени: id из чужой
 * таблицы взяться неоткуда. В эталоне подрядчиков 28, а в свежей установке 5, так что большинство
 * придётся создать.
 *
 * Группы без имени (в файле с вырезанной таблицей строк такими будут все) не сопоставляются и не
 * создаются: подрядчик без имени в журнале бесполезен, а 28 безымянных строк — тем более.
 */
private class ContractorMatcher(existing: List<ContractorEntity>, names: List<String>, now: Long) {
    private val idByIndex = HashMap<Int, String>()
    val newEntities = ArrayList<ContractorEntity>()
    var matchedCount = 0
        private set
    var unnamedCount = 0
        private set

    init {
        val byName = existing.associateBy { it.name.trim().lowercase() }
        var nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        var nextColor = (existing.maxOfOrNull { it.colorIndex } ?: -1) + 1
        val createdByName = HashMap<String, String>()
        names.forEachIndexed { index, rawName ->
            val name = rawName.trim()
            if (name.isEmpty()) {
                unnamedCount++
                return@forEachIndexed
            }
            val key = name.lowercase()
            val existingId = byName[key]?.id
            if (existingId != null) {
                idByIndex[index] = existingId
                matchedCount++
                return@forEachIndexed
            }
            // Одно имя может встретиться в шапке дважды — второй раз это та же колонка, не второй подрядчик.
            val alreadyCreated = createdByName[key]
            if (alreadyCreated != null) {
                idByIndex[index] = alreadyCreated
                return@forEachIndexed
            }
            val entity = ContractorEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                shortName = name.take(SHORT_NAME_LENGTH),
                colorIndex = nextColor++,
                orderIndex = nextOrder++,
                createdAt = now,
            )
            newEntities += entity
            createdByName[key] = entity.id
            idByIndex[index] = entity.id
        }
    }

    fun idFor(contractorIndex: Int): String? = idByIndex[contractorIndex]

    private companion object {
        const val SHORT_NAME_LENGTH = 12
    }
}

/**
 * Сопоставление колонок файла с определениями полей записи — по подписи. Встроенные «Л» и
 * «ВИД РАБОТ» ([ru.papasheets.exportkit.backup.BuiltInFields]) заведены в базе с этими же
 * заголовками, поэтому ложатся на себя сами; заводить рядом их дубликаты было бы нечем потом слить.
 */
private class FieldMatcher(existing: List<FieldDefEntity>, titles: List<String>, now: Long) {
    private val idByColumn = HashMap<Int, String>()
    val newEntities = ArrayList<FieldDefEntity>()
    var matchedCount = 0
        private set
    var unnamedCount = 0
        private set

    init {
        val byTitle = existing.associateBy { it.title.trim().lowercase() }
        var nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        titles.forEachIndexed { index, rawTitle ->
            val title = rawTitle.trim()
            if (title.isEmpty()) {
                unnamedCount++
                return@forEachIndexed
            }
            val match = byTitle[title.lowercase()]
            if (match != null) {
                idByColumn[index] = match.id
                matchedCount++
                return@forEachIndexed
            }
            val entity = FieldDefEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                label = title,
                orderIndex = nextOrder++,
                isArchived = false,
                isBuiltIn = false,
                isRequired = false,
                suggestFromHistory = true,
                columnWidthDp = DEFAULT_WIDTH_DP,
                maxLines = 0,
                showAtCompactLod = false,
                createdAt = now,
            )
            newEntities += entity
            idByColumn[index] = entity.id
        }
    }

    /** id поля → значение для одной ячейки; пустые значения и колонки без подписи отбрасываются. */
    fun valuesFor(cellValues: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        cellValues.forEachIndexed { index, value ->
            val fieldId = idByColumn[index] ?: return@forEachIndexed
            val trimmed = value.trim()
            // record_values не хранит пустых значений — пустое значение это отсутствие строки.
            if (trimmed.isNotEmpty()) result[fieldId] = trimmed
        }
        return result
    }

    private companion object {
        const val DEFAULT_WIDTH_DP = 120
    }
}
