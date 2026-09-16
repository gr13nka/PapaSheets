package ru.papasheets.ui.journals

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import ru.papasheets.BuildConfig
import ru.papasheets.R
import ru.papasheets.data.FakeDataSeeder
import ru.papasheets.data.db.dao.JournalWithStats
import ru.papasheets.ui.LocalAppGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalListScreen(
    onOpenJournal: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel: JournalListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                JournalListViewModel(graph.journalRepository, graph.deleteJournalInteractor, graph.tableStructureRepository)
            }
        },
    )
    val journals by viewModel.journals.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var showMonthPicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var journalPendingDelete by remember { mutableStateOf<JournalWithStats?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.deleteFailed.collect {
            Toast.makeText(context, context.getString(R.string.journal_delete_failed), Toast.LENGTH_LONG).show()
        }
    }

    // Долгое нажатие на FAB в debug-сборке засевает нагрузочный журнал (28×~300) и открывает его.
    val seedFakeData: (() -> Unit)? = if (BuildConfig.DEBUG) {
        {
            scope.launch {
                val journalId = FakeDataSeeder(
                    contractorRepository = graph.contractorRepository,
                    journalRepository = graph.journalRepository,
                    recordRepository = graph.recordRepository,
                    fieldRepository = graph.fieldRepository,
                ).seedAndGetJournalId()
                onOpenJournal(journalId)
            }
        }
    } else {
        null
    }

    if (showMonthPicker) {
        NewTableScreen(viewModel, journals, onBack = { showMonthPicker = false }, onCreated = { id ->
            showMonthPicker = false
            onOpenJournal(id)
        })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journals_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            NewJournalFab(onClick = { showMonthPicker = true }, onLongPress = seedFakeData)
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
                    JournalCard(
                        journal = journal,
                        onClick = { onOpenJournal(journal.id) },
                        onLongClick = { journalPendingDelete = journal },
                    )
                }
            }
        }
    }


    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(text = stringResource(R.string.backup_busy), modifier = Modifier.padding(start = 12.dp))
                }
            },
        )
    }

    journalPendingDelete?.let { journal ->
        AlertDialog(
            onDismissRequest = { journalPendingDelete = null },
            title = { Text(stringResource(R.string.journal_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.journal_delete_confirm_message, journal.title, journal.recordCount))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteJournal(journal.id)
                    journalPendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { journalPendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * FAB создания журнала. Когда задан [onLongPress] (debug-сидинг), рисуется через Surface с одним
 * combinedClickable — чтобы тап и долгое нажатие обрабатывал один детектор, а не конкурировали
 * внутренний clickable у FloatingActionButton и внешний обработчик.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewJournalFab(onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    if (onLongPress == null) {
        FloatingActionButton(onClick = onClick) { Text("+") }
    } else {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(56.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JournalCard(journal: JournalWithStats, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = journal.title, style = MaterialTheme.typography.titleMedium)
            val months = androidx.compose.ui.res.stringArrayResource(R.array.month_names_nominative)
            Text("${months[journal.month - 1]} ${journal.year}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = pluralStringResource(R.plurals.journal_records_count, journal.recordCount, journal.recordCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
