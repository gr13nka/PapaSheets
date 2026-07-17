package ru.papasheets.photos

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/** Запрос для [ActivityResultContracts.PickVisualMedia] — только изображения, оригинал в галерее не трогаем. */
object GalleryPick {
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
}
