package ru.papasheets.exportkit.backup

/**
 * Почему файл не принят как бэкап. Приложение показывает каждой причине свою переведённую строку,
 * поэтому версия формата у [TooNew] и [TooOld] — поле, а не часть текста.
 */
sealed interface BackupFormatReason {
    /** Архив не читается: оборван, повреждён или это вовсе не ZIP. */
    data object NotABackup : BackupFormatReason

    data object MissingManifest : BackupFormatReason

    data object MissingData : BackupFormatReason

    data object CorruptManifest : BackupFormatReason

    data object CorruptData : BackupFormatReason

    /** Бэкап сделан более новой версией приложения: читать вперёд мы не можем. */
    data class TooNew(val formatVersion: Int) : BackupFormatReason

    /** Формат старше [BackupManifest.MIN_SUPPORTED_FORMAT_VERSION] и больше не поддерживается. */
    data class TooOld(val formatVersion: Int) : BackupFormatReason
}

/** Повреждённый .psbackup или неподдерживаемая версия формата. Сообщение — [reason] для лога; пользователю его не показывают. */
class BackupFormatException(val reason: BackupFormatReason, cause: Throwable? = null) : Exception(reason.toString(), cause)
