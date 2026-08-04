package ru.papasheets.ui.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.papasheets.R

/**
 * Свёрнутая запись, закреплённая внизу экрана.
 *
 * Появляется, когда форму смахнули вниз или свернули шевроном. Сама запись к этому моменту уже
 * в БД (см. `RecordSheet`), поэтому полоска — не хранилище черновика, а закладка: способ вернуться
 * к начатому, не разыскивая его в матрице. Отсюда и «×»: он ничего не теряет, а просто убирает
 * закладку — потому и не спрашивает подтверждения.
 *
 * Сводка приходит готовой строкой, а не собирается здесь из записи: собрать её может только форма,
 * которая знает и подрядчика, и порядок полей, а второй источник тех же данных разошёлся бы с первым.
 */
@Composable
fun MinimizedRecordBar(summary: String, onExpand: () -> Unit, onClose: () -> Unit) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .navigationBarsPadding()
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.record_minimized_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.record_minimized_close),
                )
            }
        }
    }
}
