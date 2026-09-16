package ru.papasheets.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.papasheets.R
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.domain.ColumnWidth

/** Высота строки поля — общая для активного списка (нужна drag'у для перевода px в позиции) и архива. */
private val FieldRowHeight = 72.dp

/**
 * Список состояния «Настройки группы»: активные поля в порядке подколонок матрицы (с drag-реордером),
 * «+ Добавить поле» и свёрнутый по умолчанию архив. Тап по строке открывает [FieldEditorPane] тем же
 * действием [GroupSettingsViewModel.select] — архивировать поле теперь можно только из редактора.
 */
@Composable
fun FieldListPane(fields: List<FieldDefEntity>, viewModel: GroupSettingsViewModel, modifier: Modifier = Modifier) {
    val active = remember(fields) { fields.filter { !it.isArchived }.sortedBy { it.orderIndex } }
    val archived = remember(fields) { fields.filter { it.isArchived }.sortedBy { it.orderIndex } }
    var showAddDialog by remember { mutableStateOf(false) }
    var archivedExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.fields_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        // Все поля разом в архиве — состояние допустимое: матрица и экспорт умеют ноль колонок,
        // журнал в этом виде показывает только даты, группы и фото.
        if (active.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.fields_no_active), style = MaterialTheme.typography.bodyLarge)
            }
        }
        DragReorderColumn(
            items = active,
            key = FieldDefEntity::id,
            rowHeight = FieldRowHeight,
            onReorder = { ordered -> viewModel.reorder(ordered.map { it.id }) },
        ) { field, dragHandle ->
            FieldRow(field = field, dimmed = false, onClick = { viewModel.select(field.id) }, dragHandle = dragHandle)
        }
        TextButton(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.group_settings_add_field))
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
                    FieldRow(field = field, dimmed = true, onClick = { viewModel.select(field.id) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        AddFieldDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                viewModel.addField(name)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun FieldRow(
    field: FieldDefEntity,
    dimmed: Boolean,
    onClick: () -> Unit,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «"Л" · Узкая · до 2 строк · обязательное» — как выглядит колонка и что о ней включено. */
@Composable
private fun fieldSummary(field: FieldDefEntity): String {
    val lines = if (field.maxLines == 0) {
        stringResource(R.string.fields_lines_unlimited)
    } else {
        pluralStringResource(R.plurals.fields_lines_limit, field.maxLines, field.maxLines)
    }
    val widthLabel = stringResource(ColumnWidth.nearest(field.columnWidthDp).labelRes())
    val parts = buildList {
        add(stringResource(R.string.fields_summary_head, field.title, widthLabel, lines))
        if (field.isRequired) add(stringResource(R.string.fields_flag_required))
        if (field.suggestFromHistory) add(stringResource(R.string.fields_flag_suggest))
        if (field.showAtCompactLod) add(stringResource(R.string.fields_flag_compact))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun AddFieldDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fields_add_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.fields_label_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
