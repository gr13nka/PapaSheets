package ru.papasheets.photos

/** Метаданные сжатой medium-копии — размеры ПОСЛЕ downsample/rotate (нужны xlsx-экспорту в M6). */
data class PhotoMeta(
    val id: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)
