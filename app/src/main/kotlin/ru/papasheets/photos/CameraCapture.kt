package ru.papasheets.photos

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Снимок с системной камеры: temp-файл для [android.provider.MediaStore.ACTION_IMAGE_CAPTURE] и
 * best-effort копия оригинала в публичную галерею (Pictures/PapaSheets) — сама запись остаётся
 * рабочей, даже если копия не удалась (нет разрешения на API 26-28, сбой I/O и т.п.).
 */
object CameraCapture {
    private const val FILE_PROVIDER_AUTHORITY = "ru.papasheets.fileprovider"

    /** Разрешение нужно запросить перед съёмкой только на API 26-28 (легаси-хранилище). */
    val needsWriteStoragePermission: Boolean get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    fun hasWriteStoragePermission(context: Context): Boolean =
        !needsWriteStoragePermission ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Создаёт temp-файл в cacheDir/camera и возвращает его FileProvider-Uri для передачи камере. */
    fun createTempUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    }

    suspend fun copyToGallery(context: Context, tempUri: Uri): Unit = withContext(Dispatchers.IO) {
        runCatching { copyToGalleryOrThrow(context, tempUri) }
        // Копия в галерею — сопутствующий эффект, а не часть транзакции сохранения записи: сбой не пробрасываем.
    }

    private fun copyToGalleryOrThrow(context: Context, tempUri: Uri) {
        val resolver = context.contentResolver
        val filename = "PapaSheets_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PapaSheets")
            }
            val targetUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(targetUri)?.use { out ->
                resolver.openInputStream(tempUri)?.use { input -> input.copyTo(out) }
            }
        } else {
            if (!hasWriteStoragePermission(context)) return
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PapaSheets")
                .apply { mkdirs() }
            val file = File(dir, filename)
            resolver.openInputStream(tempUri)?.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        }
    }
}
