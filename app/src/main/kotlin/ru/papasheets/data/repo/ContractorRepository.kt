package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.papasheets.data.db.dao.ContractorDao
import ru.papasheets.data.db.entity.ContractorEntity

/**
 * Подрядчики: глобальный пул, порядок и цвет — политика этого класса, не UI. [ContractorsScreen]
 * (ui/settings) видит только команды create/rename/setArchived/reorder, не генерацию id/orderIndex/colorIndex.
 */
class ContractorRepository(private val dao: ContractorDao) {
    fun observeActive(): Flow<List<ContractorEntity>> = dao.observeActive()

    fun observeAll(): Flow<List<ContractorEntity>> = dao.observeAll()

    suspend fun getById(id: String): ContractorEntity? = dao.getById(id)

    /** Полный список (включая архивных) — источник данных для бэкапа (M7). */
    suspend fun getAll(): List<ContractorEntity> = dao.observeAll().first()

    suspend fun insert(contractor: ContractorEntity) = dao.insert(contractor)

    suspend fun update(contractor: ContractorEntity) = dao.update(contractor)

    /** Новый подрядчик в конец списка: orderIndex и colorIndex — следующие по счёту (палитра переиспользуется по модулю). */
    suspend fun create(name: String, shortName: String) {
        val all = dao.observeAll().first()
        val nextOrder = (all.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val nextColor = (all.maxOfOrNull { it.colorIndex } ?: -1) + 1
        dao.insert(
            ContractorEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                shortName = shortName,
                colorIndex = nextColor,
                orderIndex = nextOrder,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun rename(contractor: ContractorEntity, name: String, shortName: String) =
        dao.update(contractor.copy(name = name, shortName = shortName))

    /** Архив вместо удаления — на подрядчике могут быть записи (см. spec, схема Room). */
    suspend fun setArchived(contractor: ContractorEntity, archived: Boolean) =
        dao.update(contractor.copy(isArchived = archived))

    /** Персистит новый порядок drag-reorder'а: orderIndex = позиция в списке. */
    suspend fun reorder(ordered: List<ContractorEntity>) =
        dao.updateAll(ordered.mapIndexed { index, contractor -> contractor.copy(orderIndex = index) })

    /** Восстанавливает подрядчика из бэкапа как есть (id/поля уже решены [ru.papasheets.domain.backup.MergeRules]). */
    suspend fun upsertFromBackup(contractor: ContractorEntity) = dao.upsertFromBackup(contractor)

    /**
     * Стирает весь пул подрядчиков — узкий путь для [ru.papasheets.domain.backup.ImportInteractor]:
     * восстановление бэкапа на нетронутом устройстве (ни одного журнала/записи) находит здесь только
     * одноразовую заглушку [ru.papasheets.data.DefaultSeed], которую безопасно заменить целиком, а не
     * сливать по id — иначе те же 5 подрядчиков задваивались бы под новыми UUID сида. Не для общего
     * использования (не проверяет ссылки от записей).
     */
    suspend fun deleteAll() = dao.deleteAll()
}
