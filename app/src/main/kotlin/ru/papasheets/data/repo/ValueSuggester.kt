package ru.papasheets.data.repo

import kotlinx.coroutines.flow.first
import ru.papasheets.data.db.dao.LocationDao
import ru.papasheets.data.db.dao.RecordValueDao
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * Подсказки для поля с `suggestFromHistory`: что уже писали в это же поле раньше, свежее — выше.
 * Автодополнение включается самим определением поля, отдельного кода под конкретное поле нет.
 *
 * ВРЕМЕННАЯ АСИММЕТРИЯ: пресеты (`location_presets`) всё ещё привязаны к одной локации, а не к полю,
 * поэтому подмешиваются только полю [BuiltInFields.LOCATION_ID] — своё поле «Объём» пока получает
 * лишь историю. Пресеты переедут на уровень поля вместе с экраном полей, и этот `if` исчезнет.
 */
class ValueSuggester(
    private val valueDao: RecordValueDao,
    private val locationDao: LocationDao,
) {
    suspend fun suggest(fieldId: String, prefix: String): List<String> {
        // Пресеты первыми: прораб их завёл сам, они важнее того, что случайно попало в историю.
        val result = LinkedHashSet<String>()
        if (fieldId == BuiltInFields.LOCATION_ID) {
            locationDao.observePresets().first()
                .map { it.code }
                .filterTo(result) { it.startsWith(prefix, ignoreCase = true) }
        }
        result.addAll(valueDao.suggestFromHistory(fieldId, prefix))
        return result.toList()
    }
}
