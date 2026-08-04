package ru.papasheets.domain.backup

/** Итог одного импорта .psbackup — по одному счётчику на таблицу, показывается пользователю в диалоге результата. */
data class BackupImportResult(
    val journals: MergeStats,
    val contractors: MergeStats,
    val fieldDefs: MergeStats,
    val fieldPresets: MergeStats,
    val fieldValueColors: MergeStats,
    val records: MergeStats,
    /** Значения считаются построчно, но действие берут у своей записи (см. [MergeRules]). */
    val recordValues: MergeStats,
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
