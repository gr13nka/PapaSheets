package ru.papasheets.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.papasheets.R
import ru.papasheets.data.repo.FieldDeleteOutcome
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.common.formInsets

private val PreviewHeight = 240.dp

/**
 * «Настройка группы»: сверху живой кусок матрицы одной группы ([GroupPreviewPane]), снизу — список
 * полей или редактор выбранного. Отдельного enum «какой сейчас экран» нет — им управляет
 * [GroupSettingsViewModel.selectedFieldId] (`null` = список), и оба Back'а (кнопка сверху и системный
 * жест) обязаны сначала вернуть из редактора в список, а уже потом выйти с экрана целиком.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(journalId: String, initialFieldId: String? = null, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val placeholderGroupName = stringResource(R.string.group_preview_placeholder_group)
    val shortMonths = stringArrayResource(R.array.month_names_short).toList()
    val viewModel: GroupSettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                GroupSettingsViewModel(
                    journalId,
                    initialFieldId,
                    graph.fieldRepository,
                    graph.fieldPresetRepository,
                    graph.valueSuggester,
                    graph.contractorRepository,
                    graph.fieldValueColorRepository,
                    placeholderGroupName,
                    shortMonths,
                )
            }
        },
    )

    val fields by viewModel.fields.collectAsState()
    val selectedFieldId by viewModel.selectedFieldId.collectAsState()
    val deleteRefusal by viewModel.deleteRefusal.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val selectedField = fields.find { it.id == selectedFieldId }

    BackHandler(enabled = selectedFieldId != null) { viewModel.backToList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedField?.label ?: stringResource(R.string.group_settings_title)) },
                navigationIcon = {
                    TextButton(onClick = { if (selectedFieldId != null) viewModel.backToList() else onBack() }) {
                        Text(stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val model = preview
            if (model != null) {
                GroupPreviewPane(model, modifier = Modifier.fillMaxWidth().height(PreviewHeight))
            } else {
                // Первый кадр после открытия экрана: превью ещё не посчитано (примеры значений — это
                // suspend-запрос). Пустой блок той же высоты — чтобы разделитель и список ниже не
                // прыгали вверх-вниз, пока превью не появится долями секунды позже.
                Box(modifier = Modifier.fillMaxWidth().height(PreviewHeight))
            }
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f)) {
                if (selectedField != null) {
                    FieldEditorPane(
                        field = selectedField,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).formInsets(),
                    )
                } else {
                    FieldListPane(
                        fields = fields,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).formInsets(),
                    )
                }
            }
        }
    }

    deleteRefusal?.let { refusal ->
        DeleteRefusalDialog(
            refusal = refusal,
            onArchive = {
                viewModel.setArchived(refusal.field.id, true)
                viewModel.dismissDeleteRefusal()
            },
            onDismiss = viewModel::dismissDeleteRefusal,
        )
    }
}

@Composable
private fun DeleteRefusalDialog(refusal: FieldDeleteRefusal, onArchive: () -> Unit, onDismiss: () -> Unit) {
    val message = when (val reason = refusal.reason) {
        is FieldDeleteOutcome.BuiltIn -> stringResource(R.string.fields_delete_refused_built_in, refusal.field.label)
        is FieldDeleteOutcome.InUse ->
            stringResource(R.string.fields_delete_refused_in_use, refusal.field.label, reason.valueCount)
        // Отказа не было — диалог не показывается вовсе (см. GroupSettingsViewModel.delete).
        is FieldDeleteOutcome.Deleted -> ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fields_delete_refused_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onArchive) { Text(stringResource(R.string.fields_archive_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
