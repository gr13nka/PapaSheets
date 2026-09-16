package ru.papasheets.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.FieldPresetEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldDeleteOutcome
import ru.papasheets.data.repo.FieldDraft
import ru.papasheets.data.repo.FieldPresetRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.FieldValueColorRepository
import ru.papasheets.data.repo.ValueSuggester
import ru.papasheets.domain.ColumnWidth
import ru.papasheets.domain.JournalDates
import ru.papasheets.domain.buildGroupPreview
import ru.papasheets.matrixgrid.ContractorColumn
import ru.papasheets.matrixgrid.GridModel

/** Сколько примеров значения показывать в превью — этого достаточно, чтобы понять, как ляжет текст, и мало, чтобы не листать. */
private const val SAMPLE_COUNT = 3

/** id-заглушка для группы превью, пока в приложении нет ни одной активной группы колонок. */
private const val PLACEHOLDER_GROUP_ID = "preview"

/**
 * Поле, которое не удалось удалить, и причина отказа: экран показывает её и предлагает архивацию —
 * решение же, удалять или нет, принял [FieldRepository].
 */
data class FieldDeleteRefusal(val field: FieldDefEntity, val reason: FieldDeleteOutcome)

/**
 * Состояние «Настройки группы»: список полей или редактор одного из них — решает [selectedFieldId]
 * (`null` = список), отдельного enum-состояния экрана нет. Каждое действие пишет в БД немедленно,
 * своего черновика ViewModel не держит: у экрана нет «Сохранить», а разошедшийся с БД черновик был
 * бы источником рассинхрона с превью и с матрицей.
 *
 * [preview] строит [buildGroupPreview] из первой активной группы (или локализованной заглушки, пока
 * группы ни одной) и трёх примеров значения на поле — тех же самых полей, которые видит матрица.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupSettingsViewModel(
    private val journalId: String,
    initialFieldId: String? = null,
    private val fieldRepository: FieldRepository,
    private val presetRepository: FieldPresetRepository,
    private val valueSuggester: ValueSuggester,
    contractorRepository: ContractorRepository,
    fieldValueColorRepository: FieldValueColorRepository,
    private val placeholderGroupName: String,
    private val shortMonths: List<String>,
) : ViewModel() {

    /** Даты превью фиксируются на момент открытия экрана — они не про сегодня, а про «какой-то день». */
    private val firstDay: LocalDate = LocalDate.now()

    val fields: StateFlow<List<FieldDefEntity>> = fieldRepository.observeForJournal(journalId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedFieldId = MutableStateFlow<String?>(initialFieldId)
    val selectedFieldId: StateFlow<String?> = _selectedFieldId.asStateFlow()

    private val _deleteRefusal = MutableStateFlow<FieldDeleteRefusal?>(null)
    val deleteRefusal: StateFlow<FieldDeleteRefusal?> = _deleteRefusal.asStateFlow()

    private val activeFields: Flow<List<FieldDefEntity>> = fields.map { list -> list.filter { !it.isArchived } }

    /**
     * Примеры значений пересчитываются только при смене СОСТАВА активных полей (`mapLatest` по
     * набору id), а не при каждой правке текста в них: иначе правка чужого поля посреди набора текста
     * дёргала бы сеть подсказок и в этом поле тоже.
     */
    private val samples: Flow<Map<String, List<String>>> = activeFields
        .map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .mapLatest { ids ->
            ids.associateWith { id ->
                (valueSuggester.usedValues(id) + presetRepository.observeFor(id).first().map { it.code })
                    .distinct()
                    .take(SAMPLE_COUNT)
            }
        }

    val preview: StateFlow<GridModel?> = combine(
        activeFields,
        contractorRepository.observeForJournal(journalId).map { groups -> groups.firstOrNull { !it.isArchived } },
        fieldValueColorRepository.observeForJournal(journalId),
        samples,
    ) { active, firstContractor, colors, sampleMap ->
        val group = firstContractor?.toColumn()
            ?: ContractorColumn(PLACEHOLDER_GROUP_ID, placeholderGroupName, placeholderGroupName.take(1), 0)
        buildGroupPreview(active, group, sampleMap, colors, firstDay) { date -> JournalDates.shortMonth(date, shortMonths) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun presetsOf(fieldId: String): Flow<List<FieldPresetEntity>> = presetRepository.observeFor(fieldId)

    fun select(fieldId: String) {
        _selectedFieldId.value = fieldId
    }

    fun backToList() {
        _selectedFieldId.value = null
    }

    fun addField(name: String) {
        viewModelScope.launch {
            val id = fieldRepository.create(
                journalId,
                FieldDraft(
                    title = name,
                    label = name,
                    columnWidthDp = ColumnWidth.DEFAULT.dp,
                    maxLines = 2,
                    isRequired = false,
                    suggestFromHistory = true,
                    showAtCompactLod = false,
                ),
            )
            _selectedFieldId.value = id
        }
    }

    fun edit(fieldId: String, change: (FieldDraft) -> FieldDraft) {
        viewModelScope.launch { fieldRepository.edit(fieldId, change) }
    }

    /** Архивация ведёт обратно к списку — редактировать спрятанное поле нечем; разархивация остаётся в редакторе. */
    fun setArchived(fieldId: String, archived: Boolean) {
        viewModelScope.launch {
            fieldRepository.setArchived(fieldId, archived)
            if (archived) backToList()
        }
    }

    fun reorder(orderedIds: List<String>) {
        viewModelScope.launch { fieldRepository.reorder(orderedIds) }
    }

    /** Удавшееся удаление возвращает к списку; отказ показывает причину и предлагает архивацию. */
    fun delete(fieldId: String) {
        viewModelScope.launch {
            val field = fields.value.find { it.id == fieldId } ?: return@launch
            when (val outcome = fieldRepository.delete(fieldId)) {
                FieldDeleteOutcome.Deleted -> backToList()
                else -> _deleteRefusal.value = FieldDeleteRefusal(field, outcome)
            }
        }
    }

    fun dismissDeleteRefusal() {
        _deleteRefusal.value = null
    }

    fun addPreset(fieldId: String, code: String) {
        viewModelScope.launch { presetRepository.add(fieldId, code) }
    }

    fun deletePreset(preset: FieldPresetEntity) {
        viewModelScope.launch { presetRepository.delete(preset) }
    }
}

private fun ContractorEntity.toColumn(): ContractorColumn = ContractorColumn(id, name, shortName, colorIndex)
