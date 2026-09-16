package ru.papasheets.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.papasheets.R
import ru.papasheets.domain.backup.BackupImportResult
import ru.papasheets.domain.xlsx.ImportFileTypeDetector
import ru.papasheets.domain.xlsx.XlsxImportPreview
import ru.papasheets.localization.AppLanguage
import ru.papasheets.localization.setAppLanguage
import ru.papasheets.ui.LocalAppGraph
import ru.papasheets.ui.common.SettingsRow

private const val MIME_BACKUP = "application/octet-stream"

/**
 * Настройки приложения: язык (общий для всех журналов) и хостинг бэкапа/импорта/логов — операций,
 * которых нет в конкретном журнале. Группы колонок и поля записи сюда не попадают — они переехали в
 * «Настройки таблицы» ([TableSettingsScreen]), потому что описывают структуру таблицы, а не сам процесс.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    graph.backupInteractor, graph.importInteractor, graph.xlsxImportInteractor,
                    detectFileType = { uri -> ImportFileTypeDetector.detect(graph.appContext, uri) },
                    appLog = graph.appLog,
                )
            }
        },
    )
    var showLanguageDialog by remember { mutableStateOf(false) }
    val currentLanguage = AppLanguage.current()
    val busy by viewModel.busy.collectAsState()
    var importResult by remember { mutableStateOf<BackupImportResult?>(null) }
    var xlsxPreview by remember { mutableStateOf<XlsxImportPreview?>(null) }
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
                        context.resources.getQuantityString(
                            R.plurals.backup_saved_photos_skipped,
                            event.skippedPhotoFiles,
                            event.skippedPhotoFiles,
                        )
                    } else {
                        context.getString(R.string.backup_saved)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                is BackupUiEvent.ImportDone -> importResult = event.result
                is BackupUiEvent.Failure -> Toast.makeText(
                    context,
                    context.getString(event.kind.messageRes()),
                    Toast.LENGTH_LONG,
                ).show()
                is BackupUiEvent.BackupRejected -> Toast.makeText(
                    context,
                    event.reason.message(context),
                    Toast.LENGTH_LONG,
                ).show()
                is BackupUiEvent.XlsxRejected -> Toast.makeText(
                    context,
                    context.getString(event.reason.messageRes()),
                    Toast.LENGTH_LONG,
                ).show()
                is BackupUiEvent.XlsxPreviewReady -> xlsxPreview = event.preview
                is BackupUiEvent.XlsxImportDone -> Toast.makeText(
                    context,
                    context.getString(
                        R.string.xlsx_import_done,
                        event.result.journalTitle,
                        context.resources.getQuantityString(
                            R.plurals.xlsx_import_done_records,
                            event.result.importedRecords,
                            event.result.importedRecords,
                        ),
                        context.resources.getQuantityString(
                            R.plurals.xlsx_import_done_photos,
                            event.result.importedPhotos,
                            event.result.importedPhotos,
                        ),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_language),
                subtitle = stringResource(currentLanguage.labelRes()),
                onClick = { showLanguageDialog = true },
            )

            SectionHeader(stringResource(R.string.settings_section_data))
            SettingsRow(
                title = stringResource(R.string.backup_menu_save),
                onClick = { backupLauncher.launch(viewModel.defaultBackupFileName()) },
            )
            SettingsRow(
                title = stringResource(R.string.backup_menu_import_any),
                onClick = { importLauncher.launch(arrayOf("*/*")) },
            )

            SectionHeader(stringResource(R.string.settings_section_diagnostics))
            SettingsRow(
                title = stringResource(R.string.share_logs_menu),
                onClick = {
                    scope.launch {
                        try {
                            val intent = withContext(Dispatchers.IO) { graph.appLog.buildShareIntent() }
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_logs_chooser_title)))
                        } catch (e: Exception) {
                            graph.appLog.e("SettingsScreen", "не удалось отправить логи", e)
                            Toast.makeText(context, R.string.share_logs_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showLanguageDialog = false
                                    if (language != currentLanguage) setAppLanguage(language)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = language == currentLanguage,
                                onClick = null,
                            )
                            Text(stringResource(language.labelRes()))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
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

    val importDestinations by graph.journalRepository.observeAll().collectAsState(initial = emptyList())
    xlsxPreview?.let { preview ->
        XlsxImportPreviewDialog(
            preview = preview,
            destinations = importDestinations,
            onDestination = { viewModel.changeXlsxDestination(preview, it) },
            onConfirm = {
                viewModel.confirmXlsxImport(preview)
                xlsxPreview = null
            },
            onCancel = {
                viewModel.cancelXlsxImport(preview)
                xlsxPreview = null
            },
        )
    }
}

/** Заголовок раздела списка настроек («Данные», «Диагностика») — над своей группой [SettingsRow]. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    )
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.ENGLISH -> R.string.settings_language_english
    AppLanguage.RUSSIAN -> R.string.settings_language_russian
}
