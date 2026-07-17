package ru.papasheets.photos

import android.net.Uri

/** Откуда пришло фото для [PhotoStore.import] — камера пишет во временный файл, галерея отдаёт готовый content-Uri. */
sealed interface PhotoSource {
    data class Camera(val tempUri: Uri) : PhotoSource
    data class Gallery(val uri: Uri) : PhotoSource
}
