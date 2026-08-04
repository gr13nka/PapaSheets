package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Цвет, которым прораб пометил конкретное значение поля: «Штукатурка» — оранжевая, «Стяжка» — синяя.
 * В матрице этим цветом заливается подколонка поля, и таблица читается бегло, без чтения текста.
 *
 * **Ключ — пара (поле, значение), а не запись.** Цвет принадлежит значению: все записи со
 * «Штукатуркой» окрашены одинаково, и перекрасить их надо один раз. Хранить цвет в `record_values`
 * означало бы держать одно и то же решение в сотне строк.
 *
 * Значение годится в ключ, потому что оно уже нормализовано на входе: `RecordRepository.replaceValues`
 * тримит его и не хранит пустых (см. инвариант в [RecordValueEntity]). Здесь то же самое обязан
 * делать `FieldValueColorRepository` — иначе цвет лёг бы на «Штукатурка », которого в записях нет.
 *
 * **Переименование значения в записи цвет не переносит** — строка здесь осиротеет, а новое написание
 * останется бесцветным, пока его не покрасят. Тем же живут `field_presets`, и по той же причине:
 * связь идёт по тексту, отслеживать его правки было бы отдельной машинерией ради редкого случая.
 * Осиротевшие строки безвредны — их никто не читает, а `ON DELETE CASCADE` уносит их вместе с полем.
 */
@Entity(
    tableName = "field_value_colors",
    primaryKeys = ["fieldId", "value"],
    foreignKeys = [
        ForeignKey(
            entity = FieldDefEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FieldValueColorEntity(
    val fieldId: String,
    val value: String,
    /** Индекс в `MatrixPalette` — той же палитре, что раздаёт цвета подрядчикам. */
    val colorIndex: Int,
)
