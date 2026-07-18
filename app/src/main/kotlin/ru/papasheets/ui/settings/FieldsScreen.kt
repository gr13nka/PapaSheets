package ru.papasheets.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.papasheets.R
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.repo.FieldDeleteOutcome
import ru.papasheets.data.repo.FieldDraft
import ru.papasheets.ui.LocalAppGraph

/** Высота строки поля — общая для активного списка (нужна drag'у для перевода px в позиции) и архива. */
private val FieldRowHeight = 72.dp

/** Границы ширины колонки: уже — заголовок не читается, шире — на экране помещается одна колонка. */
private val ColumnWidthRange = 24..400

/** Заготовка нового поля: узкая текстовая колонка с подсказками — самый частый случай. */
private val NewFieldDraft = FieldDraft(
    title = "",
    label = "",
    columnWidthDp = 120,
    maxLines = 2,
    isRequired = false,
    suggestFromHistory = true,
    showAtCompactLod = false,
)

/**
 * Настройки → Поля: состав колонок журнала. Здесь прораб заводит свою колонку («Объём», «Замечание»),
 * переименовывает и переупорядочивает существующие — порядок строк на экране и есть порядок подколонок
 * матрицы и колонок экспорта.
 *
 * Экран не знает правил предметной области: что встроенное поле нельзя удалить, а заполненное можно
 * только архивировать, решает [ru.papasheets.data.repo.FieldRepository] — сюда приходит уже готовый
 * [FieldDeleteOutcome], и остаётся выбрать формулировку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldsScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: FieldsViewModel = viewModel(
        factory = viewModelFactory { initializer { FieldsViewModel(graph.fieldRepository, graph.fieldPresetRepository) } },
    )
    val fields by viewModel.fields.collectAsState()
    val deleteRefusal by viewModel.deleteRefusal.collectAsState()
    val active = remember(fields) { fields.filter { !it.isArchived }.sortedBy { it.orderIndex } }
    val archived = remember(fields) { fields.filter { it.isArchived }.sortedBy { it.orderIndex } }

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FieldDefEntity?>(null) }
    var archivedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fields_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Text("+") }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Все поля разом в архиве — состояние допустимое: матрица и экспорт умеют ноль колонок,
            // журнал в этом виде показывает только даты, подрядчиков и фото.
            if (active.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.fields_no_active), style = MaterialTheme.typography.bodyLarge)
                }
            }
            DragReorderColumn(
                items = active,
                key = FieldDefEntity::id,
                rowHeight = FieldRowHeight,
                onReorder = viewModel::reorder,
            ) { field, dragHandle ->
                FieldRow(
                    field = field,
                    dimmed = false,
                    onClick = { editing = field },
                    trailingLabel = stringResource(R.string.fields_archive_action),
                    onTrailingClick = { viewModel.setArchived(field, true) },
                    dragHandle = dragHandle,
                )
            }
            if (archived.isNotEmpty()) {
                TextButton(
                    onClick = { archivedExpanded = !archivedExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.fields_archived_section, archived.size))
                }
                if (archivedExpanded) {
                    archived.forEach { field ->
                        FieldRow(
                            field = field,
                            dimmed = true,
                            onClick = { editing = field },
                            trailingLabel = stringResource(R.string.fields_unarchive_action),
                            onTrailingClick = { viewModel.setArchived(field, false) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        FieldDialog(
            field = null,
            initial = NewFieldDraft,
            viewModel = viewModel,
            onDismiss = { showAddDialog = false },
            onConfirm = { draft ->
                viewModel.add(draft)
                showAddDialog = false
            },
        )
    }

    editing?.let { field ->
        FieldDialog(
            field = field,
            initial = field.toDraft(),
            viewModel = viewModel,
            onDismiss = { editing = null },
            onConfirm = { draft ->
                viewModel.update(field, draft)
                editing = null
            },
            onDelete = {
                viewModel.delete(field)
                editing = null
            },
        )
    }

    deleteRefusal?.let { refusal ->
        DeleteRefusalDialog(
            refusal = refusal,
            onArchive = {
                viewModel.setArchived(refusal.field, true)
                viewModel.dismissDeleteRefusal()
            },
            onDismiss = viewModel::dismissDeleteRefusal,
        )
    }
}

private fun FieldDefEntity.toDraft() = FieldDraft(
    title = title,
    label = label,
    columnWidthDp = columnWidthDp,
    maxLines = maxLines,
    isRequired = isRequired,
    suggestFromHistory = suggestFromHistory,
    showAtCompactLod = showAtCompactLod,
)

@Composable
private fun FieldRow(
    field: FieldDefEntity,
    dimmed: Boolean,
    onClick: () -> Unit,
    trailingLabel: String,
    onTrailingClick: () -> Unit,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val textColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FieldRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dragHandle != null) dragHandle() else Box(modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge, color = textColor)
            Text(
                text = fieldSummary(field),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onTrailingClick) { Text(trailingLabel) }
    }
}

/** Одна строка под названием поля: как колонка выглядит и что о ней включено — ««Л» · 56 dp · 2 стр. · подсказки». */
@Composable
private fun fieldSummary(field: FieldDefEntity): String {
    val lines = if (field.maxLines == 0) {
        stringResource(R.string.fields_lines_unlimited)
    } else {
        stringResource(R.string.fields_lines_limit, field.maxLines)
    }
    val parts = buildList {
        add(stringResource(R.string.fields_summary_head, field.title, field.columnWidthDp, lines))
        if (field.isRequired) add(stringResource(R.string.fields_flag_required))
        if (field.suggestFromHistory) add(stringResource(R.string.fields_flag_suggest))
        if (field.showAtCompactLod) add(stringResource(R.string.fields_flag_compact))
    }
    return parts.joinToString(" · ")
}

