package ru.papasheets.ui.journals

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.papasheets.data.db.dao.JournalWithStats
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.domain.DeleteJournalInteractor
import ru.papasheets.domain.backup.BackupImportResult
import ru.papasheets.domain.backup.BackupInteractor
import ru.papasheets.domain.backup.ImportInteractor
import ru.papasheets.domain.xlsx.ImportFileType
import ru.papasheets.domain.xlsx.ImportFileTypeDetector
import ru.papasheets.domain.xlsx.XlsxImportException
import ru.papasheets.domain.xlsx.XlsxImportInteractor
import ru.papasheets.domain.xlsx.XlsxImportPreview
import ru.papasheets.domain.xlsx.XlsxImportResult
import ru.papasheets.exportkit.backup.BackupFormatException

private const val TAG = "JournalListViewModel"

/** Итог одной попытки бэкапа/импорта — одноразовое событие для UI (тост либо диалог с цифрами). */
sealed interface BackupUiEvent {
    /** [skippedPhotoFiles] > 0 — часть файлов фото потеряна на диске, бэкап неполный, стоит предупредить. */
    data class BackupDone(val skippedPhotoFiles: Int) : BackupUiEvent
    data class BackupFailed(val message: String) : BackupUiEvent
    data class ImportDone(val result: BackupImportResult) : BackupUiEvent
    data class ImportFailed(val message: String) : BackupUiEvent
    data class DeleteFailed(val message: String) : BackupUiEvent

    /** Таблица прочитана, но ещё не записана: показать разбор и спросить подтверждения. */
    data class XlsxPreviewReady(val preview: XlsxImportPreview) : BackupUiEvent
    data class XlsxImportDone(val result: XlsxImportResult) : BackupUiEvent
}

class JournalListViewModel(
    private val journalRepository: JournalRepository,
    private val backupInteractor: BackupInteractor,
    private val importInteractor: ImportInteractor,
    private val xlsxImportInteractor: XlsxImportInteractor,
    private val deleteJournalInteractor: DeleteJournalInteractor,
    private val detectFileType: (Uri) -> ImportFileType,
) : ViewModel() {
    val journals: StateFlow<List<JournalWithStats>> = journalRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<BackupUiEvent> = _events.asSharedFlow()

    /** Открывает журнал месяца, создавая его при первом обращении; [onReady] получает id для навигации. */
    fun openOrCreateJournal(year: Int, month: Int, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val journal = journalRepository.createOrGetJournal(year, month)
            onReady(journal.id)
        }
    }

    /** Удаляет журнал со всеми записями и фото (см. [DeleteJournalInteractor]). Блокирует на время busy-диалогом. */
    fun deleteJournal(journalId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                deleteJournalInteractor.delete(journalId)
            } catch (e: Exception) {
                Log.e(TAG, "deleteJournal failed: $journalId", e)
                _events.emit(BackupUiEvent.DeleteFailed(e.message ?: "Не удалось удалить журнал"))
            } finally {
                _busy.value = false
            }
        }
    }

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
                _events.emit(BackupUiEvent.BackupFailed(e.message ?: "Не удалось сохранить бэкап"))
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
                    ImportFileType.XLSX -> _events.emit(BackupUiEvent.XlsxPreviewReady(xlsxImportInteractor.preview(uri)))
                    // Неопознанный файл ведём по пути бэкапа: он и объяснит, что с ним не так.
                    ImportFileType.BACKUP, ImportFileType.UNKNOWN ->
                        _events.emit(BackupUiEvent.ImportDone(importInteractor.import(uri)))
                }
            } catch (e: XlsxImportException) {
                Log.e(TAG, "xlsx import failed", e)
                _events.emit(BackupUiEvent.ImportFailed(e.message ?: "Не удалось прочитать таблицу"))
            } catch (e: BackupFormatException) {
                Log.e(TAG, "import failed: bad format", e)
                _events.emit(BackupUiEvent.ImportFailed(e.message ?: "Некорректный файл бэкапа"))
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                _events.emit(BackupUiEvent.ImportFailed(e.message ?: "Не удалось импортировать бэкап"))
            } finally {
                _busy.value = false
            }
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
                Log.e(TAG, "xlsx import failed", e)
                _events.emit(BackupUiEvent.ImportFailed(e.message ?: "Не удалось импортировать таблицу"))
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
