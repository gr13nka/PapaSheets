package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.entity.FieldDefEntity

@Dao
interface FieldDefDao {
    /** Все определения, включая архивные: отфильтровать дешевле, чем потерять поле из виду. */
    @Query("SELECT * FROM field_defs ORDER BY orderIndex")
    fun observeAll(): Flow<List<FieldDefEntity>>

    @Query("SELECT * FROM field_defs ORDER BY orderIndex")
    suspend fun getAll(): List<FieldDefEntity>

    /** Сколько записей ссылается на поле — ноль означает, что его безопасно удалить, а не архивировать. */
    @Query("SELECT COUNT(*) FROM record_values WHERE fieldId = :fieldId")
    suspend fun valueCount(fieldId: String): Int

    @Insert
    suspend fun insert(field: FieldDefEntity)

    @Update
    suspend fun update(field: FieldDefEntity)

    /** Персист drag-reorder'а: весь новый порядок одной транзакцией, иначе список моргнёт полупримененным. */
    @Update
    suspend fun updateAll(fields: List<FieldDefEntity>)

    /** Только для полей без значений — проверку делает [ru.papasheets.data.repo.FieldRepository.delete]. */
    @Delete
    suspend fun delete(field: FieldDefEntity)

    /** Upsert — восстановление бэкапа: это не двусторонний sync, импортируемая строка побеждает. */
    @Upsert
    suspend fun upsertFromBackup(field: FieldDefEntity)
}
