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
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.domain.RecordDisplay

/** Всё, что нужно карточке записи: сама запись, её подрядчик и уже готовое представление содержимого. */
data class RecordCardItem(
    val record: RecordEntity,
    val contractor: ContractorEntity?,
    val display: RecordDisplay,
)

/** Записи журнала за один день, в порядке отображения (по `createdAt`). */
data class DayGroup(val dateEpochDay: Long, val records: List<RecordCardItem>)

class DayListViewModel(
    journalId: String,
    journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
    fieldRepository: FieldRepository,
) : ViewModel() {
    val journal: StateFlow<JournalEntity?> = journalRepository.observeById(journalId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dayGroups: StateFlow<List<DayGroup>> = combine(
        recordRepository.observeByJournal(journalId),
        contractorRepository.observeAll(),
        fieldRepository.observeActive(),
    ) { records, contractors, fields ->
        val contractorsById = contractors.associateBy { it.id }
        // observeByJournal уже отсортирован по dateEpochDay DESC, createdAt ASC — groupBy сохраняет этот порядок.
        records.groupBy { it.record.dateEpochDay }
            .map { (date, recordsForDate) ->
                DayGroup(
                    dateEpochDay = date,
                    records = recordsForDate.map {
                        RecordCardItem(it.record, contractorsById[it.record.contractorId], RecordDisplay.of(it, fields))
                    },
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteRecord(record: RecordEntity) {
        viewModelScope.launch { recordRepository.delete(record) }
    }
}
