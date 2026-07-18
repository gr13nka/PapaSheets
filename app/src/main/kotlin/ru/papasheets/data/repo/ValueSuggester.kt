package ru.papasheets.data.repo

import ru.papasheets.data.db.dao.FieldPresetDao
import ru.papasheets.data.db.dao.RecordValueDao

/**
 * Подсказки для поля с `suggestFromHistory`: заведённые прорабом пресеты этого поля, затем то, что
 * он уже писал в него раньше — свежее выше. Кода под конкретное поле здесь нет и быть не должно:
 * автодополнение включается определением поля, а его список готовых значений — своими пресетами,
 * так что «Объём» получает ровно те же возможности, что и встроенная «Локация».
 */
class ValueSuggester(
    private val valueDao: RecordValueDao,
    private val presetDao: FieldPresetDao,
) {
    suspend fun suggest(fieldId: String, prefix: String): List<String> {
        // Пресеты первыми: прораб их завёл сам, они важнее того, что случайно попало в историю.
        val result = LinkedHashSet<String>()
        presetDao.presetsFor(fieldId)
            .map { it.code }
            .filterTo(result) { it.startsWith(prefix, ignoreCase = true) }
        result.addAll(valueDao.suggestFromHistory(fieldId, prefix))
        return result.toList()
    }

    /**
     * Значения, которые в поле уже встречаются, — варианты фильтра по столбцу (Э7).
     *
     * Тот же запрос истории, что и у [suggest], но без пресетов: пресет, которым ещё ни разу не
     * воспользовались, в фильтре означал бы кнопку, гарантированно дающую пустой экран. Потолок
     * запроса (15 значений) наследуется как есть — для выбора галочкой список длиннее бесполезен.
     */
    suspend fun usedValues(fieldId: String): List<String> = valueDao.suggestFromHistory(fieldId, "")
}
