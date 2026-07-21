package ru.papasheets.domain.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.papasheets.data.DefaultSeed
import ru.papasheets.data.MonthTitleFormatter
import ru.papasheets.data.db.AppDatabase
import ru.papasheets.data.db.TransactionRunner
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.FieldPresetRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.exportkit.backup.BackupContractor
import ru.papasheets.exportkit.backup.BackupData
import ru.papasheets.exportkit.backup.BackupJournal
import ru.papasheets.exportkit.backup.BackupManifest
import ru.papasheets.exportkit.backup.BackupPhotoProvider
import ru.papasheets.exportkit.backup.BackupRecord
import ru.papasheets.exportkit.backup.BackupRecordValue
import ru.papasheets.exportkit.backup.BackupWriter
import ru.papasheets.exportkit.backup.BuiltInFields
import ru.papasheets.photos.PhotoStore

/**
 * Значения записи при импорте следуют решению по самой записи — проверка на настоящем SQL, а не на
 * правилах в отрыве от БД: именно здесь видно, что старая строка действительно удалена, а не
 * перекрыта upsert-ом.
 *
 * Центральный случай — «очищенное поле не воскресает». Пользователь стёр локацию, снял бэкап, а на
 * другом устройстве та же запись ещё со старой локацией. Строки для локации в бэкапе нет вовсе, и
 * upsert без предварительного удаления просто не тронул бы местную — запись собралась бы из двух
 * своих версий разом. Отсюда `deleteForRecord` перед вставкой (см. `MergeRules`).
 */
