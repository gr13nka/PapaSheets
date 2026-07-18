package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.papasheets.data.db.dao.LocationDao
import ru.papasheets.data.db.entity.LocationPresetEntity

/**
 * CRUD пресетов локаций для [LocationsScreen][ru.papasheets.ui.settings.LocationsScreen] — отдельно
 * от [LocationSuggester], который только читает пресеты для автодополнения формы.
 */
class LocationRepository(private val dao: LocationDao) {
    fun observePresets(): Flow<List<LocationPresetEntity>> = dao.observePresets()

    /** Новый пресет в конец списка — reorder не нужен (spec: порядок = порядок добавления). */
    suspend fun add(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        val nextOrder = (dao.observePresets().first().maxOfOrNull { it.orderIndex } ?: -1) + 1
        dao.insert(LocationPresetEntity(id = UUID.randomUUID().toString(), code = trimmed, orderIndex = nextOrder))
    }

    suspend fun delete(preset: LocationPresetEntity) = dao.delete(preset)
}
