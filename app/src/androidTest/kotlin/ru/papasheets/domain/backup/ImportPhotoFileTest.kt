package ru.papasheets.domain.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.papasheets.data.DefaultSeed
import ru.papasheets.data.MonthTitleFormatter
import ru.papasheets.data.db.AppDatabase
import ru.papasheets.data.db.TransactionRunner
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldPresetRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.FieldValueColorRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.exportkit.backup.BackupData
import ru.papasheets.exportkit.backup.BackupManifest
import ru.papasheets.exportkit.backup.BackupPhoto
import ru.papasheets.exportkit.backup.BackupPhotoProvider
import ru.papasheets.exportkit.backup.BackupWriter
import ru.papasheets.photos.PhotoStore

/**
 * Файлы фото при импорте пишутся мимо транзакции БД, поэтому оборвавшийся импорт оставляет их на
 * диске в том виде, в каком его застали. Проверяется главное свойство этой записи: под настоящим
 * именем файл появляется только записанным целиком.
 *
 * Без него усечённый JPEG от прошлой попытки прошёл бы проверку «файл уже есть», повторный импорт
 * обошёл бы его стороной, и фото осталось бы битым навсегда — молча, без единой жалобы.
 */
@RunWith(AndroidJUnit4::class)
class ImportPhotoFileTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var photoStore: PhotoStore
    private lateinit var importInteractor: ImportInteractor
    private lateinit var backupFile: File

    /** Несжимаемые байты: иначе обрезание архива пришлось бы на служебные структуры, а не на фото. */
    private val mediumBytes = Random(1).nextBytes(400_000)
    private val thumbBytes = Random(2).nextBytes(40_000)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DefaultSeed.callback())
            .build()
        val transactionRunner = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction(block)
        }
        photoStore = PhotoStore(context, db.photoDao())
        importInteractor = ImportInteractor(
            JournalRepository(db.journalDao(), MonthTitleFormatter(context)),
            ContractorRepository(db.contractorDao()),
            RecordRepository(db.recordDao(), db.recordValueDao(), transactionRunner),
            FieldPresetRepository(db.fieldPresetDao()),
            FieldRepository(db.fieldDefDao()),
            FieldValueColorRepository(db.fieldValueColorDao()),
            photoStore,
            transactionRunner,
            context,
        )
        backupFile = File(context.cacheDir, "photo-file-test.psbackup")
        photoStore.mediumFile(PHOTO_ID).delete()
        photoStore.thumbFile(PHOTO_ID).delete()
        File(photoStore.mediumFile(PHOTO_ID).parentFile, "${photoStore.mediumFile(PHOTO_ID).name}.part").delete()
    }

    @After
    fun tearDown() {
        db.close()
        backupFile.delete()
        photoStore.mediumFile(PHOTO_ID).delete()
        photoStore.thumbFile(PHOTO_ID).delete()
    }

    /**
     * Импорт обрывается посреди копирования фото — ровно то, что делает убитый процесс или
     * кончившееся место. После него под настоящим именем не должно остаться ничего: усечённый файл
     * неотличим от целого по одному лишь `exists()`, а больше проверять нечем.
     */
    @Test
    fun interruptedImportLeavesNoPhotoFileUnderItsRealName() {
        writeBackup()
        val truncated = File(context.cacheDir, "photo-file-test-truncated.psbackup")
        truncated.writeBytes(backupFile.readBytes().copyOf(backupFile.length().toInt() / 2))

        runCatching { runBlocking { importInteractor.import(Uri.fromFile(truncated)) } }

        // Обрыв обязан прийтись именно на копирование фото, иначе тест зеленел бы впустую.
        val partial = File(
            photoStore.mediumFile(PHOTO_ID).parentFile,
            "${photoStore.mediumFile(PHOTO_ID).name}.part",
        )
        assertTrue("архив оборвался не на фото — тест ничего не проверяет", partial.exists())
        assertTrue("копирование фото не успело начаться", partial.length() > 0)
        assertFalse(
            "усечённый файл фото остался под настоящим именем: повторный импорт его уже не перезапишет",
            photoStore.mediumFile(PHOTO_ID).exists(),
        )
        truncated.delete()
        partial.delete()
    }

    /** А целый архив после такого обрыва обязан довезти фото полностью — недописанный хвост не мешает. */
    @Test
    fun importAfterAnInterruptedOneWritesCompletePhotoBytes() {
        writeBackup()
        val leftover = File(
            photoStore.mediumFile(PHOTO_ID).parentFile,
            "${photoStore.mediumFile(PHOTO_ID).name}.part",
        )
        leftover.parentFile?.mkdirs()
        leftover.writeBytes(mediumBytes.copyOf(1000))

        runBlocking { importInteractor.import(Uri.fromFile(backupFile)) }

        assertArrayEquals(mediumBytes, photoStore.mediumFile(PHOTO_ID).readBytes())
        assertArrayEquals(thumbBytes, photoStore.thumbFile(PHOTO_ID).readBytes())
        assertFalse("временный файл должен исчезнуть после переименования", leftover.exists())
    }

    /** Целое фото на диске второй раз не переписывается: байты иммутабельны, работать не над чем. */
    @Test
    fun aCompletePhotoFileIsLeftAlone() {
        writeBackup()
        runBlocking { importInteractor.import(Uri.fromFile(backupFile)) }
        val stamp = photoStore.mediumFile(PHOTO_ID).lastModified()

        runBlocking { importInteractor.import(Uri.fromFile(backupFile)) }

        assertTrue(photoStore.mediumFile(PHOTO_ID).exists())
        assertArrayEquals(mediumBytes, photoStore.mediumFile(PHOTO_ID).readBytes())
        assertTrue(stamp == photoStore.mediumFile(PHOTO_ID).lastModified())
    }

    private fun writeBackup() {
        val data = BackupData(
            journals = emptyList(),
            contractors = emptyList(),
            records = emptyList(),
            photos = listOf(
                BackupPhoto(
                    id = PHOTO_ID, width = 1280, height = 720, sizeBytes = mediumBytes.size.toLong(),
                    originUri = null, createdAt = 1,
                ),
            ),
            fieldDefs = emptyList(),
            fieldPresets = emptyList(),
            recordValues = emptyList(),
        )
        val photos = object : BackupPhotoProvider {
            override fun ids() = listOf(PHOTO_ID)
            override fun openMedium(id: String) = ByteArrayInputStream(mediumBytes)
            override fun openThumb(id: String) = ByteArrayInputStream(thumbBytes)
        }
        val manifest = BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, 2, "test", 1)
        backupFile.outputStream().use { BackupWriter.write(manifest, data, photos, it) }
    }

    private companion object {
        const val PHOTO_ID = "photo-truncation"
    }
}
