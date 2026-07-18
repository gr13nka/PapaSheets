package ru.papasheets.ui.lightbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.domain.RecordDisplay

data class LightboxUiState(
    val photoId: String? = null,
    val dateEpochDay: Long? = null,
    val contractorShortName: String = "",
    /** Опознавательная метка записи в подписи — первое поле журнала (см. [RecordDisplay]). */
    val recordLabel: String = "",
    val isLoaded: Boolean = false,
)

class LightboxViewModel(
    recordId: String,
    recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
    fieldRepository: FieldRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LightboxUiState())
    val uiState: StateFlow<LightboxUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val record = recordRepository.getWithValues(recordId) ?: return@launch
            val contractor = contractorRepository.getById(record.record.contractorId)
            val fields = fieldRepository.observeActive().first()
            _uiState.update {
                it.copy(
                    photoId = record.record.photoId,
                    dateEpochDay = record.record.dateEpochDay,
                    contractorShortName = contractor?.shortName ?: "",
                    recordLabel = RecordDisplay.of(record, fields).primary,
                    isLoaded = true,
                )
            }
        }
    }
}
