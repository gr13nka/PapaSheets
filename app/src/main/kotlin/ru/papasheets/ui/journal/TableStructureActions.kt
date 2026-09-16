package ru.papasheets.ui.journal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.papasheets.R
import ru.papasheets.data.repo.FieldDraft
import ru.papasheets.domain.buildGridModel
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.common.ContractorDialog
import ru.papasheets.ui.settings.GroupPreviewPane
import java.time.YearMonth

@Composable
fun TableStructureActions(journalId: String) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val structure by remember(journalId) { graph.tableStructureRepository.observe(journalId) }.collectAsState(initial = null)
    var addColumn by remember { mutableStateOf(false) }
    var addGroup by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun save(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); addColumn = false; addGroup = false
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                graph.appLog.e("TableStructure", "Could not save structure", e)
                android.widget.Toast.makeText(context, R.string.table_structure_failed, android.widget.Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = { name = ""; addColumn = true }) { Text(stringResource(R.string.table_add_column)) }
        TextButton(onClick = { addGroup = true }) { Text(stringResource(R.string.table_add_group)) }
    }
    if (addColumn) AlertDialog(
        onDismissRequest = { if (!busy) addColumn = false },
        title = { Text(stringResource(R.string.table_add_column)) },
        text = { Column {
            OutlinedTextField(name, { name = it }, singleLine = true, enabled = !busy, label = { Text(stringResource(R.string.fields_label_label)) })
            Text(stringResource(R.string.table_columns_scope), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = { save {
            graph.fieldRepository.create(journalId, FieldDraft(name, name, 168, 0, false, true, true))
        } }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { addColumn = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (addGroup) ContractorDialog(
        initialName = "", initialShortName = "", titleRes = R.string.table_add_group,
        onDismiss = { if (!busy) addGroup = false },
        enabled = !busy,
        initialColorIndex = structure?.groups?.size ?: 0,
        onConfirm = { groupName, shortName, color -> save { graph.contractorRepository.create(journalId, groupName, shortName, color) } },
        preview = { groupName -> structure?.let { current ->
            val groups = current.groups + ru.papasheets.data.db.entity.ContractorEntity("new-preview", groupName.ifBlank { "…" }, groupName, current.groups.size, current.groups.size, createdAt = 0, journalId = journalId)
            GroupPreviewPane(buildGridModel(emptyList(), groups, current.fields.filter { !it.isArchived }, emptyMap(), calendarMonth = YearMonth.now()), Modifier.fillMaxWidth().height(140.dp))
        } },
    )
}
