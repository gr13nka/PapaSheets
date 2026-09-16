package ru.papasheets.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papasheets.R

/**
 * Заведение и переименование подрядчика — один диалог на оба экрана: настройки подрядчиков и форму
 * записи (там он открывается пунктом «+ Создать нового подрядчика» из списка). Заголовок задаётся
 * снаружи, так что «новый» и «правка» отличаются только им.
 *
 * Что делать с введённым (создать или переименовать) решает вызывающий: диалог отдаёт готовые
 * значения и не знает ни репозитория, ни того, редактируется ли кто-то.
 */
@Composable
fun ContractorDialog(
    initialName: String,
    initialShortName: String,
    titleRes: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, shortName: String, colorIndex: Int) -> Unit,
    initialColorIndex: Int = 0,
    enabled: Boolean = true,
    preview: @Composable (String) -> Unit = {},
) {
    var name by remember { mutableStateOf(initialName) }
    var shortName by remember { mutableStateOf(initialShortName) }
    var advanced by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(initialColorIndex) }
    var pickColor by remember { mutableStateOf(false) }
    val isValid = name.isNotBlank() && enabled
    if (pickColor) ColorPickerDialog(title = stringResource(R.string.table_group_color), selected = color, onPick = { color = it ?: 0; pickColor = false }, onDismiss = { pickColor = false })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.contractors_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.table_groups_hint))
                preview(name)
                ColorSwatchButton(colorIndex = color, enabled = enabled, onClick = { pickColor = true })
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.table_more_settings)) }
                if (advanced) OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it.take(4) },
                    label = { Text(stringResource(R.string.contractors_short_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), shortName.trim().ifBlank { name.trim().take(12) }, color) }, enabled = isValid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
