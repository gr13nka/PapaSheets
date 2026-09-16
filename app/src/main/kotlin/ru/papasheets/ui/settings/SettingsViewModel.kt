package ru.papasheets.ui.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.papasheets.domain.backup.BackupImportResult
import ru.papasheets.domain.backup.BackupInteractor
import ru.papasheets.domain.backup.ImportInteractor
import ru.papasheets.domain.xlsx.ImportFileType
import ru.papasheets.domain.xlsx.ImportFileTypeDetector
import ru.papasheets.domain.xlsx.XlsxImportException
import ru.papasheets.domain.xlsx.XlsxImportInteractor
import ru.papasheets.domain.xlsx.XlsxImportPreview
import ru.papasheets.domain.xlsx.XlsxImportReason
import ru.papasheets.domain.xlsx.XlsxImportResult
import ru.papasheets.exportkit.backup.BackupFormatException
import ru.papasheets.exportkit.backup.BackupFormatReason
import ru.papasheets.logging.AppLog

private const val TAG = "SettingsViewModel"

/** Итог одной попытки бэкапа/импорта — одноразовое событие для UI (тост либо диалог с цифрами). */
sealed interface BackupUiEvent {
    /** [skippedPhotoFiles] > 0 — часть файлов фото потеряна на диске, бэкап неполный, стоит предупредить. */
    data class BackupDone(val skippedPhotoFiles: Int) : BackupUiEvent
    data class ImportDone(val result: BackupImportResult) : BackupUiEvent
    data class Failure(val kind: BackupUiFailure) : BackupUiEvent

    /** Файл не принят как бэкап, и [reason] говорит почему — например, его сделала более новая версия. */
    data class BackupRejected(val reason: BackupFormatReason) : BackupUiEvent

    /** Таблицу импортировать нельзя, и [reason] говорит почему — пользователь может выбрать другой файл. */
    data class XlsxRejected(val reason: XlsxImportReason) : BackupUiEvent

    /** Таблица прочитана, но ещё не записана: показать разбор и спросить подтверждения. */
    data class XlsxPreviewReady(val preview: XlsxImportPreview) : BackupUiEvent
    data class XlsxImportDone(val result: XlsxImportResult) : BackupUiEvent
}

enum class BackupUiFailure {
    SAVE_BACKUP,
    IMPORT_BACKUP,
    IMPORT_XLSX,
}

/** Бэкап и импорт (бэкапа либо таблицы) — общая для экрана «Настройки» операция с общим busy/events. */
class SettingsViewModel(
    private val backupInteractor: BackupInteractor,
    private val importInteractor: ImportInteractor,
    private val xlsxImportInteractor: XlsxImportInteractor,
    private val detectFileType: (Uri) -> ImportFileType,
    private val appLog: AppLog,
) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<BackupUiEvent> = _events.asSharedFlow()

    fun defaultBackupFileName(): String = backupInteractor.defaultFileName()

    fun backupTo(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = backupInteractor.backup(uri)
                _events.emit(BackupUiEvent.BackupDone(result.skippedPhotoFiles.size))
            } catch (e: Exception) {
                Log.e(TAG, "backup failed", e)
                _events.emit(BackupUiEvent.Failure(BackupUiFailure.SAVE_BACKUP))
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Один пункт меню на оба формата: пользователю незачем знать заранее, бэкап у него или таблица.
     * Расходятся они по содержимому файла ([ImportFileTypeDetector]), и дальше — разными путями:
     * бэкап восстанавливается сразу (это наши же данные), таблица сначала показывается на
     * подтверждение.
     */
    fun importFrom(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                when (detectFileType(uri)) {
                    ImportFileType.XLSX -> _events.emit(previewXlsx(uri))
                    // Неопознанный файл ведём по пути бэкапа: он и объяснит, что с ним не так.
                    ImportFileType.BACKUP, ImportFileType.UNKNOWN ->
                        _events.emit(BackupUiEvent.ImportDone(importInteractor.import(uri)))
                }
            } catch (e: BackupFormatException) {
                appLog.w(TAG, "backup rejected: ${e.reason}", e)
                _events.emit(BackupUiEvent.BackupRejected(e.reason))
            } catch (e: Exception) {
                appLog.e(TAG, "import failed", e)
                _events.emit(BackupUiEvent.Failure(BackupUiFailure.IMPORT_BACKUP))
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Разбор таблицы на подтверждение. Её сбои ловятся здесь, а не общим catch в [importFrom]: там
     * неожиданное исключение показалось бы провалом бэкапа, которого пользователь не выбирал.
     */
    private suspend fun previewXlsx(uri: Uri): BackupUiEvent = try {
        BackupUiEvent.XlsxPreviewReady(xlsxImportInteractor.preview(uri))
    } catch (e: XlsxImportException) {
        appLog.w(TAG, "xlsx import rejected: ${e.reason}", e)
        BackupUiEvent.XlsxRejected(e.reason)
    } catch (e: Exception) {
        appLog.e(TAG, "xlsx preview failed", e)
        BackupUiEvent.Failure(BackupUiFailure.IMPORT_XLSX)
    }

    fun changeXlsxDestination(preview: XlsxImportPreview, journalId: String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { _events.emit(BackupUiEvent.XlsxPreviewReady(xlsxImportInteractor.retarget(preview, journalId)))
            } catch (e: Exception) {
                appLog.e(TAG, "xlsx destination failed", e)
                _events.emit(BackupUiEvent.Failure(BackupUiFailure.IMPORT_XLSX))
            } finally { _busy.value = false }
        }
    }

    /** Пользователь посмотрел разбор таблицы и согласился — только теперь пишем в БД. */
    fun confirmXlsxImport(preview: XlsxImportPreview) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                _events.emit(BackupUiEvent.XlsxImportDone(xlsxImportInteractor.apply(preview)))
            } catch (e: Exception) {
                appLog.e(TAG, "xlsx import failed", e)
                _events.emit(BackupUiEvent.Failure(BackupUiFailure.IMPORT_XLSX))
            } finally {
                _busy.value = false
            }
        }
    }

    /** Отказ от импорта: временная копия файла на диске больше не нужна. */
    fun cancelXlsxImport(preview: XlsxImportPreview) {
        xlsxImportInteractor.discard(preview)
    }
}
