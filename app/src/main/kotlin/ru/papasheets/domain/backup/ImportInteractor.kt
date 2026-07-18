package ru.papasheets.domain.backup

import android.content.Context
import android.net.Uri
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.papasheets.data.db.TransactionRunner
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.LocationRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.exportkit.backup.BackupPhotoKind
import ru.papasheets.exportkit.backup.BackupReader
import ru.papasheets.photos.PhotoStore

/**
 * Восстанавливает .psbackup в Room. Файлы фото копируются в каталоги [PhotoStore] ДО транзакции —
 * файл-сирота без строки в БД безопасен, его подчищает [PhotoStore.collectGarbage]; сама запись в БД
 * идёт одной Room-транзакцией через [transactionRunner], чтобы частично применённый импорт был
 * невозможен. Существующие id читаются один раз до цикла (не по одному на строку — бэкап реального
 * журнала это сотни записей). Правила слияния — [MergeRules]: это восстановление бэкапа, а не
 * двусторонний sync.
 */
class ImportInteractor(
    private val journalRepository: JournalRepository,
    private val contractorRepository: ContractorRepository,
    private val recordRepository: RecordRepository,
    private val locationRepository: LocationRepository,
    private val photoStore: PhotoStore,
    private val transactionRunner: TransactionRunner,
    private val context: Context,
) {
    suspend fun import(sourceUri: Uri): BackupImportResult = withContext(Dispatchers.IO) {
        val input = requireNotNull(context.contentResolver.openInputStream(sourceUri)) {
            "Не удалось открыть файл бэкапа: $sourceUri"
        }
        val contents = input.use { stream ->
            BackupReader.read(stream) { photoId, kind, photoStream -> writePhotoFileIfAbsent(photoId, kind, photoStream) }
        }

        transactionRunner.run {
            val existingJournalIds = journalRepository.getAll().mapTo(HashSet()) { it.id }
            val existingPresetIds = locationRepository.getAll().mapTo(HashSet()) { it.id }
            val existingPhotoIds = photoStore.getAllMeta().mapTo(HashSet()) { it.id }
            val existingRecordUpdatedAt = recordRepository.getAll().associate { it.id to it.updatedAt }

            // Устройство ещё не использовалось (ни одного журнала, ни одной записи) — единственные
            // подрядчики на нём тогда это одноразовая заглушка DefaultSeed со случайными UUID, которые
            // никогда не совпадут с UUID из бэкапа. Заменяем её целиком — иначе те же 5 подрядчиков
            // задвоились бы под новыми id рядом с настоящими из бэкапа.
            if (existingJournalIds.isEmpty() && existingRecordUpdatedAt.isEmpty() && contents.data.contractors.isNotEmpty()) {
                contractorRepository.deleteAll()
            }
            val existingContractorIds = contractorRepository.getAll().mapTo(HashSet()) { it.id }

            val journalStats = contents.data.journals.fold(MergeStats()) { acc, journal ->
                val action = MergeRules.forReplaceable(journal.id in existingJournalIds)
                journalRepository.upsertFromBackup(journal.toEntity())
                acc + action
            }
            val contractorStats = contents.data.contractors.fold(MergeStats()) { acc, contractor ->
                val action = MergeRules.forReplaceable(contractor.id in existingContractorIds)
                contractorRepository.upsertFromBackup(contractor.toEntity())
                acc + action
            }
            val presetStats = contents.data.locationPresets.fold(MergeStats()) { acc, preset ->
                val action = MergeRules.forReplaceable(preset.id in existingPresetIds)
                locationRepository.upsertFromBackup(preset.toEntity())
                acc + action
            }
            // Фото-строки — до записей: RecordEntity.photoId ссылается на них по внешнему ключу.
            val photoStats = contents.data.photos.fold(MergeStats()) { acc, photo ->
                val action = MergeRules.forImmutable(photo.id in existingPhotoIds)
                if (action == MergeAction.INSERT) photoStore.insertMetaIfAbsent(photo.toMeta())
                acc + action
            }
            val recordStats = contents.data.records.fold(MergeStats()) { acc, record ->
                val action = MergeRules.forRecord(existingRecordUpdatedAt[record.id], record.updatedAt)
                if (action != MergeAction.SKIP) recordRepository.upsertFromBackup(record.toEntity())
                acc + action
            }

            BackupImportResult(
                journals = journalStats,
                contractors = contractorStats,
                locationPresets = presetStats,
                records = recordStats,
                photos = photoStats,
            )
        }
    }

    /** Файл — только если его ещё нет на диске (байты фото иммутабельны, перезаписывать нечего). */
    private fun writePhotoFileIfAbsent(photoId: String, kind: BackupPhotoKind, stream: InputStream) {
        val file = if (kind == BackupPhotoKind.MEDIUM) photoStore.mediumFile(photoId) else photoStore.thumbFile(photoId)
        if (file.exists()) return
        file.parentFile?.mkdirs()
        file.outputStream().use { out -> stream.copyTo(out) }
    }
}
