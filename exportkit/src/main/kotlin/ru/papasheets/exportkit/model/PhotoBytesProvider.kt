package ru.papasheets.exportkit.model

import java.io.InputStream

/**
 * Доступ к байтам и размерам фото по id — скрывает от exportkit файловую схему [ru.papasheets.photos.PhotoStore].
 * `size` синхронный и не читает файл: [XlsxWriter][ru.papasheets.exportkit.xlsx.XlsxWriter] работает без
 * suspend-функций, вызывающая сторона обязана подготовить размеры заранее (в app это уже сделано в БД —
 * `PhotoEntity.width/height` считаны при импорте фото).
 */
interface PhotoBytesProvider {
    fun open(photoId: String): InputStream

    /** (width, height) в пикселях — те же размеры, что и после сжатия PhotoImporter'ом. */
    fun size(photoId: String): Pair<Int, Int>
}
