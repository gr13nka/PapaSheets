package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Query
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

    /** Upsert — восстановление бэкапа: это не двусторонний sync, импортируемая строка побеждает. */
    @Upsert
    suspend fun upsertFromBackup(field: FieldDefEntity)
}
