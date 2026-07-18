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
 * Убирает их отдельная Migration(2, 3) — после того как станет видно, что всё перенеслось.
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
