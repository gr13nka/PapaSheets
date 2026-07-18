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
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.LocationSuggester
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.domain.ContinueYesterday
import ru.papasheets.domain.buildContractorOptions
import ru.papasheets.photos.PhotoSource
import ru.papasheets.photos.PhotoStore

data class RecordEditUiState(
    val date: LocalDate = LocalDate.now(),
    val contractors: List<ContractorEntity> = emptyList(),
    val selectedContractorId: String? = null,
    val locationCode: String = "",
    val locationSuggestions: List<String> = emptyList(),
    val workText: String = "",
    val photoId: String? = null,
    val photoLoading: Boolean = false,
    val showContractorError: Boolean = false,
    val showWorkTextError: Boolean = false,
    val showPhotoError: Boolean = false,
    val showPhotoImportError: Boolean = false,
    val isLoaded: Boolean = false,
    /** Записи выбранного подрядчика за (дата формы − 1 день) в этом журнале; пусто вне режима создания. */
    val yesterdayRecords: List<RecordEntity> = emptyList(),
    val showContinuationPicker: Boolean = false,
) {
    /** Кнопка «Продолжить вчерашнее» активна, только когда есть из чего выбирать. */
    val canContinueYesterday: Boolean get() = yesterdayRecords.isNotEmpty()
}

/**
 * Данные и валидация формы записи. Режим (создание/редактирование) задаётся один раз при
 * создании ViewModel — [journalId] нужен только для создания, [recordId] только для загрузки существующей записи.
 *
 * Фото ведёт себя как черновик: [PhotoStore.import] вызывается сразу при выборе (нужен превью), но
 * старое фото при замене/удалении удаляется из [PhotoStore] только после успешного сохранения записи —
 * отмена формы не должна портить уже сохранённую запись.
 */
class RecordEditViewModel(
    private val journalId: String?,
    private val recordId: String?,
    initialDate: LocalDate,
    initialContractorId: String? = null,
    private val recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
    private val locationSuggester: LocationSuggester,
    private val photoStore: PhotoStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RecordEditUiState(
            date = initialDate,
            // Предзаполнение подрядчика имеет смысл только при создании из пустого слота матрицы.
            selectedContractorId = if (recordId == null) initialContractorId else null,
            isLoaded = recordId == null,
        ),
    )
    val uiState: StateFlow<RecordEditUiState> = _uiState.asStateFlow()

    /** Фото уже сохранённой записи (null для новой) — раньше этого момента "заменить"/"убрать" не удаляют файлы. */
    private var originalPhotoId: String? = null

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

    init {
        viewModelScope.launch {
            combine(contractorRepository.observeAll(), currentContractorId) { all, currentId ->
                buildContractorOptions(all, currentId)
            }.collect { options ->
                _uiState.update { it.copy(contractors = options) }
            }
        }
        if (recordId != null) {
            viewModelScope.launch {
                recordRepository.getById(recordId)?.let { existing ->
                    originalPhotoId = existing.photoId
                    currentContractorId.value = existing.contractorId
                    _uiState.update {
                        it.copy(
                            date = LocalDate.ofEpochDay(existing.dateEpochDay),
                            selectedContractorId = existing.contractorId,
                            locationCode = existing.locationCode,
                            workText = existing.workText,
                            photoId = existing.photoId,
                            isLoaded = true,
                        )
                    }
                }
            }
        } else {
            refreshContinuationCandidates()
        }
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

    fun onContinuationPicked(record: RecordEntity) {
        applyContinuation(record)
        _uiState.update { it.copy(showContinuationPicker = false) }
    }

    fun onContinuationPickerDismissed() {
        _uiState.update { it.copy(showContinuationPicker = false) }
    }

    private fun applyContinuation(record: RecordEntity) {
        _uiState.update { it.copy(locationCode = record.locationCode, workText = record.workText) }
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

    fun onPhotoRemoved() {
        val current = _uiState.value.photoId ?: return
        _uiState.update { it.copy(photoId = null, showPhotoImportError = false) }
        deleteIfOrphaned(current)
    }

    /** Форма закрывается без сохранения — фото, выбранное в этой сессии редактирования, орфан. */
    fun discardUnsavedPhoto() {
        val current = _uiState.value.photoId
        if (current != null && current != originalPhotoId) {
            // ViewModel может пережить закрытие формы (тот же ключ при повторном открытии) — без
            // сброса state.photoId указывал бы на уже удалённое фото.
            _uiState.update { it.copy(photoId = null) }
        }
        deleteIfOrphaned(current)
    }

    private fun importPhoto(source: PhotoSource) {
        val previousStaged = _uiState.value.photoId
        _uiState.update { it.copy(photoLoading = true, showPhotoError = false, showPhotoImportError = false) }
        viewModelScope.launch {
            try {
                val meta = photoStore.import(source)
                _uiState.update { it.copy(photoId = meta.id, photoLoading = false) }
                deleteIfOrphaned(previousStaged)
            } catch (e: Exception) {
                Log.e(TAG, "importPhoto: failed for $source", e)
                _uiState.update { it.copy(photoLoading = false, showPhotoImportError = true) }
            }
        }
    }

    /** Удаляет фото, только если оно не совпадает с фото уже сохранённой записи. */
    private fun deleteIfOrphaned(photoId: String?) {
        if (photoId != null && photoId != originalPhotoId) {
            viewModelScope.launch { photoStore.delete(photoId) }
        }
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val contractorId = state.selectedContractorId
        val workText = state.workText.trim()
        val hasContractorError = contractorId == null
        val hasWorkTextError = workText.isBlank()
        val hasPhotoError = state.photoId == null
        Log.d(TAG, "save: photoId=${state.photoId} photoLoading=${state.photoLoading} contractorId=$contractorId workText='$workText'")
        if (hasContractorError || hasWorkTextError || hasPhotoError) {
            _uiState.update {
                it.copy(
                    showContractorError = hasContractorError,
                    showWorkTextError = hasWorkTextError,
                    showPhotoError = hasPhotoError,
                )
            }
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
                    photoId = state.photoId,
                )
            } else {
                recordRepository.getById(recordId)?.let { existing ->
                    recordRepository.updateRecord(
                        existing = existing,
                        dateEpochDay = state.date.toEpochDay(),
                        contractorId = contractorId!!,
                        locationCode = locationCode,
                        workText = workText,
                        photoId = state.photoId,
                    )
                }
            }
            // Старое фото убираем только теперь, когда новая ссылка точно сохранена.
            if (originalPhotoId != null && originalPhotoId != state.photoId) {
                photoStore.delete(originalPhotoId!!)
            }
            // ViewModel переживает закрытие формы (тот же ключ при повторном открытии той же
            // записи) — без этого следующая сессия сочтёт только что сохранённое фото черновиком
            // и удалит его при "заменить"/"убрать" ещё до сохранения, отвязав его от записи через FK.
            originalPhotoId = state.photoId
            onSaved()
        }
    }

    private companion object {
        const val TAG = "RecordEditViewModel"
        const val KEY_PENDING_CAMERA_URI = "pendingCameraUri"
    }
}
