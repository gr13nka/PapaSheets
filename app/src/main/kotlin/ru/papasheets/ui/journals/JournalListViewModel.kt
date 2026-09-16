package ru.papasheets.ui.journals

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

private const val TAG = "JournalListViewModel"

class JournalListViewModel(
    private val journalRepository: JournalRepository,
    private val deleteJournalInteractor: DeleteJournalInteractor,
    private val structures: ru.papasheets.data.repo.TableStructureRepository,
) : ViewModel() {
    val journals: StateFlow<List<JournalWithStats>> = journalRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Неудачное удаление — одноразовое событие для тоста; повода нести цифры или детали причины нет. */
    private val _deleteFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleteFailed: SharedFlow<Unit> = _deleteFailed.asSharedFlow()

    /** Открывает журнал месяца, создавая его при первом обращении; [onReady] получает id для навигации. */
    val createError = MutableStateFlow(false)

    fun create(title: String, year: Int, month: Int, sourceId: String?, groupName: String, description: String, onReady: (String) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        createError.value = false
        viewModelScope.launch {
            try {
                val id = structures.create(title, year, month, sourceId, groupName, description)
                onReady(id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                Log.e(TAG, "create table failed", e)
                createError.value = true
            } finally { _busy.value = false }
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
                _deleteFailed.emit(Unit)
            } finally {
                _busy.value = false
            }
        }
    }
}
