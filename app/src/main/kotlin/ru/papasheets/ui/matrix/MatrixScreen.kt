package ru.papasheets.ui.matrix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.LocalDate
import java.util.UUID
import ru.papasheets.R
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.matrixgrid.MatrixCallbacks
import ru.papasheets.matrixgrid.MatrixView
import ru.papasheets.matrixgrid.rememberMatrixState
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.record.RecordSheet
import ru.papasheets.ui.record.RecordSheetMode
import ru.papasheets.ui.record.RecordSheetModeSaver

/**
 * Главный экран журнала: зумируемая матрица во весь экран. Тапы по ячейкам ведут в форму записи
 * или лайтбокс, тап по пустому слоту создаёт запись с предзаполненными датой и подрядчиком,
 * долгое нажатие предлагает удаление.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrixScreen(journalId: String, onBack: () -> Unit, onOpenLightbox: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: MatrixViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                MatrixViewModel(
                    journalId = journalId,
                    journalRepository = graph.journalRepository,
                    recordRepository = graph.recordRepository,
                    contractorRepository = graph.contractorRepository,
                    photoStore = graph.photoStore,
                )
            }
        },
    )
    val journal by viewModel.journal.collectAsState()
    val gridModel by viewModel.gridModel.collectAsState()
    val matrixState = rememberMatrixState()

    var sheetMode by rememberSaveable(stateSaver = RecordSheetModeSaver) { mutableStateOf<RecordSheetMode?>(null) }
    var recordPendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    // Стабильный экземпляр колбэков: детектор жестов в MatrixView не пересоздаётся на рекомпозиции,
    // поэтому его нельзя «кормить» новым объектом каждый кадр — иначе он держал бы устаревшие лямбды.
    val onOpenLightboxState = rememberUpdatedState(onOpenLightbox)
    val callbacks = remember {
        object : MatrixCallbacks {
            override fun onCellTap(recordId: String) {
                sheetMode = RecordSheetMode.Edit(recordId)
            }

            override fun onPhotoTap(recordId: String) {
                onOpenLightboxState.value(recordId)
            }

            override fun onEmptySlotTap(dateEpochDay: Long, contractorId: String) {
                sheetMode = RecordSheetMode.Create(
                    journalId = journalId,
                    defaultDate = LocalDate.ofEpochDay(dateEpochDay),
                    sessionId = UUID.randomUUID().toString(),
                    contractorId = contractorId,
                )
            }

            override fun onCellLongPress(recordId: String) {
                recordPendingDelete = recordId
            }
        }
    }

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
                onClick = {
                    sheetMode = RecordSheetMode.Create(
                        journalId = journalId,
                        defaultDate = defaultDateFor(journal),
                        sessionId = UUID.randomUUID().toString(),
                    )
                },
            ) {
                Text(stringResource(R.string.day_list_add_record))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val model = gridModel
            when {
                model == null -> CircularProgressIndicator()
                model.rows.isEmpty() -> Text(
                    text = stringResource(R.string.day_list_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> MatrixView(
                    model = model,
                    state = matrixState,
                    thumbnails = viewModel.thumbnails,
                    callbacks = callbacks,
                )
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

    recordPendingDelete?.let { recordId ->
        AlertDialog(
            onDismissRequest = { recordPendingDelete = null },
            title = { Text(stringResource(R.string.record_delete_confirm_title)) },
            text = { Text(stringResource(R.string.record_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecord(recordId)
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

/** Сегодня — если журнал открыт на текущий месяц, иначе первое число месяца журнала (правило M1). */
private fun defaultDateFor(journal: JournalEntity?): LocalDate {
    val today = LocalDate.now()
    if (journal == null) return today
    return if (journal.year == today.year && journal.month == today.monthValue) {
        today
    } else {
        LocalDate.of(journal.year, journal.month, 1)
    }
}
