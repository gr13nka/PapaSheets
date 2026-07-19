package ru.papasheets.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * Заводит встроенные определения полей в `field_defs` при создании БД с нуля
 * (`DefaultSeed.onCreate`) — то есть в ТЕКУЩЕЙ схеме.
 *
 * Обновившееся с v1 устройство получает те же поля другим путём — из `Migrations.MIGRATION_1_2`,
 * которая пишет их в схему v2, с давно уже убранной колонкой `key`. Ветки не пересекаются
 * (`onCreate` не вызывается при апгрейде, миграция — при создании), и сойтись обязаны на выходе:
 * встроенное поле узнаётся по id, и разошедшиеся строки дали бы на другом устройстве две колонки
 * «Локация» рядом. Значения обе ветки берут из общего [BuiltInFields], а совпадение результата
 * проверяет `BuiltInFieldSeedTest` — на текущей версии, после всей цепочки миграций.
 *
 * `createdAt` приходит параметром, а не берётся внутри: у обеих строк должна быть одна отметка
 * времени, и тесту нужен воспроизводимый результат.
 */
internal object BuiltInFieldSeed {
    private const val INSERT_SQL =
        "INSERT INTO field_defs " +
            "(`id`, `title`, `label`, `orderIndex`, `isArchived`, `isBuiltIn`, `isRequired`, " +
            "`suggestFromHistory`, `columnWidthDp`, `maxLines`, `showAtCompactLod`, `createdAt`) " +
            "VALUES (?, ?, ?, ?, 0, 1, ?, ?, ?, ?, ?, ?)"

    fun insertInto(db: SupportSQLiteDatabase, createdAt: Long) {
        BuiltInFields.ALL.forEach { spec ->
            db.execSQL(
                INSERT_SQL,
                arrayOf<Any>(
                    spec.id,
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
