package ru.papasheets.domain

import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.RecordWithValues

/**
 * Запись там, где она не помещается целиком: карточка списка дня, подпись лайтбокса, строка выбора
 * во «вчерашнем». Всем троим нужно одно — короткая опознавательная метка и содержание.
 *
 * Роль поля здесь задана позицией, а не именем: первое поле журнала — самое узкое и опознавательное
 * (именно оно остаётся видимым на сжатом ярусе матрицы), поэтому оно и есть [primary]. Раньше все
 * три места знали про `locationCode` и `workText` по именам, и своё поле прораба прошло бы мимо них.
 */
data class RecordDisplay(
    /** Значение первого поля; пустое, если поле не заполнено или полей нет вовсе. */
    val primary: String,
    /** Непустые значения остальных полей в порядке полей — чем их соединять, решает место показа. */
    val secondaryLines: List<String>,
) {
    companion object {
        fun of(record: RecordWithValues, fields: List<FieldDefEntity>): RecordDisplay = RecordDisplay(
            primary = fields.firstOrNull()?.let { record.valueOf(it.id) }.orEmpty(),
            secondaryLines = fields.drop(1).map { record.valueOf(it.id) }.filter { it.isNotBlank() },
        )
    }
}
