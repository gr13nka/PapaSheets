package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Подрядчик из глобального пула, общего для всех журналов. Удаление — через архив, не delete. */
@Entity(
    tableName = "contractors",
    foreignKeys = [ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["journalId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("journalId")],
)
data class ContractorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val shortName: String,
    val colorIndex: Int,
    val orderIndex: Int,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val journalId: String,
)
