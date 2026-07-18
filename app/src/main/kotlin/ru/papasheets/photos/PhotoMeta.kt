package ru.papasheets.photos

/**
 * Метаданные сжатой medium-копии — размеры ПОСЛЕ downsample/rotate (нужны xlsx-экспорту в M6).
 * `originUri`/`createdAt` нужны только полному бэкапу (M7) — [PhotoImporter] их не знает, поэтому дефолт.
 */
data class PhotoMeta(
    val id: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val originUri: String? = null,
    val createdAt: Long = 0,
)
