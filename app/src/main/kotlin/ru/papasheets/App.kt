package ru.papasheets

import android.app.Application
import android.content.Context
import ru.papasheets.data.MonthTitleFormatter
import ru.papasheets.data.db.AppDatabase
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.LocationSuggester
import ru.papasheets.data.repo.RecordRepository

class App : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
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
}