@RunWith(AndroidJUnit4::class)
class ImportValueMergeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var importInteractor: ImportInteractor
    private lateinit var recordRepository: RecordRepository
    private lateinit var backupFile: File

    @Before
    fun setUp() {
        // inMemory + DefaultSeed: встроенные field_defs нужны как цель внешнего ключа record_values.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DefaultSeed.callback())
            .build()
        val transactionRunner = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction(block)
        }
        val journalRepository = JournalRepository(db.journalDao(), MonthTitleFormatter(context))
        val contractorRepository = ContractorRepository(db.contractorDao())
        val fieldPresetRepository = FieldPresetRepository(db.fieldPresetDao())
        val fieldRepository = FieldRepository(db.fieldDefDao())
        recordRepository = RecordRepository(db.recordDao(), db.recordValueDao(), transactionRunner)
        importInteractor = ImportInteractor(
            journalRepository, contractorRepository, recordRepository, fieldPresetRepository,
            fieldRepository, PhotoStore(context, db.photoDao()), transactionRunner, context,
        )

        backupFile = File(context.cacheDir, "value-merge-test.psbackup")
        runBlocking {
            journalRepository.upsertFromBackup(JournalEntity(JOURNAL_ID, 2026, 7, "Июль 2026", 1))
            contractorRepository.upsertFromBackup(
                ContractorEntity(CONTRACTOR_ID, "Петров", "ПТР", 0, 0, false, 1),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        backupFile.delete()
    }

    @Test
    fun clearedFieldDoesNotComeBackWhenImportedRecordWins() {
        givenLocalRecord(updatedAt = 100, values = mapOf(BuiltInFields.LOCATION_ID to "1-01", BuiltInFields.WORK_ID to "Старое"))
        // В бэкапе локации нет вовсе — её очистили до снятия бэкапа. Запись свежее локальной.
        givenBackup(recordUpdatedAt = 200, values = listOf(BuiltInFields.WORK_ID to "Новое"))

        importBackup()

        assertEquals(mapOf(BuiltInFields.WORK_ID to "Новое"), currentValues())
    }

    @Test
    fun localValuesSurviveWhenImportedRecordIsOlder() {
        givenLocalRecord(updatedAt = 300, values = mapOf(BuiltInFields.LOCATION_ID to "1-01", BuiltInFields.WORK_ID to "Местное"))
        givenBackup(recordUpdatedAt = 200, values = listOf(BuiltInFields.WORK_ID to "Из бэкапа"))

        importBackup()

        assertEquals(
            mapOf(BuiltInFields.LOCATION_ID to "1-01", BuiltInFields.WORK_ID to "Местное"),
            currentValues(),
        )
    }

    @Test
    fun valuesOfAnUnknownRecordAreInsertedWithIt() {
        givenBackup(
            recordUpdatedAt = 200,
            values = listOf(BuiltInFields.LOCATION_ID to "2-05", BuiltInFields.WORK_ID to "Стяжка"),
        )

        val result = importBackup()

        assertEquals(
            mapOf(BuiltInFields.LOCATION_ID to "2-05", BuiltInFields.WORK_ID to "Стяжка"),
            currentValues(),
        )
        assertEquals(MergeStats(added = 2), result.recordValues)
    }

    @Test
    fun builtInFieldDefsFromBackupReplaceLocalOnesInsteadOfDoubling() {
        givenBackup(recordUpdatedAt = 200, values = listOf(BuiltInFields.WORK_ID to "Стяжка"))

        val result = importBackup()

        // Константные id: два встроенных поля из бэкапа легли на два здешних, третьей колонки нет.
        assertEquals(2, runBlocking { db.fieldDefDao().getAll() }.size)
        assertEquals(MergeStats(updated = 2), result.fieldDefs)
    }

    private fun givenLocalRecord(updatedAt: Long, values: Map<String, String>) = runBlocking {
        recordRepository.insertAll(listOf(recordEntity(updatedAt)))
        recordRepository.insertValues(values.map { (fieldId, value) -> RecordValueEntity(RECORD_ID, fieldId, value) })
    }

    private fun recordEntity(updatedAt: Long) = RecordEntity(
        id = RECORD_ID, journalId = JOURNAL_ID, dateEpochDay = 100, contractorId = CONTRACTOR_ID,
        photoId = null, photoId2 = null, createdAt = 1, updatedAt = updatedAt,
    )

    /** Настоящий .psbackup текущего формата: путь чтения в тесте тот же, что и в приложении. */
    private fun givenBackup(recordUpdatedAt: Long, values: List<Pair<String, String>>) {
        val data = BackupData(
            journals = listOf(BackupJournal(JOURNAL_ID, 2026, 7, "Июль 2026", 1)),
            contractors = listOf(BackupContractor(CONTRACTOR_ID, "Петров", "ПТР", 0, 0, false, 1)),
            records = listOf(
                BackupRecord(
                    id = RECORD_ID, journalId = JOURNAL_ID, dateEpochDay = 100, contractorId = CONTRACTOR_ID,
                    photoId = null, createdAt = 1, updatedAt = recordUpdatedAt,
                ),
            ),
            photos = emptyList(),
            fieldDefs = runBlocking { db.fieldDefDao().getAll() }.map { it.toBackup() },
            recordValues = values.map { (fieldId, value) -> BackupRecordValue(RECORD_ID, fieldId, value) },
        )
        val manifest = BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, 2, "test", 1)
        val noPhotos = object : BackupPhotoProvider {
            override fun ids() = emptyList<String>()
            override fun openMedium(id: String) = null
            override fun openThumb(id: String) = null
        }
        backupFile.outputStream().use { BackupWriter.write(manifest, data, noPhotos, it) }
    }

    private fun importBackup(): BackupImportResult = runBlocking { importInteractor.import(Uri.fromFile(backupFile)) }

    private fun currentValues(): Map<String, String> = runBlocking {
        db.recordValueDao().getAll().filter { it.recordId == RECORD_ID }.associate { it.fieldId to it.value }
    }

    private companion object {
        const val JOURNAL_ID = "j1"
        const val CONTRACTOR_ID = "c1"
        const val RECORD_ID = "r1"
    }
}
