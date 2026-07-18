package ru.papasheets.domain.backup

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.papasheets.BuildConfig
import ru.papasheets.data.db.APP_DATABASE_VERSION
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldPresetRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.exportkit.backup.BackupData
import ru.papasheets.exportkit.backup.BackupManifest
import ru.papasheets.exportkit.backup.BackupPhotoProvider
import ru.papasheets.exportkit.backup.BackupWriteResult
import ru.papasheets.exportkit.backup.BackupWriter
import ru.papasheets.photos.PhotoStore

private val BACKUP_FILE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/**
 * Собирает полный слепок Room — все журналы/подрядчики/поля/записи/значения/фото-метаданные/пресеты,
 * без фильтра по одному журналу (в отличие от [ru.papasheets.domain.export.ExportInteractor]) — и
 * стримит его в .psbackup через [BackupWriter]. Это транспорт переноса на новый телефон и передачи
 * другому человеку, задел будущей синхронизации (см. spec).
 */
class BackupInteractor(
    private val journalRepository: JournalRepository,
    private val contractorRepository: ContractorRepository,
    private val recordRepository: RecordRepository,
    private val fieldPresetRepository: FieldPresetRepository,
    private val fieldRepository: FieldRepository,
    private val photoStore: PhotoStore,
    private val context: Context,
) {
    suspend fun backup(targetUri: Uri): BackupWriteResult = withContext(Dispatchers.IO) {
        val data = BackupData(
            journals = journalRepository.getAll().map { it.toBackup() },
            contractors = contractorRepository.getAll().map { it.toBackup() },
            records = recordRepository.getAll().map { it.toBackup() },
            photos = photoStore.getAllMeta().map { it.toBackup() },
            fieldPresets = fieldPresetRepository.getAll().map { it.toBackup() },
            // Определения полей — вместе со значениями и всегда: без них значения нечем истолковать.
            fieldDefs = fieldRepository.getAll().map { it.toBackup() },
            recordValues = recordRepository.getAllValues().map { it.toBackup() },
        )
        val manifest = BackupManifest(
            formatVersion = BackupManifest.CURRENT_FORMAT_VERSION,
            // Справочная пометка: из какой версии схемы Room снят слепок. Берётся из продакшн-кода —
            // вписанная числом, она бы отстала от следующего же bump'а и молча врала о содержимом файла.
            dbSchemaVersion = APP_DATABASE_VERSION,
            appVersionName = BuildConfig.VERSION_NAME,
            exportedAtMillis = System.currentTimeMillis(),
        )
        val photoIds = data.photos.map { it.id }
        val photoProvider = object : BackupPhotoProvider {
            override fun ids() = photoIds
            override fun openMedium(id: String) = photoStore.mediumFile(id).takeIf { it.exists() }?.inputStream()
            override fun openThumb(id: String) = photoStore.thumbFile(id).takeIf { it.exists() }?.inputStream()
        }

        val out = requireNotNull(context.contentResolver.openOutputStream(targetUri)) {
            "Не удалось открыть поток для записи: $targetUri"
        }
        out.use { BackupWriter.write(manifest, data, photoProvider, it) }
    }

    /** Имя файла по умолчанию для SAF `CreateDocument` — «PapaSheets-2026-07-18.psbackup». */
    fun defaultFileName(): String = "PapaSheets-${BACKUP_FILE_DATE_FORMAT.format(Date())}.psbackup"
}
