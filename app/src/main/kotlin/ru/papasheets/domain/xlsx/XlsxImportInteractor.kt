package ru.papasheets.domain.xlsx

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.papasheets.data.db.TransactionRunner
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.exportkit.xlsx.read.ParsedSheet
import ru.papasheets.exportkit.xlsx.read.PhotoRef
import ru.papasheets.exportkit.xlsx.read.XlsxFormatException
import ru.papasheets.exportkit.xlsx.read.XlsxReader
import ru.papasheets.photos.PhotoImporter
import ru.papasheets.photos.PhotoSource
import ru.papasheets.photos.PhotoStore

/**
 * Затаскивает в журнал старую таблицу-месяц со встроенными фото — ту, что заказчик вёл в Google
 * Sheets до появления приложения.
 *
 * Импорт двухшаговый и это не удобство, а требование: [preview] только читает файл и раскладывает
 * его по существующим подрядчикам и полям, [apply] пишет уже подтверждённый человеком план. Файл
 * приходит из системного диалога, и цена молчаливой ошибки — мусор в базе без возможности отката.
 *
 * С [ru.papasheets.domain.backup.ImportInteractor] общего только транзакционность и приём с фото:
 * тот восстанавливает наш собственный бэкап, где всё уже разложено по id, а здесь чужой файл, где
 * сопоставлять приходится по именам. Переиспользовать его целиком не выйдет, и ломать ради этого
 * восстановление бэкапа — тем более.
 */
