package ru.papasheets.ui.journal

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.LocalDate
import java.util.UUID
import ru.papasheets.R
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.domain.ViewMode
import ru.papasheets.matrixgrid.MatrixCallbacks
import ru.papasheets.matrixgrid.MatrixView
import ru.papasheets.matrixgrid.rememberMatrixState
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.export.ExportDialog
import ru.papasheets.ui.record.RecordSheet
import ru.papasheets.ui.record.RecordSheetMode
import ru.papasheets.ui.record.RecordSheetModeSaver

/**
 * Главный экран журнала. Показывает одну и ту же (отфильтрованную) выборку записей двумя видами:
 * зумируемой матрицей «дата × подрядчик» и плоским списком.
 *
 * Виды дополняют друг друга, а не дублируются. Матрица отвечает на «что делали в этот день» и
 * отсортирована по дате по своей природе — сортировать её по локации нельзя, не разрушив саму сетку.
 * Список отвечает на «где мы вообще работали»: он сортируется по любому столбцу и показывает текст
 * ячеек целиком, без фиксированных высот (второй способ выполнить требование «весь текст должен быть
 * виден», дополняющий автовысоту строк матрицы). Фильтр общий: он применяется до раскладки, поэтому
 * переключение вида не может изменить набор записей.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    journalId: String,
    onBack: () -> Unit,
    onOpenLightbox: (recordId: String, slot: Int) -> Unit,
    onOpenContractors: () -> Unit,
    onOpenFields: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel: JournalViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                JournalViewModel(
                    journalId = journalId,
                    journalRepository = graph.journalRepository,
                    recordRepository = graph.recordRepository,
                    contractorRepository = graph.contractorRepository,
                    fieldRepository = graph.fieldRepository,
                    valueSuggester = graph.valueSuggester,
                    photoStore = graph.photoStore,
                    exportInteractor = graph.exportInteractor,
                    appContext = graph.appContext,
                )
            }
        },
    )
    val journal by viewModel.journal.collectAsState()
    val content by viewModel.content.collectAsState()
    val query by viewModel.query.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val matrixState = rememberMatrixState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var sheetMode by rememberSaveable(stateSaver = RecordSheetModeSaver) { mutableStateOf<RecordSheetMode?>(null) }
    var recordPendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.exportEvents.collect { event ->
            showExportDialog = false
            val message = when (event) {
                is ExportEvent.Success -> context.getString(R.string.export_success)
                is ExportEvent.Failure -> event.message
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Стабильный экземпляр колбэков: детектор жестов в MatrixView не пересоздаётся на рекомпозиции,
    // поэтому его нельзя «кормить» новым объектом каждый кадр — иначе он держал бы устаревшие лямбды.
    val onOpenLightboxState = rememberUpdatedState(onOpenLightbox)
    val callbacks = remember {
        object : MatrixCallbacks {
            override fun onCellTap(recordId: String) {
                sheetMode = RecordSheetMode.Edit(recordId)
            }

            override fun onPhotoTap(recordId: String, slot: Int) {
                onOpenLightboxState.value(recordId, slot)
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
            // Полоса режима отдельной строкой под шапкой, а не среди actions: в шапке уже четыре
            // элемента, и втиснутый туда переключатель вида съел бы название журнала. Заодно вид и
            // фильтр — два состояния всего экрана — стоят рядом и читаются как одна панель.
            Column {
                TopAppBar(
                    title = { Text(journal?.title ?: "") },
                    navigationIcon = {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    },
                    actions = {
                        // Сортировка дат и обзор — органы управления матрицы: в списке порядок задают
                        // тапом по шапке столбца, а зума там нет вовсе.
                        if (query.viewMode == ViewMode.MATRIX) {
                            // Метка — текущий порядок дат сверху вниз (не целевой, в отличие от кнопки
                            // обзора ниже): «↓» — новые сверху, «↑» — старые сверху. Перестройка раскладки
                            // меняет порядок строк местами, поэтому pan сбрасывается к началу мира.
                            TextButton(
                                onClick = {
                                    viewModel.toggleDateOrder()
                                    matrixState.jumpToStart()
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (query.sort.matrixDatesDesc) R.string.matrix_sort_desc
                                        else R.string.matrix_sort_asc,
                                    ),
                                )
                            }

                            // Кнопка обзора: тот же код-путь, что double-tap. «Месяц» уводит в fit («вся
                            // картина месяца»), «1:1» возвращает к детальному зуму. Метка следит за ярусом.
                            if (content?.grid?.rows?.isNotEmpty() == true) {
                                TextButton(onClick = { matrixState.toggleOverview(scope) }) {
                                    Text(
                                        stringResource(
                                            if (matrixState.isOverview) R.string.matrix_zoom_detail
                                            else R.string.matrix_zoom_overview,
                                        ),
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.matrix_menu_action),
                            )
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.matrix_menu_export)) },
                                onClick = {
                                    menuExpanded = false
                                    showExportDialog = true
                                },
                            )
                            HorizontalDivider()
                            // Настройка таблицы — отсюда же, из таблицы: искать её на экране списка
                            // журналов неоткуда догадаться, когда правишь колонки прямо перед глазами.
                            MenuItemWithHint(
                                title = stringResource(R.string.settings_contractors),
                                hint = stringResource(R.string.settings_contractors_hint),
                                onClick = {
                                    menuExpanded = false
                                    onOpenContractors()
                                },
                            )
                            MenuItemWithHint(
                                title = stringResource(R.string.settings_fields),
                                hint = stringResource(R.string.settings_fields_hint),
                                onClick = {
                                    menuExpanded = false
                                    onOpenFields()
                                },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(),
                )
                ModeBar(
                    viewMode = query.viewMode,
                    filterConditions = query.filter.conditionCount,
                    onSelectViewMode = viewModel::setViewMode,
                    onOpenFilter = {
                        viewModel.refreshFilterOptions()
                        showFilterSheet = true
                    },
                )
            }
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
            val model = content
            when {
                model == null -> CircularProgressIndicator()
                model.isEmpty -> Text(
                    text = stringResource(
                        if (query.filter.isEmpty) R.string.day_list_empty else R.string.journal_filter_empty,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                query.viewMode == ViewMode.LIST -> RecordList(
                    columns = model.columns,
                    rows = model.rows,
                    sort = query.sort,
                    photoStore = graph.photoStore,
                    onSortBy = viewModel::sortBy,
                    onRecordClick = { sheetMode = RecordSheetMode.Edit(it) },
                    onRecordLongClick = { recordPendingDelete = it },
                    // Плоский debug-список показывает только первое фото записи — открываем его же.
                    onPhotoClick = { onOpenLightbox(it, 0) },
                )
                else -> MatrixView(
                    model = model.grid,
                    state = matrixState,
                    thumbnails = viewModel.thumbnails,
                    callbacks = callbacks,
                )
            }
        }
    }

    if (showFilterSheet) {
        val contractors by viewModel.contractors.collectAsState()
        val fields by viewModel.fields.collectAsState()
        val options by viewModel.filterOptions.collectAsState()
        FilterSheet(
            filter = query.filter,
            contractors = contractors,
            fields = fields,
            valueOptions = options,
            onFilterChange = viewModel::setFilter,
            onClear = viewModel::clearFilter,
            onDismiss = { showFilterSheet = false },
        )
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

    if (showExportDialog) {
        val exportFolderName by viewModel.exportFolderName.collectAsState()
        // Диалог открылся — перечитываем папку: доступ к ней мог отвалиться с прошлого раза.
        LaunchedEffect(Unit) { viewModel.refreshExportFolder() }
        ExportDialog(
            folderName = exportFolderName,
            exporting = exporting,
            filterActive = !query.filter.isEmpty,
            onFolderChosen = viewModel::onExportFolderChosen,
            onExport = { format ->
                viewModel.exportTo(format)
                showExportDialog = false
            },
            onDismiss = { showExportDialog = false },
        )
    }
}

/**
 * Полоса под шапкой: чем показан журнал и чем он ограничен. Оба состояния относятся к экрану целиком,
 * поэтому стоят рядом и одинаково выглядят — включённый вид и включённый фильтр читаются одним взглядом.
 *
 * Число условий стоит прямо в метке фильтра: молчаливо отфильтрованный журнал легко принять за
 * потерянные записи, поэтому «фильтр включён» обязано быть видно, не открывая панель. Иконок в проекте
 * нет — чипы текстовые.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeBar(
    viewMode: ViewMode,
    filterConditions: Int,
    onSelectViewMode: (ViewMode) -> Unit,
    onOpenFilter: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = viewMode == ViewMode.MATRIX,
            onClick = { onSelectViewMode(ViewMode.MATRIX) },
            label = { Text(stringResource(R.string.journal_view_matrix)) },
        )
        FilterChip(
            selected = viewMode == ViewMode.LIST,
            onClick = { onSelectViewMode(ViewMode.LIST) },
            label = { Text(stringResource(R.string.journal_view_list)) },
        )
        Spacer(modifier = Modifier.weight(1f))
        FilterChip(
            selected = filterConditions > 0,
            onClick = onOpenFilter,
            label = {
                Text(
                    if (filterConditions == 0) stringResource(R.string.journal_filter)
                    else stringResource(R.string.journal_filter_active, filterConditions),
                )
            },
        )
    }
}

/**
 * Пункт меню с пояснением под названием.
 *
 * «Подрядчики» и «Поля» — слова из головы разработчика: по ним не видно, что первое задаёт большие
 * колонки таблицы, а второе — подколонки внутри каждой. Подпись снимает этот вопрос на месте,
 * вместо того чтобы заставлять зайти и посмотреть.
 */
@Composable
private fun MenuItemWithHint(title: String, hint: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
    )
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
