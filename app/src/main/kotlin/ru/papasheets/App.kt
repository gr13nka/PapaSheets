package ru.papasheets

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.papasheets.data.MonthTitleFormatter
import ru.papasheets.data.db.AppDatabase
import ru.papasheets.data.db.TransactionRunner
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldPresetRepository
import ru.papasheets.data.repo.FieldValueColorRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.data.repo.ValueSuggester
import ru.papasheets.domain.DeleteJournalInteractor
import ru.papasheets.domain.LastPlace
import ru.papasheets.domain.backup.BackupInteractor
import ru.papasheets.domain.backup.ImportInteractor
import ru.papasheets.domain.export.ExportFolder
import ru.papasheets.domain.export.ExportInteractor
import ru.papasheets.domain.xlsx.XlsxImportInteractor
import ru.papasheets.logging.AppLog
import ru.papasheets.photos.PhotoStore

/** GC сирот-фото гоняется при выходе приложения на передний план, но не чаще одного раза за этот период. */
private val PHOTO_GC_MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

class App : Application() {

    lateinit var graph: AppGraph
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastGcAt = 0L

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.appLog.installAsUncaughtExceptionHandler()
        scheduleForegroundGc()
    }

    /**
     * Подчистка рассинхрона фото/БД ([PhotoStore.collectGarbage]) при каждом выходе на передний план,
     * с троттлингом [PHOTO_GC_MIN_INTERVAL_MS]. Надёжнее разового старта: чинит и при долгой работе, и
     * после возврата из фона; свежие фото защищены 24-часовым grace-периодом внутри самого GC.
     */
    private fun scheduleForegroundGc() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val now = System.currentTimeMillis()
                if (now - lastGcAt < PHOTO_GC_MIN_INTERVAL_MS) return
                lastGcAt = now
                appScope.launch { graph.photoStore.collectGarbage() }
            }
        })
    }
}

/** Ручной контейнер зависимостей приложения (без Hilt). Всё — по требованию, через `by lazy`. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext

    val appLog: AppLog by lazy { AppLog(appContext) }
    private val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    private val monthTitleFormatter: MonthTitleFormatter by lazy { MonthTitleFormatter(appContext) }

    val journalRepository: JournalRepository by lazy { JournalRepository(database.journalDao(), monthTitleFormatter) }
    val recordRepository: RecordRepository by lazy {
        RecordRepository(database.recordDao(), database.recordValueDao(), transactionRunner)
    }
    val contractorRepository: ContractorRepository by lazy { ContractorRepository(database.contractorDao()) }
    val fieldRepository: FieldRepository by lazy { FieldRepository(database.fieldDefDao()) }
    val deleteJournalInteractor: DeleteJournalInteractor by lazy {
        DeleteJournalInteractor(journalRepository, recordRepository, photoStore, lastPlace)
    }
    val lastPlace: LastPlace by lazy { LastPlace(appContext) }
    val valueSuggester: ValueSuggester by lazy { ValueSuggester(database.recordValueDao(), database.fieldPresetDao()) }
    val fieldPresetRepository: FieldPresetRepository by lazy { FieldPresetRepository(database.fieldPresetDao()) }
    val fieldValueColorRepository: FieldValueColorRepository by lazy {
        FieldValueColorRepository(database.fieldValueColorDao())
    }
    val photoStore: PhotoStore by lazy { PhotoStore(appContext, database.photoDao()) }
    private val exportFolder: ExportFolder by lazy { ExportFolder(appContext) }
    val exportInteractor: ExportInteractor by lazy {
        ExportInteractor(journalRepository, recordRepository, contractorRepository, fieldRepository, photoStore, exportFolder, appLog)
    }

    /**
     * Единственное место, где виден [database] целиком: одна транзакция сразу по нескольким таблицам
     * нужна и восстановлению бэкапа (M7), и сохранению записи вместе с её значениями.
     */
    private val transactionRunner: TransactionRunner by lazy {
        object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction(block)
        }
    }
    val backupInteractor: BackupInteractor by lazy {
        BackupInteractor(
            journalRepository, contractorRepository, recordRepository, fieldPresetRepository,
            fieldRepository, fieldValueColorRepository, photoStore, appContext,
        )
    }
    val importInteractor: ImportInteractor by lazy {
        ImportInteractor(
            journalRepository, contractorRepository, recordRepository, fieldPresetRepository,
            fieldRepository, fieldValueColorRepository, photoStore, transactionRunner, appContext,
        )
    }
    val xlsxImportInteractor: XlsxImportInteractor by lazy {
        XlsxImportInteractor(
            journalRepository, contractorRepository, recordRepository,
            fieldRepository, photoStore, transactionRunner, appContext,
        )
    }
}
