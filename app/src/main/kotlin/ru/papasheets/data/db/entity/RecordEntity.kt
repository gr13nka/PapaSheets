package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Одна запись журнала: дата + подрядчик + фото. Содержимое записи (локация, вид работ и любые
 * добавленные прорабом поля) живёт в `record_values` — здесь только то, что есть у каждой записи
 * независимо от набора полей.
 *
 * Фото — два слота, а не список в отдельной таблице: части работ нужен второй ракурс, больше двух
 * не требовалось ни разу. Потолок сознательный, поэтому и выражен схемой — таблица связи позволяла
 * бы сколько угодно и требовала бы ограничивать число где-то в коде.
 *
 * Оба слота nullable в схеме (SET_NULL при удалении фото) — обязательность проверяется валидацией
 * формы, а не БД: у сохранённой записи есть хотя бы одно фото, но пока форма не сохранена, ссылки
 * нет. Слоты равноправны и заполняются по порядку: [photoId2] без [photoId] форма не создаёт.
 *
 * UNIQUE на обоих слотах — то же, что и раньше на одном: фото принадлежит ровно одной записи,
 * иначе удаление записи уносило бы кадр, который ещё виден в другой.
 */
@Entity(
    tableName = "records",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["id"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContractorEntity::class,
            parentColumns = ["id"],
            childColumns = ["contractorId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId2"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["journalId", "dateEpochDay"]),
        Index(value = ["contractorId"]),
        Index(value = ["photoId"], unique = true),
        Index(value = ["photoId2"], unique = true),
    ],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val journalId: String,
    val dateEpochDay: Long,
    val contractorId: String,
    val photoId: String?,
    val photoId2: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Фото записи в порядке слотов; пустой список — фото нет (так бывает только у несохранённой). */
    val photoIds: List<String> get() = listOfNotNull(photoId, photoId2)
}
