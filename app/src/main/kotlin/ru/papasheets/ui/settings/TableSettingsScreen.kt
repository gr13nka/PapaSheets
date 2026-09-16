package ru.papasheets.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papasheets.R
import ru.papasheets.ui.common.SettingsRow

/**
 * «Настройки таблицы»: развилка на два редактора структуры — группы колонок (подрядчики) и поля
 * внутри группы. Своего ViewModel нет: экран сам не хранит и не читает данные, только переходит.
 *
 * Обе цели общие для всех журналов (подрядчики и `field_defs` не привязаны к journalId), а не для
 * того, который открыт сейчас, — отсюда заметка сверху: без неё «настройки таблицы» естественно
 * прочитать как настройки именно этого месяца.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableSettingsScreen(
    journalId: String,
    onBack: () -> Unit,
    onOpenColumnGroups: () -> Unit,
    onOpenGroupSettings: () -> Unit,
) {
    val graph = ru.papasheets.ui.LocalAppGraph.current
    val journal by graph.journalRepository.observeById(journalId).collectAsState(initial = null)
    var renaming by remember { mutableStateOf(false) }
    var name by remember(journal?.title) { mutableStateOf(journal?.title.orEmpty()) }
    val scope = rememberCoroutineScope()
    if (renaming) AlertDialog(
        onDismissRequest = { renaming = false },
        title = { Text(stringResource(R.string.table_rename)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            scope.launch { graph.journalRepository.rename(journalId, name); renaming = false }
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.table_settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Text(
                text = stringResource(R.string.table_columns_scope),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            SettingsRow(title = stringResource(R.string.table_rename), subtitle = journal?.title.orEmpty(), onClick = { renaming = true })
            SettingsRow(
                title = stringResource(R.string.table_settings_column_groups),
                subtitle = stringResource(R.string.table_settings_column_groups_hint),
                onClick = onOpenColumnGroups,
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.table_settings_group_fields),
                subtitle = stringResource(R.string.table_settings_group_fields_hint),
                onClick = onOpenGroupSettings,
            )
        }
    }
}
