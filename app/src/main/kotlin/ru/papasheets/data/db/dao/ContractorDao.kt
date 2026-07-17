package ru.papasheets.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
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
}
