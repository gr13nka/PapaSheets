package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.first
import ru.papasheets.data.db.dao.ContractorDao
import ru.papasheets.data.db.entity.ContractorEntity

/**
 * Группы выбранной таблицы: порядок и цвет — политика этого класса, не UI. [ContractorsScreen]
 * (ui/settings) видит только команды create/rename/setArchived/reorder, не генерацию id/orderIndex/colorIndex.
 */
class ContractorRepository(private val dao: ContractorDao) {
    fun observeForJournal(journalId: String) = dao.observeForJournal(journalId)
    suspend fun getForJournal(journalId: String) = dao.getForJournal(journalId)

    suspend fun getById(id: String): ContractorEntity? = dao.getById(id)

    /** Полный список (включая архивных) — источник данных для бэкапа (M7). */
    suspend fun getAll(): List<ContractorEntity> = dao.observeAll().first()

    suspend fun insert(contractor: ContractorEntity) = dao.insert(contractor)

    suspend fun update(contractor: ContractorEntity) = dao.update(contractor)

    /**
     * Новый подрядчик в конец списка: orderIndex и colorIndex — следующие по счёту (палитра
     * переиспользуется по модулю).
     *
     * @return id заведённого подрядчика — форма записи выбирает его сразу после создания, а искать
     * себя же по имени в списке ненадёжно (имена не уникальны).
     */
    suspend fun create(journalId: String, name: String, shortName: String, colorIndex: Int? = null): String {
        val all = dao.getForJournal(journalId)
        val nextOrder = (all.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val nextColor = (all.maxOfOrNull { it.colorIndex } ?: -1) + 1
        val id = UUID.randomUUID().toString()
        dao.insert(
            ContractorEntity(
                id = id,
                journalId = journalId,
                name = name,
                shortName = shortName,
                colorIndex = colorIndex ?: nextColor,
                orderIndex = nextOrder,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun rename(contractor: ContractorEntity, name: String, shortName: String, colorIndex: Int = contractor.colorIndex) =
        dao.update(contractor.copy(name = name, shortName = shortName, colorIndex = colorIndex))

    /** Архив вместо удаления — на подрядчике могут быть записи (см. spec, схема Room). */
    suspend fun setArchived(contractor: ContractorEntity, archived: Boolean) =
        dao.update(contractor.copy(isArchived = archived))

    /** Персистит новый порядок drag-reorder'а: orderIndex = позиция в списке. */
    suspend fun reorder(ordered: List<ContractorEntity>) =
        dao.updateAll(ordered.mapIndexed { index, contractor -> contractor.copy(orderIndex = index) })

    /** Восстанавливает подрядчика из бэкапа как есть (id/поля уже решены [ru.papasheets.domain.backup.MergeRules]). */
    suspend fun upsertFromBackup(contractor: ContractorEntity) = dao.upsertFromBackup(contractor)

}
