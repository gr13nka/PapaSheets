package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.entity.JournalEntity

/** Журнал вместе со счётчиком его записей — то, что показывает список журналов. */
data class JournalWithStats(
    val id: String,
    val year: Int,
    val month: Int,
    val title: String,
    val createdAt: Long,
    val recordCount: Int,
)

@Dao
interface JournalDao {
    @Query(
        """
        SELECT j.id AS id, j.year AS year, j.month AS month, j.title AS title, j.createdAt AS createdAt,
               COUNT(r.id) AS recordCount
        FROM journals j
        LEFT JOIN records r ON r.journalId = j.id
        GROUP BY j.id
        ORDER BY j.year DESC, j.month DESC
        """,
    )
    fun observeAll(): Flow<List<JournalWithStats>>

    @Query("SELECT * FROM journals WHERE id = :id")
    fun observeById(id: String): Flow<JournalEntity?>

    @Query("SELECT * FROM journals WHERE id = :id")
    suspend fun getById(id: String): JournalEntity?

    @Query("SELECT * FROM journals WHERE year = :year AND month = :month LIMIT 1")
    suspend fun getByYearMonth(year: Int, month: Int): JournalEntity?

    /** Все журналы как есть — источник данных для полного бэкапа (M7). */
    @Query("SELECT * FROM journals")
    suspend fun getAll(): List<JournalEntity>

    @Insert
    suspend fun insert(journal: JournalEntity)

    /**
     * Upsert, не `@Insert(onConflict = REPLACE)` — REPLACE в SQLite это DELETE+INSERT, а DELETE
     * каскадно уносит все записи этого журнала ([ru.papasheets.data.db.entity.RecordEntity] — CASCADE
     * по journalId). @Upsert делает UPDATE по конфликту, строку не трогая — что и нужно восстановлению
     * бэкапа (M7): это не двусторонний sync, импортируемая версия побеждает, но её дети должны выжить.
     */
    @Upsert
    suspend fun upsertFromBackup(journal: JournalEntity)
}
