package ru.papasheets.ui.record

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.RecordWithValues
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.FieldValueColorRepository
import ru.papasheets.data.repo.FieldValueColors
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.data.repo.ValueSuggester
import ru.papasheets.domain.ContinueYesterday
import ru.papasheets.domain.RecordCloseAction
import ru.papasheets.domain.buildContractorOptions
import ru.papasheets.domain.recordCloseAction
import ru.papasheets.domain.validateRecord
import ru.papasheets.matrixgrid.MAX_PHOTOS_PER_CELL
import ru.papasheets.photos.PhotoSource
import ru.papasheets.photos.PhotoStore

data class RecordEditUiState(
    val date: LocalDate = LocalDate.now(),
    val contractors: List<ContractorEntity> = emptyList(),
    val selectedContractorId: String? = null,
    /** Активные определения полей в порядке отображения — форма рисуется ровно по ним. */
    val fields: List<FieldDefEntity> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    /** Поле, в котором сейчас печатают: подсказки нужны только ему одному. */
    val suggestionFieldId: String? = null,
    val suggestions: List<String> = emptyList(),
    /**
     * Черновиковые фото по порядку слотов, без дырок: 0, 1 или [MAX_PHOTOS_PER_CELL] штук. Форма
     * заполняет слоты по порядку и при удалении сдвигает — так же, как их видят матрица и экспорт
     * (там фото тоже гапless-список), поэтому «второй слот без первого» здесь невозможен.
     */
    val photoIds: List<String> = emptyList(),
    /** Слот, для которого сейчас идёт импорт (крутилка), или null. */
    val loadingSlot: Int? = null,
    val showContractorError: Boolean = false,
    val emptyRequiredFieldIds: Set<String> = emptySet(),
    val showBlankRecordError: Boolean = false,
    val showPhotoImportError: Boolean = false,
    val fieldsLoaded: Boolean = false,
    val recordLoaded: Boolean = false,
    /** Записи выбранного подрядчика за (дата формы − 1 день) в этом журнале; пусто вне режима создания. */
    val yesterdayRecords: List<RecordWithValues> = emptyList(),
    val showContinuationPicker: Boolean = false,
    /** Цвета значений всех полей: `fieldId` → значение → индекс палитры (см. [FieldValueColorRepository]). */
    val valueColors: FieldValueColors = emptyMap(),
) {
    /** Форма показывается целиком или не показывается вовсе: рисовать её без полей нечем. */
    val isLoaded: Boolean get() = fieldsLoaded && recordLoaded

    /** У записи есть хотя бы одно фото — этого достаточно для валидации (второе необязательно). */
    val hasPhoto: Boolean get() = photoIds.isNotEmpty()

    /**
     * Сколько слотов фото показать в форме: заполненные плюс один пустой «добавить», но не больше
     * потолка. Пустой слот появляется только вслед за заполненным — второй нельзя завести раньше
     * первого.
     */
    val visiblePhotoSlots: Int get() = (photoIds.size + 1).coerceAtMost(MAX_PHOTOS_PER_CELL)

    /** Кнопка «Продолжить вчерашнее» активна, только когда есть из чего выбирать. */
    val canContinueYesterday: Boolean get() = yesterdayRecords.isNotEmpty()

    fun valueOf(fieldId: String): String = values[fieldId].orEmpty()

    fun suggestionsFor(fieldId: String): List<String> = if (suggestionFieldId == fieldId) suggestions else emptyList()

    /**
     * Цвет значения, набранного в поле прямо сейчас. Значение тримится так же, как это делает
     * `FieldValueColorRepository.setColor` при покраске, — иначе кружок в форме и заливка в матрице
     * разошлись бы на одном хвостовом пробеле.
     */
    fun colorOf(fieldId: String, value: String): Int? = valueColors[fieldId]?.get(value.trim())
}

