package ru.papasheets.ui.record

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import ru.papasheets.domain.contractorDisplayName
import ru.papasheets.photos.CameraCapture
import ru.papasheets.photos.GalleryPick
import ru.papasheets.ui.LocalAppGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(mode: RecordSheetMode, onDismiss: () -> Unit, onSaved: () -> Unit) {
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

    fun dismiss(after: () -> Unit) {
        viewModel.discardUnsavedPhoto()
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
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
    fun requestCamera() {
        if (CameraCapture.needsWriteStoragePermission && !CameraCapture.hasWriteStoragePermission(context)) {
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            launchCamera()
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onGalleryPicked(uri)
    }

    ModalBottomSheet(onDismissRequest = { dismiss(onDismiss) }, sheetState = sheetState) {
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (mode is RecordSheetMode.Edit) R.string.record_title_edit else R.string.record_title_create,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

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
                }
            }

            state.fields.forEach { field ->
                key(field.id) {
                    FieldInput(
                        field = field,
                        value = state.valueOf(field.id),
                        suggestions = state.suggestionsFor(field.id),
                        isError = field.id in state.emptyRequiredFieldIds,
                        onValueChange = { viewModel.onValueChanged(field.id, it) },
                        onSuggestionPicked = { viewModel.onSuggestionPicked(field.id, it) },
                    )
                }
            }

            PhotoSlot(
                state = state,
                onCameraClick = ::requestCamera,
                onGalleryClick = { galleryLauncher.launch(GalleryPick.request) },
                onRemoveClick = viewModel::onPhotoRemoved,
                thumbFile = state.photoId?.let(graph.photoStore::thumbFile),
            )

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
                    onClick = { dismiss(onDismiss) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { viewModel.save(onSaved = { dismiss(onSaved) }) },
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
 * Одна строка формы по определению поля. Вид ввода целиком выводится из определения: подсказки —
 * там, где включена история, многострочность — там же, где матрица снимает потолок строк
 * ([FieldDefEntity.maxLines] != 1), так что ячейка и поле ввода не расходятся видом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(
    field: FieldDefEntity,
    value: String,
    suggestions: List<String>,
    isError: Boolean,
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

@Composable
private fun FieldTextField(
    field: FieldDefEntity,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    val singleLine = field.maxLines == 1
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
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 6,
        modifier = modifier,
    )
}

@Composable
private fun PhotoSlot(
    state: RecordEditUiState,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onRemoveClick: () -> Unit,
    thumbFile: File?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            state.photoLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.record_photo_processing))
            }

            thumbFile != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = thumbFile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }

            else -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCameraClick, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.record_photo_camera))
                }
                OutlinedButton(onClick = onGalleryClick, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.record_photo_gallery))
                }
            }
        }

        if (state.showPhotoImportError) {
            Text(
                text = stringResource(R.string.record_photo_import_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
