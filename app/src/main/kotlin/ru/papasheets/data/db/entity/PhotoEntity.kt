package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сжатая копия фото записи (medium+thumb лежат в filesDir, эта строка — только метаданные).
 * Размеры и вес — ПОСЛЕ сжатия PhotoImporter'ом (нужны xlsx-экспорту в M6).
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val id: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val originUri: String?,
    val createdAt: Long,
)
