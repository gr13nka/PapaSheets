package ru.papasheets.ui.daylist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.LocalDate
import ru.papasheets.R
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.common.ContractorColors
import ru.papasheets.ui.record.RecordSheet
import ru.papasheets.ui.record.RecordSheetMode

/**
 * Временный экран журнала до матрицы (M3): плоский список записей по дням, но с полноценным CRUD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayListScreen(journalId: String, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: DayListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DayListViewModel(
                    journalId = journalId,
                    journalRepository = graph.journalRepository,
                    recordRepository = graph.recordRepository,
                    contractorRepository = graph.contractorRepository,
                )
            }
        },
    )
    val journal by viewModel.journal.collectAsState()
    val dayGroups by viewModel.dayGroups.collectAsState()
    var sheetMode by remember { mutableStateOf<RecordSheetMode?>(null) }
    var recordPendingDelete by remember { mutableStateOf<RecordEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(journal?.title ?: "") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { sheetMode = RecordSheetMode.Create(journalId, defaultDateFor(journal)) },
            ) {
                Text(stringResource(R.string.day_list_add_record))
            }
        },
    ) { padding ->
        if (dayGroups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.day_list_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dayGroups.forEach { group ->
                    item(key = "header-${group.dateEpochDay}") {
                        Text(
                            text = dayHeaderText(group.dateEpochDay),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(group.records, key = { it.record.id }) { entry ->
                        RecordCard(
                            entry = entry,
                            onClick = { sheetMode = RecordSheetMode.Edit(entry.record.id) },
                            onLongClick = { recordPendingDelete = entry.record },
                        )
                    }
                }
            }
        }
    }

    sheetMode?.let { mode ->
        RecordSheet(
            mode = mode,
            onDismiss = { sheetMode = null },
            onSaved = { sheetMode = null },
        )
    }

    recordPendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { recordPendingDelete = null },
            title = { Text(stringResource(R.string.record_delete_confirm_title)) },
            text = { Text(stringResource(R.string.record_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecord(record)
                    recordPendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { recordPendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Сегодня — если журнал открыт на текущий месяц, иначе первое число месяца журнала. */
private fun defaultDateFor(journal: JournalEntity?): LocalDate {
    val today = LocalDate.now()
    if (journal == null) return today
    return if (journal.year == today.year && journal.month == today.monthValue) {
        today
    } else {
        LocalDate.of(journal.year, journal.month, 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordCard(entry: RecordWithContractor, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(ContractorColors.forIndex(entry.contractor?.colorIndex ?: 0)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.contractor?.shortName ?: "?",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (entry.record.locationCode.isNotBlank()) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(entry.record.locationCode) },
                            colors = SuggestionChipDefaults.suggestionChipColors(),
                        )
                    }
                }
                Text(
                    text = entry.record.workText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun dayHeaderText(epochDay: Long): String {
    val date = remember(epochDay) { LocalDate.ofEpochDay(epochDay) }
    val monthsGenitive = stringArrayResource(R.array.month_names_genitive)
    val weekdays = stringArrayResource(R.array.weekday_names_nominative)
    return "${date.dayOfMonth} ${monthsGenitive[date.monthValue - 1]}, ${weekdays[date.dayOfWeek.value - 1]}"
}
