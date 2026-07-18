package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import ru.papasheets.data.db.entity.RecordValueEntity

@Dao
interface RecordValueDao {
    @Query("SELECT * FROM record_values")
    suspend fun getAll(): List<RecordValueEntity>

    /**
     * Сохранение записи — это `deleteForRecord` + `upsertAll` непустых значений в одной транзакции.
     * Так очистка поля становится обычным случаем: значения для него просто не приходит
     * (см. инвариант «пустых значений не хранится» в `RecordValueEntity`).
     */
    @Query("DELETE FROM record_values WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: String)

    @Upsert
    suspend fun upsertAll(values: List<RecordValueEntity>)

    /**
     * Самые недавно использованные значения поля с заданным префиксом — история для автодополнения.
     *
     * Джойн с `records` нужен ради сортировки: «недавно использованное» — это `MAX(records.updatedAt)`,
     * а собственного `updatedAt` у значения нет. Без джойна пришлось бы сортировать по `rowid`,
     * то есть по порядку вставки, а не редактирования — свежеотредактированное значение уезжало бы
     * в конец списка вместо начала.
     */
    @Query(
        """
        SELECT v.value FROM record_values v JOIN records r ON r.id = v.recordId
        WHERE v.fieldId = :fieldId AND v.value LIKE :prefix || '%' AND v.value != ''
        GROUP BY v.value
        ORDER BY MAX(r.updatedAt) DESC
        LIMIT 15
        """,
    )
    suspend fun suggestFromHistory(fieldId: String, prefix: String): List<String>
}
