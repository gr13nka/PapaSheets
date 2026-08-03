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
    onConfirm: (name: String, shortName: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var shortName by remember { mutableStateOf(initialShortName) }
    val isValid = name.isNotBlank() && shortName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.contractors_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it.take(4) },
                    label = { Text(stringResource(R.string.contractors_short_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), shortName.trim()) }, enabled = isValid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
