package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.entity.RecordEntity

@Dao
interface RecordDao {
    /** Отсортировано так, чтобы группировка по дате в UI сохраняла и порядок дат, и порядок внутри дня. */
    @Query("SELECT * FROM records WHERE journalId = :journalId ORDER BY dateEpochDay DESC, createdAt ASC")
    fun observeByJournal(journalId: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getById(id: String): RecordEntity?

    /** Записи вчерашнего дня того же подрядчика — источник для «продолжить вчерашнее» (M5). */
    @Query("SELECT * FROM records WHERE journalId = :journalId AND contractorId = :contractorId AND dateEpochDay = :dateEpochDay")
    suspend fun listByContractorAndDate(journalId: String, contractorId: String, dateEpochDay: Long): List<RecordEntity>

    @Insert
    suspend fun insert(record: RecordEntity)

    /** Пакетная вставка одной транзакцией — путь генератора тестовых данных (debug). */
    @Insert
    suspend fun insertAll(records: List<RecordEntity>)

    @Update
    suspend fun update(record: RecordEntity)

    @Delete
    suspend fun delete(record: RecordEntity)
}
