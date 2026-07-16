package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Заранее заданный код локации, предлагаемый в автодополнении наравне с историей. */
@Entity(tableName = "location_presets")
data class LocationPresetEntity(
    @PrimaryKey val id: String,
    val code: String,
    val orderIndex: Int,
)
