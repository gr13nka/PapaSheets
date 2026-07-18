package ru.papasheets.exportkit.backup

import kotlinx.serialization.Serializable

/**
 * Паспорт .psbackup: пишется в архив первым и проверяется первым при чтении — до разбора data.json,
 * чтобы неподдерживаемая версия формата обнаруживалась сразу, а не после разбора мегабайт данных.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val dbSchemaVersion: Int,
    val appVersionName: String,
    val exportedAtMillis: Long,
) {
    companion object {
        /** Версия формата .psbackup, которую пишет и умеет читать эта версия приложения. */
        const val CURRENT_FORMAT_VERSION = 1
    }
}
