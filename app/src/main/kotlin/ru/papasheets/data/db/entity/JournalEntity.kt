package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Один журнал = один календарный месяц. */
@Entity(
    tableName = "journals",
    indices = [Index(value = ["year", "month"], unique = true)],
)
data class JournalEntity(
    @PrimaryKey val id: String,
    val year: Int,
    val month: Int,
    val title: String,
    val createdAt: Long,
)
