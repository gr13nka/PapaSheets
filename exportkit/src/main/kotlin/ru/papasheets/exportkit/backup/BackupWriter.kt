package ru.papasheets.exportkit.backup

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

private const val MANIFEST_ENTRY = "manifest.json"
private const val DATA_ENTRY = "data.json"

/**
 * Стримит .psbackup в [out]: manifest → data → файлы фото по одному — как и [ru.papasheets.exportkit.xlsx.XlsxWriter],
 * ничего не буферизует в памяти целиком (сотни фото на телефоне прораба). Заканчивает запись
 * ([ZipOutputStream.finish]), но не закрывает [out] — закрытие остаётся за вызывающей стороной.
 */
object BackupWriter {
    fun write(manifest: BackupManifest, data: BackupData, photos: BackupPhotoProvider, out: OutputStream): BackupWriteResult {
        val zip = ZipOutputStream(out)

        putEntry(zip, MANIFEST_ENTRY, Json.encodeToString(BackupManifest.serializer(), manifest))
        putEntry(zip, DATA_ENTRY, Json.encodeToString(BackupData.serializer(), data))

        var written = 0
        val skipped = ArrayList<SkippedPhotoFile>()
        for (id in photos.ids()) {
            written += putPhotoFile(zip, id, BackupPhotoKind.MEDIUM, photos.openMedium(id), skipped)
            written += putPhotoFile(zip, id, BackupPhotoKind.THUMB, photos.openThumb(id), skipped)
        }

        zip.finish()
        return BackupWriteResult(photoFilesWritten = written, skippedPhotoFiles = skipped)
    }

    /** Возвращает 1, если файл записан, 0 и запись в [skipped], если поток отсутствовал (файл потерян на диске). */
    private fun putPhotoFile(
        zip: ZipOutputStream,
        photoId: String,
        kind: BackupPhotoKind,
        stream: InputStream?,
        skipped: MutableList<SkippedPhotoFile>,
    ): Int {
        if (stream == null) {
            skipped += SkippedPhotoFile(photoId, kind)
            return 0
        }
        zip.putNextEntry(ZipEntry("photos/${kind.zipDir}/$photoId.jpg"))
        stream.use { it.copyTo(zip) }
        zip.closeEntry()
        return 1
    }

    private fun putEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
