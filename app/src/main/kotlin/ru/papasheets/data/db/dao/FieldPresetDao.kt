package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.entity.FieldPresetEntity

@Dao
interface FieldPresetDao {
    @Query("SELECT * FROM field_presets WHERE fieldId = :fieldId ORDER BY orderIndex")
    fun observeFor(fieldId: String): Flow<List<FieldPresetEntity>>

    /**
     * Разовый снимок пресетов поля — то, что нужно автодополнению: оно спрашивает подсказки на каждое
     * нажатие клавиши, и подписываться на поток ради одного ответа тут не на что.
     */
    @Query("SELECT * FROM field_presets WHERE fieldId = :fieldId ORDER BY orderIndex")
    suspend fun presetsFor(fieldId: String): List<FieldPresetEntity>

    /** Все пресеты всех полей — источник данных для бэкапа. */
    @Query("SELECT * FROM field_presets ORDER BY fieldId, orderIndex")
    suspend fun getAll(): List<FieldPresetEntity>

    @Insert
    suspend fun insert(preset: FieldPresetEntity)

    @Delete
    suspend fun delete(preset: FieldPresetEntity)

    /** Upsert — восстановление бэкапа: это не двусторонний sync, импортируемая строка побеждает. */
    @Upsert
    suspend fun upsertFromBackup(preset: FieldPresetEntity)
}
