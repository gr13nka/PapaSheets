package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.dao.RecordDao
import ru.papasheets.data.db.entity.RecordEntity

class RecordRepository(private val dao: RecordDao) {
    fun observeByJournal(journalId: String): Flow<List<RecordEntity>> = dao.observeByJournal(journalId)

    suspend fun getById(id: String): RecordEntity? = dao.getById(id)

    suspend fun createRecord(
        journalId: String,
        dateEpochDay: Long,
        contractorId: String,
        locationCode: String,
        workText: String,
    ) {
        val now = System.currentTimeMillis()
        dao.insert(
            RecordEntity(
                id = UUID.randomUUID().toString(),
                journalId = journalId,
                dateEpochDay = dateEpochDay,
                contractorId = contractorId,
                locationCode = locationCode,
                workText = workText,
                photoId = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateRecord(
        existing: RecordEntity,
        dateEpochDay: Long,
        contractorId: String,
        locationCode: String,
        workText: String,
    ) {
        dao.update(
            existing.copy(
                dateEpochDay = dateEpochDay,
                contractorId = contractorId,
                locationCode = locationCode,
                workText = workText,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(record: RecordEntity) = dao.delete(record)
}
