package ru.papasheets.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.FieldPresetEntity
import ru.papasheets.data.repo.FieldDeleteOutcome
import ru.papasheets.data.repo.FieldDraft
import ru.papasheets.data.repo.FieldPresetRepository
import ru.papasheets.data.repo.FieldRepository

/**
 * Поле, которое не удалось удалить, и причина отказа: экран показывает её и предлагает архивацию —
 * решение же, удалять или нет, принял [FieldRepository].
 */
data class FieldDeleteRefusal(val field: FieldDefEntity, val reason: FieldDeleteOutcome)

/** Тонкая обёртка над репозиториями для экрана полей — вся политика (id/key/order/удаление) в них. */
class FieldsViewModel(
    private val fieldRepository: FieldRepository,
    private val presetRepository: FieldPresetRepository,
) : ViewModel() {

    val fields: StateFlow<List<FieldDefEntity>> = fieldRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _deleteRefusal = MutableStateFlow<FieldDeleteRefusal?>(null)
    val deleteRefusal: StateFlow<FieldDeleteRefusal?> = _deleteRefusal.asStateFlow()

    fun add(draft: FieldDraft) {
        viewModelScope.launch { fieldRepository.create(draft) }
    }

    fun update(field: FieldDefEntity, draft: FieldDraft) {
        viewModelScope.launch { fieldRepository.update(field, draft) }
    }

    fun setArchived(field: FieldDefEntity, archived: Boolean) {
        viewModelScope.launch { fieldRepository.setArchived(field, archived) }
    }

    fun reorder(ordered: List<FieldDefEntity>) {
        viewModelScope.launch { fieldRepository.reorder(ordered) }
    }

    /** Удавшееся удаление ничего не показывает — поле просто исчезает из списка; отказ объясняется. */
    fun delete(field: FieldDefEntity) {
        viewModelScope.launch {
            val outcome = fieldRepository.delete(field)
            if (outcome != FieldDeleteOutcome.Deleted) _deleteRefusal.value = FieldDeleteRefusal(field, outcome)
        }
    }

    fun dismissDeleteRefusal() {
        _deleteRefusal.value = null
    }

    fun presetsOf(fieldId: String): Flow<List<FieldPresetEntity>> = presetRepository.observeFor(fieldId)

    fun addPreset(fieldId: String, code: String) {
        viewModelScope.launch { presetRepository.add(fieldId, code) }
    }

    fun deletePreset(preset: FieldPresetEntity) {
        viewModelScope.launch { presetRepository.delete(preset) }
    }
}
