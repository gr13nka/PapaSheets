package ru.papasheets.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import ru.papasheets.R
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.domain.ColumnWidth

/** Ступени слайдера ширины — по числу пресетов [ColumnWidth]. */
private val WIDTH_STEPS = ColumnWidth.entries.size - 2
private val WIDTH_RANGE = 0f..(ColumnWidth.entries.size - 1).toFloat()

/** Готовые чипы «Строк в ячейке»; значение вне этого набора (бэкап, старая версия) добавляет свой, пятый. */
private val LINE_OPTIONS = listOf(1, 2, 3, 4)

/**
 * Редактор одного поля: каждое изменение пишется через [GroupSettingsViewModel.edit] сразу, без
 * «Сохранить» — это то же правило, что и у формы записи («уход с формы — это сохранение»), только
 * здесь уходить не с чего, потому что сохранять уже нечего к моменту ухода.
 *
 * Текст названия и подписи держится в локальном состоянии, а не читается напрямую из [field]: поток
 * полей может прислать своё же обновление на кадр позже, и синхронизация с ним на каждый чих
 * перехватывала бы курсор посреди набора.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FieldEditorPane(field: FieldDefEntity, viewModel: GroupSettingsViewModel, modifier: Modifier = Modifier) {
    var label by remember(field.id) { mutableStateOf(field.label) }
    var title by remember(field.id) { mutableStateOf(field.title) }
    var advanced by remember(field.id) { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = { text ->
                label = text
                if (text.isNotBlank()) viewModel.edit(field.id) {
                    if (it.title == it.label) { title = text; it.copy(label = text, title = text) }
                    else it.copy(label = text)
                }
            },
            label = { Text(stringResource(R.string.fields_label_label)) },
            singleLine = true,
            isError = label.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.table_columns_scope), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.table_more_settings)) }
        if (advanced) {
        OutlinedTextField(
            value = title,
            onValueChange = { text ->
                title = text
                if (text.isNotBlank()) viewModel.edit(field.id) { it.copy(title = text) }
            },
            label = { Text(stringResource(R.string.fields_title_label)) },
            singleLine = true,
            isError = title.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )

        WidthSection(field = field, viewModel = viewModel)
        LinesSection(field = field, viewModel = viewModel)

        ToggleRow(
            label = stringResource(R.string.fields_required_label),
            hint = stringResource(R.string.fields_required_hint),
            checked = field.isRequired,
            onCheckedChange = { checked -> viewModel.edit(field.id) { it.copy(isRequired = checked) } },
        )
        ToggleRow(
            label = stringResource(R.string.fields_suggest_label),
            hint = stringResource(R.string.fields_suggest_hint),
            checked = field.suggestFromHistory,
            onCheckedChange = { checked -> viewModel.edit(field.id) { it.copy(suggestFromHistory = checked) } },
        )
        ToggleRow(
            label = stringResource(R.string.fields_compact_label),
            hint = stringResource(R.string.fields_compact_hint),
            checked = field.showAtCompactLod,
            onCheckedChange = { checked -> viewModel.edit(field.id) { it.copy(showAtCompactLod = checked) } },
        )

        HorizontalDivider()
        PresetsSection(field = field, suggestionsEnabled = field.suggestFromHistory, viewModel = viewModel)

        }
        HorizontalDivider()
        OutlinedButton(
            onClick = { viewModel.setArchived(field.id, !field.isArchived) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (field.isArchived) R.string.fields_unarchive_action else R.string.fields_archive_action))
        }
        TextButton(onClick = { viewModel.delete(field.id) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.fields_delete_action), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Слайдер на 4 стопа вместо ввода dp: прораб выбирает пресет по имени, а не подбирает пиксели. */
@Composable
private fun WidthSection(field: FieldDefEntity, viewModel: GroupSettingsViewModel) {
    var index by remember(field.id) { mutableStateOf(ColumnWidth.nearest(field.columnWidthDp).ordinal) }
    val preset = ColumnWidth.entries[index]

    Column {
        Text(
            "${stringResource(R.string.fields_width_label)} · ${stringResource(preset.labelRes())}",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { raw ->
                val snapped = raw.roundToInt().coerceIn(0, ColumnWidth.entries.lastIndex)
                // Пишем в БД, только когда слайдер реально сменил пресет: иначе просто открытый
                // редактор перезаписал бы off-preset значение (старый бэкап, ручной dp) первым же
                // событием слайдера, хотя прораб его пальцем не трогал.
                if (snapped != index) {
                    index = snapped
                    viewModel.edit(field.id) { it.copy(columnWidthDp = ColumnWidth.entries[snapped].dp) }
                }
            },
            valueRange = WIDTH_RANGE,
            steps = WIDTH_STEPS,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinesSection(field: FieldDefEntity, viewModel: GroupSettingsViewModel) {
    Column {
        Text(stringResource(R.string.fields_max_lines_label), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LineChip(stringResource(R.string.fields_lines_all), selected = field.maxLines == 0) {
                viewModel.edit(field.id) { it.copy(maxLines = 0) }
            }
            LINE_OPTIONS.forEach { n ->
                LineChip(n.toString(), selected = field.maxLines == n) {
                    viewModel.edit(field.id) { it.copy(maxLines = n) }
                }
            }
            // Значение вне {0..4} (бэкап, старая версия) показываем отдельным выбранным чипом, а не
            // молча округляем или прячем — прораб должен видеть, что там на самом деле стоит.
            if (field.maxLines !in 0..4) {
                LineChip(field.maxLines.toString(), selected = true, onClick = {})
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ToggleRow(label: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Готовые значения поля — то, что предлагается в форме раньше истории ввода. Показываются у любого
 * поля, а не только у «Локации»: список кодов имеет смысл и для «Объёма», и для «Замечания». Пресеты
 * правятся сразу, а не по «Сохранить» — это отдельные строки БД, а не часть [FieldDraft].
 */
@Composable
private fun PresetsSection(field: FieldDefEntity, suggestionsEnabled: Boolean, viewModel: GroupSettingsViewModel) {
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
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Тот же приём, что и `AppLanguage.labelRes()` в `SettingsScreen.kt`: перечисление ↔ строковый ресурс подписи. */
internal fun ColumnWidth.labelRes(): Int = when (this) {
    ColumnWidth.NARROW -> R.string.column_width_narrow
    ColumnWidth.MEDIUM -> R.string.column_width_medium
    ColumnWidth.WIDE -> R.string.column_width_wide
    ColumnWidth.EXTRA_WIDE -> R.string.column_width_extra_wide
}
