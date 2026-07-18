package ru.papasheets.ui.journals

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import ru.papasheets.domain.backup.BackupImportResult
import ru.papasheets.domain.backup.MergeStats
import ru.papasheets.ui.LocalAppGraph

private const val MIME_BACKUP = "application/octet-stream"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalListScreen(
    onOpenJournal: (String) -> Unit,
    onOpenContractors: () -> Unit,
    onOpenLocations: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel: JournalListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                JournalListViewModel(
                    graph.journalRepository, graph.backupInteractor, graph.importInteractor,
                    graph.deleteJournalInteractor,
                )
            }
        },
    )
    val journals by viewModel.journals.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var showMonthPicker by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<BackupImportResult?>(null) }
    var journalPendingDelete by remember { mutableStateOf<JournalWithStats?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_BACKUP)) { uri ->
        if (uri != null) viewModel.backupTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupUiEvent.BackupDone -> {
                    val message = if (event.skippedPhotoFiles > 0) {
                        context.getString(R.string.backup_saved_photos_skipped, event.skippedPhotoFiles)
                    } else {
                        context.getString(R.string.backup_saved)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                is BackupUiEvent.BackupFailed -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                is BackupUiEvent.ImportDone -> importResult = event.result
                is BackupUiEvent.ImportFailed -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                is BackupUiEvent.DeleteFailed -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
            }
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
                ).seedAndGetJournalId()
                onOpenJournal(journalId)
            }
        }
    } else {
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journals_title)) },
                actions = {
                    var menuExpanded by remember { mutableStateOf(false) }
                    TextButton(onClick = { menuExpanded = true }) { Text("⋮") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_contractors)) },
                            onClick = { menuExpanded = false; onOpenContractors() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_locations)) },
                            onClick = { menuExpanded = false; onOpenLocations() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.backup_menu_save)) },
                            onClick = { menuExpanded = false; backupLauncher.launch(viewModel.defaultBackupFileName()) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.backup_menu_import)) },
                            onClick = { menuExpanded = false; importLauncher.launch(arrayOf("*/*")) },
                        )
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

    if (showMonthPicker) {
        MonthPickerDialog(
            onDismiss = { showMonthPicker = false },
            onMonthSelected = { year, month ->
                showMonthPicker = false
                viewModel.openOrCreateJournal(year, month, onReady = onOpenJournal)
            },
        )
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

    importResult?.let { result ->
        ImportResultDialog(result = result, onDismiss = { importResult = null })
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

/** Диалог с итогом импорта: по строке на таблицу — добавлено/обновлено/пропущено (см. [BackupImportResult]). */
@Composable
private fun ImportResultDialog(result: BackupImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_result_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImportResultRow(R.string.import_result_journals, result.journals)
                ImportResultRow(R.string.import_result_contractors, result.contractors)
                ImportResultRow(R.string.import_result_presets, result.locationPresets)
                ImportResultRow(R.string.import_result_records, result.records)
                ImportResultRow(R.string.import_result_photos, result.photos)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun ImportResultRow(labelRes: Int, stats: MergeStats) {
    Text(stringResource(R.string.import_result_row, stringResource(labelRes), stats.added, stats.updated, stats.skipped))
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
            Text(
                text = pluralStringResource(R.plurals.journal_records_count, journal.recordCount, journal.recordCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
