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
            val photoMetaByRecord = importPhotos(plan, preview.photoBytes)

            transactionRunner.run {
                val journal = journalRepository.createOrGetJournal(plan.year, plan.month)
                plan.newContractors.forEach { contractorRepository.insert(it) }
                plan.newFields.forEach { fieldRepository.upsertFromBackup(it) }
                photoMetaByRecord.values.flatten().forEach { photoStore.insertMetaIfAbsent(it) }

                val now = System.currentTimeMillis()
                val records = ArrayList<RecordEntity>(plan.records.size)
                val values = ArrayList<RecordValueEntity>()
                plan.records.forEachIndexed { index, planned ->
                    val recordId = UUID.randomUUID().toString()
                    val photoIds = photoMetaByRecord[index].orEmpty().map { it.id }
                    records += RecordEntity(
                        id = recordId,
                        journalId = journal.id,
                        dateEpochDay = planned.date.toEpochDay(),
                        contractorId = planned.contractorId,
                        photoId = photoIds.getOrNull(0),
                        photoId2 = photoIds.getOrNull(1),
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
            preview.sourceFile.delete()
        }
    }

    /** Пользователь отказался от импорта — временная копия файла больше не нужна. */
    fun discard(preview: XlsxImportPreview) {
        preview.sourceFile.delete()
    }

    // --- планирование -------------------------------------------------------------------------

    /**
     * Само сопоставление считает [XlsxImportPlanner] — здесь остаётся только то, ради чего нужны
     * `Context` и репозитории: человеческое название месяца и проверка, есть ли уже такой журнал.
     */
    private suspend fun plan(sheet: ParsedSheet, file: File): XlsxImportPreview {
        val plan = XlsxImportPlanner.plan(
            sheet = sheet,
            existingContractors = contractorRepository.getAll(),
            existingFields = fieldRepository.getAll(),
            existingJournals = journalRepository.getAll(),
            now = System.currentTimeMillis(),
        )

        return XlsxImportPreview(
            journalTitle = monthTitle(LocalDate.of(plan.year, plan.month, 1)),
            plan = plan,
            sourceFile = file,
            photoBytes = { ref -> sheet.photoBytes(ref) },
        )
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
     * @return индекс записи в плане → мета созданных фото по порядку; записи без фото в карту не
     * попадают, а фото, которое не удалось декодировать, просто отсутствует в списке своей записи.
     */
    private fun importPhotos(
        plan: XlsxImportPlan,
        photoBytes: (PhotoRef) -> ByteArray?,
    ): Map<Int, List<ru.papasheets.photos.PhotoMeta>> {
        val importer = PhotoImporter(context)
        val result = LinkedHashMap<Int, List<ru.papasheets.photos.PhotoMeta>>()
        val staging = File(context.cacheDir, "xlsx-photo-staging.jpg")
        try {
            plan.records.forEachIndexed { index, planned ->
                val metas = planned.photos.mapNotNull { ref ->
                    val bytes = photoBytes(ref) ?: return@mapNotNull null
                    staging.writeBytes(bytes)
                    val id = UUID.randomUUID().toString()
                    try {
                        importer.importTo(id, PhotoSource.Gallery(Uri.fromFile(staging)), photoStore.mediumFile(id), photoStore.thumbFile(id))
                    } catch (e: IllegalStateException) {
                        // Не всякая картинка в чужом файле — фотография: встречаются png-иконки и битые
                        // вложения. Такое фото пропускаем, а не роняем весь импорт из-за одной картинки.
                        null
                    }
                }
                if (metas.isNotEmpty()) result[index] = metas
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

