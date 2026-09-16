package ru.papasheets.ui.journals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import ru.papasheets.R
import ru.papasheets.data.db.dao.JournalWithStats
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.repo.TableStructure
import ru.papasheets.domain.buildGridModel
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.settings.GroupPreviewPane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTableScreen(viewModel: JournalListViewModel, journals: List<JournalWithStats>, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val today = remember { LocalDate.now() }
    val months = stringArrayResource(R.array.month_names_nominative)
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var month by rememberSaveable { mutableIntStateOf(today.monthValue) }
    var title by rememberSaveable { mutableStateOf("${months[month - 1]} $year") }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickMonth by rememberSaveable { mutableStateOf(false) }
    val busy by viewModel.busy.collectAsState()
    val failed by viewModel.createError.collectAsState()
    val groupName = stringResource(R.string.table_default_group)
    val description = stringResource(R.string.table_default_description)
    val structure by produceState<TableStructure?>(null, sourceId, groupName, description) {
        value = null
        val source = sourceId
        if (source != null) graph.tableStructureRepository.observe(source).collect { value = it }
        else value = TableStructure(
            listOf(ContractorEntity("preview-group", groupName, groupName, 0, 0, createdAt = 0, journalId = "preview")),
            listOf(FieldDefEntity("preview-field", description, description, 0, false, false, false, true, 168, 0, true, 0, "preview")),
        )
    }
    BackHandler { if (!busy) onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.table_new)) }, navigationIcon = {
            TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.action_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.table_name)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pickMonth = true }, enabled = !busy) { Text("${months[month - 1]} $year") }
            Text(stringResource(R.string.table_structure), style = MaterialTheme.typography.titleMedium)
            FilterChip(selected = sourceId == null, onClick = { sourceId = null }, enabled = !busy, label = { Text(stringResource(R.string.table_simple)) })
            if (journals.isNotEmpty()) Text(stringResource(R.string.table_reuse))
            val lastId = remember { graph.lastPlace.journalId() }
            journals.sortedWith(compareByDescending<JournalWithStats> { it.id == lastId }.thenByDescending { it.createdAt }).forEach { source ->
                FilterChip(selected = sourceId == source.id, onClick = { sourceId = source.id }, enabled = !busy,
                    label = { Text("${source.title} · ${months[source.month - 1]} ${source.year}") })
            }
            if (sourceId != null) Text(stringResource(R.string.table_copy_hint), style = MaterialTheme.typography.bodySmall)
            structure?.let { current ->
                val model = remember(current, year, month) {
                    buildGridModel(emptyList(), current.groups, current.fields.filter { !it.isArchived }, emptyMap(), calendarMonth = YearMonth.of(year, month))
                }
                GroupPreviewPane(model, Modifier.fillMaxWidth().height(220.dp))
            }
            if (failed) Text(stringResource(R.string.table_create_failed), color = MaterialTheme.colorScheme.error)
            Button(onClick = { viewModel.create(title, year, month, sourceId, groupName, description, onCreated) },
                enabled = !busy && title.isNotBlank() && structure != null, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp)) else Text(stringResource(R.string.table_create))
            }
        }
    }
    if (pickMonth) MonthPickerDialog(onDismiss = { pickMonth = false }, onMonthSelected = { y, m ->
        if (title == "${months[month - 1]} $year") title = "${months[m - 1]} $y"
        year = y; month = m; pickMonth = false
    })
}
