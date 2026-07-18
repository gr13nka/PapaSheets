package ru.papasheets.ui.matrix

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.domain.buildGridModel
import ru.papasheets.domain.export.ExportFormat
import ru.papasheets.domain.export.ExportInteractor
import ru.papasheets.matrixgrid.GridModel
import ru.papasheets.photos.BitmapThumbnailSource
import ru.papasheets.photos.PhotoStore

private const val TAG = "MatrixViewModel"

/** Итог одной попытки экспорта — одноразовое событие для тоста в UI (см. [MatrixViewModel.exportEvents]). */
sealed interface ExportEvent {
    data object Success : ExportEvent
    data class Failure(val message: String) : ExportEvent
}

/**
 * Модель экрана матрицы. Держит потоки записей, подрядчиков и тумблера сортировки и сшивает их в
 * иммутабельный [GridModel] на [Dispatchers.Default] (раскладка сотен строк не должна дёргать главный
 * поток). Подрядчики берутся через [ContractorRepository.observeAll] (не только активные) — архивный
 * с записями в этом журнале остаётся колонкой, решает об этом уже [buildGridModel]. [thumbnails]
 * живёт столько же, сколько ViewModel — его LRU переживает рекомпозиции экрана.
 */
class MatrixViewModel(
    private val journalId: String,
    journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
    photoStore: PhotoStore,
    private val exportInteractor: ExportInteractor,
) : ViewModel() {

    val thumbnails = BitmapThumbnailSource(photoStore, viewModelScope)

    val journal: StateFlow<JournalEntity?> = journalRepository.observeById(journalId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Дефолт — старые сверху, как в бумажном журнале (см. spec, M5).
    private val _sortDesc = MutableStateFlow(false)
    val sortDesc: StateFlow<Boolean> = _sortDesc.asStateFlow()

    // null до первой готовой раскладки — экран показывает загрузку/пустое состояние.
    val gridModel: StateFlow<GridModel?> = combine(
        recordRepository.observeByJournal(journalId),
        contractorRepository.observeAll(),
        _sortDesc,
    ) { records, contractors, sortDesc ->
        withContext(Dispatchers.Default) { buildGridModel(records, contractors, sortDesc) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleSortDesc() {
        _sortDesc.update { !it }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            recordRepository.getById(recordId)?.let { recordRepository.delete(it) }
        }
    }

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _exportEvents = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 1)
    val exportEvents: SharedFlow<ExportEvent> = _exportEvents.asSharedFlow()

    /** Имя файла по умолчанию для SAF `CreateDocument` — показывает [ru.papasheets.ui.export.ExportDialog]. */
    fun defaultExportFileName(format: ExportFormat): String =
        exportInteractor.defaultFileName(journal.value?.title ?: "Журнал", format)

    fun exportTo(uri: Uri, format: ExportFormat) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                exportInteractor.export(journalId, format, uri)
                _exportEvents.emit(ExportEvent.Success)
            } catch (e: Exception) {
                Log.e(TAG, "export failed: format=$format", e)
                _exportEvents.emit(ExportEvent.Failure(e.message ?: "Не удалось экспортировать"))
            } finally {
                _exporting.value = false
            }
        }
    }
}
