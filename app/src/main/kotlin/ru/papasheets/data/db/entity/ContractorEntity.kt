package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Подрядчик из глобального пула, общего для всех журналов. Удаление — через архив, не delete. */
@Entity(tableName = "contractors")
data class ContractorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val shortName: String,
    val colorIndex: Int,
    val orderIndex: Int,
    val isArchived: Boolean = false,
    val createdAt: Long,
)
