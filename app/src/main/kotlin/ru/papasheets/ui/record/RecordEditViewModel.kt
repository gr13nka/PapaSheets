package ru.papasheets.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.LocationSuggester
import ru.papasheets.data.repo.RecordRepository

data class RecordEditUiState(
    val date: LocalDate = LocalDate.now(),
    val contractors: List<ContractorEntity> = emptyList(),
    val selectedContractorId: String? = null,
    val locationCode: String = "",
    val locationSuggestions: List<String> = emptyList(),
    val workText: String = "",
    val showContractorError: Boolean = false,
    val showWorkTextError: Boolean = false,
    val isLoaded: Boolean = false,
)

/**
 * Данные и валидация формы записи. Режим (создание/редактирование) задаётся один раз при
 * создании ViewModel — [journalId] нужен только для создания, [recordId] только для загрузки существующей записи.
 */
class RecordEditViewModel(
    private val journalId: String?,
    private val recordId: String?,
    initialDate: LocalDate,
    private val recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
    private val locationSuggester: LocationSuggester,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordEditUiState(date = initialDate, isLoaded = recordId == null))
    val uiState: StateFlow<RecordEditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            contractorRepository.observeActive().collect { contractors ->
                _uiState.update { it.copy(contractors = contractors) }
            }
        }
        if (recordId != null) {
            viewModelScope.launch {
                recordRepository.getById(recordId)?.let { existing ->
                    _uiState.update {
                        it.copy(
                            date = LocalDate.ofEpochDay(existing.dateEpochDay),
                            selectedContractorId = existing.contractorId,
                            locationCode = existing.locationCode,
                            workText = existing.workText,
                            isLoaded = true,
                        )
                    }
                }
            }
        }
    }

    fun onDateSelected(date: LocalDate) {
        _uiState.update { it.copy(date = date) }
    }

    fun onContractorSelected(contractorId: String) {
        _uiState.update { it.copy(selectedContractorId = contractorId, showContractorError = false) }
    }

    fun onLocationChanged(text: String) {
        _uiState.update { it.copy(locationCode = text) }
        viewModelScope.launch {
            val suggestions = if (text.isBlank()) emptyList() else locationSuggester.suggest(text)
            _uiState.update { it.copy(locationSuggestions = suggestions) }
        }
    }

    fun onLocationSuggestionPicked(code: String) {
        _uiState.update { it.copy(locationCode = code, locationSuggestions = emptyList()) }
    }

    fun onWorkTextChanged(text: String) {
        _uiState.update { it.copy(workText = text, showWorkTextError = false) }
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val contractorId = state.selectedContractorId
        val workText = state.workText.trim()
        val hasContractorError = contractorId == null
        val hasWorkTextError = workText.isBlank()
        if (hasContractorError || hasWorkTextError) {
            _uiState.update { it.copy(showContractorError = hasContractorError, showWorkTextError = hasWorkTextError) }
            return
        }
        viewModelScope.launch {
            val locationCode = state.locationCode.trim()
            if (recordId == null) {
                recordRepository.createRecord(
                    journalId = requireNotNull(journalId) { "journalId обязателен при создании записи" },
                    dateEpochDay = state.date.toEpochDay(),
                    contractorId = contractorId!!,
                    locationCode = locationCode,
                    workText = workText,
                )
            } else {
                recordRepository.getById(recordId)?.let { existing ->
                    recordRepository.updateRecord(
                        existing = existing,
                        dateEpochDay = state.date.toEpochDay(),
                        contractorId = contractorId!!,
                        locationCode = locationCode,
                        workText = workText,
                    )
                }
            }
            onSaved()
        }
    }
}
