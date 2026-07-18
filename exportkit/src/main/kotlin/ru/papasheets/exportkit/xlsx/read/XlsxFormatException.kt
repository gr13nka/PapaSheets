package ru.papasheets.exportkit.xlsx.read

/**
 * Файл не удалось прочитать как журнал-матрицу. Сообщение рассчитано на показ пользователю
 * как есть — по образцу [ru.papasheets.exportkit.backup.BackupFormatException]: человек выбирает
 * файл в системном диалоге и вполне может ткнуть в фотографию или чужую таблицу, и «IOException:
 * unexpected end of stream» ему ничего не объяснит.
 */
class XlsxFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
