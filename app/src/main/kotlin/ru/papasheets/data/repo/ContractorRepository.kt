package ru.papasheets.data.repo

import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.dao.ContractorDao
import ru.papasheets.data.db.entity.ContractorEntity

class ContractorRepository(private val dao: ContractorDao) {
    fun observeActive(): Flow<List<ContractorEntity>> = dao.observeActive()

    fun observeAll(): Flow<List<ContractorEntity>> = dao.observeAll()

    suspend fun insert(contractor: ContractorEntity) = dao.insert(contractor)

    suspend fun update(contractor: ContractorEntity) = dao.update(contractor)
}
