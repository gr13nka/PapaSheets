package ru.papasheets.exportkit.backup

/** Повреждённый .psbackup или неподдерживаемая версия формата — сообщение уже человекочитаемое, для показа в UI как есть. */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
