package ru.papasheets.ui.journals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.papasheets.R
import ru.papasheets.data.db.dao.JournalWithStats
import ru.papasheets.ui.LocalAppGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalListScreen(onOpenJournal: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: JournalListViewModel = viewModel(
        factory = viewModelFactory {
            initializer { JournalListViewModel(graph.journalRepository) }
        },
    )
    val journals by viewModel.journals.collectAsState()
    var showMonthPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.journals_title)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showMonthPicker = true }) {
                Text("+")
            }
        },
    ) { padding ->
        if (journals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.journals_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(journals, key = JournalWithStats::id) { journal ->
                    JournalCard(journal = journal, onClick = { onOpenJournal(journal.id) })
                }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            onDismiss = { showMonthPicker = false },
            onMonthSelected = { year, month ->
                showMonthPicker = false
                viewModel.openOrCreateJournal(year, month, onReady = onOpenJournal)
            },
        )
    }
}

@Composable
private fun JournalCard(journal: JournalWithStats, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = journal.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = pluralStringResource(R.plurals.journal_records_count, journal.recordCount, journal.recordCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
