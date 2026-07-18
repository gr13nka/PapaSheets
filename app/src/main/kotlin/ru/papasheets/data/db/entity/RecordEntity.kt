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
 * `photoId` nullable в схеме (SET_NULL при удалении фото) — обязательность фото проверяется
 * валидацией формы, а не БД: ровно одно фото на запись, но пока форма не сохранена, ссылки нет.
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
    ],
    indices = [
        Index(value = ["journalId", "dateEpochDay"]),
        Index(value = ["contractorId"]),
        Index(value = ["photoId"], unique = true),
    ],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val journalId: String,
    val dateEpochDay: Long,
    val contractorId: String,
    val photoId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
