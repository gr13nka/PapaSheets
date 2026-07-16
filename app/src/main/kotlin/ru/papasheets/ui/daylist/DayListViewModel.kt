package ru.papasheets.ui.daylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository

data class RecordWithContractor(val record: RecordEntity, val contractor: ContractorEntity?)

/** Записи журнала за один день, в порядке отображения (по `createdAt`). */
data class DayGroup(val dateEpochDay: Long, val records: List<RecordWithContractor>)

class DayListViewModel(
    journalId: String,
    journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
) : ViewModel() {
    val journal: StateFlow<JournalEntity?> = journalRepository.observeById(journalId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dayGroups: StateFlow<List<DayGroup>> = combine(
        recordRepository.observeByJournal(journalId),
        contractorRepository.observeAll(),
    ) { records, contractors ->
        val contractorsById = contractors.associateBy { it.id }
        // observeByJournal уже отсортирован по dateEpochDay DESC, createdAt ASC — groupBy сохраняет этот порядок.
        records.groupBy { it.dateEpochDay }
            .map { (date, recordsForDate) ->
                DayGroup(
                    dateEpochDay = date,
                    records = recordsForDate.map { RecordWithContractor(it, contractorsById[it.contractorId]) },
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteRecord(record: RecordEntity) {
        viewModelScope.launch { recordRepository.delete(record) }
    }
}
