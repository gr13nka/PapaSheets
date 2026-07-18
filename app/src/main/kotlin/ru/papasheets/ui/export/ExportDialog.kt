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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papasheets.R
import ru.papasheets.domain.export.ExportFormat

private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MIME_CSV = "text/csv"

/**
 * Диалог экспорта журнала: выбор формата → системный SAF `CreateDocument` → прогресс. Сам решает,
 * с каким MIME-типом и именем открыть системный пикер; фактическую запись делает [onExport]
 * (VM/Interactor) — диалог только собирает выбор пользователя и показывает [exporting].
 */
@Composable
fun ExportDialog(
    defaultFileName: (ExportFormat) -> String,
    exporting: Boolean,
    onExport: (ExportFormat, Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingFormat by remember { mutableStateOf<ExportFormat?>(null) }

    val xlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_XLSX)) { uri ->
        val format = pendingFormat
        pendingFormat = null
        if (uri != null && format != null) onExport(format, uri)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_CSV)) { uri ->
        pendingFormat = null
        if (uri != null) onExport(ExportFormat.CSV, uri)
    }

    fun launch(format: ExportFormat) {
        pendingFormat = format
        val fileName = defaultFileName(format)
        if (format == ExportFormat.CSV) csvLauncher.launch(fileName) else xlsxLauncher.launch(fileName)
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
                    TextButton(onClick = { launch(ExportFormat.XLSX_WITH_PHOTOS) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_option_xlsx_photos))
                    }
                    TextButton(onClick = { launch(ExportFormat.XLSX_NO_PHOTOS) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_option_xlsx_no_photos))
                    }
                    TextButton(onClick = { launch(ExportFormat.CSV) }, modifier = Modifier.fillMaxWidth()) {
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
