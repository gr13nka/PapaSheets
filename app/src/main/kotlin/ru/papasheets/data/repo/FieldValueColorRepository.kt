package ru.papasheets.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.papasheets.data.db.dao.FieldValueColorDao
import ru.papasheets.data.db.entity.FieldValueColorEntity

/** Цвета значений: `fieldId` → значение → индекс в палитре. Пустая вложенная карта не хранится. */
typealias FieldValueColors = Map<String, Map<String, Int>>

/**
 * Цвета, которыми прораб пометил значения полей (см. [FieldValueColorEntity]).
 *
 * Наружу отдаётся не список строк, а готовая для поиска карта: обоим потребителям — форме записи и
 * сборке модели матрицы — нужен ответ на вопрос «какого цвета вот это значение вот этого поля», и
 * раскладывать список в карту у каждого из них означало бы делать это дважды и по-разному.
 */
class FieldValueColorRepository(private val dao: FieldValueColorDao) {
    fun observeAll(): Flow<FieldValueColors> = dao.observeAll().map { rows ->
        rows.groupBy { it.fieldId }.mapValues { (_, forField) ->
            forField.associate { it.value to it.colorIndex }
        }
    }

    /** Полный список по всем полям — источник данных для бэкапа. */
    suspend fun getAll(): List<FieldValueColorEntity> = dao.getAll()

    /**
     * Красит значение или снимает цвет ([colorIndex] = null).
     *
     * Значение тримится так же, как это делает `RecordRepository.replaceValues` перед записью в
     * `record_values`: иначе цвет лёг бы на строку с хвостовым пробелом, которой в записях нет,
     * и в матрице не проявился бы никак — молча.
     */
    suspend fun setColor(fieldId: String, value: String, colorIndex: Int?) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        if (colorIndex == null) {
            dao.delete(fieldId, trimmed)
        } else {
            dao.upsert(FieldValueColorEntity(fieldId, trimmed, colorIndex))
        }
    }

    /** Восстанавливает цвет из бэкапа как есть (конфликт уже решён [ru.papasheets.domain.backup.MergeRules]). */
    suspend fun upsertFromBackup(color: FieldValueColorEntity) = dao.upsert(color)
}
