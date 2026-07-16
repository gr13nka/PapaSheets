package ru.papasheets.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import ru.papasheets.R
import ru.papasheets.ui.LocalAppGraph

private val dateButtonFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(mode: RecordSheetMode, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModelKey = when (mode) {
        is RecordSheetMode.Create -> "create-${mode.journalId}-${mode.defaultDate}"
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
                    recordRepository = graph.recordRepository,
                    contractorRepository = graph.contractorRepository,
                    locationSuggester = graph.locationSuggester,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsState()
    // Форма не помещается в свёрнутое состояние (кнопка «Сохранить» уходит за экран) — открываем сразу развёрнутой.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun dismiss(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                Text(state.date.format(dateButtonFormatter))
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

            var contractorExpanded by remember { mutableStateOf(false) }
            val selectedContractor = state.contractors.find { it.id == state.selectedContractorId }
            ExposedDropdownMenuBox(
                expanded = contractorExpanded,
                onExpandedChange = { contractorExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedContractor?.name ?: "",
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
                            text = { Text(contractor.name) },
                            onClick = {
                                viewModel.onContractorSelected(contractor.id)
                                contractorExpanded = false
                            },
                        )
                    }
                }
            }

            var locationExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = locationExpanded && state.locationSuggestions.isNotEmpty(),
                onExpandedChange = { locationExpanded = it },
            ) {
                OutlinedTextField(
                    value = state.locationCode,
                    onValueChange = {
                        viewModel.onLocationChanged(it)
                        locationExpanded = true
                    },
                    label = { Text(stringResource(R.string.record_location_label)) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = locationExpanded && state.locationSuggestions.isNotEmpty(),
                    onDismissRequest = { locationExpanded = false },
                ) {
                    state.locationSuggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                viewModel.onLocationSuggestionPicked(suggestion)
                                locationExpanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.workText,
                onValueChange = viewModel::onWorkTextChanged,
                label = { Text(stringResource(R.string.record_work_label)) },
                isError = state.showWorkTextError,
                supportingText = if (state.showWorkTextError) {
                    { Text(stringResource(R.string.record_validation_required)) }
                } else {
                    null
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.record_photo_placeholder))
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
}
