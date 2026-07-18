package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.dao.FieldPresetDao
import ru.papasheets.data.db.entity.FieldPresetEntity

/**
 * Списки готовых значений полей: то, что редактируется на экране полей, — отдельно от
 * [ValueSuggester], который эти же строки только читает, подмешивая их в автодополнение формы.
 */
class FieldPresetRepository(private val dao: FieldPresetDao) {
    fun observeFor(fieldId: String): Flow<List<FieldPresetEntity>> = dao.observeFor(fieldId)

    /** Полный список по всем полям — источник данных для бэкапа. */
    suspend fun getAll(): List<FieldPresetEntity> = dao.getAll()

    /**
     * Новый пресет в конец списка своего поля. Порядок = порядок добавления, отдельного reorder нет:
     * список пресетов одного поля короткий, и перетаскивать в нём нечего.
     */
    suspend fun add(fieldId: String, code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        val nextOrder = (dao.presetsFor(fieldId).maxOfOrNull { it.orderIndex } ?: -1) + 1
        dao.insert(
            FieldPresetEntity(
                id = UUID.randomUUID().toString(),
                fieldId = fieldId,
                code = trimmed,
                orderIndex = nextOrder,
            ),
        )
    }

    suspend fun delete(preset: FieldPresetEntity) = dao.delete(preset)

    /** Восстанавливает пресет из бэкапа как есть (id/поля уже решены [ru.papasheets.domain.backup.MergeRules]). */
    suspend fun upsertFromBackup(preset: FieldPresetEntity) = dao.upsertFromBackup(preset)
}
