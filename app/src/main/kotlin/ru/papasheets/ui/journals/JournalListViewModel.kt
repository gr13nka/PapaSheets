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
import ru.papasheets.domain.backup.BackupImportResult
import ru.papasheets.domain.backup.BackupInteractor
import ru.papasheets.domain.backup.ImportInteractor
import ru.papasheets.exportkit.backup.BackupFormatException

private const val TAG = "JournalListViewModel"

/** Итог одной попытки бэкапа/импорта — одноразовое событие для UI (тост либо диалог с цифрами). */
sealed interface BackupUiEvent {
    /** [skippedPhotoFiles] > 0 — часть файлов фото потеряна на диске, бэкап неполный, стоит предупредить. */
    data class BackupDone(val skippedPhotoFiles: Int) : BackupUiEvent
    data class BackupFailed(val message: String) : BackupUiEvent
    data class ImportDone(val result: BackupImportResult) : BackupUiEvent
    data class ImportFailed(val message: String) : BackupUiEvent
}

class JournalListViewModel(
    private val journalRepository: JournalRepository,
    private val backupInteractor: BackupInteractor,
    private val importInteractor: ImportInteractor,
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

    fun importFrom(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = importInteractor.import(uri)
                _events.emit(BackupUiEvent.ImportDone(result))
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
}
