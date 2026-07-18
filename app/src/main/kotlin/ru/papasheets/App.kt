package ru.papasheets

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.papasheets.data.MonthTitleFormatter
import ru.papasheets.data.db.AppDatabase
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.LocationRepository
import ru.papasheets.data.repo.LocationSuggester
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.photos.PhotoStore

/** Задержка перед первым GC — даёт форме записи время доимпортировать и сохранить свежее фото. */
private const val PHOTO_GC_STARTUP_DELAY_MS = 10_000L

class App : Application() {

    lateinit var graph: AppGraph
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        appScope.launch {
            delay(PHOTO_GC_STARTUP_DELAY_MS)
            graph.photoStore.collectGarbage()
        }
    }
}

/** Ручной контейнер зависимостей приложения (без Hilt). Всё — по требованию, через `by lazy`. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    private val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    private val monthTitleFormatter: MonthTitleFormatter by lazy { MonthTitleFormatter(appContext) }

    val journalRepository: JournalRepository by lazy { JournalRepository(database.journalDao(), monthTitleFormatter) }
    val recordRepository: RecordRepository by lazy { RecordRepository(database.recordDao()) }
    val contractorRepository: ContractorRepository by lazy { ContractorRepository(database.contractorDao()) }
    val locationSuggester: LocationSuggester by lazy { LocationSuggester(database.locationDao()) }
    val locationRepository: LocationRepository by lazy { LocationRepository(database.locationDao()) }
    val photoStore: PhotoStore by lazy { PhotoStore(appContext, database.photoDao()) }
}
