package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ru.papasheets.data.db.entity.PhotoEntity

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getById(id: String): PhotoEntity?

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: String)

    /** Все id — основа для сверки с файлами на диске в PhotoStore.collectGarbage(). */
    @Query("SELECT id FROM photos")
    suspend fun listAllIds(): List<String>
}
