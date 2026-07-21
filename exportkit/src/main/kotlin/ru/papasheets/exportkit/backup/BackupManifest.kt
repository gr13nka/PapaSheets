package ru.papasheets.exportkit.backup

import kotlinx.serialization.Serializable

/**
 * Паспорт .psbackup: пишется в архив первым и проверяется первым при чтении — до разбора data.json,
 * чтобы неподдерживаемая версия формата обнаруживалась сразу, а не после разбора мегабайт данных.
 *
 * **Политика совместимости: читать назад обязаны, читать вперёд не можем.** Бэкап — это путь
 * восстановления после неудачной миграции, то есть страховка, которая нужна ровно в момент перехода
 * на новую версию формата. Отказ читать предыдущие версии отобрал бы её именно тогда, когда до неё
 * дошло дело, поэтому всё от [MIN_SUPPORTED_FORMAT_VERSION] до [CURRENT_FORMAT_VERSION] читается —
 * старое приводится к текущей форме в памяти ([BackupUpgrade]), файл при этом не трогается.
 * Версию выше текущей отклоняем: содержимого, которого эта сборка не знает, ей неоткуда взять смысл,
 * а молча потерять его хуже, чем отказаться.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val dbSchemaVersion: Int,
    val appVersionName: String,
    val exportedAtMillis: Long,
) {
    companion object {
        /** Версия формата .psbackup, которую пишет эта версия приложения. */
        const val CURRENT_FORMAT_VERSION = 5

        /** Самая старая версия, которую эта сборка ещё умеет читать (апгрейдя в памяти). */
        const val MIN_SUPPORTED_FORMAT_VERSION = 1
    }
}
