package ru.papasheets.logging

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.papasheets.BuildConfig

/**
 * Файловый лог приложения — единственная замена logcat на офлайн-телефоне: прораб не может
 * прислать разработчику вывод adb, но может прислать файл через [buildShareIntent].
 *
 * Глубокий модуль: снаружи — четыре метода уровня (как у [android.util.Log]) плюс установка
 * обработчика краша и сборка Intent для шаринга; внутри — формат строки, ротация по
 * [LogRotationPolicy] и вся файловая работа в `filesDir/logs`. Единый [lock] на все файловые
 * операции обязателен: обычная запись приходит из корутин на `Dispatchers.IO`, необработанное
 * исключение — с любого потока, а склейка для шаринга — снова с IO; без общего замка эти вызовы
 * гонялись бы за одним файлом и портили друг другу запись.
 */
class AppLog(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val logsDir = File(appContext.filesDir, "logs")
    private val logFile = File(logsDir, LOG_FILE_NAME)

    init {
        logsDir.mkdirs()
        writeSessionHeader()
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) = write(Level.DEBUG, tag, message, throwable)
    fun i(tag: String, message: String, throwable: Throwable? = null) = write(Level.INFO, tag, message, throwable)
    fun w(tag: String, message: String, throwable: Throwable? = null) = write(Level.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = write(Level.ERROR, tag, message, throwable)

    /**
     * Ставит себя обработчиком необработанных исключений, запомнив прежний. Делегирование прежнему
     * в `finally` обязательно: именно он показывает системный диалог «приложение остановлено» и
     * завершает процесс — пропустить его значило бы подвесить процесс вместо штатного краша.
     */
    fun installAsUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("Crash", "необработанное исключение в потоке ${thread.name}", throwable)
            } catch (_: Throwable) {
                // Логгер не имеет права помешать штатной обработке краша.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Склеивает файлы ротации в хронологическом порядке (от самой старой копии к текущему файлу) в
     * `cacheDir/logshare/papasheets-logi.txt` и заворачивает его в Intent для «Поделиться». Логов
     * ещё может не быть (первый запуск, сразу после установки) — тогда файл всё равно создаётся, с
     * одной строкой-заголовком, чтобы шаринг пустого лога не падал.
     */
    fun buildShareIntent(): Intent {
        val shareFile = synchronized(lock) { buildShareFile() }
        val uri = FileProvider.getUriForFile(appContext, FILE_PROVIDER_AUTHORITY, shareFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildShareFile(): File {
        val shareDir = File(appContext.cacheDir, "logshare").apply { mkdirs() }
        val shareFile = File(shareDir, SHARE_FILE_NAME)
        try {
            shareFile.outputStream().use { out ->
                var wroteAnything = false
                // app.log.2, потом app.log.1, потом сам app.log — от старой копии к свежей.
                for (index in LogRotationPolicy.KEEP_ROTATED downTo 0) {
                    val part = File(logsDir, LogRotationPolicy.rotatedName(LOG_FILE_NAME, index))
                    if (!part.exists()) continue
                    part.inputStream().use { it.copyTo(out) }
                    wroteAnything = true
                }
                if (!wroteAnything) out.write("PapaSheets: логов пока нет.\n".toByteArray())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "buildShareFile: не удалось собрать файл для шаринга", t)
        }
        return shareFile
    }

    private fun write(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        mirrorToLogcat(level, tag, message, throwable)
        persistLine(formatLine(level, tag, message, throwable))
    }

    /** [android.util.Log] не перегружен единым методом на все уровни — только явная развилка. */
    private fun mirrorToLogcat(level: Level, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            Level.DEBUG -> if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
            Level.INFO -> if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
            Level.WARN -> if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
            Level.ERROR -> if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }

    /** Заголовок сессии — разделитель и версия приложения/ОС/устройства, чтобы файл был читаем без контекста. */
    private fun writeSessionHeader() {
        val header = buildString {
            appendLine("==================== новая сессия ====================")
            appendLine("PapaSheets ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        }
        persistLine(header)
    }

    /** Ротация при необходимости и добавление [text] в конец файла — под общим [lock]. Свои сбои проглатываются. */
    private fun persistLine(text: String) {
        try {
            synchronized(lock) {
                rotateIfNeeded()
                logFile.appendText(text)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "persistLine: не удалось записать в лог-файл", t)
        }
    }

    private fun rotateIfNeeded() {
        if (!LogRotationPolicy.shouldRotate(logFile.length())) return
        LogRotationPolicy.rotationPlan(LOG_FILE_NAME).forEach { (from, to) ->
            val source = File(logsDir, from)
            if (source.exists()) source.renameTo(File(logsDir, to))
        }
    }

    private fun formatLine(level: Level, tag: String, message: String, throwable: Throwable?): String {
        val timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now())
        val head = "$timestamp ${level.label} $tag: $message\n"
        return if (throwable == null) head else head + throwable.stackTraceToString() + "\n"
    }

    /** Уровень записи: буква в файле и соответствующий метод [android.util.Log]. */
    private enum class Level(val label: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    private companion object {
        const val TAG = "AppLog"
        const val LOG_FILE_NAME = "app.log"
        const val SHARE_FILE_NAME = "papasheets-logi.txt"
        const val FILE_PROVIDER_AUTHORITY = "ru.papasheets.fileprovider"
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }
}
