package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.entity.ContractorEntity

@Dao
interface ContractorDao {
    @Query("SELECT * FROM contractors WHERE isArchived = 0 ORDER BY orderIndex")
    fun observeActive(): Flow<List<ContractorEntity>>

    @Query("SELECT * FROM contractors ORDER BY orderIndex")
    fun observeAll(): Flow<List<ContractorEntity>>

    @Query("SELECT * FROM contractors WHERE id = :id")
    suspend fun getById(id: String): ContractorEntity?

    @Insert
    suspend fun insert(contractor: ContractorEntity)

    @Update
    suspend fun update(contractor: ContractorEntity)

    /** Пакетное обновление одной транзакцией — путь reorder'а (drag-n-drop меняет orderIndex у многих сразу). */
    @Update
    suspend fun updateAll(contractors: List<ContractorEntity>)

    /**
     * Upsert, не `@Insert(onConflict = REPLACE)` — REPLACE в SQLite это DELETE+INSERT, а RecordEntity
     * ссылается на подрядчика с `onDelete = RESTRICT`: DELETE обязан либо упасть, либо (после того как
     * записи уже стёрты каскадом от журнала) молча пройти, оставив пустоту вместо реальной защиты.
     * @Upsert делает UPDATE по конфликту, строку не удаляя — восстановление бэкапа (M7): это не
     * двусторонний sync, импортируемая версия побеждает, но её дети должны выжить.
     */
    @Upsert
    suspend fun upsertFromBackup(contractor: ContractorEntity)

    /** Только для восстановления бэкапа на нетронутом устройстве — см. [ru.papasheets.data.repo.ContractorRepository.deleteAll]. */
    @Query("DELETE FROM contractors")
    suspend fun deleteAll()
}
