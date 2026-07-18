package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.dao.RecordDao
import ru.papasheets.data.db.entity.RecordEntity

class RecordRepository(private val dao: RecordDao) {
    fun observeByJournal(journalId: String): Flow<List<RecordEntity>> = dao.observeByJournal(journalId)

    suspend fun getById(id: String): RecordEntity? = dao.getById(id)

    /** Записи подрядчика за конкретный день этого журнала — источник для «продолжить вчерашнее» (M5). */
    suspend fun listByContractorAndDate(journalId: String, contractorId: String, dateEpochDay: Long): List<RecordEntity> =
        dao.listByContractorAndDate(journalId, contractorId, dateEpochDay)

    suspend fun createRecord(
        journalId: String,
        dateEpochDay: Long,
        contractorId: String,
        locationCode: String,
        workText: String,
        photoId: String?,
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
                photoId = photoId,
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
        photoId: String?,
    ) {
        dao.update(
            existing.copy(
                dateEpochDay = dateEpochDay,
                contractorId = contractorId,
                locationCode = locationCode,
                workText = workText,
                photoId = photoId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(record: RecordEntity) = dao.delete(record)

    /** Пакетная вставка готовых записей одной транзакцией — используется генератором тестовых данных. */
    suspend fun insertAll(records: List<RecordEntity>) = dao.insertAll(records)
}
