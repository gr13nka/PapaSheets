package ru.papasheets.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * Заводит встроенные определения полей в `field_defs`.
 *
 * Существует ровно одна такая функция, и это существенно: строки должны появляться одинаковыми на
 * обоих путях — при создании БД с нуля (`DefaultSeed.onCreate`) и при обновлении с v1
 * (`Migrations.MIGRATION_1_2`). Ветки не пересекаются (`onCreate` не вызывается при апгрейде,
 * миграция — при создании), поэтому расхождение между ними ничем бы себя не проявило до первого
 * бэкапа с одного устройства на другое. Общий вызов делает совпадение структурным свойством,
 * а не тем, что кто-то не забыл поправить второе место.
 *
 * `createdAt` приходит параметром, а не берётся внутри: у обеих строк должна быть одна отметка
 * времени, и тесту нужен воспроизводимый результат.
 */
internal object BuiltInFieldSeed {
    private const val INSERT_SQL =
        "INSERT INTO field_defs " +
            "(`id`, `key`, `title`, `label`, `orderIndex`, `isArchived`, `isBuiltIn`, `isRequired`, " +
            "`suggestFromHistory`, `columnWidthDp`, `maxLines`, `showAtCompactLod`, `createdAt`) " +
            "VALUES (?, ?, ?, ?, ?, 0, 1, ?, ?, ?, ?, ?, ?)"

    fun insertInto(db: SupportSQLiteDatabase, createdAt: Long) {
        BuiltInFields.ALL.forEach { spec ->
            db.execSQL(
                INSERT_SQL,
                arrayOf<Any>(
                    spec.id,
                    spec.key,
                    spec.title,
                    spec.label,
                    spec.orderIndex,
                    if (spec.isRequired) 1 else 0,
                    if (spec.suggestFromHistory) 1 else 0,
                    spec.columnWidthDp,
                    spec.maxLines,
                    if (spec.showAtCompactLod) 1 else 0,
                    createdAt,
                ),
            )
        }
    }
}