/**
 * Всё о поле в одном диалоге, включая список готовых значений: разводить их по двум экранам значило бы
 * заставлять возвращаться туда-сюда ради одной колонки. Пресеты правятся сразу, а не по «Сохранить», —
 * это отдельные строки БД, а не часть черновика ([FieldDraft]).
 *
 * @param field редактируемое поле или `null`, если поле только заводится: у нового поля ещё нет id,
 *   и привязать к нему пресеты не к чему.
 */
@Composable
private fun FieldDialog(
    field: FieldDefEntity?,
    initial: FieldDraft,
    viewModel: FieldsViewModel,
    onDismiss: () -> Unit,
    onConfirm: (FieldDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    // Ключ по редактируемому полю: диалог живёт в одном слоте дерева, и без него черновик пережил бы
    // переход к другому полю, показав чужие значения.
    val editedId = field?.id
    var title by remember(editedId) { mutableStateOf(initial.title) }
    var label by remember(editedId) { mutableStateOf(initial.label) }
    var widthDp by remember(editedId) { mutableStateOf(initial.columnWidthDp.toString()) }
    var maxLines by remember(editedId) { mutableStateOf(initial.maxLines.toString()) }
    var isRequired by remember(editedId) { mutableStateOf(initial.isRequired) }
    var suggest by remember(editedId) { mutableStateOf(initial.suggestFromHistory) }
    var showAtCompactLod by remember(editedId) { mutableStateOf(initial.showAtCompactLod) }
    val isValid = title.isNotBlank() && label.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (field == null) R.string.fields_add_title else R.string.fields_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.fields_label_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.fields_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = widthDp,
                        onValueChange = { widthDp = it },
                        labelRes = R.string.fields_width_label,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = maxLines,
                        onValueChange = { maxLines = it },
                        labelRes = R.string.fields_max_lines_label,
                        modifier = Modifier.weight(1f),
                    )
                }
                CheckboxRow(stringResource(R.string.fields_required_label), isRequired) { isRequired = it }
                CheckboxRow(stringResource(R.string.fields_suggest_label), suggest) { suggest = it }
                CheckboxRow(stringResource(R.string.fields_compact_label), showAtCompactLod) { showAtCompactLod = it }
                Hint(stringResource(R.string.fields_compact_hint))

                if (field != null) {
                    HorizontalDivider()
                    PresetsSection(field = field, suggestionsEnabled = suggest, viewModel = viewModel)
                }
                if (onDelete != null && field != null && !field.isBuiltIn) {
                    HorizontalDivider()
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.fields_delete_action)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onConfirm(
                        FieldDraft(
                            title = title.trim(),
                            label = label.trim(),
                            columnWidthDp = widthDp.toIntOrNull()?.coerceIn(ColumnWidthRange)
                                ?: initial.columnWidthDp,
                            maxLines = maxLines.toIntOrNull() ?: initial.maxLines,
                            isRequired = isRequired,
                            suggestFromHistory = suggest,
                            showAtCompactLod = showAtCompactLod,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Готовые значения поля — то, что предлагается в форме раньше истории ввода. Показываются у любого
 * поля, а не только у «Локации»: список кодов имеет смысл и для «Объёма», и для «Замечания».
 */
@Composable
private fun PresetsSection(field: FieldDefEntity, suggestionsEnabled: Boolean, viewModel: FieldsViewModel) {
    val presetsFlow = remember(field.id) { viewModel.presetsOf(field.id) }
    val presets by presetsFlow.collectAsState(initial = emptyList())
    var newPreset by remember { mutableStateOf("") }

    Text(stringResource(R.string.fields_presets_section), style = MaterialTheme.typography.titleSmall)
    // Пресеты и история показываются одним списком и одним выключателем — заведённые значения при
    // выключенных подсказках просто никогда не всплывут, и молчать об этом нельзя.
    if (!suggestionsEnabled) Hint(stringResource(R.string.fields_presets_disabled_hint))
    presets.forEach { preset ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(preset.code, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { viewModel.deletePreset(preset) }) { Text("×") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = newPreset,
            onValueChange = { newPreset = it },
            label = { Text(stringResource(R.string.fields_preset_new_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = newPreset.isNotBlank(),
            onClick = { viewModel.addPreset(field.id, newPreset); newPreset = "" },
        ) {
            Text("+")
        }
    }
}

@Composable
private fun DeleteRefusalDialog(refusal: FieldDeleteRefusal, onArchive: () -> Unit, onDismiss: () -> Unit) {
    val message = when (val reason = refusal.reason) {
        is FieldDeleteOutcome.BuiltIn -> stringResource(R.string.fields_delete_refused_built_in, refusal.field.label)
        is FieldDeleteOutcome.InUse ->
            stringResource(R.string.fields_delete_refused_in_use, refusal.field.label, reason.valueCount)
        // Отказа не было — диалог не показывается вовсе (см. FieldsViewModel.delete).
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

/** Числовой ввод: нецифры отсекаются на вводе, чтобы «сохранить» не приходилось запрещать задним числом. */
@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, labelRes: Int, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() }.take(3)) },
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
