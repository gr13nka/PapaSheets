package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Значение одного поля в одной записи — замена колонкам `records.locationCode`/`records.workText`.
 *
 * **Инвариант: пустых значений не хранится.** Если `value.isBlank()`, строки просто нет — очистить
 * поле означает удалить строку, а не записать `""`. Иначе таблица распухала бы мусором: каждое
 * необязательное поле давало бы строку на каждую запись журнала, а «пусто» существовало бы в двух
 * неразличимых видах — отсутствующая строка и пустая. Инвариант держит вся система целиком,
 * включая будущее слияние бэкапа, где «нет строки» и «пустая строка» разошлись бы в поведении.
 *
 * FK на `field_defs` — RESTRICT, зеркало `ContractorEntity`: определение поля нельзя удалить, пока
 * на него ссылаются значения. UI вместо удаления предлагает архивацию — так исторические записи
 * не теряют содержимое.
 *
 * Отдельный индекс по `recordId` не нужен: это левый префикс составного первичного ключа.
 */
@Entity(
    tableName = "record_values",
    primaryKeys = ["recordId", "fieldId"],
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FieldDefEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["fieldId"])],
)
data class RecordValueEntity(
    val recordId: String,
    val fieldId: String,
    val value: String,
)
