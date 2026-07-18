package ru.papasheets.exportkit.backup

/** Итог записи .psbackup: сколько файлов фото реально попало в архив и какие пропущены (нет файла на диске). */
data class BackupWriteResult(
    val photoFilesWritten: Int,
    val skippedPhotoFiles: List<SkippedPhotoFile>,
)

data class SkippedPhotoFile(val photoId: String, val kind: BackupPhotoKind)
