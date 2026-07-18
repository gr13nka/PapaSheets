package ru.papasheets.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papasheets.R
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.domain.JournalFilter

/**
 * Панель фильтра журнала: подрядчики, значения по каждому полю и поиск подстрокой.
 *
 * Панель ничего не отбирает сама — она только собирает [JournalFilter] и отдаёт его наверх, где его
 * применяет единственная в проекте фильтрация ([ru.papasheets.domain.applyFilter]). Изменения
 * применяются сразу, без кнопки «Применить»: результат виден за панелью, и подтверждать нечего.
 *
 * Значения полей приходят готовыми ([valueOptions]) — это то, что в поле уже написано; вариантов,
 * дающих заведомо пустой экран, здесь не предлагается (см. [ru.papasheets.data.repo.ValueSuggester.usedValues]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filter: JournalFilter,
    contractors: List<ContractorEntity>,
    fields: List<FieldDefEntity>,
    valueOptions: Map<String, List<String>>,
    onFilterChange: (JournalFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.journal_filter), style = MaterialTheme.typography.titleMedium)
                if (!filter.isEmpty) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.journal_filter_clear)) }
                }
            }

            OutlinedTextField(
                value = filter.query,
                onValueChange = { onFilterChange(filter.copy(query = it)) },
                label = { Text(stringResource(R.string.journal_filter_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (contractors.isNotEmpty()) {
                Section(title = stringResource(R.string.record_contractor_label)) {
                    contractors.forEach { contractor ->
                        ToggleChip(
                            label = contractor.name,
                            selected = contractor.id in filter.contractorIds,
                            onToggle = {
                                onFilterChange(filter.copy(contractorIds = filter.contractorIds.toggled(contractor.id)))
                            },
                        )
                    }
                }
            }

            fields.forEach { field ->
                val options = valueOptions[field.id].orEmpty()
                if (options.isEmpty()) return@forEach
                val selected = filter.values[field.id].orEmpty()
                Section(title = field.label) {
                    options.forEach { value ->
                        ToggleChip(
                            label = value,
                            selected = value in selected,
                            onToggle = {
                                onFilterChange(
                                    filter.copy(values = filter.values + (field.id to selected.toggled(value))),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToggleChip(label: String, selected: Boolean, onToggle: () -> Unit) {
    FilterChip(selected = selected, onClick = onToggle, label = { Text(label) })
}

/** Мультивыбор: повторный тап по выбранному значению снимает выбор. */
private fun Set<String>.toggled(value: String): Set<String> =
    if (value in this) this - value else this + value
