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
class ContractorsViewModel(private val repository: ContractorRepository) : ViewModel() {

    val contractors: StateFlow<List<ContractorEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, shortName: String) {
        viewModelScope.launch { repository.create(name, shortName) }
    }

    fun rename(contractor: ContractorEntity, name: String, shortName: String) {
        viewModelScope.launch { repository.rename(contractor, name, shortName) }
    }

    fun setArchived(contractor: ContractorEntity, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(contractor, archived) }
    }

    fun reorder(ordered: List<ContractorEntity>) {
        viewModelScope.launch { repository.reorder(ordered) }
    }
}
