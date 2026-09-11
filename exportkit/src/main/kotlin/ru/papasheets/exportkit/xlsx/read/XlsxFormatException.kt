package ru.papasheets.exportkit.xlsx.read

/**
 * Файл не удалось прочитать как журнал-матрицу. Сообщение объясняет, что именно не так с файлом, и
 * идёт в лог приложения, а пользователю приложение показывает свою переведённую строку. Человек
 * выбирает файл в системном диалоге и вполне может ткнуть в фотографию или чужую таблицу, и
 * «IOException: unexpected end of stream» при разборе такого лога ничего не объяснит.
 */
class XlsxFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
