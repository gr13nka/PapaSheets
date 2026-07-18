package ru.papasheets.domain.xlsx

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.util.zip.ZipInputStream

/** Что за файл выбрал пользователь в системном диалоге. */
enum class ImportFileType {
    BACKUP,
    XLSX,
    UNKNOWN,
}

/**
 * Определяет, каким путём вести импорт.
 *
 * SAF-диалог открыт по MIME-маске «любой тип» — иначе часть файловых менеджеров прячет `.psbackup`,
 * у которого нет общепринятого MIME-типа. Значит, тип приходится определять самим, и по содержимому,
 * а не по имени:
 * оба формата — ZIP-архивы, имя приходит из чужого приложения и запросто может быть «Copy of
 * Июнь (1)» без расширения вовсе.
 *
 * Смотрим на состав архива: у xlsx на месте `[Content_Types].xml` (обязательная часть OPC),
 * у нашего бэкапа — `manifest.json`. Читаются только заголовки записей, не данные.
 */
object ImportFileTypeDetector {

    private const val XLSX_MARKER = "[Content_Types].xml"
    private const val BACKUP_MARKER = "manifest.json"

    /** Записей в начале архива, за которые маркер обязан встретиться — дальше это не наш файл. */
    private const val ENTRIES_TO_SCAN = 64

    fun detect(context: Context, uri: Uri): ImportFileType = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var type = ImportFileType.UNKNOWN
                var scanned = 0
                var entry = zip.nextEntry
                while (entry != null && scanned < ENTRIES_TO_SCAN) {
                    when (entry.name) {
                        XLSX_MARKER -> type = ImportFileType.XLSX
                        BACKUP_MARKER -> type = ImportFileType.BACKUP
                    }
                    if (type != ImportFileType.UNKNOWN) break
                    scanned++
                    entry = zip.nextEntry
                }
                type
            }
        } ?: ImportFileType.UNKNOWN
    } catch (e: IOException) {
        // Не ZIP вовсе (картинка, документ) — пусть о причине скажет тот, кто попробует прочитать.
        ImportFileType.UNKNOWN
    }
}
