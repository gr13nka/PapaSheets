package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ru.papasheets.data.db.TransactionRunner
import ru.papasheets.data.db.dao.RecordDao
import ru.papasheets.data.db.dao.RecordValueDao
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.data.db.entity.RecordWithValues

/**
 * Записи журнала вместе с их содержимым.
 *
 * Здесь и только здесь живут два правила хранения значений: значение обрезается по краям, а пустое
 * после обрезки не сохраняется вовсе (инвариант [RecordValueEntity]); набор значений записи при
 * сохранении заменяется целиком, иначе очищенное пользователем поле воскресало бы старым значением.
 * Вызывающая сторона передаёт карту «как в форме» и об этих правилах не знает.
 */
class RecordRepository(
    private val dao: RecordDao,
    private val valueDao: RecordValueDao,
    private val transactionRunner: TransactionRunner,
) {
    fun observeByJournal(journalId: String): Flow<List<RecordWithValues>> = dao.observeWithValuesByJournal(journalId)

    /** Только строка записи — для удаления и для переноса неизменных полей при обновлении. */
    suspend fun getById(id: String): RecordEntity? = dao.getById(id)

    suspend fun getWithValues(id: String): RecordWithValues? = dao.getWithValuesById(id)

    /** Записи подрядчика за конкретный день этого журнала — источник для «продолжить вчерашнее» (M5). */
    suspend fun listByContractorAndDate(journalId: String, contractorId: String, dateEpochDay: Long): List<RecordWithValues> =
        dao.listWithValuesByContractorAndDate(journalId, contractorId, dateEpochDay)

    /** Все записи всех журналов — источник данных для бэкапа (M7). */
    suspend fun getAll(): List<RecordEntity> = dao.getAll()

    /**
     * Все значения всех записей — вторая половина слепка для бэкапа.
     *
     * Отдельным запросом целиком, а не через `@Relation` к [getAll]: бэкап берёт записи всех журналов
     * сразу, а `@Relation` подставил бы их id в `IN (...)` и упёрся в лимит переменных SQLite.
     */
    suspend fun getAllValues(): List<RecordValueEntity> = valueDao.getAll()

    /** photoId записей журнала — каскадное удаление журнала (M8) сносит эти фото явно. */
    suspend fun photoIdsForJournal(journalId: String): List<String> = dao.photoIdsForJournal(journalId)

    /**
     * @param values `fieldId → значение`, как их набрали в форме; чистить и отсеивать пустые не нужно.
     */
    suspend fun createRecord(
        journalId: String,
        dateEpochDay: Long,
        contractorId: String,
        values: Map<String, String>,
        photoId: String?,
    ) = transactionRunner.run {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insert(
            RecordEntity(
                id = id,
                journalId = journalId,
                dateEpochDay = dateEpochDay,
                contractorId = contractorId,
                // Колонки заморожены с v2: содержимое живёт в record_values, читать их больше нельзя.
                locationCode = "",
                workText = "",
                photoId = photoId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        replaceValues(id, values)
    }

    suspend fun updateRecord(
        existing: RecordEntity,
        dateEpochDay: Long,
        contractorId: String,
        values: Map<String, String>,
        photoId: String?,
    ) = transactionRunner.run {
        dao.update(
            existing.copy(
                dateEpochDay = dateEpochDay,
                contractorId = contractorId,
                photoId = photoId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        replaceValues(existing.id, values)
    }

    suspend fun delete(record: RecordEntity) = dao.delete(record)

    /** Пакетная вставка готовых записей одной транзакцией — используется генератором тестовых данных. */
    suspend fun insertAll(records: List<RecordEntity>) = dao.insertAll(records)

    /** Значения готовых записей одной пачкой — тот же путь генератора тестовых данных. */
    suspend fun insertValues(values: List<RecordValueEntity>) = valueDao.upsertAll(values)

    /** Восстанавливает запись из бэкапа как есть (побеждающая версия уже решена [ru.papasheets.domain.backup.MergeRules]). */
    suspend fun upsertFromBackup(record: RecordEntity) = dao.upsertFromBackup(record)

    /**
     * Значения записи, восстановленной из бэкапа: набор заменяется целиком — тем же путём, что и при
     * сохранении формы, поэтому «очистить поле» и здесь означает отсутствие строки, а не пустую.
     * Замена (а не upsert) обязательна: без предварительного удаления запись, у которой поле очистили
     * уже после снятия бэкапа, воскресила бы старое значение — строки для него в бэкапе просто нет,
     * а upsert ничего не удаляет.
     *
     * Вызывается внутри транзакции импорта, после вставки самой записи — иначе не пройдёт FK.
     */
    suspend fun replaceValuesFromBackup(recordId: String, values: List<RecordValueEntity>) =
        replaceValues(recordId, values.associate { it.fieldId to it.value })

    private suspend fun replaceValues(recordId: String, values: Map<String, String>) {
        valueDao.deleteForRecord(recordId)
        val rows = values.mapNotNull { (fieldId, raw) ->
            val value = raw.trim()
            if (value.isEmpty()) null else RecordValueEntity(recordId, fieldId, value)
        }
        if (rows.isNotEmpty()) valueDao.upsertAll(rows)
    }
}
