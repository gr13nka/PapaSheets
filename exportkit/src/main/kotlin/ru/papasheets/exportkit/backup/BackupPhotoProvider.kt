package ru.papasheets.exportkit.backup

import java.io.InputStream

/**
 * Доступ к байтам фото при записи бэкапа. В отличие от [ru.papasheets.exportkit.model.PhotoBytesProvider]
 * (один файл на фото, размеры для EMU) бэкап переносит обе сжатые копии как есть — medium и thumb —
 * и сам перечисляет id, а не получает их из снимка одного журнала. Null-поток — файл на диске
 * отсутствует (рассинхрон); [BackupWriter] пропускает его и отражает пропуск в [BackupWriteResult].
 */
interface BackupPhotoProvider {
    /** id всех фото, на которые есть строки в БД, — основа для перебора файлов. */
    fun ids(): List<String>

    fun openMedium(id: String): InputStream?

    fun openThumb(id: String): InputStream?
}
