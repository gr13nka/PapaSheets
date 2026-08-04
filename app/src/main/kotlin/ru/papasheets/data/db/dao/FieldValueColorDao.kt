package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.entity.FieldValueColorEntity

@Dao
interface FieldValueColorDao {
    /**
     * Вся таблица разом, потоком. Потребителей двое — форма записи и матрица, — и обоим нужны цвета
     * не одного значения, а всех сразу: матрица красит тысячи ячеек в кадре. Строк тут столько,
     * сколько прораб выбрал цветов вручную (десятки), так что целиком дешевле точечных запросов.
     */
    @Query("SELECT * FROM field_value_colors")
    fun observeAll(): Flow<List<FieldValueColorEntity>>

    /** Разовый снимок — источник данных для бэкапа. */
    @Query("SELECT * FROM field_value_colors ORDER BY fieldId, value")
    suspend fun getAll(): List<FieldValueColorEntity>

    @Upsert
    suspend fun upsert(color: FieldValueColorEntity)

    @Query("DELETE FROM field_value_colors WHERE fieldId = :fieldId AND value = :value")
    suspend fun delete(fieldId: String, value: String)
}
