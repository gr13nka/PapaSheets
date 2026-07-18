package ru.papasheets.domain.backup

/** Итог одного импорта .psbackup — по одному счётчику на таблицу, показывается пользователю в диалоге результата. */
data class BackupImportResult(
    val journals: MergeStats,
    val contractors: MergeStats,
    val locationPresets: MergeStats,
    val records: MergeStats,
    val photos: MergeStats,
)

/** Аккумулятор [MergeAction] по одной таблице: `stats + action` — обычный шаг fold по списку импортируемых строк. */
data class MergeStats(val added: Int = 0, val updated: Int = 0, val skipped: Int = 0) {
    operator fun plus(action: MergeAction): MergeStats = when (action) {
        MergeAction.INSERT -> copy(added = added + 1)
        MergeAction.REPLACE -> copy(updated = updated + 1)
        MergeAction.SKIP -> copy(skipped = skipped + 1)
    }
}
