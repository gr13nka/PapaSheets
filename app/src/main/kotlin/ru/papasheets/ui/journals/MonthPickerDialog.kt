package ru.papasheets.ui.journals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import ru.papasheets.R

/** Компактные отступы кнопки месяца — дефолтные (24dp по бокам) не оставляют места для «Сентябрь» в 3 колонках. */
private val MonthButtonPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

/** Свой диалог выбора года и месяца: стрелки года + сетка 12 месяцев по-русски, дефолт — текущий месяц. */
@Composable
fun MonthPickerDialog(
    onDismiss: () -> Unit,
    onMonthSelected: (year: Int, month: Int) -> Unit,
) {
    val today = remember { LocalDate.now() }
    var year by remember { mutableIntStateOf(today.year) }
    val monthNames = stringArrayResource(R.array.month_names_nominative)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.month_picker_title)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { year -= 1 }) {
                        Text(text = "‹", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(text = year.toString(), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { year += 1 }) {
                        Text(text = "›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    items(12) { index ->
                        val month = index + 1
                        val isCurrent = year == today.year && month == today.monthValue
                        OutlinedButton(
                            onClick = { onMonthSelected(year, month) },
                            modifier = Modifier.padding(2.dp),
                            contentPadding = MonthButtonPadding,
                            colors = if (isCurrent) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                        ) {
                            Text(
                                text = monthNames[index],
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
