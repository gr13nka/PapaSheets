package ru.papasheets.ui.lightbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.RecordRepository

data class LightboxUiState(
    val photoId: String? = null,
    val dateEpochDay: Long? = null,
    val contractorShortName: String = "",
    val locationCode: String = "",
    val isLoaded: Boolean = false,
)

class LightboxViewModel(
    recordId: String,
    recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LightboxUiState())
    val uiState: StateFlow<LightboxUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val record = recordRepository.getById(recordId) ?: return@launch
            val contractor = contractorRepository.getById(record.contractorId)
            _uiState.update {
                it.copy(
                    photoId = record.photoId,
                    dateEpochDay = record.dateEpochDay,
                    contractorShortName = contractor?.shortName ?: "",
                    locationCode = record.locationCode,
                    isLoaded = true,
                )
            }
        }
    }
}
