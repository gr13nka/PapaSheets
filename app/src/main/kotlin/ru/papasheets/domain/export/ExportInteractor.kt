package ru.papasheets.domain.export

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

/** Что писать в выбранный пользователем файл — три варианта диалога экспорта (M6). */
enum class ExportFormat { XLSX_WITH_PHOTOS, XLSX_NO_PHOTOS, CSV }

/**
 * Собирает [JournalSnapshot] журнала из Room (на [Dispatchers.IO] — сотни записей не должны дёргать
 * главный поток) и рендерит его в файл журнала внутри выбранной прорабом папки ([ExportFolder]).
 * Куда именно писать, как перезаписать и куда деть прежнюю версию — знает [ExportFolder]; интерактор
 * лишь называет файл и наполняет поток. Вызывающей стороне (VM) нужно знать только журнал и формат.
 */
class ExportInteractor(
    private val journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    private val contractorRepository: ContractorRepository,
    private val fieldRepository: FieldRepository,
    private val photoStore: PhotoStore,
    private val exportFolder: ExportFolder,
) {
    suspend fun export(journalId: String, format: ExportFormat): Unit = withContext(Dispatchers.IO) {
        val journal = requireNotNull(journalRepository.getById(journalId)) { "Журнал не найден: $journalId" }
        val records = recordRepository.observeByJournal(journalId).first()
        val contractors = contractorRepository.observeAll().first()
        val fields = fieldRepository.observeActive().first()
        val snapshot = buildJournalSnapshot(journal.title, records, contractors, fields)

        // replace() уносит прежнюю версию в архив и открывает усекающий поток — см. ExportFolder.
        exportFolder.replace(defaultFileName(journal.title, format), mimeType(format)).use {
            when (format) {
                ExportFormat.CSV -> CsvWriter.write(snapshot, it)
                ExportFormat.XLSX_NO_PHOTOS -> XlsxWriter.write(snapshot, photos = null, out = it)
                ExportFormat.XLSX_WITH_PHOTOS -> XlsxWriter.write(snapshot, photoBytesProvider(collectPhotoSizes(snapshot)), it)
            }
        }
    }

    /** Имя файла журнала — «Июль 2026.xlsx» / «…csv»; одно и то же от экспорта к экспорту. */
    fun defaultFileName(journalTitle: String, format: ExportFormat): String {
        val extension = if (format == ExportFormat.CSV) "csv" else "xlsx"
        return "$journalTitle.$extension"
    }

    /** Имя выбранной папки экспорта для показа в диалоге, или `null`, если папка не выбрана. */
    suspend fun exportFolderName(): String? = withContext(Dispatchers.IO) { exportFolder.displayName() }

    /** Запоминает выбранное в системном диалоге дерево папок (см. [ExportFolder.remember]). */
    fun rememberExportFolder(treeUri: Uri) = exportFolder.remember(treeUri)

    private fun mimeType(format: ExportFormat): String =
        if (format == ExportFormat.CSV) MIME_CSV else MIME_XLSX

    /**
     * [PhotoBytesProvider.size] синхронный (exportkit — чистый JVM без suspend), поэтому размеры
     * фото, на которые ссылается снимок, читаются из Room заранее, одним проходом.
     */
    private suspend fun collectPhotoSizes(snapshot: JournalSnapshot): Map<String, Pair<Int, Int>> {
        val photoIds = snapshot.days.asSequence()
            .flatMap { it.rows.asSequence() }
            .flatMap { it.cells.asSequence() }
            .filterNotNull()
            .flatMap { it.photoIds.asSequence() }
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

    private companion object {
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MIME_CSV = "text/csv"
    }
}
