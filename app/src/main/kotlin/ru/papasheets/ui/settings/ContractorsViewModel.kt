package ru.papasheets.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.repo.ContractorRepository

/** Тонкая обёртка над [ContractorRepository] для экрана настроек — вся политика (id/order/color) в репозитории. */
class ContractorsViewModel(private val journalId: String, private val repository: ContractorRepository) : ViewModel() {

    val contractors: StateFlow<List<ContractorEntity>> = repository.observeForJournal(journalId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, shortName: String, colorIndex: Int) {
        viewModelScope.launch { repository.create(journalId, name, shortName, colorIndex) }
    }

    fun rename(contractor: ContractorEntity, name: String, shortName: String, colorIndex: Int) {
        viewModelScope.launch { repository.rename(contractor, name, shortName, colorIndex) }
    }

    fun setArchived(contractor: ContractorEntity, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(contractor, archived) }
    }

    fun reorder(ordered: List<ContractorEntity>) {
        viewModelScope.launch { repository.reorder(ordered) }
    }
}
