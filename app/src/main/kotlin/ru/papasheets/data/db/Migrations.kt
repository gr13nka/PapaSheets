package ru.papasheets.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * v1 → v2: набор полей записи становится данными (`field_defs` + `record_values`).
 *
 * **Миграция строго аддитивная — ни одного DROP или RENAME.** На телефоне лежит единственный
 * экземпляр рабочего журнала прораба без второй копии где бы то ни было, поэтому шаг устроен так,
 * чтобы худшим исходом была лишняя неиспользуемая таблица, а не потерянная запись: старые колонки
 * `records.locationCode`/`records.workText` остаются на месте нетронутыми и продолжают содержать
 * исходные данные. Если перенос окажется неполным, чинить можно по ним, а не по бэкапу.
 * Убрала их [Migrations.MIGRATION_2_3], когда стало видно, что всё перенеслось.
 *
 * Переносятся только непустые значения — см. инвариант «пустых значений не хранится»
 * в `RecordValueEntity`.
 */
object Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_DDL.forEach(db::execSQL)
            // Обязательно до переноса значений: FK на field_defs проверяется сразу при вставке.
            BuiltInFieldSeed.insertInto(db, System.currentTimeMillis())
            MIGRATION_1_2_COPY.forEach(db::execSQL)
        }
    }

    /**
     * v2 → v3: из `records` уходят страховочные колонки `locationCode`/`workText`.
     *
     * Дороже аддитивного шага: SQLite не умел `DROP COLUMN` до 3.35, а Android тащит собственный
     * SQLite, версия которого зависит от прошивки телефона, — рассчитывать на `DROP COLUMN` нельзя.
     * Поэтому классический обходной путь: новая таблица с нужной схемой, перелив данных без
     * лишних колонок, снос старой, переименование. Это единственная миграция в проекте, которая
     * пересоздаёт таблицу с данными.
     *
     * Позволить её себе можно ровно сейчас, пока приложением никто не пользуется и реальных данных
     * нет ни на одном устройстве: цена ошибки — переустановка. С первым же настоящим журналом
     * прораба та же правка станет операцией с необратимым риском ради косметики схемы, то есть
     * практически неоплатной. Отсюда правило: чистить схему — до выката, не после.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // `record_values.recordId` ссылается на `records(id)`, и на время подмены таблицы связь
            // заведомо рвётся. `PRAGMA foreign_keys` здесь бесполезен — Room выполняет миграцию
            // внутри транзакции, а он внутри транзакции игнорируется молча. `defer_foreign_keys`
            // же откладывает проверку до коммита: связи всё равно будут проверены, но уже после
            // того, как таблица встанет на место под своим именем.
            db.execSQL("PRAGMA defer_foreign_keys = TRUE")
            MIGRATION_2_3_DDL.forEach(db::execSQL)
        }
    }

    /**
     * Полная цепочка в порядке версий. Существует затем, чтобы список миграций был ровно один:
     * [AppDatabase] и тест цепочки берут его отсюда, поэтому забытая в сборке миграция валит тест,
     * а не телефон прораба, пропустившего пару версий.
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}

/**
 * DDL скопирован дословно из экспортированной схемы `app/schemas/.../2.json`, а не написан по памяти.
 * Room сверяет фактическую схему с ожидаемой по хешу при первом открытии БД: любое расхождение,
 * вплоть до порядка колонок, роняет приложение на identityHash. Правило — сначала правится entity
 * и пересобирается проект, потом `createSql` из свежего 2.json переносится сюда.
 */
internal val MIGRATION_1_2_DDL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `field_defs` (`id` TEXT NOT NULL, `key` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `label` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, " +
        "`isArchived` INTEGER NOT NULL, `isBuiltIn` INTEGER NOT NULL, `isRequired` INTEGER NOT NULL, " +
        "`suggestFromHistory` INTEGER NOT NULL, `columnWidthDp` INTEGER NOT NULL, " +
        "`maxLines` INTEGER NOT NULL, `showAtCompactLod` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_field_defs_key` ON `field_defs` (`key`)",
    "CREATE TABLE IF NOT EXISTS `record_values` (`recordId` TEXT NOT NULL, `fieldId` TEXT NOT NULL, " +
        "`value` TEXT NOT NULL, PRIMARY KEY(`recordId`, `fieldId`), " +
        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`fieldId`) REFERENCES `field_defs`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
    "CREATE INDEX IF NOT EXISTS `index_record_values_fieldId` ON `record_values` (`fieldId`)",
)

/**
 * Перенос содержимого старых колонок. `TRIM(...) <> ''` отсекает не только пустые строки, но и
 * пробельные: до v2 форма могла сохранить «   » в необязательную локацию, а в `record_values`
 * такое значение было бы неотличимо от осмысленного.
 *
 * Значение переносится тоже обрезанным — раз пробелы уже признаны незначащими при решении
 * «пусто или нет», хранить их внутри значения непоследовательно: « К1 » и «К1» разъехались бы в
 * подсказках истории и в фильтрах как два разных значения. Форма после v2 обрезает при сохранении,
 * так что нормализуются и старые записи, и новые.
 */
internal val MIGRATION_1_2_COPY: List<String> = listOf(
    "INSERT INTO record_values (recordId, fieldId, value) " +
        "SELECT id, '${BuiltInFields.LOCATION_ID}', TRIM(locationCode) FROM records WHERE TRIM(locationCode) <> ''",
    "INSERT INTO record_values (recordId, fieldId, value) " +
        "SELECT id, '${BuiltInFields.WORK_ID}', TRIM(workText) FROM records WHERE TRIM(workText) <> ''",
)

/**
 * Пересоздание `records` без двух мёртвых колонок. `CREATE TABLE` скопирован дословно из
 * `app/schemas/.../3.json` — по тем же причинам, что и DDL v2 выше.
 *
 * Индексы перечислены явно и все: `DROP TABLE` уносит индексы вместе с таблицей, а Room сверяет
 * их по схеме наравне с колонками — забытый индекс уронит приложение на identityHash так же
 * надёжно, как забытая колонка.
 */
internal val MIGRATION_2_3_DDL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `_new_records` (`id` TEXT NOT NULL, `journalId` TEXT NOT NULL, " +
        "`dateEpochDay` INTEGER NOT NULL, `contractorId` TEXT NOT NULL, `photoId` TEXT, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`journalId`) REFERENCES `journals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`contractorId`) REFERENCES `contractors`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
        "FOREIGN KEY(`photoId`) REFERENCES `photos`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "INSERT INTO `_new_records` (`id`, `journalId`, `dateEpochDay`, `contractorId`, `photoId`, `createdAt`, `updatedAt`) " +
        "SELECT `id`, `journalId`, `dateEpochDay`, `contractorId`, `photoId`, `createdAt`, `updatedAt` FROM `records`",
    "DROP TABLE `records`",
    "ALTER TABLE `_new_records` RENAME TO `records`",
    "CREATE INDEX IF NOT EXISTS `index_records_journalId_dateEpochDay` ON `records` (`journalId`, `dateEpochDay`)",
    "CREATE INDEX IF NOT EXISTS `index_records_contractorId` ON `records` (`contractorId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_records_photoId` ON `records` (`photoId`)",
)
