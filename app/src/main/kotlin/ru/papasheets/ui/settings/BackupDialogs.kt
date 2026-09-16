package ru.papasheets.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papasheets.R
import ru.papasheets.domain.backup.BackupImportResult
import ru.papasheets.domain.backup.MergeStats
import ru.papasheets.domain.xlsx.XlsxImportPreview
import ru.papasheets.domain.xlsx.XlsxImportReason
import ru.papasheets.exportkit.backup.BackupFormatReason

internal fun BackupUiFailure.messageRes(): Int = when (this) {
    BackupUiFailure.SAVE_BACKUP -> R.string.backup_save_failed
    BackupUiFailure.IMPORT_BACKUP -> R.string.backup_import_failed
    BackupUiFailure.IMPORT_XLSX -> R.string.xlsx_import_failed
}

/** Строка, а не id ресурса: у двух причин в тексте версия формата. */
internal fun BackupFormatReason.message(context: Context): String = when (this) {
    BackupFormatReason.NotABackup -> context.getString(R.string.backup_rejected_not_a_backup)
    BackupFormatReason.MissingManifest -> context.getString(R.string.backup_rejected_missing_manifest)
    BackupFormatReason.MissingData -> context.getString(R.string.backup_rejected_missing_data)
    BackupFormatReason.CorruptManifest -> context.getString(R.string.backup_rejected_corrupt_manifest)
    BackupFormatReason.CorruptData -> context.getString(R.string.backup_rejected_corrupt_data)
    is BackupFormatReason.TooNew -> context.getString(R.string.backup_rejected_too_new, formatVersion)
    is BackupFormatReason.TooOld -> context.getString(R.string.backup_rejected_too_old, formatVersion)
}

internal fun XlsxImportReason.messageRes(): Int = when (this) {
    XlsxImportReason.FILE_NOT_OPENED -> R.string.xlsx_rejected_file_not_opened
    XlsxImportReason.FILE_NOT_READ -> R.string.xlsx_rejected_file_not_read
    XlsxImportReason.UNREADABLE_SPREADSHEET -> R.string.xlsx_read_failed
    XlsxImportReason.NO_DATES_WITH_YEAR -> R.string.xlsx_rejected_no_dates_with_year
    XlsxImportReason.NO_RECORDS -> R.string.xlsx_rejected_no_records
}

/** Диалог с итогом импорта: по строке на таблицу — добавлено/обновлено/пропущено (см. [BackupImportResult]). */
@Composable
internal fun ImportResultDialog(result: BackupImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_result_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImportResultRow(R.string.import_result_journals, result.journals)
                ImportResultRow(R.string.import_result_contractors, result.contractors)
                ImportResultRow(R.string.import_result_fields, result.fieldDefs)
                ImportResultRow(R.string.import_result_presets, result.fieldPresets)
                ImportResultRow(R.string.import_result_records, result.records)
                ImportResultRow(R.string.import_result_values, result.recordValues)
                ImportResultRow(R.string.import_result_colors, result.fieldValueColors)
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
 * Разбор выбранной таблицы до записи в БД. Обязательный шаг, а не подтверждение ради вежливости:
 * файл выбран в системном диалоге и может оказаться чужим журналом, файлом за другой месяц или
 * таблицей с двумя десятками незнакомых подрядчиков — а откатить импорт после нечем.
 *
 * Поэтому показываются именно те решения, которые изменят базу необратимо: в какой месяц лягут
 * записи, кого и какие поля придётся завести, и что импорт пропустит.
 */
@Composable
internal fun XlsxImportPreviewDialog(preview: XlsxImportPreview, onConfirm: () -> Unit, onCancel: () -> Unit,
    destinations: List<ru.papasheets.data.db.dao.JournalWithStats> = emptyList(), onDestination: (String?) -> Unit = {},
) {
    var choosing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.xlsx_preview_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Box {
                    OutlinedButton(onClick = { choosing = true }) { Text(stringResource(R.string.table_import_destination) + ": " + if (preview.journalExists) preview.journalTitle else stringResource(R.string.table_import_new)) }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.table_import_new)) }, onClick = { choosing = false; onDestination(null) })
                        destinations.forEach { table ->
                            DropdownMenuItem(text = { Text("${table.title} · ${table.month}/${table.year}") }, onClick = { choosing = false; onDestination(table.id) })
                        }
                    }
                }
                Text(
                    stringResource(
                        if (preview.journalExists) R.string.xlsx_preview_journal_existing else R.string.xlsx_preview_journal_new,
                        preview.journalTitle,
                    ),
                )
                Text(stringResource(R.string.xlsx_preview_totals, preview.dayCount, preview.recordCount, preview.photoCount))
                Text(stringResource(R.string.xlsx_preview_contractors_matched, preview.matchedContractorCount))
                if (preview.newContractors.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.xlsx_preview_contractors_new,
                            preview.newContractors.size,
                            preview.newContractors.joinToString(", "),
                        ),
                    )
                }
                Text(stringResource(R.string.xlsx_preview_fields_matched, preview.matchedFieldCount))
                if (preview.newFields.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.xlsx_preview_fields_new,
                            preview.newFields.size,
                            preview.newFields.joinToString(", "),
                        ),
                    )
                }
                // Безымянные колонки — верный признак, что файл прочитан не целиком; молчать об этом нельзя.
                if (preview.skippedUnnamedContractors > 0 || preview.skippedUnnamedFields > 0) {
                    Text(
                        stringResource(
                            R.string.xlsx_preview_skipped_unnamed,
                            preview.skippedUnnamedContractors,
                            preview.skippedUnnamedFields,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.xlsx_preview_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