class XlsxImportInteractor(
    private val journalRepository: JournalRepository,
    private val contractorRepository: ContractorRepository,
    private val recordRepository: RecordRepository,
    private val fieldRepository: FieldRepository,
    private val photoStore: PhotoStore,
    private val transactionRunner: TransactionRunner,
    private val context: Context,
) {

    /**
     * Читает файл и раскладывает его по тому, что уже есть на устройстве. В БД не пишет ничего.
     * Временную копию файла оставляет за собой — она нужна [apply], чтобы достать байты фото.
     */
    suspend fun preview(sourceUri: Uri): XlsxImportPreview = withContext(Dispatchers.IO) {
        val file = copyToCache(sourceUri)
        try {
            plan(XlsxReader.read(file), file)
        } catch (e: XlsxFormatException) {
            file.delete()
            throw XlsxImportException(e.message ?: "Не удалось прочитать таблицу", e)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    /**
     * Пишет подтверждённый план. Фото раскладываются по каталогам [PhotoStore] ДО транзакции — файл
     * без строки в БД безопасен, его подчистит [PhotoStore.collectGarbage], тогда как строка без
     * файла показала бы в журнале битую картинку. Сама запись идёт одной транзакцией: частично
     * применённый импорт чужого месяца разбирать вручную невозможно.
     */
    suspend fun apply(preview: XlsxImportPreview): XlsxImportResult = withContext(Dispatchers.IO) {
        val plan = preview.plan
        try {
            val photoMetaByRecord = importPhotos(plan)

            transactionRunner.run {
                val journal = journalRepository.createOrGetJournal(plan.year, plan.month)
                plan.newContractors.forEach { contractorRepository.insert(it) }
                plan.newFields.forEach { fieldRepository.upsertFromBackup(it) }
                photoMetaByRecord.values.forEach { photoStore.insertMetaIfAbsent(it) }

                val now = System.currentTimeMillis()
                val records = ArrayList<RecordEntity>(plan.records.size)
                val values = ArrayList<RecordValueEntity>()
                plan.records.forEachIndexed { index, planned ->
                    val recordId = UUID.randomUUID().toString()
                    records += RecordEntity(
                        id = recordId,
                        journalId = journal.id,
                        dateEpochDay = planned.date.toEpochDay(),
                        contractorId = planned.contractorId,
                        photoId = photoMetaByRecord[index]?.id,
                        createdAt = now,
                        updatedAt = now,
                    )
                    planned.values.forEach { (fieldId, value) ->
                        values += RecordValueEntity(recordId = recordId, fieldId = fieldId, value = value)
                    }
                }
                // Пакетно: реальный месяц — это сотни записей, по одной они шли бы заметно дольше.
                recordRepository.insertAll(records)
                recordRepository.insertValues(values)

                XlsxImportResult(
                    journalTitle = journal.title,
                    createdContractors = plan.newContractors.size,
                    createdFields = plan.newFields.size,
                    importedRecords = records.size,
                    importedPhotos = photoMetaByRecord.size,
                )
            }
        } finally {
            plan.sourceFile.delete()
        }
    }

    /** Пользователь отказался от импорта — временная копия файла больше не нужна. */
    fun discard(preview: XlsxImportPreview) {
        preview.plan.sourceFile.delete()
    }

    // --- планирование -------------------------------------------------------------------------

    private suspend fun plan(sheet: ParsedSheet, file: File): XlsxImportPreview {
        val month = resolveMonth(sheet)

        val contractors = ContractorMatcher(contractorRepository.getAll(), sheet.contractors)
        val fields = FieldMatcher(fieldRepository.getAll(), sheet.fieldTitles)

        val records = ArrayList<PlannedRecord>()
        var photoCount = 0
        for (day in sheet.days) {
            val date = day.date ?: continue
            for (row in day.rows) {
                row.cells.forEachIndexed { contractorIndex, cell ->
                    if (cell == null) return@forEachIndexed
                    val contractorId = contractors.idFor(contractorIndex) ?: return@forEachIndexed
                    val values = fields.valuesFor(cell.values)
                    val photo = cell.photo?.takeIf { it.isPresent }
                    // Запись без единого значения и без фото переносить нечего.
                    if (values.isEmpty() && photo == null) return@forEachIndexed
                    if (photo != null) photoCount++
                    records += PlannedRecord(date, contractorId, values, photo)
                }
            }
        }

        if (records.isEmpty()) {
            throw XlsxImportException("В таблице не нашлось ни одной записи, которую можно перенести")
        }

        val plan = XlsxImportPlan(
            year = month.year,
            month = month.monthValue,
            newContractors = contractors.newEntities,
            newFields = fields.newEntities,
            records = records,
            sourceFile = file,
            photoBytes = { ref -> sheet.photoBytes(ref) },
        )
        val existingJournal = journalRepository.getAll().any { it.year == month.year && it.month == month.monthValue }

        return XlsxImportPreview(
            journalTitle = monthTitle(month),
            journalExists = existingJournal,
            dayCount = records.map { it.date }.distinct().size,
            recordCount = records.size,
            photoCount = photoCount,
            newContractors = contractors.newEntities.map { it.name },
            matchedContractorCount = contractors.matchedCount,
            newFields = fields.newEntities.map { it.title },
            matchedFieldCount = fields.matchedCount,
            skippedUnnamedContractors = contractors.unnamedCount,
            skippedUnnamedFields = fields.unnamedCount,
            plan = plan,
        )
    }

    /**
     * Месяц журнала берётся из самих дат — это единственный надёжный источник. Наш собственный
     * экспорт пишет дату без года («01.06»), и тогда определить месяц нечем: имя листа у заказчика
     * «Лист2», а в имени файла года тоже нет. Угадывать здесь нельзя — записи уехали бы в чужой год.
     */
    private fun resolveMonth(sheet: ParsedSheet): LocalDate {
        val dates = sheet.days.mapNotNull { it.date }
        if (dates.isEmpty()) {
            throw XlsxImportException(
                "В таблице не удалось распознать даты с годом — импортировать такой файл нельзя",
            )
        }
        // Файл-месяц может задевать соседний день на стыке — берём месяц большинства записей.
        return dates.groupingBy { LocalDate.of(it.year, it.monthValue, 1) }.eachCount()
            .maxByOrNull { it.value }!!.key
    }

    private fun monthTitle(month: LocalDate): String {
        val names = context.resources.getStringArray(ru.papasheets.R.array.month_names_nominative)
        return "${names[month.monthValue - 1]} ${month.year}"
    }

    // --- фото ---------------------------------------------------------------------------------

    /**
     * Байты из архива прогоняются тем же [PhotoImporter], что и съёмка с камеры: он делает medium и
     * thumb нужных размеров и разворачивает кадр по EXIF. Свой путь «просто положить файл» дал бы
     * полноразмерные снимки в списке и тормоза при прокрутке.
     *
     * [PhotoImporter] умеет читать только Uri, поэтому байты сначала ложатся во временный файл —
     * `file://`-Uri для `ContentResolver` неотличим от галерейного.
     *
     * @return индекс записи в плане → мета созданного фото; записи без фото в карту не попадают.
     */
    private fun importPhotos(plan: XlsxImportPlan): Map<Int, ru.papasheets.photos.PhotoMeta> {
        val importer = PhotoImporter(context)
        val result = LinkedHashMap<Int, ru.papasheets.photos.PhotoMeta>()
        val staging = File(context.cacheDir, "xlsx-photo-staging.jpg")
        try {
            plan.records.forEachIndexed { index, planned ->
                val ref = planned.photo ?: return@forEachIndexed
                val bytes = plan.photoBytes(ref) ?: return@forEachIndexed
                staging.writeBytes(bytes)
                val id = UUID.randomUUID().toString()
                val meta = try {
                    importer.importTo(id, PhotoSource.Gallery(Uri.fromFile(staging)), photoStore.mediumFile(id), photoStore.thumbFile(id))
                } catch (e: IllegalStateException) {
                    // Не всякая картинка в чужом файле — фотография: встречаются png-иконки и битые
                    // вложения. Запись переносим без фото, а не роняем весь импорт из-за одной.
                    return@forEachIndexed
                }
                result[index] = meta
            }
        } finally {
            staging.delete()
        }
        return result
    }

    private fun copyToCache(sourceUri: Uri): File {
        val file = File(context.cacheDir, "xlsx-import-${System.currentTimeMillis()}.xlsx")
        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw XlsxImportException("Не удалось открыть выбранный файл")
            input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
        } catch (e: IOException) {
            file.delete()
            throw XlsxImportException("Не удалось прочитать выбранный файл", e)
        }
        return file
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
private class ContractorMatcher(existing: List<ContractorEntity>, names: List<String>) {
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
                createdAt = System.currentTimeMillis(),
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
private class FieldMatcher(existing: List<FieldDefEntity>, titles: List<String>) {
    private val idByColumn = HashMap<Int, String>()
    val newEntities = ArrayList<FieldDefEntity>()
    var matchedCount = 0
        private set
    var unnamedCount = 0
        private set

    init {
        val byTitle = existing.associateBy { it.title.trim().lowercase() }
        val usedKeys = existing.mapTo(HashSet()) { it.key }
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
                key = uniqueKey(title, usedKeys),
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
                createdAt = System.currentTimeMillis(),
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

    /** Ключ поля уникален по схеме; из подписи он читается лучше, чем из случайного UUID. */
    private fun uniqueKey(title: String, used: MutableSet<String>): String {
        val base = title.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
            .ifEmpty { "field" }
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "${base}_${suffix++}"
        }
        return candidate
    }

    private companion object {
        const val DEFAULT_WIDTH_DP = 120
    }
}
