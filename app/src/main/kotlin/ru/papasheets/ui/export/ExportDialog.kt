package ru.papasheets.ui.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papasheets.R
import ru.papasheets.domain.export.ExportFormat

/**
 * Диалог экспорта журнала: папка (выбранная однажды) → формат → перезапись файла → прогресс.
 *
 * Файл всегда один и тот же («Июль 2026.xlsx»), поэтому системного пикера имени тут больше нет:
 * прораб один раз выбирает папку ([ActivityResultContracts.OpenDocumentTree]), а дальше видит, как
 * тот же файл обновляется. Пока папка не выбрана, форматы недоступны — писать некуда; фактическую
 * запись (и уход прежней версии в архив) делает [onExport] через [ru.papasheets.domain.export.ExportFolder].
 *
 * @param folderName имя выбранной папки, или `null` — папка не выбрана / доступ к ней потерян.
 * @param filterActive на экране включён фильтр. Выгрузка его не учитывает (см.
 *   [ru.papasheets.domain.export.buildJournalSnapshot]), и об этом надо предупредить здесь: без
 *   предупреждения прораб ждёт в файле ровно то, что видит на экране, а получает весь журнал.
 */
@Composable
fun ExportDialog(
    folderName: String?,
    exporting: Boolean,
    filterActive: Boolean,
    onFolderChosen: (Uri) -> Unit,
    onExport: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderChosen(uri)
    }

    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        title = { Text(stringResource(R.string.export_dialog_title)) },
        text = {
            if (exporting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = stringResource(R.string.export_in_progress),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (filterActive) {
                        Text(
                            text = stringResource(R.string.export_filter_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Text(
                        text = folderName?.let { stringResource(R.string.export_folder_current, it) }
                            ?: stringResource(R.string.export_folder_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { folderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (folderName == null) R.string.export_choose_folder else R.string.export_change_folder,
                            ),
                        )
                    }

                    val folderReady = folderName != null
                    TextButton(
                        onClick = { onExport(ExportFormat.XLSX_WITH_PHOTOS) },
                        enabled = folderReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.export_option_xlsx_photos))
                    }
                    TextButton(
                        onClick = { onExport(ExportFormat.CSV) },
                        enabled = folderReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.export_option_csv))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!exporting) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
