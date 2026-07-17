package ru.papasheets.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Вписать длинную сторону в это число исходных пикселей — граница для thumb (M2 спека). */
private const val THUMB_LONG_SIDE = 256
private const val MEDIUM_JPEG_QUALITY = 72
private const val THUMB_JPEG_QUALITY = 70

/**
 * Декодирует фото по content-Uri в две сжатые JPEG-копии на диске: medium (вписана в 1280×720
 * альбомно / 720×1280 портретно, без апскейла) и thumb (вписана по длинной стороне в 256px).
 *
 * Порядок специально даунсемпл→поворот, а не наоборот: EXIF-поворот применяется к уже
 * уменьшенному битмапу — поворот полноразмерного кадра с камеры был бы на порядок дороже.
 */
internal class PhotoImporter(private val context: Context) {

    fun importTo(id: String, source: PhotoSource, mediumFile: File, thumbFile: File): PhotoMeta {
        val uri = source.uri()
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать размеры $uri" }

        val rotationDegrees = readExifRotationDegrees(resolver, uri)
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val effectiveWidth = if (rotated) bounds.outHeight else bounds.outWidth
        val effectiveHeight = if (rotated) bounds.outWidth else bounds.outHeight
        val (targetW, targetH) = if (effectiveWidth >= effectiveHeight) 1280 to 720 else 720 to 1280
        // Sample size считаем на исходных (ещё не повёрнутых) осях — раскрутим требуемый бокс обратно.
        val reqRawW = if (rotated) targetH else targetW
        val reqRawH = if (rotated) targetW else targetH

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqRawW, reqRawH)
        }
        val rawBitmap = openStream(resolver, uri).use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: error("Не удалось декодировать $uri")

        val uprightBitmap = applyRotation(rawBitmap, rotationDegrees)
        val mediumBitmap = fitInto(uprightBitmap, targetW, targetH)
        if (mediumBitmap !== uprightBitmap) uprightBitmap.recycle()

        val mediumWidth = mediumBitmap.width
        val mediumHeight = mediumBitmap.height
        mediumFile.parentFile?.mkdirs()
        FileOutputStream(mediumFile).use { out ->
            mediumBitmap.compress(Bitmap.CompressFormat.JPEG, MEDIUM_JPEG_QUALITY, out)
        }

        val thumbBitmap = fitLongestSide(mediumBitmap, THUMB_LONG_SIDE)
        thumbFile.parentFile?.mkdirs()
        FileOutputStream(thumbFile).use { out ->
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
        }
        if (thumbBitmap !== mediumBitmap) thumbBitmap.recycle()
        mediumBitmap.recycle()

        return PhotoMeta(id = id, width = mediumWidth, height = mediumHeight, sizeBytes = mediumFile.length())
    }

    private fun PhotoSource.uri(): Uri = when (this) {
        is PhotoSource.Camera -> tempUri
        is PhotoSource.Gallery -> uri
    }

    private fun openStream(resolver: android.content.ContentResolver, uri: Uri) =
        resolver.openInputStream(uri) ?: error("Не удалось открыть $uri")

    private fun readExifRotationDegrees(resolver: android.content.ContentResolver, uri: Uri): Int {
        val orientation = openStream(resolver, uri).use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Вписывает в maxW×maxH с сохранением пропорций; не увеличивает, если картинка уже меньше. */
    private fun fitInto(bitmap: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val scale = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) return bitmap
        val newWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun fitLongestSide(bitmap: Bitmap, box: Int): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        val scale = minOf(box.toFloat() / longestSide, 1f)
        if (scale >= 1f) return bitmap
        val newWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
