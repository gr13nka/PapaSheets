package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.MonthTitleFormatter
import ru.papasheets.data.db.dao.JournalDao
import ru.papasheets.data.db.dao.JournalWithStats
import ru.papasheets.data.db.entity.JournalEntity

class JournalRepository(
    private val dao: JournalDao,
    private val monthTitleFormatter: MonthTitleFormatter,
) {
    fun observeAll(): Flow<List<JournalWithStats>> = dao.observeAll()

    fun observeById(id: String): Flow<JournalEntity?> = dao.observeById(id)

    suspend fun getById(id: String): JournalEntity? = dao.getById(id)

    /** Возвращает существующий журнал месяца, если он уже есть, иначе создаёт новый — never падает на unique(year,month). */
    suspend fun createOrGetJournal(year: Int, month: Int): JournalEntity {
        dao.getByYearMonth(year, month)?.let { return it }
        val journal = JournalEntity(
            id = UUID.randomUUID().toString(),
            year = year,
            month = month,
            title = monthTitleFormatter.title(year, month),
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(journal)
        return journal
    }
}
