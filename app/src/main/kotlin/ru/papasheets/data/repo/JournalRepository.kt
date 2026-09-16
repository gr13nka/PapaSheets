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

    /** Полный список журналов — источник данных для бэкапа (M7). */
    suspend fun getAll(): List<JournalEntity> = dao.getAll()

    /** Возвращает существующий журнал месяца, если он уже есть, иначе создаёт новый — never падает на unique(year,month). */
    suspend fun createJournal(year: Int, month: Int, title: String = monthTitleFormatter.title(year, month), id: String = UUID.randomUUID().toString()): JournalEntity {
        val journal = JournalEntity(
            id = id,
            year = year,
            month = month,
            title = title.trim(),
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(journal)
        return journal
    }

    /** Восстанавливает журнал из бэкапа как есть (id/поля уже решены [ru.papasheets.domain.backup.MergeRules]). */
    suspend fun upsertFromBackup(journal: JournalEntity) = dao.upsertFromBackup(journal)

    /** Удаляет журнал (его записи уносит FK CASCADE). Фото сносит [ru.papasheets.domain.DeleteJournalInteractor]. */
    suspend fun delete(journalId: String) = dao.deleteTable(journalId)

    suspend fun rename(journalId: String, title: String) {
        require(title.isNotBlank())
        dao.rename(journalId, title.trim())
    }
}
