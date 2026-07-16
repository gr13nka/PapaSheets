package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Одна запись журнала: дата + подрядчик + локация + вид работ (+ фото — придёт в M2).
 * `photoId` пока просто nullable-поле без FK: PhotoEntity ещё не существует.
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
    ],
    indices = [
        Index(value = ["journalId", "dateEpochDay"]),
        Index(value = ["contractorId"]),
    ],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val journalId: String,
    val dateEpochDay: Long,
    val contractorId: String,
    val locationCode: String,
    val workText: String,
    val photoId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
