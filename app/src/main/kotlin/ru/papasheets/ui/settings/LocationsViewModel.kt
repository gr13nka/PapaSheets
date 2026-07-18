package ru.papasheets.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.papasheets.data.db.entity.LocationPresetEntity
import ru.papasheets.data.repo.LocationRepository

class LocationsViewModel(private val repository: LocationRepository) : ViewModel() {

    val presets: StateFlow<List<LocationPresetEntity>> = repository.observePresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(code: String) {
        viewModelScope.launch { repository.add(code) }
    }

    fun delete(preset: LocationPresetEntity) {
        viewModelScope.launch { repository.delete(preset) }
    }
}
