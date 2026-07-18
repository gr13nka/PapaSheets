package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Определение поля записи: что вообще можно заполнить в записи и как это выглядит в матрице.
 *
 * Раньше набор полей был зашит в схему (`records.locationCode`, `records.workText`); теперь он
 * данные, и прораб может завести своё поле («Объём», «Замечание») без изменения схемы. Встроенные
 * «Локация» и «Вид работ» — такие же строки этой таблицы, отличаются только `isBuiltIn` и тем,
 * что их id — константы (см. `BuiltInFields`).
 *
 * `type` в схеме намеренно нет: всё содержимое стройжурнала — текст. «Объём» здесь пишут как
 * «12 м²», и числовой тип только мешал бы вводу, ничего не давая взамен.
 */
@Entity(tableName = "field_defs", indices = [Index(value = ["key"], unique = true)])
data class FieldDefEntity(
    @PrimaryKey val id: String,
    /** Стабильный машинный ключ; у встроенных полей не меняется никогда. */
    val key: String,
    /** Подпись подколонки в матрице: «Л», «ВИД РАБОТ», «Объём». */
    val title: String,
    /** Полное имя в форме записи: «Локация», «Вид работ». */
    val label: String,
    val orderIndex: Int,
    val isArchived: Boolean,
    /** Встроенное поле: нельзя удалить и нельзя менять `key`. */
    val isBuiltIn: Boolean,
    val isRequired: Boolean,
    val suggestFromHistory: Boolean,
    val columnWidthDp: Int,
    /** Потолок строк текста в ячейке; 0 = без ограничения. */
    val maxLines: Int,
    val showAtCompactLod: Boolean,
    val createdAt: Long,
)
