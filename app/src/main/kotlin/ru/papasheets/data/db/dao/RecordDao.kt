package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordWithValues

@Dao
interface RecordDao {
    /** Отсортировано так, чтобы группировка по дате в UI сохраняла и порядок дат, и порядок внутри дня. */
    @Transaction
    @Query("SELECT * FROM records WHERE journalId = :journalId ORDER BY dateEpochDay DESC, createdAt ASC")
    fun observeWithValuesByJournal(journalId: String): Flow<List<RecordWithValues>>

    /** Без значений: путь удаления и путь обновления правят саму строку записи, содержимое им не нужно. */
    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getById(id: String): RecordEntity?

    @Transaction
    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getWithValuesById(id: String): RecordWithValues?

    /** Записи вчерашнего дня того же подрядчика — источник для «продолжить вчерашнее» (M5). */
    @Transaction
    @Query("SELECT * FROM records WHERE journalId = :journalId AND contractorId = :contractorId AND dateEpochDay = :dateEpochDay")
    suspend fun listWithValuesByContractorAndDate(journalId: String, contractorId: String, dateEpochDay: Long): List<RecordWithValues>

    /** Все записи всех журналов как есть — источник данных для полного бэкапа (M7). */
    @Query("SELECT * FROM records")
    suspend fun getAll(): List<RecordEntity>

    /** photoId записей журнала (без null) — каскадное удаление журнала (M8) сносит эти фото явно: FK
     *  CASCADE уносит записи вместе с журналом, но файлы/строки фото не трогает. */
    @Query("SELECT DISTINCT photoId FROM records WHERE journalId = :journalId AND photoId IS NOT NULL")
    suspend fun photoIdsForJournal(journalId: String): List<String>

    @Insert
    suspend fun insert(record: RecordEntity)

    /** Пакетная вставка одной транзакцией — путь генератора тестовых данных (debug). */
    @Insert
    suspend fun insertAll(records: List<RecordEntity>)

    @Update
    suspend fun update(record: RecordEntity)

    /** Upsert — восстановление бэкапа (M7): побеждает более свежий updatedAt, решение уже принято вызывающей стороной. */
    @Upsert
    suspend fun upsertFromBackup(record: RecordEntity)

    @Delete
    suspend fun delete(record: RecordEntity)
}
