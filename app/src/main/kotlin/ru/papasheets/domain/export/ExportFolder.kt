package ru.papasheets.domain.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream
import java.time.LocalDateTime

/** Экспорт не смог записать файл в выбранную папку — с текстом для пользователя. */
class ExportFolderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Папка, в которую пишется экспорт, выбранная один раз и запомненная между запусками.
 *
 * Глубокий модуль над Storage Access Framework: снаружи — «запомни папку», «есть ли папка», «отдай
 * поток на перезапись файла», и ни одного намёка на дерево документов, `takePersistableUriPermission`
 * или `DocumentFile`. До этого экспорт открывал системный `CreateDocument` на каждый раз и ничего не
 * помнил; теперь прораб выбирает папку однажды, а дальше видит всё время один и тот же «Июль 2026.xlsx»,
 * который просто обновляется.
 *
 * Выбранное дерево хранится в [android.content.SharedPreferences] — одна строка Uri, ради которой
 * заводить Room-сущность с миграцией было бы несоразмерно. Право доступа к дереву переживает
 * перезапуск через persistable-разрешение; если его отобрали (папку удалили, карту вынули), это
 * честно превращается в «папки нет» — молча терять экспорт нельзя.
 */
class ExportFolder(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Имя выбранной папки для показа в UI, или `null`, если папка не выбрана или доступ потерян. */
    fun displayName(): String? = tree()?.name

    /** Есть ли рабочая папка прямо сейчас (право не отозвано, каталог на месте и доступен на запись). */
    fun isReady(): Boolean = tree() != null

    /**
     * Запоминает выбранное в системном диалоге ([android.content.Intent.ACTION_OPEN_DOCUMENT_TREE])
     * дерево и берёт стойкое право на чтение и запись — без него Uri протух бы к следующему запуску.
     */
    fun remember(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        appContext.contentResolver.takePersistableUriPermission(treeUri, flags)
        prefs.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }

    /** Забывает папку — после потери доступа UI попросит выбрать заново. */
    fun forget() {
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    /**
     * Уносит текущую версию файла в «Архив» и отдаёт поток на перезапись самого файла.
     *
     * Порядок именно такой — сначала страховка, потом запись: оборванная запись стоит одной
     * испорченной текущей копии при целой предыдущей в архиве, а не потерянного экспорта. Отдельного
     * `.part` поэтому не нужно.
     *
     * Поток открывается в режиме `"wt"`, а не `"w"`: провайдер SAF при `"w"` не обязан усекать файл,
     * и экспорт короче предыдущего оставил бы хвост старого ZIP — получился бы битый xlsx, который
     * Excel открыл бы с ошибкой.
     */
    fun replace(fileName: String, mimeType: String): OutputStream {
        val dir = tree() ?: throw ExportFolderException("Папка для экспорта не выбрана")
        archiveExisting(dir, fileName, mimeType)
        val target = dir.findFile(fileName) ?: dir.createFile(mimeType, fileName)
            ?: throw ExportFolderException("Не удалось создать файл «$fileName» в выбранной папке")
        return appContext.contentResolver.openOutputStream(target.uri, "wt")
            ?: throw ExportFolderException("Не удалось открыть файл «$fileName» для записи")
    }

    /**
     * Копирует текущую версию файла (если она есть) в подпапку [ExportArchive.DIR_NAME] и оставляет
     * там только [ExportArchive.KEEP] новейших копий этого файла. Копия, а не перемещение: `moveDocument`
     * работает лишь у провайдеров с `FLAG_SUPPORTS_MOVE`, а копия — везде; файл локальный, цена копии
     * приемлема. Архивация — best-effort: если она не удалась, экспорт всё равно должен состояться,
     * поэтому сбой здесь логируется, но не срывает перезапись.
     */
    private fun archiveExisting(dir: DocumentFile, fileName: String, mimeType: String) {
        val current = dir.findFile(fileName) ?: return
        try {
            val archiveDir = dir.findFile(ExportArchive.DIR_NAME)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(ExportArchive.DIR_NAME)
                ?: return
            val copyName = ExportArchive.archiveName(fileName, LocalDateTime.now())
            val copy = archiveDir.createFile(mimeType, copyName) ?: return
            appContext.contentResolver.openInputStream(current.uri)?.use { input ->
                appContext.contentResolver.openOutputStream(copy.uri)?.use { output -> input.copyTo(output) }
            }
            val existingNames = archiveDir.listFiles().mapNotNull { it.name }
            ExportArchive.obsolete(fileName, existingNames).forEach { obsolete ->
                archiveDir.findFile(obsolete)?.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "archiveExisting: не удалось заархивировать $fileName", e)
        }
    }

    /**
     * Рабочее дерево или `null`. Uri без действующего права доступа не годится — таким его делает и
     * удалённая пользователем папка, и отозванное разрешение; в этом случае забываем его сами, чтобы
     * UI спросил папку заново, а не падал на каждой попытке.
     */
    private fun tree(): DocumentFile? {
        val uri = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse) ?: return null
        val held = appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!held) {
            forget()
            return null
        }
        val dir = DocumentFile.fromTreeUri(appContext, uri)
        return if (dir != null && dir.isDirectory && dir.canWrite()) dir else null
    }

    private companion object {
        const val TAG = "ExportFolder"
        const val PREFS_NAME = "export"
        const val KEY_TREE_URI = "treeUri"
    }
}
