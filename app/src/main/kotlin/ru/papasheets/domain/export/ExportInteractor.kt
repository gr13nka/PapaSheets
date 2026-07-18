package ru.papasheets.domain.export

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.exportkit.csv.CsvWriter
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.PhotoBytesProvider
import ru.papasheets.exportkit.xlsx.XlsxWriter
import ru.papasheets.photos.PhotoStore

/** Что писать в выбранный пользователем SAF-uri — три варианта диалога экспорта (M6). */
enum class ExportFormat { XLSX_WITH_PHOTOS, XLSX_NO_PHOTOS, CSV }

/**
 * Собирает [JournalSnapshot] журнала из Room (на [Dispatchers.IO] — сотни записей не должны дёргать
 * главный поток) и рендерит его в SAF-uri, который уже выбрал пользователь через `CreateDocument`.
 * Сама открывает и закрывает выходной поток — вызывающей стороне (VM) нужно только знать журнал,
 * формат и uri.
 */
class ExportInteractor(
    private val journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    private val contractorRepository: ContractorRepository,
    private val fieldRepository: FieldRepository,
    private val photoStore: PhotoStore,
    private val context: Context,
) {
    suspend fun export(journalId: String, format: ExportFormat, targetUri: Uri): Unit = withContext(Dispatchers.IO) {
        val journal = requireNotNull(journalRepository.getById(journalId)) { "Журнал не найден: $journalId" }
        val records = recordRepository.observeByJournal(journalId).first()
        val contractors = contractorRepository.observeAll().first()
        val fields = fieldRepository.observeActive().first()
        val snapshot = buildJournalSnapshot(journal.title, records, contractors, fields)

        val out = requireNotNull(context.contentResolver.openOutputStream(targetUri)) {
            "Не удалось открыть поток для записи: $targetUri"
        }
        out.use {
            when (format) {
                ExportFormat.CSV -> CsvWriter.write(snapshot, it)
                ExportFormat.XLSX_NO_PHOTOS -> XlsxWriter.write(snapshot, photos = null, out = it)
                ExportFormat.XLSX_WITH_PHOTOS -> XlsxWriter.write(snapshot, photoBytesProvider(collectPhotoSizes(snapshot)), it)
            }
        }
    }

    /** Имя файла по умолчанию для SAF `CreateDocument` — «Июль 2026.xlsx» / «…csv». */
    fun defaultFileName(journalTitle: String, format: ExportFormat): String {
        val extension = if (format == ExportFormat.CSV) "csv" else "xlsx"
        return "$journalTitle.$extension"
    }

    /**
     * [PhotoBytesProvider.size] синхронный (exportkit — чистый JVM без suspend), поэтому размеры
     * фото, на которые ссылается снимок, читаются из Room заранее, одним проходом.
     */
    private suspend fun collectPhotoSizes(snapshot: JournalSnapshot): Map<String, Pair<Int, Int>> {
        val photoIds = snapshot.days.asSequence()
            .flatMap { it.rows.asSequence() }
            .flatMap { it.cells.asSequence() }
            .mapNotNull { it?.photoId }
            .toSet()
        return photoIds.associateWith { id ->
            val meta = requireNotNull(photoStore.getMeta(id)) { "Фото не найдено: $id" }
            meta.width to meta.height
        }
    }

    private fun photoBytesProvider(sizes: Map<String, Pair<Int, Int>>): PhotoBytesProvider = object : PhotoBytesProvider {
        override fun open(photoId: String) = photoStore.mediumFile(photoId).inputStream()
        override fun size(photoId: String) = sizes.getValue(photoId)
    }
}
