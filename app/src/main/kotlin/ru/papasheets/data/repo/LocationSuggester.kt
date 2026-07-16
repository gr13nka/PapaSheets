package ru.papasheets.data.repo

import kotlinx.coroutines.flow.first
import ru.papasheets.data.db.dao.LocationDao

/** Склеивает пресеты локаций с историей записей: пресеты первыми, дедуп, история — по недавности. */
class LocationSuggester(private val dao: LocationDao) {
    suspend fun suggest(prefix: String): List<String> {
        val presets = dao.observePresets().first()
            .map { it.code }
            .filter { it.startsWith(prefix, ignoreCase = true) }
        val history = dao.suggestFromHistory(prefix)

        val result = LinkedHashSet<String>()
        result.addAll(presets)
        result.addAll(history)
        return result.toList()
    }
}
