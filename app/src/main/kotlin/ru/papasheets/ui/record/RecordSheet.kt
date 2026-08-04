package ru.papasheets.ui.record

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import ru.papasheets.R
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.domain.ContinueYesterday
import ru.papasheets.domain.JournalDates
import ru.papasheets.domain.RecordCloseAction
import ru.papasheets.domain.contractorDisplayName
import ru.papasheets.matrixgrid.MatrixPalette
import ru.papasheets.photos.CameraCapture
import ru.papasheets.photos.GalleryPick
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.common.ColorPickerDialog
import ru.papasheets.ui.common.ColorSwatchButton
import ru.papasheets.ui.common.ContractorDialog
import ru.papasheets.ui.common.formInsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(
    mode: RecordSheetMode,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onMinimize: (recordId: String, summary: String) -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModelKey = when (mode) {
        is RecordSheetMode.Create -> "create-${mode.sessionId}"
        is RecordSheetMode.Edit -> "edit-${mode.recordId}"
    }
    val viewModel: RecordEditViewModel = viewModel(
        key = viewModelKey,
        factory = viewModelFactory {
            initializer {
                RecordEditViewModel(
                    journalId = (mode as? RecordSheetMode.Create)?.journalId,
                    recordId = (mode as? RecordSheetMode.Edit)?.recordId,
                    initialDate = (mode as? RecordSheetMode.Create)?.defaultDate ?: LocalDate.now(),
                    initialContractorId = (mode as? RecordSheetMode.Create)?.contractorId,
                    recordRepository = graph.recordRepository,
                    contractorRepository = graph.contractorRepository,
                    fieldRepository = graph.fieldRepository,
                    valueSuggester = graph.valueSuggester,
                    valueColorRepository = graph.fieldValueColorRepository,
                    photoStore = graph.photoStore,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsState()
    // Форма не помещается в свёрнутое состояние (кнопка «Сохранить» уходит за экран) — открываем сразу развёрнутой.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun closeSheet(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }

    /** Явный отказ от набранного — только кнопкой «Не сохранять»: фото этой сессии осиротели. */
    fun discard() {
        viewModel.discardUnsavedPhoto()
        closeSheet(onDismiss)
    }

    /**
     * Свайп вниз, «Назад», тап по затемнению и шеврон «свернуть» — всё это сохраняет начатую запись
     * и сворачивает форму в полоску внизу ([MinimizedRecordBar]), а не закрывает её насовсем: на
     * объекте форму сворачивают, чтобы свериться с таблицей, и разыскивать потом начатую запись в
     * матрице прораб не должен.
     *
     * Что делать, решает по-прежнему [RecordEditViewModel.onCloseRequested] на общем
     * [ru.papasheets.domain.recordCloseAction]; свернуть можно ровно то, что удалось сохранить:
     * - [RecordCloseAction.Save] — запись в БД, дальше полоска (форма ждёт `onSaved`, а не
     *   сворачивается сразу: id создаваемой записи известен только после вставки);
     * - [RecordCloseAction.Discard] — пустую форму сворачивать нечего, закрываем совсем;
     * - [RecordCloseAction.KeepOpen] — без подрядчика запись негде хранить (FK), и уехавший вниз
     *   лист приходится возвращать: жалоба видна только на открытой форме.
     */
    fun minimizeRequested() {
        when (viewModel.onCloseRequested(onSaved = { id -> closeSheet { onMinimize(id, summaryOf(state)) } })) {
            RecordCloseAction.Save -> Unit
            RecordCloseAction.Discard -> discard()
            RecordCloseAction.KeepOpen -> scope.launch { sheetState.show() }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        viewModel.onCameraResult(success)?.let { uri ->
            scope.launch { CameraCapture.copyToGallery(context, uri) }
        }
    }
    fun launchCamera() {
        val uri = CameraCapture.createTempUri(context)
        viewModel.onCameraLaunchStarted(uri)
        cameraLauncher.launch(uri)
    }
    val writePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Снимок работает и без разрешения — просто не попадёт копией в галерею (см. CameraCapture).
        launchCamera()
    }
    // Слот выбирается ДО запуска камеры/галереи и запоминается ViewModel — результат приходит потом
    // (у камеры вообще в новом процессе), и связать его со слотом больше нечем.
    fun requestCamera(slot: Int) {
        viewModel.onPhotoSlotTargeted(slot)
        if (CameraCapture.needsWriteStoragePermission && !CameraCapture.hasWriteStoragePermission(context)) {
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            launchCamera()
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onGalleryPicked(uri)
    }
    fun requestGallery(slot: Int) {
        viewModel.onPhotoSlotTargeted(slot)
        galleryLauncher.launch(GalleryPick.request)
    }

    ModalBottomSheet(onDismissRequest = { minimizeRequested() }, sheetState = sheetState) {
        if (!state.isLoaded) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Прокрутка здесь не удобство, а условие работы отступа: сжать форму мало, поле под
                // фокусом надо ещё и поднять над клавиатурой — поднимать нечем без скролла.
                .verticalScroll(rememberScrollState())
                .formInsets()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (mode is RecordSheetMode.Edit) R.string.record_title_edit else R.string.record_title_create,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                // Свайп вниз делает то же самое, но догадаться о нём неоткуда — кнопка видна.
                IconButton(onClick = { minimizeRequested() }) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.record_minimize_action),
                    )
                }
            }

            var showDatePicker by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(JournalDates.numeric(state.date))
            }
            if (showDatePicker) {
                val datePickerState = rememberDatePickerState(
                    initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                viewModel.onDateSelected(picked)
                            }
                            showDatePicker = false
                        }) { Text(stringResource(R.string.action_ok)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
                    },
                ) {
                    DatePicker(state = datePickerState)
                }
            }

            // Только при создании: редактируемая запись уже имеет свои дату/подрядчика/текст.
            if (mode is RecordSheetMode.Create) {
                OutlinedButton(
                    onClick = viewModel::onContinueYesterdayClicked,
                    enabled = state.canContinueYesterday,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.record_continue_yesterday))
                }
            }

            var contractorExpanded by remember { mutableStateOf(false) }
            val selectedContractor = state.contractors.find { it.id == state.selectedContractorId }
            // Новый подрядчик заводится не выходя из формы: уход в настройки стоил бы набранного.
            var showNewContractorDialog by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = contractorExpanded,
                onExpandedChange = { contractorExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedContractor?.let(::contractorDisplayName) ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.record_contractor_label)) },
                    isError = state.showContractorError,
                    supportingText = if (state.showContractorError) {
                        { Text(stringResource(R.string.record_validation_required)) }
                    } else {
                        null
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = contractorExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = contractorExpanded,
                    onDismissRequest = { contractorExpanded = false },
                ) {
                    state.contractors.forEach { contractor ->
                        DropdownMenuItem(
                            text = { Text(contractorDisplayName(contractor)) },
                            onClick = {
                                viewModel.onContractorSelected(contractor.id)
                                contractorExpanded = false
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.record_contractor_create)) },
                        onClick = {
                            contractorExpanded = false
                            showNewContractorDialog = true
                        },
                    )
                }
            }
            if (showNewContractorDialog) {
                ContractorDialog(
                    initialName = "",
                    initialShortName = "",
                    titleRes = R.string.contractors_add_title,
                    onDismiss = { showNewContractorDialog = false },
                    onConfirm = { name, shortName ->
                        viewModel.createContractor(name, shortName)
                        showNewContractorDialog = false
                    },
                )
            }

            state.fields.forEach { field ->
                key(field.id) {
                    FieldInput(
                        field = field,
                        value = state.valueOf(field.id),
                        suggestions = state.suggestionsFor(field.id),
                        isError = field.id in state.emptyRequiredFieldIds,
                        colorOf = { state.colorOf(field.id, it) },
                        onValueChange = { viewModel.onValueChanged(field.id, it) },
                        onSuggestionPicked = { viewModel.onSuggestionPicked(field.id, it) },
                        onColorPicked = { viewModel.onValueColorPicked(field.id, state.valueOf(field.id), it) },
                    )
                }
            }

            // Слоты фото: заполненные плюс один пустой «добавить», пока не упёрлись в потолок.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (slot in 0 until state.visiblePhotoSlots) {
                    PhotoSlot(
                        modifier = Modifier.weight(1f),
                        loading = state.loadingSlot == slot,
                        thumbFile = state.photoIds.getOrNull(slot)?.let(graph.photoStore::thumbFile),
                        onCameraClick = { requestCamera(slot) },
                        onGalleryClick = { requestGallery(slot) },
                        onRemoveClick = { viewModel.onPhotoRemoved(slot) },
                    )
                }
            }
            if (state.showPhotoImportError) {
                Text(
                    text = stringResource(R.string.record_photo_import_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.showBlankRecordError) {
                Text(
                    text = stringResource(R.string.record_validation_blank),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { discard() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.record_discard))
                }
                Button(
                    onClick = { viewModel.save(onSaved = { closeSheet(onSaved) }) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (state.showContinuationPicker) {
        AlertDialog(
            onDismissRequest = viewModel::onContinuationPickerDismissed,
            title = { Text(stringResource(R.string.record_continue_yesterday_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.yesterdayRecords.forEach { record ->
                        TextButton(
                            onClick = { viewModel.onContinuationPicked(record) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(ContinueYesterday.preview(record, state.fields), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::onContinuationPickerDismissed) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Подпись для свёрнутой полоски: подрядчик и первое непустое значение — «ГП · Штукатурка».
 *
 * Собирается здесь, а не в полоске: только форма знает и выбранного подрядчика, и порядок полей.
 * Пустой она не бывает — сворачивается лишь то, что прошло [ru.papasheets.domain.recordCloseAction],
 * а там пустая запись отсеяна, и подрядчик обязателен.
 */
private fun summaryOf(state: RecordEditUiState): String = listOfNotNull(
    state.contractors.firstOrNull { it.id == state.selectedContractorId }?.shortName,
    state.fields.firstNotNullOfOrNull { field -> state.valueOf(field.id).trim().ifBlank { null } },
).joinToString(" · ")

/**
 * Одна строка формы по определению поля. Вид ввода целиком выводится из определения: подсказки —
 * там, где включена история, высота — по тому же [FieldDefEntity.maxLines], которым матрица
 * ограничивает текст в ячейке, так что ячейка и поле ввода не расходятся видом.
 *
 * Кружок цвета стоит у каждого поля, а не только у «Вида работ»: в матрице цветом заливается
 * подколонка своего поля, поэтому «какое поле красить» — вопрос, которого нет нигде в коде, и
 * заводить его в форме значило бы завести правило, которое неоткуда узнать. Пустое значение красить
 * нечего — кнопка выключена.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(
    field: FieldDefEntity,
    value: String,
    suggestions: List<String>,
    isError: Boolean,
    colorOf: (String) -> Int?,
    onValueChange: (String) -> Unit,
    onSuggestionPicked: (String) -> Unit,
    onColorPicked: (Int?) -> Unit,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    // По верху: поле «Вида работ» растёт на три строки, и кружок, вставший по центру такого поля,
    // уезжал бы вниз тем дальше, чем длиннее текст.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            FieldValueInput(field, value, suggestions, isError, colorOf, onValueChange, onSuggestionPicked)
        }
        ColorSwatchButton(
            colorIndex = colorOf(value),
            enabled = value.isNotBlank(),
            onClick = { showColorPicker = true },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (showColorPicker) {
        ColorPickerDialog(
            title = stringResource(R.string.color_picker_title, value.trim()),
            selected = colorOf(value),
            onPick = {
                onColorPicked(it)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false },
        )
    }
}

/** Само поле ввода: с выпадашкой подсказок или без неё — по флагу истории у определения поля. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldValueInput(
    field: FieldDefEntity,
    value: String,
    suggestions: List<String>,
    isError: Boolean,
    colorOf: (String) -> Int?,
    onValueChange: (String) -> Unit,
    onSuggestionPicked: (String) -> Unit,
) {
    if (!field.suggestFromHistory) {
        FieldTextField(field, value, isError, onValueChange, Modifier.fillMaxWidth())
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val menuOpen = expanded && suggestions.isNotEmpty()
    ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { expanded = it }) {
        FieldTextField(
            field = field,
            value = value,
            isError = isError,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { expanded = false }) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    // Цвет подсказки виден до выбора: иначе прораб узнавал бы, какого цвета
                    // «Штукатурка», только выбрав её и посмотрев на кружок.
                    leadingIcon = { ColorDot(colorOf(suggestion)) },
                    text = { Text(suggestion) },
                    onClick = {
                        onSuggestionPicked(suggestion)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Метка цвета значения в списке подсказок; у бесцветного — пустое место той же ширины. */
@Composable
private fun ColorDot(colorIndex: Int?) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .then(
                if (colorIndex == null) Modifier
                else Modifier.background(MatrixPalette.color(colorIndex, isSystemInDarkTheme())),
            ),
    )
}

@Composable
private fun FieldTextField(
    field: FieldDefEntity,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    // Высота ввода — потолок строк самого поля, а не «многострочное значит высокое»: «Локация» (2)
    // занимает одну строку и растягивается до двух, а блок на три строки остаётся у полей без
    // потолка вроде «Вида работ». Потолок читается как в матрице ([GridField.lineCap]): <= 0 — нет.
    val unlimited = field.maxLines <= 0
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(field.label) },
        isError = isError,
        supportingText = if (isError) {
            { Text(stringResource(R.string.record_validation_required)) }
        } else {
            null
        },
        singleLine = field.maxLines == 1,
        minLines = if (unlimited) 3 else 1,
        maxLines = if (unlimited) 6 else field.maxLines,
        modifier = modifier,
    )
}

/**
 * Один слот фото формы: пустой (кнопки Камера/Галерея), с превью (Заменить/Удалить) или крутилка на
 * время импорта. Слоты стоят рядом ([Modifier.weight] снаружи), поэтому содержимое вертикальное —
 * превью сверху, кнопки под ним, а не сбоку: в половине ширины формы кнопки рядом не помещаются.
 */
@Composable
private fun PhotoSlot(
    modifier: Modifier,
    loading: Boolean,
    thumbFile: File?,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.record_photo_processing))
            }

            thumbFile != null -> {
                AsyncImage(
                    model = thumbFile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                var replaceExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { replaceExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.record_photo_replace))
                    }
                    DropdownMenu(expanded = replaceExpanded, onDismissRequest = { replaceExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.record_photo_camera)) },
                            onClick = { replaceExpanded = false; onCameraClick() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.record_photo_gallery)) },
                            onClick = { replaceExpanded = false; onGalleryClick() },
                        )
                    }
                }
                OutlinedButton(onClick = onRemoveClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.record_photo_remove))
                }
            }

            else -> {
                OutlinedButton(onClick = onCameraClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.record_photo_camera))
                }
                OutlinedButton(onClick = onGalleryClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.record_photo_gallery))
                }
            }
        }
    }
}