/**
 * Данные и валидация формы записи. Режим (создание/редактирование) задаётся один раз при
 * создании ViewModel — [journalId] нужен только для создания, [recordId] только для загрузки существующей записи.
 *
 * Состав полей формы — данные, а не код: приходит из [FieldRepository], и заведённое прорабом поле
 * появляется в форме само. Обрезка значений и отбрасывание пустых — забота [RecordRepository];
 * что считать заполненной записью — забота [validateRecord]; здесь только состояние экрана.
 *
 * Фото ведёт себя как черновик: [PhotoStore.import] вызывается сразу при выборе (нужен превью), но
 * старое фото при замене/удалении удаляется из [PhotoStore] только после успешного сохранения записи —
 * отмена формы не должна портить уже сохранённую запись. Слотов два ([MAX_PHOTOS_PER_CELL]), и правило
 * «сирота — это фото, которого нет среди сохранённых» распространяется на оба разом ([originalPhotoIds]).
 */
class RecordEditViewModel(
    private val journalId: String?,
    private val recordId: String?,
    initialDate: LocalDate,
    initialContractorId: String? = null,
    private val recordRepository: RecordRepository,
    private val contractorRepository: ContractorRepository,
    fieldRepository: FieldRepository,
    private val valueSuggester: ValueSuggester,
    private val valueColorRepository: FieldValueColorRepository,
    private val photoStore: PhotoStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RecordEditUiState(
            date = initialDate,
            // Предзаполнение подрядчика имеет смысл только при создании из пустого слота матрицы.
            selectedContractorId = if (recordId == null) initialContractorId else null,
            recordLoaded = recordId == null,
        ),
    )
    val uiState: StateFlow<RecordEditUiState> = _uiState.asStateFlow()

    /** Фото уже сохранённой записи (пусто для новой) — раньше сохранения "заменить"/"убрать" не удаляют файлы. */
    private var originalPhotoIds: List<String> = emptyList()

    /** Идёт запись в БД — см. [persist]. */
    private var persisting = false

    /**
     * Подрядчик редактируемой записи (null в режиме создания) — держит его в дропдауне через
     * [buildContractorOptions], даже если он тем временем архивирован. Не переезжает при выборе
     * пользователем другого подрядчика в форме — это личность ИСХОДНОЙ записи, а не текущий выбор.
     */
    private val currentContractorId = MutableStateFlow<String?>(null)

    private var pendingCameraUri: String?
        get() = savedStateHandle[KEY_PENDING_CAMERA_URI]
        set(value) {
            savedStateHandle[KEY_PENDING_CAMERA_URI] = value
        }

    /**
     * Слот, в который ляжет следующее импортируемое фото. В [savedStateHandle], чтобы пережить смерть
     * процесса вместе с [pendingCameraUri]: камера возвращает результат уже в новом процессе.
     */
    private var pendingPhotoSlot: Int
        get() = savedStateHandle[KEY_PENDING_PHOTO_SLOT] ?: 0
        set(value) {
            savedStateHandle[KEY_PENDING_PHOTO_SLOT] = value
        }

    init {
        viewModelScope.launch {
            combine(contractorRepository.observeAll(), currentContractorId) { all, currentId ->
                buildContractorOptions(all, currentId)
            }.collect { options ->
                _uiState.update { it.copy(contractors = options) }
            }
        }
        viewModelScope.launch {
            fieldRepository.observeActive().collect { fields ->
                _uiState.update { it.copy(fields = fields, fieldsLoaded = true) }
            }
        }
        viewModelScope.launch {
            valueColorRepository.observeAll().collect { colors ->
                _uiState.update { it.copy(valueColors = colors) }
            }
        }
        if (recordId != null) {
            viewModelScope.launch {
                recordRepository.getWithValues(recordId)?.let { existing ->
                    originalPhotoIds = existing.record.photoIds
                    currentContractorId.value = existing.record.contractorId
                    _uiState.update {
                        it.copy(
                            date = LocalDate.ofEpochDay(existing.record.dateEpochDay),
                            selectedContractorId = existing.record.contractorId,
                            values = existing.asMap(),
                            photoIds = existing.record.photoIds,
                            recordLoaded = true,
                        )
                    }
                }
            }
        } else {
            refreshContinuationCandidates()
        }
    }

    /**
     * Красит набранное в поле значение или снимает с него цвет ([colorIndex] = null).
     *
     * Цвет пишется сразу, не дожидаясь сохранения записи: он принадлежит значению, а не этой записи,
     * и отменять его вместе с формой было бы неверно — «Штукатурка» осталась бы бесцветной у всех
     * остальных записей тоже. Обновлённая карта прилетит обратно потоком из репозитория.
     */
    fun onValueColorPicked(fieldId: String, value: String, colorIndex: Int?) {
        viewModelScope.launch { valueColorRepository.setColor(fieldId, value, colorIndex) }
    }

    fun onDateSelected(date: LocalDate) {
        _uiState.update { it.copy(date = date) }
        refreshContinuationCandidates()
    }

    fun onContractorSelected(contractorId: String) {
        _uiState.update { it.copy(selectedContractorId = contractorId, showContractorError = false) }
        refreshContinuationCandidates()
    }

    /**
     * Заводит подрядчика прямо из формы и сразу выбирает его: прораб столкнулся с новым подрядчиком
     * посреди заполнения записи, и уход в настройки стоил бы ему набранного. В списке дропдауна новый
     * появится сам — он собран из [ContractorRepository.observeAll].
     */
    fun createContractor(name: String, shortName: String) {
        viewModelScope.launch {
            onContractorSelected(contractorRepository.create(name, shortName))
        }
    }

    /**
     * «Продолжить вчерашнее»: одна вчерашняя запись подставляется сразу, несколько — открывают
     * диалог выбора ([showContinuationPicker]); фото никогда не копируется (спец — новое фото
     * каждый раз), дата формы не меняется.
     */
    fun onContinueYesterdayClicked() {
        val records = _uiState.value.yesterdayRecords
        val single = ContinueYesterday.singleCandidate(records)
        when {
            single != null -> applyContinuation(single)
            records.isNotEmpty() -> _uiState.update { it.copy(showContinuationPicker = true) }
        }
    }

    fun onContinuationPicked(record: RecordWithValues) {
        applyContinuation(record)
        _uiState.update { it.copy(showContinuationPicker = false) }
    }

    fun onContinuationPickerDismissed() {
        _uiState.update { it.copy(showContinuationPicker = false) }
    }

    private fun applyContinuation(record: RecordWithValues) {
        _uiState.update { state ->
            state.copy(
                // Только поля формы: у вчерашней записи могут остаться значения уже архивированного поля.
                values = state.fields.associate { it.id to record.valueOf(it.id) },
                emptyRequiredFieldIds = emptySet(),
                showBlankRecordError = false,
            )
        }
    }

    /** Только для создания новой записи — у редактируемой уже есть неизменные подрядчик/дата. */
    private fun refreshContinuationCandidates() {
        if (recordId != null) return
        val jid = journalId ?: return
        val contractorId = _uiState.value.selectedContractorId
        if (contractorId == null) {
            _uiState.update { it.copy(yesterdayRecords = emptyList()) }
            return
        }
        val yesterday = ContinueYesterday.yesterday(_uiState.value.date.toEpochDay())
        viewModelScope.launch {
            val records = recordRepository.listByContractorAndDate(jid, contractorId, yesterday)
            _uiState.update { it.copy(yesterdayRecords = records) }
        }
    }

    fun onValueChanged(fieldId: String, text: String) {
        _uiState.update { it.withValue(fieldId, text) }
        val field = _uiState.value.fields.find { it.id == fieldId } ?: return
        if (!field.suggestFromHistory) return
        viewModelScope.launch {
            val suggestions = if (text.isBlank()) emptyList() else valueSuggester.suggest(fieldId, text)
            _uiState.update { it.copy(suggestionFieldId = fieldId, suggestions = suggestions) }
        }
    }

    fun onSuggestionPicked(fieldId: String, value: String) {
        _uiState.update { it.withValue(fieldId, value).copy(suggestions = emptyList()) }
    }

    /** Правка любого поля снимает жалобы, которые она могла погасить — ошибка не должна висеть до Save. */
    private fun RecordEditUiState.withValue(fieldId: String, text: String) = copy(
        values = values + (fieldId to text),
        emptyRequiredFieldIds = emptyRequiredFieldIds - fieldId,
        showBlankRecordError = false,
    )

    /**
     * Какой слот фото сейчас правит форма — вызывается перед запуском камеры или галереи. Слот
     * запоминается до результата (в т.ч. через смерть процесса для камеры).
     */
    fun onPhotoSlotTargeted(slot: Int) {
        pendingPhotoSlot = slot
    }

    /** Вызывается композаблом сразу перед запуском системной камеры — temp-uri должен пережить смерть процесса. */
    fun onCameraLaunchStarted(uri: Uri) {
        pendingCameraUri = uri.toString()
    }

    /** @return uri снимка для best-effort копии в галерею, если камера вернула успех, иначе null. */
    fun onCameraResult(success: Boolean): Uri? {
        val uriString = pendingCameraUri
        pendingCameraUri = null
        if (!success || uriString == null) return null
        val uri = Uri.parse(uriString)
        importPhoto(PhotoSource.Camera(uri))
        return uri
    }

    fun onGalleryPicked(uri: Uri?) {
        if (uri != null) importPhoto(PhotoSource.Gallery(uri))
    }

    fun onPhotoRemoved(slot: Int) {
        val current = _uiState.value.photoIds.getOrNull(slot) ?: return
        // Удаление сдвигает: слот 1 при пустом слоте 0 не остаётся — список схлопывается без дырок.
        _uiState.update {
            it.copy(
                photoIds = it.photoIds.filterIndexed { index, _ -> index != slot },
                showPhotoImportError = false,
            )
        }
        deleteIfOrphaned(current)
    }

    /** Форма закрывается без сохранения — все фото этой сессии, которых нет в сохранённой записи, сироты. */
    fun discardUnsavedPhoto() {
        val staged = _uiState.value.photoIds
        // ViewModel может пережить закрытие формы (тот же ключ при повторном открытии) — возвращаем
        // state к сохранённой правде, иначе он указывал бы на уже удалённые файлы.
        _uiState.update { it.copy(photoIds = originalPhotoIds) }
        staged.forEach { deleteIfOrphaned(it) }
    }

    /**
     * Импортирует фото в слот [pendingPhotoSlot]: замена, если слот занят, иначе добавление в конец.
     * Слот зажимается по длине списка — так фото, вернувшееся из камеры после смерти процесса (когда
     * черновиковый список уже потерян), не создаёт дырку, а становится первым.
     */
    private fun importPhoto(source: PhotoSource) {
        val slot = pendingPhotoSlot.coerceIn(0, _uiState.value.photoIds.size)
        val replaced = _uiState.value.photoIds.getOrNull(slot)
        _uiState.update { it.copy(loadingSlot = slot, showBlankRecordError = false, showPhotoImportError = false) }
        viewModelScope.launch {
            try {
                val meta = photoStore.import(source)
                _uiState.update { state ->
                    val updated = state.photoIds.toMutableList()
                    if (slot < updated.size) updated[slot] = meta.id else updated.add(meta.id)
                    state.copy(photoIds = updated, loadingSlot = null)
                }
                deleteIfOrphaned(replaced)
            } catch (e: Exception) {
                Log.e(TAG, "importPhoto: failed for $source", e)
                _uiState.update { it.copy(loadingSlot = null, showPhotoImportError = true) }
            }
        }
    }

    /** Удаляет фото, только если его нет среди фото уже сохранённой записи. */
    private fun deleteIfOrphaned(photoId: String?) {
        if (photoId != null && photoId !in originalPhotoIds) {
            viewModelScope.launch { photoStore.delete(photoId) }
        }
    }

    /** Явное «Сохранить»: строгая проверка, все жалобы разом. */
    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val validation = validate(state)
        if (!validation.isValid) {
            _uiState.update {
                it.copy(
                    showContractorError = validation.contractorMissing,
                    emptyRequiredFieldIds = validation.emptyRequiredFieldIds,
                    showBlankRecordError = validation.isBlankRecord,
                )
            }
            return
        }
        persist(state, onSaved)
    }

    /**
     * Форму закрывают (свайп, «Назад», затемнение) — недозаполненную запись сохраняем как есть, см.
     * [recordCloseAction]. Возвращает то, что сделано, чтобы форма не проверяла условия повторно:
     * при [RecordCloseAction.Save] закрываться нужно из [onSaved], когда запись уже в БД, при
     * [RecordCloseAction.KeepOpen] — не закрываться вовсе (жалоба уже выставлена в состоянии).
     */
    fun onCloseRequested(onSaved: () -> Unit): RecordCloseAction {
        val state = _uiState.value
        val action = recordCloseAction(validate(state))
        when (action) {
            RecordCloseAction.Save -> persist(state, onSaved)
            RecordCloseAction.KeepOpen -> _uiState.update { it.copy(showContractorError = true) }
            RecordCloseAction.Discard -> Unit
        }
        return action
    }

    private fun validate(state: RecordEditUiState) = validateRecord(
        contractorId = state.selectedContractorId,
        fields = state.fields,
        values = state.values,
        hasPhoto = state.hasPhoto,
    )

    /**
     * Запись в БД и уборка отвязанных фото; вызывается только после проверки.
     *
     * Повторный вызов, пока предыдущий не закончил, игнорируется: закрытие формы сохраняет, а
     * дотянуться до него дважды подряд (второй «Назад» раньше, чем закроется лист) легко — при
     * создании это завело бы вторую такую же запись. Закроет форму первый вызов.
     */
    private fun persist(state: RecordEditUiState, onSaved: () -> Unit) {
        if (persisting) return
        persisting = true
        val contractorId = requireNotNull(state.selectedContractorId)
        val photoId = state.photoIds.getOrNull(0)
        val photoId2 = state.photoIds.getOrNull(1)
        viewModelScope.launch {
            if (recordId == null) {
                recordRepository.createRecord(
                    journalId = requireNotNull(journalId) { "journalId обязателен при создании записи" },
                    dateEpochDay = state.date.toEpochDay(),
                    contractorId = contractorId,
                    values = state.values,
                    photoId = photoId,
                    photoId2 = photoId2,
                )
            } else {
                recordRepository.getById(recordId)?.let { existing ->
                    recordRepository.updateRecord(
                        existing = existing,
                        dateEpochDay = state.date.toEpochDay(),
                        contractorId = contractorId,
                        values = state.values,
                        photoId = photoId,
                        photoId2 = photoId2,
                    )
                }
            }
            // Старые фото убираем только теперь, когда новые ссылки точно сохранены: те из прежних,
            // что новая запись уже не держит ни в одном слоте.
            originalPhotoIds.filter { it !in state.photoIds }.forEach { photoStore.delete(it) }
            // ViewModel переживает закрытие формы (тот же ключ при повторном открытии той же
            // записи) — без этого следующая сессия сочла бы только что сохранённые фото черновиком
            // и удалила бы их при "заменить"/"убрать" ещё до сохранения, отвязав от записи через FK.
            originalPhotoIds = state.photoIds
            persisting = false
            onSaved()
        }
    }

    private companion object {
        const val TAG = "RecordEditViewModel"
        const val KEY_PENDING_CAMERA_URI = "pendingCameraUri"
        const val KEY_PENDING_PHOTO_SLOT = "pendingPhotoSlot"
    }
}
