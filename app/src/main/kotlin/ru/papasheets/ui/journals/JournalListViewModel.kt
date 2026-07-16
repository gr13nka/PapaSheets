package ru.papasheets.ui.journals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.papasheets.data.db.dao.JournalWithStats
import ru.papasheets.data.repo.JournalRepository

class JournalListViewModel(private val journalRepository: JournalRepository) : ViewModel() {
    val journals: StateFlow<List<JournalWithStats>> = journalRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Открывает журнал месяца, создавая его при первом обращении; [onReady] получает id для навигации. */
    fun openOrCreateJournal(year: Int, month: Int, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val journal = journalRepository.createOrGetJournal(year, month)
            onReady(journal.id)
        }
    }
}
