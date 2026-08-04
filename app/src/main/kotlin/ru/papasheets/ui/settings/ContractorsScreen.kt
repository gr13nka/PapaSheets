package ru.papasheets.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.papasheets.R
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.matrixgrid.MatrixPalette
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.common.ContractorDialog

/** Высота строки подрядчика — общая для активного списка (нужна drag'у для перевода px в позиции) и архива. */
private val ContractorRowHeight = 64.dp

/**
 * Настройки → Подрядчики: список активных в порядке orderIndex с drag-reorder (перетаскивание за
 * ручку "≡"), архив — свёрнутая секция внизу. Порядок колонок матрицы следует orderIndex автоматически
 * ([ru.papasheets.domain.buildGridModel] сортирует по нему же).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractorsScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: ContractorsViewModel = viewModel(
        factory = viewModelFactory { initializer { ContractorsViewModel(graph.contractorRepository) } },
    )
    val contractors by viewModel.contractors.collectAsState()
    val active = remember(contractors) { contractors.filter { !it.isArchived }.sortedBy { it.orderIndex } }
    val archived = remember(contractors) { contractors.filter { it.isArchived }.sortedBy { it.orderIndex } }

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ContractorEntity?>(null) }
    var archivedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contractors_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Text("+") }
        },
    ) { padding ->
        if (contractors.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.contractors_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                DragReorderColumn(
                    items = active,
                    key = ContractorEntity::id,
                    rowHeight = ContractorRowHeight,
                    onReorder = viewModel::reorder,
                ) { contractor, dragHandle ->
                    ContractorRow(
                        contractor = contractor,
                        dimmed = false,
                        onClick = { editing = contractor },
                        trailingLabel = stringResource(R.string.contractors_archive_action),
                        onTrailingClick = { viewModel.setArchived(contractor, true) },
                        dragHandle = dragHandle,
                    )
                }
                if (archived.isNotEmpty()) {
                    TextButton(
                        onClick = { archivedExpanded = !archivedExpanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.contractors_archived_section, archived.size))
                    }
                    if (archivedExpanded) {
                        archived.forEach { contractor ->
                            ContractorRow(
                                contractor = contractor,
                                dimmed = true,
                                onClick = { editing = contractor },
                                trailingLabel = stringResource(R.string.contractors_unarchive_action),
                                onTrailingClick = { viewModel.setArchived(contractor, false) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ContractorDialog(
            initialName = "",
            initialShortName = "",
            titleRes = R.string.contractors_add_title,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, shortName ->
                viewModel.add(name, shortName)
                showAddDialog = false
            },
        )
    }

    editing?.let { contractor ->
        ContractorDialog(
            initialName = contractor.name,
            initialShortName = contractor.shortName,
            titleRes = R.string.contractors_edit_title,
            onDismiss = { editing = null },
            onConfirm = { name, shortName ->
                viewModel.rename(contractor, name, shortName)
                editing = null
            },
        )
    }
}

@Composable
private fun ContractorRow(
    contractor: ContractorEntity,
    dimmed: Boolean,
    onClick: () -> Unit,
    trailingLabel: String,
    onTrailingClick: () -> Unit,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val textColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ContractorRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dragHandle != null) dragHandle() else Box(modifier = Modifier.size(20.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MatrixPalette.color(contractor.colorIndex, isSystemInDarkTheme())),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(contractor.name, style = MaterialTheme.typography.bodyLarge, color = textColor)
            Text(contractor.shortName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onTrailingClick) { Text(trailingLabel) }
    }
}
