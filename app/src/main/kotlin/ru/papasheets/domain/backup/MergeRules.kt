package ru.papasheets.domain.backup

/** Что сделать со строкой БД при импорте одного объекта бэкапа. */
enum class MergeAction { INSERT, REPLACE, SKIP }

/**
 * Правила слияния .psbackup с текущими данными устройства (M7). Восстановление бэкапа — не
 * двусторонняя синхронизация: для сущностей без `updatedAt` (журналы, подрядчики, пресеты локаций)
 * совпавший id — это тот же объект, каким он был на момент экспорта, поэтому импортируемая строка
 * всегда побеждает ([forReplaceable]). Для записей есть `updatedAt` — решает более свежая версия
 * ([forRecord]). Фото иммутабельны — при уже существующем id импортируемая строка не нужна вообще
 * ([forImmutable]).
 */
object MergeRules {
    fun forReplaceable(alreadyExists: Boolean): MergeAction = if (alreadyExists) MergeAction.REPLACE else MergeAction.INSERT

    fun forImmutable(alreadyExists: Boolean): MergeAction = if (alreadyExists) MergeAction.SKIP else MergeAction.INSERT

    fun forRecord(existingUpdatedAt: Long?, importedUpdatedAt: Long): MergeAction = when {
        existingUpdatedAt == null -> MergeAction.INSERT
        importedUpdatedAt > existingUpdatedAt -> MergeAction.REPLACE
        else -> MergeAction.SKIP
    }
}
