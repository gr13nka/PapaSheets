package ru.papasheets.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.domain.buildGridModel
import ru.papasheets.matrixgrid.GridModel
import ru.papasheets.photos.BitmapThumbnailSource
import ru.papasheets.photos.PhotoStore

/**
 * Модель экрана матрицы. Держит потоки записей и подрядчиков и сшивает их в иммутабельный
 * [GridModel] на [Dispatchers.Default] (раскладка сотен строк не должна дёргать главный поток).
 * [thumbnails] живёт столько же, сколько ViewModel — его LRU переживает рекомпозиции экрана.
 */
class MatrixViewModel(
    journalId: String,
    journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
    photoStore: PhotoStore,
) : ViewModel() {

    val thumbnails = BitmapThumbnailSource(photoStore, viewModelScope)

    val journal: StateFlow<JournalEntity?> = journalRepository.observeById(journalId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // null до первой готовой раскладки — экран показывает загрузку/пустое состояние.
    val gridModel: StateFlow<GridModel?> = combine(
        recordRepository.observeByJournal(journalId),
        contractorRepository.observeActive(),
    ) { records, contractors ->
        withContext(Dispatchers.Default) { buildGridModel(records, contractors, sortDesc = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            recordRepository.getById(recordId)?.let { recordRepository.delete(it) }
        }
    }
}
