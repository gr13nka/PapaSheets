package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.entity.LocationPresetEntity

@Dao
interface LocationDao {
    @Query("SELECT * FROM location_presets ORDER BY orderIndex")
    fun observePresets(): Flow<List<LocationPresetEntity>>

    @Insert
    suspend fun insert(preset: LocationPresetEntity)

    @Delete
    suspend fun delete(preset: LocationPresetEntity)

    /** Upsert — восстановление бэкапа (M7): это не двусторонний sync, импортируемая строка побеждает. */
    @Upsert
    suspend fun upsertFromBackup(preset: LocationPresetEntity)

    /** Самые недавно использованные коды локаций с заданным префиксом — история для автодополнения. */
    @Query(
        """
        SELECT DISTINCT locationCode FROM records
        WHERE locationCode LIKE :prefix || '%' AND locationCode != ''
        GROUP BY locationCode
        ORDER BY MAX(updatedAt) DESC
        LIMIT 15
        """,
    )
    suspend fun suggestFromHistory(prefix: String): List<String>
}
